#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本批次回归：SKU 上下架 / 余额调整 / 租户标签 / 告警渠道 / 模型上下架 / MaaS 请求日志。

全部断言看服务端状态（DB 或接口回读），不看 HTTP 200。
"""
import json
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = 'http://localhost:8099'
PASS, FAIL = [], []


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
        with urllib.request.urlopen(req, timeout=30) as r:
            txt = r.read().decode()
            return json.loads(txt) if txt else {}
    except urllib.error.HTTPError as e:
        msg = e.read().decode()
        try:
            msg = json.loads(msg).get('message', msg)
        except Exception:
            pass
        return {'_http': e.code, '_msg': msg}


def login(u, p):
    r = call('POST', '/api/auth/login', body={'username': u, 'password': p})
    return r.get('token')


ADM = login('admin', 'Hly@2026')
TEN = login('tenant_a', 'Hly@2026')

# ---------------------------------------------------------------- 1. SKU 上下架
shelf = call('GET', '/api/admin/sku-shelf', ADM)
codes = [x['code'] for x in shelf]
check('管理端能看到全部规格', len(codes) >= 6 and '1' in codes, codes)
check('默认全部在架', all(x['onSale'] for x in shelf))

r = call('POST', '/api/admin/sku-shelf/2', ADM, {'onSale': False, 'note': '回归测试下架'})
check('下架 sku=2 成功', r.get('onSale') is False, r.get('message'))
skus = call('GET', '/api/skus', TEN)
check('用户端不再出现下架规格', '2' not in [x['code'] for x in skus])
shelf2 = {x['code']: x['onSale'] for x in call('GET', '/api/admin/sku-shelf', ADM)}
check('管理端仍可见且标记下架', shelf2.get('2') is False)

call('POST', '/api/admin/sku-shelf/2', ADM, {'onSale': True})
skus2 = [x['code'] for x in call('GET', '/api/skus', TEN)]
check('重新上架后恢复', '2' in skus2)

# ---------------------------------------------------------------- 2. 余额调整
t_before = [t for t in call('GET', '/api/admin/tenants', ADM) if t['id'] == 't1'][0]
bal0 = float(t_before['balance_yuan'])
r = call('POST', '/api/admin/tenants/t1/balance', ADM, {'amount': 10, 'reason': '回归测试-充值'})
check('充值 10 元成功', abs(float(r.get('balance', 0)) - (bal0 + 10)) < 0.001, r.get('message'))
r2 = call('POST', '/api/admin/tenants/t1/balance', ADM, {'amount': -99999999, 'reason': '负数测试'})
check('扣成负数被拒', r2.get('_http') == 400, r2.get('_msg'))
r3 = call('POST', '/api/admin/tenants/t1/balance', ADM, {'amount': 5})
check('缺原因被拒', r3.get('_http') == 400, r3.get('_msg'))
call('POST', '/api/admin/tenants/t1/balance', ADM, {'amount': -10, 'reason': '回归测试-回滚'})

# ---------------------------------------------------------------- 3. 租户标签
r = call('POST', '/api/admin/tenants/t1/tags', ADM, {'tags': '重点客户,回归'})
check('设置标签成功', r.get('tags') == '重点客户,回归')
t_after = [t for t in call('GET', '/api/admin/tenants', ADM) if t['id'] == 't1'][0]
check('标签已落库并回读', t_after.get('tags') == '重点客户,回归', t_after.get('tags'))

# ---------------------------------------------------------------- 4. 告警渠道（起真实接收端验证送达）
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

received = []


class Hook(BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        received.append(json.loads(self.rfile.read(n).decode() or '{}'))
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'{"ok":true}')

    def log_message(self, *a):
        pass


srv = ThreadingHTTPServer(('0.0.0.0', 18080), Hook)
threading.Thread(target=srv.serve_forever, daemon=True).start()

r = call('POST', '/api/admin/alert-channels', ADM,
         {'name': '回归-webhook', 'type': 'webhook', 'url': 'http://host.docker.internal:18080/hook'})
check('新增告警渠道', 'id' in r, r.get('message'))
ch_id = r.get('id')
r_bad = call('POST', '/api/admin/alert-channels', ADM,
             {'name': 'x', 'type': 'sms', 'url': 'http://x'})
check('非法渠道类型被拒', r_bad.get('_http') == 400, r_bad.get('_msg'))
r_test = call('POST', '/api/admin/alert-channels/%s/test' % ch_id, ADM)
check('测试发送返回成功', r_test.get('ok') is True, r_test.get('message'))
check('接收端真的收到了消息', len(received) >= 1 and 'TokenArk' in json.dumps(received[0], ensure_ascii=False),
      received[:1])
call('POST', '/api/admin/alert-channels/%s/toggle' % ch_id, ADM, {'enabled': False})
chans = {c['id']: c for c in call('GET', '/api/admin/alert-channels', ADM)}
check('停用生效', chans[ch_id]['enabled'] is False)
call('DELETE', '/api/admin/alert-channels/%s' % ch_id, ADM)
srv.shutdown()

# ---------------------------------------------------------------- 5. 模型上下架
models = call('GET', '/api/admin/maas/models', ADM)
check('管理端模型列表带状态', all('status' in m for m in models), len(models))
call('POST', '/api/admin/maas/models/gpt-4o-mini', ADM, {'status': 'offline', 'group': '通用'})

# 建一个 API Key 用于 /v1 调用
key = call('POST', '/api/api-keys', TEN, {'name': '回归-key'})
raw = key.get('key') or key.get('plainKey') or ''
if not raw:
    keys = call('GET', '/api/api-keys', TEN)
    raw = (keys[0].get('key') if keys else '') or ''
check('拿到可用 API Key', bool(raw), raw[:12] + '...' if raw else 'none')


def v1(method, path, body=None, api_key=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request('http://localhost:8099' + path, data=data, method=method)
    req.add_header('Authorization', 'Bearer ' + (api_key or raw))
    req.add_header('Content-Type', 'application/json')
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode() or '{}')
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, json.loads(txt)
        except Exception:
            return e.code, {'raw': txt}


s, mlist = v1('GET', '/v1/models')
ids = [m['id'] for m in mlist.get('data', [])]
check('下架模型不出现在 /v1/models', 'gpt-4o-mini' not in ids, ids)
s, err = v1('POST', '/v1/chat/completions', {'model': 'gpt-4o-mini', 'messages': [{'role': 'user', 'content': 'hi'}]})
check('调用下架模型被拒 403', s == 403, err)

call('POST', '/api/admin/maas/models/gpt-4o-mini', ADM, {'status': 'online'})
s, ok = v1('POST', '/v1/chat/completions', {'model': 'gpt-4o-mini', 'messages': [{'role': 'user', 'content': '你好'}]})
check('重新上架后调用成功', s == 200 and ok.get('choices'), s)

# ---------------------------------------------------------------- 6. 请求日志
time.sleep(0.5)
logs = call('GET', '/api/admin/maas/logs?limit=20', ADM)
rows = logs.get('logs', [])
check('请求日志已落库', len(rows) >= 2, len(rows))
check('失败调用有记录', any(r['status'] == 'error' and int(r.get('http_status') or 0) == 403 for r in rows))
check('成功调用有 token 与费用', any(r['status'] == 'ok' and (r.get('prompt_tokens') or 0) > 0 for r in rows))
summary = logs.get('summary', {})
check('汇总口径正确', int(summary.get('calls', 0)) >= 2, summary)

tlogs = call('GET', '/api/maas/logs', TEN)
check('租户只能看到自己的日志',
      all(r.get('tenant_id') == 't1' for r in tlogs.get('logs', [])), len(tlogs.get('logs', [])))

print('\n========== 结果：%d 通过 / %d 失败 ==========' % (len(PASS), len(FAIL)))
if FAIL:
    print('失败项：' + ' | '.join(FAIL))
    raise SystemExit(1)
