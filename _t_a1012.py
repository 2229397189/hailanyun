#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""A-10 / A-11 / A-12 端到端验收：用户真的能连上 Jupyter、SSH、远程桌面。

不做任何 mock：所有断言都基于 Pod 内真实进程、真实 HTTP 响应、真实 SSH banner。
"""
import json
import socket
import subprocess
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


def http_get(url, timeout=5):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status, r.read().decode('utf-8', 'ignore')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'ignore')
    except Exception:
        return 0, ''


ADM = call('POST', '/api/auth/login', body={'username': 'admin', 'password': 'Hly@2026'})['token']
TEN = call('POST', '/api/auth/login', body={'username': 'tenant_a', 'password': 'Hly@2026'})['token']

# ---------------------------------------------------------------- 1. 镜像映射
r = call('POST', '/api/admin/settings', ADM,
         {'k': 'image.map', 'v': '{"devbox":"tokenark/devbox:1.0"}'})
check('写入 devbox 镜像映射', r.get('changed') == 1, r.get('message'))

# ---------------------------------------------------------------- 2. 下单开发机实例
order = call('POST', '/api/orders', TEN,
             {'sku': 'debug', 'hours': 2, 'orderNo': 'devbox-%d' % int(time.time()), 'image': 'devbox'})
iid = order.get('instanceId')
check('创建开发机实例', iid is not None, order.get('message'))
for _ in range(30):
    me = [x for x in call('GET', '/api/instances', TEN) if x['id'] == iid]
    if me and me[0]['status'] == 'running':
        break
    time.sleep(2)
check('实例进入 running', bool(me) and me[0]['status'] == 'running', me[0]['status'] if me else 'none')
pod = 'gpu-pod-' + iid

def kubectl(*args):
    return subprocess.run(['docker', 'exec', 'k3s-test', 'kubectl'] + list(args),
                          capture_output=True, text=True, timeout=30).stdout.strip()

actual_image = kubectl('get', 'pod', '-n', 'tenant-t1', pod,
                       '-o', 'jsonpath={.spec.containers[0].image}')
check('Pod 真的跑 devbox 镜像', 'tokenark/devbox' in actual_image, actual_image)

# ---------------------------------------------------------------- 3. 起三个真实服务
jobs = [
    ('jupyter', "jupyter notebook --no-browser --ip=0.0.0.0 --port=8888 --allow-root --NotebookApp.token='' --NotebookApp.password=''"),
    ('sshd', '/usr/sbin/sshd -D -p 22'),
    ('desktop', '/usr/local/bin/start-desktop.sh'),
]
for name, cmd in jobs:
    r = call('POST', '/api/instances/%s/jobs' % iid, TEN, {'name': name, 'command': cmd})
    check('启动服务 %s' % name, bool(r.get('pid')), r.get('message'))

time.sleep(8)
ps = kubectl('exec', '-n', 'tenant-t1', pod, '--', 'ps', 'ax')
check('三个服务进程都在 Pod 里', 'jupyter' in ps and 'sshd' in ps and 'Xvfb' in ps,
      [l for l in ps.split('\n') if any(k in l for k in ('jupyter', 'sshd', 'Xvfb'))][:3])

# ---------------------------------------------------------------- 4. 暴露三个端口
urls = {}
for port in (8888, 22, 6080):
    r = call('POST', '/api/instances/%s/expose' % iid, TEN, {'port': port})
    ok = bool(r.get('nodePort'))
    check('暴露端口 %d' % port, ok, r.get('message'))
    urls[port] = r.get('url') if ok else None

check('SSH 返回的是 ssh 命令而不是 http 链接', str(urls.get(22, '')).startswith('ssh -p'), urls.get(22))
check('三个端口互不冲突', len(set(v for v in urls.values() if v)) == 3,
      [urls.get(p) for p in (8888, 22, 6080)])

# ---------------------------------------------------------------- 5. 逐个验收
def wait_http(url, keyword, tries=12):
    for _ in range(tries):
        code, body = http_get(url)
        if code == 200 and keyword.lower() in body.lower():
            return code, body
        time.sleep(2)
    return code, body

# Jupyter：访问 /tree 应看到 Notebook 界面
jurl = urls.get(8888)
if jurl:
    code, body = wait_http(jurl, 'jupyter')
    check('Jupyter 页面可打开', code == 200 and 'jupyter' in body.lower(), '%s → %s' % (jurl, code))

# 桌面：noVNC 页面
durl = urls.get(6080)
if durl:
    code, body = wait_http(durl, 'noVNC')
    check('远程桌面页面可打开', code == 200 and ('novnc' in body.lower() or 'vnc' in body.lower()),
          '%s → %s' % (durl, code))

# SSH：读 banner
ssh_ok = False
banner = ''
if urls.get(22):
    np = int(urls[22].split()[-2]) if urls[22].startswith('ssh') else int(urls[22].split(':')[-1].rstrip('/'))
    for _ in range(10):
        try:
            s = socket.create_connection(('127.0.0.1', np), timeout=4)
            s.settimeout(4)
            banner = s.recv(128).decode('utf-8', 'ignore').strip()
            s.close()
            if banner.startswith('SSH-'):
                ssh_ok = True
                break
        except Exception:
            time.sleep(2)
check('SSH 端口握手成功', ssh_ok, banner)

# ---------------------------------------------------------------- 6. 接入信息（用户怎么用）
acc = call('GET', '/api/instances/%s/access' % iid, TEN)
check('接入信息列出 3 个端口', len(acc.get('exposed', [])) == 3,
      [x.get('kind') for x in acc.get('exposed', [])])
check('接入信息给出 SSH 命令', bool(acc.get('ssh')) and 'ssh -p' in str(acc.get('ssh')), acc.get('ssh'))
check('接入信息给出 Jupyter 地址', bool(acc.get('jupyter')), acc.get('jupyter'))
check('接入信息给出桌面地址', bool(acc.get('desktop')), acc.get('desktop'))
check('接入信息含使用步骤', len(acc.get('howTo', [])) >= 3, len(acc.get('howTo', [])))

# ---------------------------------------------------------------- 清理
call('DELETE', '/api/instances/%s/expose' % iid, TEN)
call('POST', '/api/instances/%s/release' % iid, TEN)
print('\n========== 结果：%d 通过 / %d 失败 ==========' % (len(PASS), len(FAIL)))
if FAIL:
    print('失败项：' + ' | '.join(FAIL))
    raise SystemExit(1)
