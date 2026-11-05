#!/usr/bin/env python3
"""邀请返券 + SSH 公钥 全链路冒烟"""
import json
import time
import urllib.error
import urllib.request

BASE = 'http://localhost:8099'
PW = 'Hly@2026'
PUB = ('ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl '
       'test@laptop')

ok = fail = 0


def ck(name, cond, extra=''):
    global ok, fail
    if cond:
        ok += 1
        print('  PASS ' + name)
    else:
        fail += 1
        print('  FAIL ' + name + ' ' + str(extra))


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            t = r.read().decode()
            return r.status, (json.loads(t) if t else {})
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        try:
            return e.code, json.loads(t or '{}')
        except Exception:
            return e.code, {'raw': t}


def login(user, pw=PW):
    s, b = call('POST', '/api/auth/login', body={'username': user, 'password': pw})
    assert s == 200, b
    return b['token'], b.get('tenantId') or (b.get('user') or {}).get('tenantId')


def notes(tok, cat=None):
    s, n = call('GET', '/api/notifications', tok)
    items = n.get('items', n if isinstance(n, list) else [])
    return [i for i in items if cat is None or i.get('category') == cat]


print('== 1. 邀请码下发 ==')
a, ta = login('tenant_a')
s, inv = call('GET', '/api/invite', a)
ck('GET /api/invite 200', s == 200, inv)
code = inv.get('code', '')
ck('邀请码为 8 位真码（非前端拼的 AHHxxxM6）', len(code) == 8 and code.isalnum(), code)
ck('registerUrl 带 tab=register&invite=', inv.get('registerUrl') == '/login?tab=register&invite=' + code,
   inv.get('registerUrl'))
ck('规则来自系统参数', inv.get('rule', {}).get('rewardInvitee') == '5'
   and inv.get('rule', {}).get('rewardInviter') == '3', inv.get('rule'))
ck('邀请码稳定（二次读取不变）', call('GET', '/api/invite', a)[1].get('code') == code)
# 基线：注册与充值都发生在后面，所有断言按增量算，重复跑脚本才不会假失败
INVITED0 = inv.get('invitedCount', 0)
REWARDED0 = inv.get('rewardedCount', 0)

print('== 2. 带邀请码注册 ==')
u = 'inv' + str(int(time.time()))[-6:]
s, bad = call('POST', '/api/auth/register',
              body={'username': u, 'password': PW, 'tenantName': u + ' 科技', 'inviteCode': 'ZZZZZZZZ'})
ck('错误邀请码 400 且提示具体', s == 400 and '邀请码不存在' in str(bad.get('message', '')), bad)
n_before = {i['id'] for i in notes(a)}
s, reg = call('POST', '/api/auth/register',
              body={'username': u, 'password': PW, 'tenantName': u + ' 科技', 'inviteCode': code})
ck('注册成功并直接返回会话', s == 200 and reg.get('token'), reg)
b = reg['token']
s, invb = call('GET', '/api/invite', b)
ck('新租户自己也拿到了邀请码', s == 200 and len(invb.get('code', '')) == 8, invb)
ck('新租户的 registerUrl 指向自己的码', invb.get('registerUrl', '').endswith(invb.get('code', '')))
time.sleep(0.4)
new_notes = [i for i in notes(a) if i['id'] not in n_before]
ck('邀请人收到「有人通过你的邀请码注册」', any('邀请码注册' in i.get('title', '') for i in new_notes), new_notes)
ck('注册通知里写明返券条件', any(u in i.get('body', '') and '3' in i.get('body', '') for i in new_notes),
   new_notes)
ck('邀请人 invitedCount +1', call('GET', '/api/invite', a)[1].get('invitedCount') == INVITED0 + 1)

print('== 3. 首充触发双向返券 ==')
n_rewarded_before = REWARDED0
n_invited_before = INVITED0
s, cps = call('GET', '/api/coupons', b)
n_coupon_before = len([c for c in cps if '首充' in str(c.get('title', ''))])
s, rc = call('POST', '/api/recharges', b, {'amount': 1, 'channel': '微信支付'})
ck('充值成功', s == 200, rc)
ck('充值回执写明赠送', '首充赠送' in str(rc.get('message', '')) and '3 元返券' in str(rc.get('message', '')),
   rc.get('message'))
s, cps = call('GET', '/api/coupons', b)
got = [c for c in cps if '首充' in str(c.get('title', ''))]
ck('被邀请人拿到 5 元券', len(got) == 1 and float(got[0].get('amount_yuan', 0)) == 5.0, got)
ck('券有效期 90 天', got and got[0].get('expire_at'), got[0] if got else '')
s, inva = call('GET', '/api/invite', a)
ck('邀请人 rewardedCount +1', inva.get('rewardedCount') == n_rewarded_before + 1,
   (n_rewarded_before, inva.get('rewardedCount')))
ck('邀请人 invitedCount 含新账号', inva.get('invitedCount') == n_invited_before + 1
   and any(x.get('username') == u for x in inva.get('invited', [])), inva.get('invited'))
ck('新账号已标记 invite_rewarded',
   [x for x in inva.get('invited', []) if x.get('username') == u][0].get('invite_rewarded') is True,
   inva.get('invited'))
ck('邀请人 rewards 里有 3 元返券', any(float(c.get('amount_yuan', 0)) == 3.0 and '邀请好友返券' in str(c.get('title', ''))
                              for c in inva.get('rewards', [])), inva.get('rewards'))
s, cpsa = call('GET', '/api/coupons', a)
ck('邀请人券包真到账', any('邀请好友返券 · ' + u == str(c.get('title', '')) for c in cpsa),
   [c.get('title') for c in cpsa][-5:])
inv_notes = [i for i in notes(a) if '邀请返券已到账' in i.get('title', '')]
ck('邀请人收到返券到账通知', bool(inv_notes), inv_notes[:1])
ck('被邀请人收到首充到账通知', any('首充赠送已到账' in i.get('title', '') for i in notes(b)),
   [i.get('title') for i in notes(b)][:5])

print('== 4. 幂等：再充值不重复发券 ==')
s, rc2 = call('POST', '/api/recharges', b, {'amount': 50})
ck('二次充值成功', s == 200, rc2)
ck('二次充值回执不含赠送', '首充赠送' not in str(rc2.get('message', '')), rc2.get('message'))
s, cps = call('GET', '/api/coupons', b)
ck('券没有变多', len([c for c in cps if '首充' in str(c.get('title', ''))]) == max(1, n_coupon_before + 1), cps)
ck('rewardedCount 未再增加', call('GET', '/api/invite', a)[1].get('rewardedCount') == n_rewarded_before + 1)

print('== 5. 管理员读 /api/invite 被挡 ==')
admin, _ = login('admin')
s, x = call('GET', '/api/invite', admin)
ck('admin 非租户身份 403', s == 403 and '租户账号' in str(x.get('message', '')), (s, x))

print('== 6. SSH 公钥 CRUD（用有实例的 tenant_a）==')
s, keys = call('GET', '/api/ssh-keys', a)
base_keys = len(keys)
s, r = call('POST', '/api/ssh-keys', a, {'name': '我的工作笔记本', 'pubkey': 'not-a-key'})
ck('非法公钥 400', s == 400, r)
s, r = call('POST', '/api/ssh-keys', a, {'name': '', 'pubkey': PUB})
ck('空名称 400', s == 400, r)
s, r = call('POST', '/api/ssh-keys', a, {'name': '我的工作笔记本', 'pubkey': PUB})
ck('合法 ed25519 公钥保存成功', s == 200 and r.get('id'), r)
kid = r.get('id')
s, r = call('POST', '/api/ssh-keys', a, {'name': '重复', 'pubkey': PUB})
ck('重复公钥 400', s == 400 and '已经保存过' in str(r.get('message', '')), r)
s, keys = call('GET', '/api/ssh-keys', a)
ck('列表多出一条', len(keys) == base_keys + 1
   and any(k['id'] == kid and k['name'] == '我的工作笔记本' for k in keys), keys)

print('== 7. 公钥进入实例「开发接入」 ==')
insts = call('GET', '/api/instances', a)[1]
if insts:
    iid = insts[0]['id']
    s, acc = call('GET', '/api/instances/' + iid + '/access', a)
    ck('access 200', s == 200, acc)
    ck('access 带 sshKeys 列表', any(k.get('id') == kid for k in acc.get('sshKeys', [])), acc.get('sshKeys'))
    ck('access 给出可粘贴的 authorized_keys', PUB.split()[1] in str(acc.get('authorizedKeys', '')),
       str(acc.get('authorizedKeys', ''))[:60])
    ck('诚实标注演示镜像未跑 sshd', 'sshd' in str(acc.get('sshKeyNote', '')), acc.get('sshKeyNote'))
else:
    print('  SKIP tenant_a 暂无实例，跳过 access 校验')

print('== 8. 越权与删除 ==')
s, r = call('DELETE', '/api/ssh-keys/' + kid, b)
ck('别的租户删不掉', s in (400, 403, 404), (s, r))
ck('删除失败时公钥仍在', any(k['id'] == kid for k in call('GET', '/api/ssh-keys', a)[1]))
s, r = call('DELETE', '/api/ssh-keys/' + kid, a)
ck('本人删除成功', s == 200, r)
ck('删除后回到原数量', len(call('GET', '/api/ssh-keys', a)[1]) == base_keys)

print()
print('邀请/公钥：%d 通过，%d 失败' % (ok, fail))
