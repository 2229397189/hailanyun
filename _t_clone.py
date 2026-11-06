#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""下单→操控服务器 + 完整克隆 端到端验收（全部看真实副作用）。

覆盖：
  1) 镜像目录默认 devbox（用户下单就能用 SSH/Jupyter/桌面）
  2) 下单后 Web 终端能看到真实显卡、能写数据盘
  3) 克隆：数据盘文件真实复制、端口按用途重新分配、原实例不受影响
  4) 关闭复制时确实不复制
"""
import json
import time
import urllib.error
import urllib.request

BASE = 'http://localhost:8099'
PASS, FAIL = [], []


def check(name, cond, detail=''):
    (PASS if cond else FAIL).append(name)
    print(('PASS ' if cond else 'FAIL ') + name + ('  ' + str(detail) if detail else ''))


def call(method, path, token=None, body=None, timeout=90):
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


def wait_status(iid, want, tries=35):
    for _ in range(tries):
        me = [x for x in call('GET', '/api/instances', TEN) if x['id'] == iid]
        if me and me[0]['status'] == want:
            return me[0]
        time.sleep(2)
    return None


def exec_cmd(iid, cmd):
    return call('POST', '/api/instances/%s/exec' % iid, TEN, {'command': cmd})


TEN = call('POST', '/api/auth/login', body={'username': 'tenant_a', 'password': 'Hly@2026'})['token']

# ---------------------------------------------------------------- 1. 镜像目录
catalog = call('GET', '/api/catalog/images')
check('镜像目录首位是开发机', bool(catalog) and catalog[0]['name'].startswith('开发机'))
check('默认镜像 ref=devbox 且标记已导入',
      catalog and catalog[0]['versions'][0]['ref'] == 'devbox' and catalog[0].get('imported') is True,
      catalog[0]['versions'][0]['ref'] if catalog else 'none')

# ---------------------------------------------------------------- 2. 下单 → 操控服务器
order = call('POST', '/api/orders', TEN, {
    'sku': '1', 'hours': 2, 'orderNo': 'clone-src-%d' % int(time.time()),
    'image': 'devbox', 'dataDiskGb': 1})
src = order.get('instanceId')
check('用户下单成功（默认镜像 devbox）', src is not None, order.get('message'))
inst = wait_status(src, 'running')
check('Pod Ready 后才 running', inst is not None, inst['status'] if inst else 'timeout')

gpu = str(exec_cmd(src, 'nvidia-smi --query-gpu=name --format=csv,noheader').get('output', ''))
check('下单后就能看到真实显卡', 'GeForce' in gpu or 'RTX' in gpu, gpu[:60])

marker = 'CLONED_FROM_SRC_%d' % int(time.time())
exec_cmd(src, 'echo %s > /data/clone_marker.txt; mkdir -p /data/sub; echo nested > /data/sub/n.txt' % marker)
check('数据盘写入成功', marker in str(exec_cmd(src, 'cat /data/clone_marker.txt').get('output', '')))

p1 = call('POST', '/api/instances/%s/expose' % src, TEN, {'port': 8888})
p2 = call('POST', '/api/instances/%s/expose' % src, TEN, {'port': 6080})
check('原实例暴露两个端口', bool(p1.get('nodePort')) and bool(p2.get('nodePort')),
      [p1.get('nodePort'), p2.get('nodePort')])
src_ports = call('GET', '/api/instances/%s/access' % src, TEN).get('exposed', [])
check('接入信息列出两个端口', len(src_ports) == 2, [x['kind'] for x in src_ports])

# ---------------------------------------------------------------- 3. 完整克隆
clone = call('POST', '/api/instances/%s/clone' % src, TEN, {'withData': True, 'withPorts': True})
new = clone.get('instanceId')
check('克隆创建新实例', bool(new), clone.get('message'))
check('克隆返回值标明复制结果', clone.get('clonedData') is True and clone.get('clonedPorts') == 2,
      'data=%s ports=%s' % (clone.get('clonedData'), clone.get('clonedPorts')))
check('克隆实例独立就绪', wait_status(new, 'running') is not None)

import subprocess
def uid(iid):
    return subprocess.run(['docker', 'exec', 'k3s-test', 'kubectl', 'get', 'pod',
                           '-n', 'tenant-t1', 'gpu-pod-' + iid,
                           '-o', 'jsonpath={.metadata.uid}'],
                          capture_output=True, text=True, timeout=30).stdout.strip()
check('克隆实例是独立 Pod', uid(src) and uid(new) and uid(src) != uid(new),
      '%s / %s' % (uid(src)[:8], uid(new)[:8]))

# 数据盘内容真复制（含子目录）
got = str(exec_cmd(new, 'cat /data/clone_marker.txt; cat /data/sub/n.txt').get('output', ''))
check('数据盘文件完整复制到新实例', marker in got and 'nested' in got, got[:80])

new_acc = call('GET', '/api/instances/%s/access' % new, TEN).get('exposed', [])
check('端口用途复制', sorted(x['port'] for x in new_acc) == [6080, 8888],
      [x['port'] for x in new_acc])
check('NodePort 重新分配（不与原实例冲突）',
      all(x['nodePort'] not in [p['nodePort'] for p in src_ports] for x in new_acc),
      [x['nodePort'] for x in new_acc])
check('原实例仍可正常操作', marker in str(exec_cmd(src, 'cat /data/clone_marker.txt').get('output', '')))

# ---------------------------------------------------------------- 4. 不复制数据
plain = call('POST', '/api/instances/%s/clone' % src, TEN, {'withData': False, 'withPorts': False})
pid = plain.get('instanceId')
check('可选择只克隆规格', bool(pid) and plain.get('clonedData') is False and plain.get('clonedPorts') == 0,
      plain.get('message'))
wait_status(pid, 'running')
empty = str(exec_cmd(pid, 'test -f /data/clone_marker.txt && echo EXISTS || echo EMPTY').get('output', ''))
check('不复制时数据盘为空', 'EMPTY' in empty, empty.strip())

# ---------------------------------------------------------------- 清理
for iid in (new, pid, src):
    if iid:
        call('DELETE', '/api/instances/%s/expose' % iid, TEN)
        call('POST', '/api/instances/%s/release' % iid, TEN)
print('\n========== 结果：%d 通过 / %d 失败 ==========' % (len(PASS), len(FAIL)))
if FAIL:
    print('失败项：' + ' | '.join(FAIL))
    raise SystemExit(1)
