#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批次三回归：常驻任务（守护进程）+ 端口暴露（JupyterLab / WebUI 场景）。

守护进程：起一个持续输出的进程 → 读日志确认它在跑 → 断开不依赖会话。
端口暴露：Pod 内起 busybox httpd → NodePort 访问 → 撤销后不可达。
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


def call(method, path, token=None, body=None, timeout=40):
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


def http_get(url, timeout=8):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status, r.read().decode('utf-8', 'ignore')
    except urllib.error.HTTPError as e:
        # 4xx/5xx 也算「端口通了」，状态码要带出来
        return e.code, e.read().decode('utf-8', 'ignore')
    except Exception as e:
        return 0, str(e)


TEN = call('POST', '/api/auth/login', body={'username': 'tenant_a', 'password': 'Hly@2026'})['token']

# ---------------------------------------------------------------- 起一台无卡调试机
inst = call('POST', '/api/orders', TEN, {'sku': 'debug', 'hours': 1,
                                        'orderNo': 'job-%d' % int(time.time())})
iid = inst.get('instanceId')
check('创建调试实例', iid is not None, inst.get('message'))
for _ in range(20):
    lst = call('GET', '/api/instances', TEN)
    me = [x for x in lst if x['id'] == iid]
    if me and me[0]['status'] == 'running':
        break
    time.sleep(2)
check('实例进入 running', me and me[0]['status'] == 'running', me[0]['status'] if me else 'none')

# ---------------------------------------------------------------- 守护进程
job = call('POST', '/api/instances/%s/jobs' % iid, TEN,
           {'name': 'ticker', 'command': 'i=0; while true; do i=$((i+1)); echo tick $i; sleep 2; done'})
check('常驻任务已启动', job.get('pid'), job.get('message'))
pid = job.get('pid')

time.sleep(6)
log = call('GET', '/api/instances/%s/jobs/ticker/log' % iid, TEN)
body = log.get('log', '')
check('持续输出写入日志', 'tick' in body and body.count('tick') >= 2, body.strip().split('\n')[:3])

jobs = call('GET', '/api/instances/%s/jobs' % iid, TEN)
check('任务列表可查', any(j['name'] == 'ticker' and j['status'] == 'running' for j in jobs), len(jobs))

# 进程还在（Pod 内 kill -0 探活）
import subprocess
alive = subprocess.run(['docker', 'exec', 'k3s-test', 'kubectl', 'exec', '-n', 'tenant-t1',
                        'gpu-pod-' + iid, '--', 'sh', '-c', 'kill -0 %s && echo ALIVE' % pid],
                       capture_output=True, text=True, timeout=30).stdout
check('进程仍在运行（kill -0 成功）', 'ALIVE' in alive, alive.strip())

# ---------------------------------------------------------------- 端口暴露
web = call('POST', '/api/instances/%s/jobs' % iid, TEN, {'name': 'web', 'command': 'echo "<h1>hello from pod</h1>" > /tmp/index.html; busybox httpd -f -p 8080 -h /tmp'})
check('启动 Web 服务任务', web.get('pid'), web.get('message'))
time.sleep(3)

exp = call('POST', '/api/instances/%s/expose' % iid, TEN, {'port': 8080})
check('暴露端口成功', exp.get('nodePort'), exp.get('message'))
url = exp.get('url', '')
# kube-proxy 下发 NodePort 有秒级延迟，轮询到通为止（最多 20s）
code, html = 0, ''
for _ in range(10):
    code, html = http_get(url, timeout=4)
    if code:
        break
    time.sleep(2)
check('通过 NodePort 可访问', code == 200 and 'hello from pod' in html, '%s → %s' % (url, code))

# 端口对外可见后，平台侧记录也应更新
lst = call('GET', '/api/instances', TEN)
me = [x for x in lst if x['id'] == iid][0]
check('实例记录了暴露端口', me.get('dev_port') == 8080 and me.get('dev_node_port') == exp.get('nodePort'),
      '%s / %s' % (me.get('dev_port'), me.get('dev_node_port')))

call('DELETE', '/api/instances/%s/expose' % iid, TEN)
time.sleep(2)
code2, _ = http_get(url)
check('撤销后不可访问', code2 == 0, code2)

# ---------------------------------------------------------------- 清理
call('POST', '/api/instances/%s/release' % iid, TEN)
print('\n========== 结果：%d 通过 / %d 失败 ==========' % (len(PASS), len(FAIL)))
if FAIL:
    print('失败项：' + ' | '.join(FAIL))
    raise SystemExit(1)
