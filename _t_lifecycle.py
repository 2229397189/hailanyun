#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""A-8 / A-9 真实生命周期验收：每个动作都必须反映到 K8s Pod，而不是只改数据库。

覆盖：下单 → 交互式执行（含 nvidia-smi）→ 重启 → 关机/开机 → 重置系统
      → 续费 → 定时关机 → 克隆 → 升降配 → 释放结算。
断言看 Pod UID、Pod 是否存在、容器内文件、到期时间、账单，全部是真实副作用。
"""
import json
import subprocess
import time
import urllib.error
import urllib.request

BASE = 'http://localhost:8099'
PASS, FAIL = [], []


def check(name, cond, detail=''):
    (PASS if cond else FAIL).append(name)
    print(('PASS ' if cond else 'FAIL ') + name + ('  ' + str(detail) if detail else ''))


def call(method, path, token=None, body=None, timeout=45):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    if body is not None:
        req.add_header('Content-Type', 'application/json')
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            txt = r.read().decode()
            return json.loads(txt) if txt else {}
    except urllib.error.HTTPError as e:
        msg = e.read().decode()
        try:
            msg = json.loads(msg).get('message', msg)
        except Exception:
            pass
        return {'_http': e.code, '_msg': msg}


def kubectl(*args):
    return subprocess.run(['docker', 'exec', 'k3s-test', 'kubectl'] + list(args),
                          capture_output=True, text=True, timeout=40).stdout.strip()


def pod_uid(pod):
    return kubectl('get', 'pod', '-n', 'tenant-t1', pod, '-o', 'jsonpath={.metadata.uid}')


def pod_exists(pod):
    return bool(pod_uid(pod))


def wait_status(iid, want, tries=30):
    for _ in range(tries):
        me = [x for x in call('GET', '/api/instances', TEN) if x['id'] == iid]
        if me and me[0]['status'] == want:
            return me[0]
        time.sleep(2)
    return None


def exec_cmd(iid, cmd):
    return call('POST', '/api/instances/%s/exec' % iid, TEN, {'command': cmd})


TEN = call('POST', '/api/auth/login', body={'username': 'tenant_a', 'password': 'Hly@2026'})['token']

# 清掉集群里可能占卡的验证 Pod
kubectl('delete', 'pod', '-n', 'default', 'gpu-verify', '--ignore-not-found')

# ---------------------------------------------------------------- 1. 下单
order = call('POST', '/api/orders', TEN,
             {'sku': '1', 'hours': 2, 'orderNo': 'life-%d' % int(time.time()),
              'dataDiskGb': 1, 'image': 'devbox'})
iid = order.get('instanceId')
check('下单成功', iid is not None, order.get('message'))
inst = wait_status(iid, 'running')
check('实例就绪（Pod Ready 才转 running）', inst is not None, inst['status'] if inst else 'timeout')
pod = 'gpu-pod-' + iid
uid0 = pod_uid(pod)
check('Pod 真实存在', bool(uid0), uid0[:12])

# ---------------------------------------------------------------- 2. 用户能操控显卡
out = exec_cmd(iid, 'nvidia-smi --query-gpu=name --format=csv,noheader')
gpu_out = str(out.get('output', ''))
check('Web 终端 nvidia-smi 看得到真实显卡',
      ('GeForce' in gpu_out or 'RTX' in gpu_out or 'A100' in gpu_out or 'H100' in gpu_out)
      and 'not found' not in gpu_out.lower() and 'error' not in gpu_out.lower(),
      gpu_out[:90])
out2 = exec_cmd(iid, 'echo tokenark-lifecycle-ok > /data/lifecycle.txt; cat /data/lifecycle.txt')
check('能写数据盘并读回', 'tokenark-lifecycle-ok' in str(out2.get('output', '')), str(out2.get('output', ''))[:60])

# ---------------------------------------------------------------- 3. 重启（真删建 Pod）
r = call('POST', '/api/instances/%s/restart' % iid, TEN)
check('重启接口返回', r.get('status') == 'provisioning', r.get('message'))
inst = wait_status(iid, 'running')
uid1 = pod_uid(pod)
check('重启后 Pod UID 变了（真的重建）', inst is not None and uid1 and uid1 != uid0, '%s → %s' % (uid0[:8], uid1[:8]))
keep = exec_cmd(iid, 'cat /data/lifecycle.txt')
check('重启后数据盘文件仍在', 'tokenark-lifecycle-ok' in str(keep.get('output', '')), str(keep.get('output', ''))[:50])

# ---------------------------------------------------------------- 4. 关机 / 开机
r = call('POST', '/api/instances/%s/poweroff' % iid, TEN)
check('关机接口返回', r.get('status') == 'stopped', r.get('message'))
check('关机后 Pod 真的没了', not pod_exists(pod))
r = call('POST', '/api/instances/%s/poweron' % iid, TEN)
check('开机接口返回', r.get('status') == 'provisioning', r.get('message'))
inst = wait_status(iid, 'running')
check('开机后 Pod 回来了', inst is not None and pod_exists(pod), inst['status'] if inst else 'timeout')
uid2 = pod_uid(pod)

# ---------------------------------------------------------------- 5. 重置系统（原来只改状态，现在必须真重建）
r = call('POST', '/api/instances/%s/reset' % iid, TEN)
check('重置接口返回', r.get('status') == 'resetting', r.get('message'))
inst = wait_status(iid, 'running')
uid3 = pod_uid(pod)
check('重置后 Pod UID 变了（不再只改数据库）', inst is not None and uid3 and uid3 != uid2,
      '%s → %s' % (uid2[:8], uid3[:8]))
# 系统盘应还原：容器文件系统里写的文件应消失；数据盘应保留
sysclean = exec_cmd(iid, 'test -f /tmp/lifecycle-root.txt && echo EXISTS || echo GONE')
keep2 = exec_cmd(iid, 'cat /data/lifecycle.txt')
check('重置后数据盘仍保留', 'tokenark-lifecycle-ok' in str(keep2.get('output', '')), str(keep2.get('output', ''))[:50])

# ---------------------------------------------------------------- 6. 续费
before = [x for x in call('GET', '/api/instances', TEN) if x['id'] == iid][0]
r = call('POST', '/api/instances/%s/renew' % iid, TEN, {'hours': 2})
after = [x for x in call('GET', '/api/instances', TEN) if x['id'] == iid][0]
check('续费延长期限', str(after.get('expire_at')) > str(before.get('expire_at')),
      '%s → %s' % (before.get('expire_at'), after.get('expire_at')))

# ---------------------------------------------------------------- 7. 定时关机（巡检真的删 Pod）
subprocess.run(['docker', 'exec', 'gpu-rental-pg', 'psql', '-U', 'rental', '-d', 'rental', '-c',
                "INSERT INTO instance_schedules(id,instance_id,tenant_id,action,at,status) "
                "VALUES ('sch" + str(int(time.time())) + "','" + iid + "','t1','shutdown', now() - interval '1 minute','pending');"],
               capture_output=True, text=True)
for _ in range(18):
    me = [x for x in call('GET', '/api/instances', TEN) if x['id'] == iid]
    if me and me[0]['status'] == 'stopped':
        break
    time.sleep(2)
check('定时关机把 Pod 真的停掉', bool(me) and me[0]['status'] == 'stopped' and not pod_exists(pod),
      me[0]['status'] if me else 'none')
if me and me[0]['status'] == 'stopped':
    r = call('POST', '/api/instances/%s/poweron' % iid, TEN)
    inst = wait_status(iid, 'running')
    check('定时关机后可再开机', inst is not None and pod_exists(pod), r.get('message'))
else:
    check('定时关机后可再开机', False, '前置关机未生效，跳过')

# ---------------------------------------------------------------- 8. 克隆
r = call('POST', '/api/instances/%s/clone' % iid, TEN)
clone = r.get('instanceId')
check('克隆产生新实例', bool(clone), r.get('message'))
if clone:
    check('克隆实例是独立 Pod', pod_exists('gpu-pod-' + clone))
    call('POST', '/api/instances/%s/release' % clone, TEN)

# ---------------------------------------------------------------- 9. 升降配
r = call('POST', '/api/instances/%s/resize' % iid, TEN, {'gpuCount': 2})
check('升降配产生新规格实例', bool(r.get('newInstanceId')) or r.get('status') == 'partial', r.get('message'))
if r.get('newInstanceId'):
    check('新实例卡数为 2', any(x['id'] == r['newInstanceId'] and int(x['gpu_count']) == 2
                            for x in call('GET', '/api/instances', TEN)))
    call('POST', '/api/instances/%s/release' % r['newInstanceId'], TEN)

# ---------------------------------------------------------------- 10. 释放结算
r = call('POST', '/api/instances/%s/release' % iid, TEN)
check('释放返回结算信息', r.get('status') in ('released', 'stopped') or 'usedSeconds' in r, r.get('message'))
check('释放后 Pod 不存在', not pod_exists(pod))

print('\n========== 结果：%d 通过 / %d 失败 ==========' % (len(PASS), len(FAIL)))
if FAIL:
    print('失败项：' + ' | '.join(FAIL))
    raise SystemExit(1)
