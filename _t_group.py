#!/usr/bin/env python3
"""拼团全链路冒烟：拼单 → 自动成团 → 真实实例 → 我的拼团 → 取消退款 → 管理端可见"""
import json
import subprocess
import time
import urllib.error
import urllib.request

BASE = 'http://localhost:8099'
PW = 'Hly@2026'


def sql(q):
    p = subprocess.run(['docker', 'exec', 'gpu-rental-pg', 'psql', '-U', 'rental', '-d', 'rental', '-t', '-A', '-c', q],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    return p.stdout.strip()


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or '{}')


def login(user):
    s, b = call('POST', '/api/auth/login', body={'username': user, 'password': PW})
    assert s == 200, b
    return b['token']


a = login('tenant_a')
admin = login('admin')

# 可重复跑：车位 done=7/8 是种子数据，上一轮成团写进的拼单记录会让它永远满员，
# 先清掉这些记录并把残留实例释放回卡池，否则第二次跑必然报「车位已满」而不是被测功能坏了。
for i in [x for x in sql("SELECT id FROM instances WHERE tenant_id='t1' AND status<>'released';").split() if x]:
    try:
        call('POST', '/api/admin/instances/%s/release' % i, admin)
    except Exception:
        pass
sql("DELETE FROM group_joins WHERE tenant_id='t1';")
# 成团要占 1 张卡，租户配额被上一轮压到 1 卡时必然「配额不足」，先复位到种子值 8 卡
call('POST', '/api/admin/tenants/t1/quota', admin, {'quota': 8})
for _ in range(20):
    if not sql("SELECT COUNT(*) FROM instances WHERE tenant_id='t1' AND status<>'released';").startswith('0'):
        time.sleep(2)
        continue
    break

# 1. 余额基线
s, tenants = call('GET', '/api/tenants', a)
bal0 = float(tenants[0]['balance_yuan'])
print('余额基线 ¥%.2f' % bal0)

# 2. 拼一张 A100 40G（浙江）——该团 base done=7/8，拼 1 张即满员自动成团
s, cards = call('GET', '/api/catalog/group')
card = [c for c in cards if c['cardKey'] == 'A100|40|浙江'][0]
print('拼单前：%s done=%s left=%s' % (card['cardKey'], card['done'], card['left']))
assert card['left'] == 1, card

s, b = call('POST', '/api/group/join', a, {'cardKey': card['cardKey'], 'qty': 1})
assert s == 200, b
print('拼单结果：status=%s prepay=%s message=%s' % (b['status'], b['prepay'], b['message']))
join_id = b['joinId']
formed = b['formed']
assert formed and formed[0]['status'] == 'formed', formed
instance_id = formed[0]['instanceId']
print('成团实例：%s' % instance_id)

# 3. 目录进度应已满员
s, cards = call('GET', '/api/catalog/group')
card = [c for c in cards if c['cardKey'] == 'A100|40|浙江'][0]
assert card['left'] == 0 and card['done'] == 8, card
print('拼单后：%s done=%s left=%s joined=%s' % (card['cardKey'], card['done'], card['left'], card['joined']))

# 4. 我的拼团：formed + 实例状态
s, b = call('GET', '/api/group/joins', a)
mine = [j for j in b['items'] if j['id'] == join_id][0]
print('我的拼团：status=%s instanceId=%s instanceStatus=%s price=%s' %
      (mine['status'], mine['instance_id'], mine.get('instanceStatus'), mine['price_yuan']))
assert mine['status'] == 'formed' and mine['instance_id'] == instance_id

# 5. 余额：预付款转订单，净额 = 扣一个月拼团价
s, tenants = call('GET', '/api/tenants', a)
bal1 = float(tenants[0]['balance_yuan'])
print('成团后余额 ¥%.2f（差 %.2f，应约等于 -1300）' % (bal1, bal1 - bal0))
assert abs((bal0 - bal1) - 1300) < 5, (bal0, bal1)

# 6. 订单快照价 = 拼团价 / 720
s, orders = call('GET', '/api/orders?tenantId=t1', a)
grp = [o for o in orders if o['order_no'] == 'grp-' + join_id]
assert grp, 'grp 订单不存在'
print('拼团订单：sku=%s gpu=%s hours=%s unit=%s amount=%s' %
      (grp[0]['sku'], grp[0]['gpu_count'], grp[0]['hours'], grp[0]['unit_price'], grp[0]['amount']))
assert float(grp[0]['amount']) == 1300.0, grp[0]

# 7. 通知里有「拼团成功」
s, n = call('GET', '/api/notifications', a)
titles = [x['title'] for x in n['items']][:6]
print('最新通知：%s' % titles)
assert any('拼团成功' in t for t in titles), titles

# 8. 管理端能看到这笔拼单与团进度
s, b = call('GET', '/api/admin/group-joins?status=formed', admin)
row = [j for j in b['items'] if j['id'] == join_id]
assert row, b
print('管理端拼单：租户=%s status=%s instance=%s' % (row[0]['tenantName'], row[0]['status'], row[0]['instance_id']))
s, b = call('GET', '/api/admin/group-cards', admin)
c = [x for x in b if x['cardKey'] == 'A100|40|浙江'][0]
print('管理端团进度：%s done=%s left=%s formingJoins=%s' % (c['cardKey'], c['done'], c['left'], c['formingJoins']))

# 9. 管理端代操作：关机 → 开机（与用户端同一条路径），等实例真的 Ready
import time
st = ''
for _ in range(40):
    s, insts = call('GET', '/api/instances?tenantId=t1', a)
    row = [i for i in insts if i['id'] == instance_id]
    st = row[0]['status'] if row else 'gone'
    if st == 'running':
        break
    time.sleep(3)
print('实例状态：%s' % st)
assert st == 'running', st

s, b = call('POST', '/api/admin/instances/%s/poweroff' % instance_id, admin)
print('管理端关机：%s %s' % (s, b.get('message')))
assert s == 200, b
s, b = call('POST', '/api/admin/instances/%s/poweron' % instance_id, admin)
print('管理端开机：%s %s' % (s, b.get('message')))
assert s == 200, b

# 10. 清理：释放实例，差额退回
s, b = call('POST', '/api/instances/%s/release' % instance_id, a)
print('释放：%s 实结=%s 退回=%s' % (s, b.get('actualAmount'), b.get('refund')))
assert s == 200, b

# 11. 未成团拼单可取消并全额退款
s, b = call('POST', '/api/group/join', a, {'cardKey': 'RTX3090|24|重庆', 'qty': 2})
assert s == 200 and b['status'] == 'forming', b
jid2 = b['joinId']
print('拼单 2：prepay=%s left=%s' % (b['prepay'], b['card']['left']))
s, tenants = call('GET', '/api/tenants', a)
bal2 = float(tenants[0]['balance_yuan'])
s, b = call('POST', '/api/group/joins/%s/cancel' % jid2, a)
assert s == 200 and b['status'] == 'cancelled', b
print('取消退款：¥%s %s' % (b['refund'], b['message']))
s, tenants = call('GET', '/api/tenants', a)
bal3 = float(tenants[0]['balance_yuan'])
assert abs((bal3 - bal2) - 1100) < 5, (bal2, bal3)

# 12. 重复拼同一个团应被拦住
s, b = call('POST', '/api/group/join', a, {'cardKey': 'RTX3090|24|重庆', 'qty': 1})
assert s == 200, b
jid3 = b['joinId']
s, b = call('POST', '/api/group/join', a, {'cardKey': 'RTX3090|24|重庆', 'qty': 1})
print('重复拼单拦截：%s %s' % (s, b.get('message')))
assert s == 400, b
# 超额拼单拦截
s, b = call('POST', '/api/group/join', a, {'cardKey': 'A100|40|浙江', 'qty': 1})
print('满员团拦截：%s %s' % (s, b.get('message')))
assert s == 400, b
s, b = call('POST', '/api/group/joins/%s/cancel' % jid3, a)
assert s == 200, b

# 13. 已成团的拼单不可取消
s, b = call('POST', '/api/group/joins/%s/cancel' % join_id, a)
print('已成团取消拦截：%s %s' % (s, b.get('message')))
assert s == 400, b

print('\n拼团全链路 PASS')
