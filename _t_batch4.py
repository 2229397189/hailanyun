#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批次四回归：多上游路由 / 三方聚合 / Key 限流与额度。

起一个本地假上游（OpenAI 兼容），验证：
  1) 按成本从低到高路由，命中期望的那家
  2) 最便宜那家挂掉时自动切下一家（failover）
  3) Key 速率限制（rpm）与月度额度（quota_yuan）
"""
import json
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = 'http://localhost:8099'
PASS, FAIL = [], []
HITS = []


def check(name, cond, detail=''):
    (PASS if cond else FAIL).append(name)
    print(('PASS ' if cond else 'FAIL ') + name + ('  ' + str(detail) if detail else ''))


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    if body is not None:
        req.add_header('Content-Type', 'application/json')
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            txt = r.read().decode()
            return json.loads(txt) if txt else {}
    except urllib.error.HTTPError as e:
        msg = e.read().decode()
        try:
            msg = json.loads(msg).get('message', msg)
        except Exception:
            pass
        return {'_http': e.code, '_msg': msg}


class FakeLLM(BaseHTTPRequestHandler):
    """最小 OpenAI 兼容实现，回显自己被调用了多少次"""
    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        body = json.loads(self.rfile.read(n).decode() or '{}')
        HITS.append(body.get('model'))
        content = '来自假上游的回复 #%d，模型 %s' % (len(HITS), body.get('model'))
        out = {
            'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': content},
                         'finish_reason': 'stop'}],
            'usage': {'prompt_tokens': 7, 'completion_tokens': 9},
        }
        data = json.dumps(out).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass


srv = ThreadingHTTPServer(('0.0.0.0', 18090), FakeLLM)
threading.Thread(target=srv.serve_forever, daemon=True).start()

ADM = call('POST', '/api/auth/login', body={'username': 'admin', 'password': 'Hly@2026'})['token']
TEN = call('POST', '/api/auth/login', body={'username': 'tenant_a', 'password': 'Hly@2026'})['token']

# 清掉上一轮残留，保证路由结果可判定
for u in call('GET', '/api/admin/llm-upstreams', ADM):
    call('DELETE', '/api/admin/llm-upstreams/' + u['id'], ADM)

# ---------------------------------------------------------------- 1. 成本优先路由
r1 = call('POST', '/api/admin/llm-upstreams', ADM,
          {'name': 'cheap', 'baseUrl': 'http://host.docker.internal:18090/v1',
           'models': 'deepseek-chat', 'unitCost': 1})
check('注册便宜上游', 'id' in r1, r1.get('message'))
r2 = call('POST', '/api/admin/llm-upstreams', ADM,
          {'name': 'pricey', 'baseUrl': 'http://127.0.0.1:19999/v1',
           'models': 'deepseek-chat', 'unitCost': 9})
check('注册高价上游（地址不可达）', 'id' in r2, r2.get('message'))

key = call('POST', '/api/api-keys', TEN, {'name': '路由回归'})
raw = key.get('key') or ''
check('拿到 API Key', bool(raw))


def chat(model='deepseek-chat', prompt='路由测试'):
    req = urllib.request.Request(BASE + '/v1/chat/completions',
                                 data=json.dumps({'model': model,
                                                  'messages': [{'role': 'user', 'content': prompt}],
                                                  'max_tokens': 16}).encode(),
                                 method='POST')
    req.add_header('Authorization', 'Bearer ' + raw)
    req.add_header('Content-Type', 'application/json')
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode() or '{}')
    except urllib.error.HTTPError as e:
        return e.code, {}


s, resp = chat()
content = (resp.get('choices') or [{}])[0].get('message', {}).get('content', '')
check('请求命中便宜上游', s == 200 and '假上游' in content, content[:40])
check('假上游真的被调用', len(HITS) >= 1, len(HITS))

logs = call('GET', '/api/admin/maas/logs?limit=5', ADM)
top = (logs.get('logs') or [{}])[0]
check('日志记录了命中的上游', top.get('upstream') == 'cheap', top.get('upstream'))

# ---------------------------------------------------------------- 2. failover
r3 = call('POST', '/api/admin/llm-upstreams', ADM,
          {'name': 'dead-but-cheaper', 'baseUrl': 'http://127.0.0.1:19998/v1',
           'models': 'deepseek-chat', 'unitCost': 0.1})
check('插入更便宜但不可达的上游', 'id' in r3, r3.get('message'))
before = len(HITS)
s2, resp2 = chat(prompt='故障切换测试')
c2 = (resp2.get('choices') or [{}])[0].get('message', {}).get('content', '')
check('不可达时自动切到下一家', s2 == 200 and '假上游' in c2, c2[:40])
check('切换后便宜上游被再次调用', len(HITS) > before, '%d → %d' % (before, len(HITS)))

# 清理这个故障源，避免影响后续限流用例
for u in call('GET', '/api/admin/llm-upstreams', ADM):
    if u['name'] == 'dead-but-cheaper':
        call('DELETE', '/api/admin/llm-upstreams/' + u['id'], ADM)

# ---------------------------------------------------------------- 3. Key 限流
keys = call('GET', '/api/api-keys', TEN)
kid = [k for k in keys if k['name'] == '路由回归'][0]['id']
r = call('POST', '/api/api-keys/%s/limits' % kid, TEN, {'quotaYuan': 100, 'rpm': 2})
check('设置 2 次/分钟', '限制已更新' in str(r.get('message')), r.get('message'))

codes = [chat(prompt='限流 %d' % i)[0] for i in range(4)]
check('超过速率返回 429', 429 in codes, codes)

# ---------------------------------------------------------------- 4. 月度额度
r = call('POST', '/api/api-keys/%s/limits' % kid, TEN, {'quotaYuan': 0.00005, 'rpm': 0})
check('设置极小额度', '限制已更新' in str(r.get('message')), r.get('message'))
time.sleep(1)
s3, err = chat(prompt='额度测试')
check('额度用尽返回 402', s3 == 402, s3)

# 清理
call('POST', '/api/api-keys/%s/limits' % kid, TEN, {'quotaYuan': None, 'rpm': None})
call('DELETE', '/api/api-keys/' + kid, TEN)
for u in call('GET', '/api/admin/llm-upstreams', ADM):
    call('DELETE', '/api/admin/llm-upstreams/' + u['id'], ADM)
srv.shutdown()

print('\n========== 结果：%d 通过 / %d 失败 ==========' % (len(PASS), len(FAIL)))
if FAIL:
    print('失败项：' + ' | '.join(FAIL))
    raise SystemExit(1)
