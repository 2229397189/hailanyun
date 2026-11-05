#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批次二回归：组织架构/成本分摊、审批流、资源包、子账号配额、租户网络隔离、离线授权、文档与 SDK。

断言全部看服务端状态（DB / 接口回读 / kubectl），不看 HTTP 200。
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


def login(u, p):
    return call('POST', '/api/auth/login', body={'username': u, 'password': p}).get('token')


def kubectl(*args):
    try:
        return subprocess.run(['docker', 'exec', 'k3s-test', 'kubectl'] + list(args),
                              capture_output=True, text=True, timeout=30).stdout
    except Exception as e:
        return 'ERR ' + str(e)


ADM = login('admin', 'Hly@2026')
TEN = login('tenant_a', 'Hly@2026')

# ---------------------------------------------------------------- 1. 组织架构 + 成本分摊
r1 = call('POST', '/api/admin/org-units', ADM, {'name': '回归-研发中心'})
check('新建顶级组织', 'id' in r1, r1.get('message'))
root = r1.get('id')
r2 = call('POST', '/api/admin/org-units', ADM, {'name': '回归-AI 平台部', 'parentId': root})
check('新建子组织', 'id' in r2, r2.get('message'))
child = r2.get('id')

r3 = call('POST', '/api/admin/tenants/t1/org', ADM, {'orgUnitId': child})
check('租户挂到子组织', r3.get('orgUnitId') == child, r3.get('message'))
cost = call('GET', '/api/admin/reports/cost-by-org', ADM)
rows = {r['org_id']: r for r in cost.get('rows', [])}
check('成本报表出现该组织', child in rows, list(rows.keys())[:4])
check('报表总额与明细一致',
      abs(sum(float(r['amount']) for r in cost['rows']) - float(cost['total'])) < 0.01,
      cost.get('total'))

r_del = call('DELETE', '/api/admin/org-units/%s' % root, ADM)
check('有下级的组织不能删', r_del.get('_http') == 400, r_del.get('_msg'))

# ---------------------------------------------------------------- 2. 审批流
r4 = call('POST', '/api/approvals', TEN, {'type': 'quota', 'payload': '16 # 回归提额'})
check('租户提交提额申请', r4.get('status') == 'pending', r4.get('message'))
ap = r4.get('id')
pend = call('GET', '/api/admin/approvals?status=pending', ADM)
check('管理端能看到待审批', any(x['id'] == ap for x in pend), len(pend))

r5 = call('POST', '/api/admin/approvals/%s/decide' % ap, ADM, {'decision': 'approved', 'remark': '回归通过'})
check('审批通过', r5.get('status') == 'approved', r5.get('message'))
t1 = [t for t in call('GET', '/api/admin/tenants', ADM) if t['id'] == 't1'][0]
check('通过后配额已生效', int(t1['quota_gpus']) == 16, t1['quota_gpus'])
r6 = call('POST', '/api/admin/approvals/%s/decide' % ap, ADM, {'decision': 'approved'})
check('重复审批被拒', r6.get('_http') == 400, r6.get('_msg'))
mine = call('GET', '/api/approvals', TEN)
check('租户能看到自己的审批结果', any(x['id'] == ap and x['status'] == 'approved' for x in mine))
call('POST', '/api/admin/tenants/t1/quota', ADM, {'quotaGpus': 8})

# ---------------------------------------------------------------- 3. 资源包（Token 套餐）
r7 = call('POST', '/api/admin/packs', ADM, {'tenantId': 't1', 'amount': 100, 'name': '回归套餐', 'days': 30})
check('发放 Token 套餐', 'id' in r7, r7.get('message'))
packs = call('GET', '/api/packs', TEN)
check('租户可见套餐', float(packs.get('remaining', 0)) >= 100, packs.get('remaining'))

key = call('POST', '/api/api-keys', TEN, {'name': '回归-套餐key'})
raw = key.get('key') or ''
bal_before = float([t for t in call('GET', '/api/admin/tenants', ADM) if t['id'] == 't1'][0]['balance_yuan'])


def v1(path, body=None):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body else None,
                                 method='POST' if body else 'GET')
    req.add_header('Authorization', 'Bearer ' + raw)
    req.add_header('Content-Type', 'application/json')
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode() or '{}')
    except urllib.error.HTTPError as e:
        return e.code, {}


s1, _ = v1('/v1/chat/completions', {'model': 'deepseek-chat', 'messages': [{'role': 'user', 'content': '套餐抵扣测试'}]})
check('有套餐时调用成功', s1 == 200, s1)
bal_after = float([t for t in call('GET', '/api/admin/tenants', ADM) if t['id'] == 't1'][0]['balance_yuan'])
packs2 = call('GET', '/api/packs', TEN)
check('费用从套餐扣、余额不变', abs(bal_after - bal_before) < 0.0001,
      '余额 %.4f → %.4f' % (bal_before, bal_after))
check('套餐剩余减少', float(packs2['remaining']) < float(packs.get('remaining')),
      '%s → %s' % (packs.get('remaining'), packs2.get('remaining')))

# ---------------------------------------------------------------- 4. 子账号配额
sub_user = 'subreg%d' % (int(time.time()) % 100000)
r8 = call('POST', '/api/subaccounts', TEN, {'username': sub_user, 'password': 'Sub@2026pass', 'quotaGpus': 1})
check('子账号带配额创建', 'id' in r8, r8.get('message'))
subs = call('GET', '/api/subaccounts', TEN)
check('列表带出配额字段', any(x['username'] == sub_user and x.get('quota_gpus') == 1 for x in subs))

SUB = login(sub_user, 'Sub@2026pass')
check('子账号可登录', bool(SUB))
ok = call('POST', '/api/orders', SUB, {'sku': '1', 'hours': 1, 'orderNo': 'sub-ok-%d' % int(time.time())})
check('子账号 1 卡下单成功', ok.get('instanceId') is not None, ok.get('message'))
bad = call('POST', '/api/orders', SUB, {'sku': '4', 'hours': 1, 'orderNo': 'sub-bad-%d' % int(time.time())})
check('超出子账号配额被拒', bad.get('_http') == 400 and '配额' in str(bad.get('_msg')), bad.get('_msg'))
if ok.get('instanceId'):
    call('POST', '/api/instances/%s/release' % ok['instanceId'], SUB)

# ---------------------------------------------------------------- 5. 租户网络隔离
prov = call('POST', '/api/admin/tenants/provision', ADM,
            {'name': '回归-隔离租户', 'quotaGpus': 2, 'mode': 'trial',
             'username': 'isoreg%d' % (int(time.time()) % 100000)})
ns = prov.get('namespace') or ('tenant-' + str(prov.get('id')))
time.sleep(1)
np = kubectl('get', 'networkpolicy', '-n', ns, '-o', 'name')
check('命名空间已下发 NetworkPolicy', 'tenant-isolation' in np, np.strip() or '(空)')

# ---------------------------------------------------------------- 6. 离线授权
lic = call('POST', '/api/admin/license', ADM, {'licensee': '回归客户', 'edition': 'standard', 'maxGpus': 8, 'days': 30})
check('签发 License', 'signature' in lic, lic.get('message'))
st = call('GET', '/api/admin/license', ADM)
check('授权校验为有效', st.get('status') == 'valid', st.get('message'))

# 过期授权：直接写一条过期记录再验
subprocess.run(['docker', 'exec', 'gpu-rental-pg', 'psql', '-U', 'rental', '-d', 'rental', '-c',
                "INSERT INTO licenses(id,licensee,edition,max_gpus,expire_at,signature,created_at) "
                "VALUES ('licexp','过期客户','standard',8, now() - interval '1 day','deadbeef', now());"],
               capture_output=True, text=True)
st2 = call('GET', '/api/admin/license', ADM)
check('过期授权判定为无效', st2.get('status') == 'invalid', st2.get('message'))
bad_order = call('POST', '/api/orders', TEN, {'sku': '1', 'hours': 1, 'orderNo': 'lic-bad-%d' % int(time.time())})
check('授权过期时禁止新建实例', bad_order.get('_http') == 400 and '授权' in str(bad_order.get('_msg')),
      bad_order.get('_msg'))
call('DELETE', '/api/admin/license', ADM)
st3 = call('GET', '/api/admin/license', ADM)
check('撤销后回到开发模式', st3.get('status') == 'none', st3.get('message'))

# ---------------------------------------------------------------- 7. 文档与 SDK
with urllib.request.urlopen(BASE + '/docs', timeout=20) as r:
    html = r.read().decode()
check('文档页可访问且含快速开始', r.status == 200 and '快速开始' in html, len(html))
spec = call('GET', '/api/public/openapi.json')
check('OpenAPI 描述可解析', spec.get('openapi', '').startswith('3.'), list(spec.get('paths', {}))[:4])
check('OpenAPI 覆盖推理与实例接口',
      '/v1/chat/completions' in spec.get('paths', {}) and '/api/orders' in spec.get('paths', {}))
import os
sdk = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   'gpu-rental-skeleton', 'deploy', 'sdk', 'tokenark_sdk.py')
check('Python SDK 文件存在', os.path.exists(sdk), sdk)
compile(open(sdk, encoding='utf-8').read(), sdk, 'exec')
check('Python SDK 语法可编译', True)

print('\n========== 结果：%d 通过 / %d 失败 ==========' % (len(PASS), len(FAIL)))
if FAIL:
    print('失败项：' + ' | '.join(FAIL))
    raise SystemExit(1)
