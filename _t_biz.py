#!/usr/bin/env python3
"""业务动作全链路冒烟：商务需求 / 智能助手 / 实例订阅推送 / 到货提醒处置"""
import json
import time
import urllib.error
import urllib.request

BASE = 'http://localhost:8099'
PW = 'Hly@2026'


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


def notes():
    s, n = call('GET', '/api/notifications', a)
    return n['items']


def note_ids():
    return {x['id'] for x in notes()}


def new_notes(before_ids):
    """接口只返回最近 100 条，用 id 集合差集判断新增，不能用长度差"""
    return [x for x in notes() if x['id'] not in before_ids]


# ---------- 1. 官网未登录提交商务需求 ----------
s, b = call('POST', '/api/public/leads', body={
    'source': '裸金属', 'model': 'H100 8卡整机', 'contactName': '陆强',
    'contactPhone': '13800001111', 'note': '要 2 台，带 RDMA 组网'})
assert s == 200 and b['status'] == 'new', b
lead_guest = b['id']
print('访客需求：%s %s' % (lead_guest, b['message']))

# 校验：手机号不合法要被拦住
s, b = call('POST', '/api/public/leads', body={'source': '裸金属', 'contactName': '张三', 'contactPhone': '12'})
print('非法手机号拦截：%s %s' % (s, b.get('message')))
assert s == 400, b

# ---------- 2. 管理端看到并跟进 ----------
s, b = call('GET', '/api/admin/leads?status=new', admin)
row = [x for x in b['items'] if x['id'] == lead_guest][0]
print('管理端需求：来源=%s 机型=%s 联系人=%s 租户=%s 待跟进=%s' %
      (row['source'], row['model'], row['contact_name'], row['tenantName'], b['newCount']))
assert row['tenantName'] == '未登录访客'

s, b = call('POST', '/api/admin/leads/%s/follow' % lead_guest, admin,
            {'status': 'done', 'reply': '已协调 2 台 H100 整机，含 RDMA，报价已发邮箱'})
assert s == 200 and b['status'] == 'done', b
print('跟进结果：%s' % b['message'])

# 结单不写回复要被拦住
s, b2 = call('POST', '/api/public/leads', body={'source': '容器云', 'contactName': '李四', 'contactPhone': '13800002222'})
s, b = call('POST', '/api/admin/leads/%s/follow' % b2['id'], admin, {'status': 'done', 'reply': ''})
print('空回复结单拦截：%s %s' % (s, b.get('message')))
assert s == 400, b

# ---------- 3. 登录租户提交需求 → 受理通知 + 跟进回复推送 ----------
before = note_ids()
s, b = call('POST', '/api/leads', a, {'source': '算力市场', 'model': 'RTX 6000D',
                                      'contactName': '星辰-王工', 'contactPhone': '13900003333',
                                      'note': '需要 4 张，跑 84G 显存模型'})
assert s == 200, b
lead_tenant = b['id']
s, b = call('POST', '/api/admin/leads/%s/follow' % lead_tenant, admin,
            {'status': 'following', 'reply': '已锁定 4 张 RTX 6000D，明天上午开通'})
assert s == 200, b
titles = [x['title'] for x in new_notes(before)]
print('租户收到的通知：%s' % titles)
assert any('需求已受理' in t for t in titles) and any('需求跟进中' in t for t in titles), titles
s, mine = call('GET', '/api/leads', a)
print('我的需求：%s 条，最新状态=%s 回复=%s' % (len(mine), mine[0]['status'], mine[0]['reply']))
assert mine[0]['id'] == lead_tenant and mine[0]['status'] == 'following'

# ---------- 4. 智能助手 ----------
for q in ['拼团怎么退钱', '现在多少钱一小时', '跑 70B 模型要多少显存']:
    s, b = call('POST', '/api/assistant/chat', a, {'message': q})
    assert s == 200, b
    print('助手[%s] source=%s\n  %s' % (q, b['source'], b['answer'][:90]))
s, b = call('POST', '/api/assistant/chat', a, {'message': ''})
print('空问题拦截：%s %s' % (s, b.get('message')))
assert s == 400, b
s, b = call('POST', '/api/assistant/chat', admin, {'message': '空闲卡还有多少'})
print('管理员问助手（无租户上下文）：%s' % b['answer'][:60])

# ---------- 5. 实例订阅推送开关真的生效 ----------
s, insts = call('GET', '/api/instances?tenantId=t1', a)
running = [i for i in insts if i['status'] == 'running']
if not running:
    # 前面的套件会把实例释放干净，这里自己开一台调试机（不占卡），不依赖环境残留
    call('POST', '/api/orders', a, {'sku': 'debug', 'hours': 1, 'orderNo': 'biz-' + str(int(time.time()))})
    for _ in range(30):
        time.sleep(2)
        s, insts = call('GET', '/api/instances?tenantId=t1', a)
        running = [i for i in insts if i['status'] == 'running']
        if running:
            break
assert running, '没有可用实例，订阅推送无从验证'
inst = running[0]
iid = inst['id']
print('测试实例：%s（%s 卡，%s）' % (iid, inst['gpu_count'], inst['status']))

s, b = call('POST', '/api/instances/%s/subscribe' % iid, a, {'on': False})
assert s == 200 and b['notifyPush'] is False, b
print('关闭推送：%s' % b['message'])

before = note_ids()
s, b = call('POST', '/api/admin/instances/%s/poweroff' % iid, admin)
assert s == 200, b
time.sleep(1)
new = new_notes(before)
print('关推送后关机，新通知 %s 条：%s' % (len(new), [x['title'] for x in new]))
assert not any('实例已关机' in x['title'] for x in new), new

s, b = call('POST', '/api/instances/%s/subscribe' % iid, a, {'on': True})
assert s == 200 and b['notifyPush'] is True, b
assert any('已开启消息推送' in x['title'] for x in notes()), '重新开启订阅应有确认通知'
before = note_ids()
s, b = call('POST', '/api/admin/instances/%s/poweron' % iid, admin)
assert s == 200, b
time.sleep(1)
new = new_notes(before)
print('开推送后开机，新通知 %s 条：%s' % (len(new), [x['title'] for x in new]))
assert any('实例开机中' in x['title'] for x in new), new

# ---------- 6. 到货提醒：登记 → 管理端处置 → 租户收到通知 ----------
s, b = call('POST', '/api/stock-alerts', a, {'model': 'H800'})
print('登记到货提醒：%s' % b['message'])
s, b = call('GET', '/api/admin/stock-alerts?status=open', admin)
row = [x for x in b['items'] if x['model'] == 'H800' and x['tenant_id'] == 't1'][0]
print('管理端待处置：%s 租户=%s 未完结=%s 条' % (row['model'], row['tenantName'], b['openCount']))
before = note_ids()
s, b = call('POST', '/api/admin/stock-alerts/%s/fulfill' % row['id'], admin, {'note': '已协调 2 张'})
assert s == 200, b
new = new_notes(before)
print('完结后租户通知：%s' % [x['title'] for x in new])
assert any('到货提醒' in x['title'] for x in new), new
s, b = call('POST', '/api/admin/stock-alerts/%s/fulfill' % row['id'], admin, {})
print('重复完结拦截：%s %s' % (s, b.get('message')))
assert s == 400, b

print('\n业务动作全链路 PASS')
