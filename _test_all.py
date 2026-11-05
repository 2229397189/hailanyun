#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""海兰云 POC 全量功能测试（三角色真交互 + 服务端状态断言），仅标准库。"""
import json
import time
import urllib.parse
import urllib.request
import urllib.error

BASE = 'http://localhost:8099'
PASS, FAIL = [], []


def check(name, cond, detail=''):
    (PASS if cond else FAIL).append(name)
    print(('PASS ' if cond else 'FAIL ') + name + ('  ' + str(detail) if detail else ''))


def req(method, path, token=None, body=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    r = urllib.request.Request(BASE + '/api' + path, data=data, method=method)
    if token:
        r.add_header('Authorization', 'Bearer ' + token)
    if body is not None:
        r.add_header('Content-Type', 'application/json')
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            txt = resp.read().decode('utf-8')
            return json.loads(txt) if txt else {}
    except urllib.error.HTTPError as e:
        txt = e.read().decode('utf-8')
        try:
            msg = json.loads(txt).get('message')
        except Exception:
            msg = txt[:80]
        raise RuntimeError('HTTP %s %s %s' % (e.code, method, msg))


def login(username, password='Hly@2026'):
    return req('POST', '/auth/login', body={'username': username, 'password': password})


RUN = str(int(time.time()))


def tenant_of(token, tid):
    return next(t for t in req('GET', '/tenants', token) if t['id'] == tid)


def wait_inst(token, tid, iid, target, timeout=90):
    t0 = time.time()
    while time.time() - t0 < timeout:
        rows = req('GET', '/instances?tenantId=' + tid, token)
        it = next((x for x in rows if x.get('id') == iid), None)
        if it and it.get('status') in (target, 'failed'):
            return it
        time.sleep(3)
    return it


# ============ 租户 tenant_a ============
r = login('tenant_a')
TA = r['token']
TID = r['user']['tenantId']
print('== 租户 tenant_a 登录，租户', TID, '==')

# 清理本脚本历史残留实例（避免多次运行耗尽卡池；释放走正常结算路径）
for x in req('GET', '/instances?tenantId=' + TID, TA):
    if x.get('status') in ('running', 'stopped', 'provisioning', 'resetting'):
        try:
            req('POST', '/instances/%s/release' % x['id'], TA)
        except Exception:
            pass
time.sleep(6)  # 等 reconciler 完成回收

# 配额复位到种子值 8 卡：有人在管理端把它调成 1 之后，本脚本后半段的多卡下单必然失败，
# 报的是「配额不足」而不是被测功能的问题。
try:
    req('POST', '/admin/tenants/%s/quota' % TID, login('admin')['token'], {'quota': 8})
except Exception as e:
    print('配额复位跳过：', e)

skus = req('GET', '/skus', TA)
codes = {s['code'] for s in skus}
check('SKU 六类齐全(1/2/4/whole/offpeak/debug)', codes >= {'1', '2', '4', 'whole', 'offpeak', 'debug'}, sorted(codes))
sp = {s['code']: float(s['unitPrice']) for s in skus}
check('闲时卡 5 折', abs(sp.get('offpeak', 0) - sp.get('1', 0) * 0.5) < 0.01, sp.get('offpeak'))
check('无卡调试 0.10 元/时', abs(sp.get('debug', 0) - 0.10) < 0.001, sp.get('debug'))

plans = req('GET', '/plans', TA)
check('套餐档位存在', len(plans) >= 4, [p['label'] for p in plans])

# 单卡订单
bal0 = float(tenant_of(TA, TID)['balance_yuan'])
o1 = req('POST', '/orders', TA, {'tenantId': TID, 'sku': '1', 'hours': 1, 'orderNo': 't-ord1-' + RUN})
check('单卡下单返回实例', bool(o1.get('instanceId')), o1.get('instanceId'))
i1 = o1['instanceId']
it = wait_inst(TA, TID, i1, 'running')
check('单卡实例 -> running（真实 K8s）', it and it['status'] == 'running', it and it.get('node_id'))
bal1 = float(tenant_of(TA, TID)['balance_yuan'])
check('下单预扣余额减少', bal1 < bal0, '%.2f -> %.2f' % (bal0, bal1))

# 无卡调试
oD = req('POST', '/orders', TA, {'tenantId': TID, 'sku': 'debug', 'hours': 1, 'orderNo': 't-ordd-' + RUN})
iD = oD['instanceId']
itD = wait_inst(TA, TID, iD, 'running')
check('无卡调试实例 running', itD and itD['status'] == 'running', itD and itD.get('gpu_count'))
check('无卡调试 gpu_count=0', itD and int(itD.get('gpu_count')) == 0)

# 闲时订单金额核对（按小时预扣：2.8 元/时 × 0.5 折 × 1 时；若学生认证已通过再叠加 9 折）
balA = float(tenant_of(TA, TID)['balance_yuan'])
oF = req('POST', '/orders', TA, {'tenantId': TID, 'sku': 'offpeak', 'hours': 1, 'orderNo': 't-ordf-' + RUN})
iF = oF['instanceId']
balB = float(tenant_of(TA, TID)['balance_yuan'])
cost_f = balA - balB
stu_disc = 0.9 if req('GET', '/student-cert', TA).get('status') == 'approved' else 1.0
cost_expect = sp.get('1', 2.8) * 0.5 * stu_disc
check('闲时订单按 5 折预扣', abs(cost_f - cost_expect) < 0.01, '实扣 %.2f 期望 %.2f' % (cost_f, cost_expect))
wait_inst(TA, TID, iF, 'running')

# 关机 / 开机
req('POST', '/instances/%s/poweroff' % i1, TA)
it = wait_inst(TA, TID, i1, 'stopped')
check('关机 -> stopped', it and it['status'] == 'stopped')
req('POST', '/instances/%s/poweron' % i1, TA)
it = wait_inst(TA, TID, i1, 'running')
check('开机 -> running', it and it['status'] == 'running')

# 重启
req('POST', '/instances/%s/restart' % i1, TA)
it = wait_inst(TA, TID, i1, 'running')
check('重启后仍 running', it and it['status'] == 'running')

# 定时关机（epoch 毫秒，1 小时后）
req('POST', '/instances/%s/schedule-shutdown' % i1, TA, {'at': str(int(time.time() * 1000) + 3600_000)})
sch = req('GET', '/instances/%s/schedules' % i1, TA)
check('定时关机任务落库', len(sch) >= 1, sch)
req('POST', '/instances/%s/cancel-schedule' % i1, TA)
sch2 = req('GET', '/instances/%s/schedules' % i1, TA)
check('取消定时任务', all(x.get('status') != 'pending' for x in sch2), sch2)

# 克隆（自动沿用原规格与剩余时长）
oc = req('POST', '/instances/%s/clone' % i1, TA)
check('克隆产生新实例', oc.get('instanceId') and oc['instanceId'] != i1, oc.get('instanceId'))
iC = oc.get('instanceId')
wait_inst(TA, TID, iC, 'running')

# 重置
req('POST', '/instances/%s/reset' % iC, TA)
itC = wait_inst(TA, TID, iC, 'running')
check('重置后恢复 running', itC and itC['status'] == 'running')

# 续费
exp0 = itC['expire_at']
req('POST', '/instances/%s/renew' % iC, TA, {'hours': 2})
itC2 = next(x for x in req('GET', '/instances?tenantId=' + TID, TA) if x['id'] == iC)
check('续费延长到期时间', itC2['expire_at'] > exp0, '%s -> %s' % (exp0, itC2['expire_at']))

# 升降配：单卡 -> 2 卡
or_ = req('POST', '/instances/%s/resize' % iC, TA, {'gpuCount': 2})
iR = or_.get('newInstanceId') or or_.get('instanceId')
check('升配返回新实例', bool(iR), or_)
wait_inst(TA, TID, iR, 'running')
rows = req('GET', '/instances?tenantId=' + TID, TA)
old = next((x for x in rows if x['id'] == iC), None)
new2 = next((x for x in rows if x['id'] == iR), None)
check('旧实例已释放', old and old['status'] == 'released')
check('新实例 2 卡', new2 and int(new2.get('gpu_count')) == 2)

# 存镜像 / 分享 / 扫描
m1 = req('POST', '/instances/%s/save-image' % iR, TA, {'name': 'venv-mind-h 快照'})
img_id = m1.get('id')
check('存镜像落库', bool(img_id), m1)
req('POST', '/images/%s/share' % img_id, TA)
comm = req('GET', '/images/community', TA)
check('社区镜像可见', any(x.get('id') == img_id for x in comm))
m3 = req('POST', '/images/%s/scan' % img_id, TA)
check('镜像扫描出结果', m3.get('status') in ('安全', '风险'), m3)

# 数据集
ds = req('GET', '/datasets', TA)
check('数据集 5 个', len(ds) >= 5, [d['name'] for d in ds])
mt = req('POST', '/instances/%s/mount-dataset' % iR, TA, {'datasetId': ds[0]['id']})
check('数据集挂载落库', '已挂载' in str(mt.get('message', '')), mt)
mts = req('GET', '/instances/%s/mounts' % iR, TA)
check('挂载列表含该数据集', any(x.get('id') == ds[0]['id'] for x in mts))

# 代金券（兑换对象由管理端发券提供，见下方 admin 段；此处先只验证券包可读）
cps = req('GET', '/coupons', TA)
check('租户券包可读', isinstance(cps, list), [(c['title'], c['amount_yuan']) for c in cps])

# 实名 / 学生认证（幂等：已通过则跳过提交）
rn_exist = req('GET', '/realname', TA)
if rn_exist.get('status') not in ('pending', 'approved'):
    rn = req('POST', '/realname', TA, {'realName': '陆强', 'idNo': '110101200001010011', 'phone': '13800000001'})
    check('实名认证提交', bool(rn.get('message')), rn)
sc_exist = req('GET', '/student-cert', TA)
if sc_exist.get('status') not in ('pending', 'approved'):
    sc = req('POST', '/student-cert', TA, {'school': '北京理工大学', 'studentNo': '1120201234'})
    check('学生认证提交', bool(sc.get('message')), sc)

# 文件存储
st = req('POST', '/storage', TA, {'name': '模型权重-测试', 'zone': '可用区 A', 'isDir': False, 'sizeGb': 1.5})
check('存储新建文件', st.get('message'), st)
stl = req('GET', '/storage?zone=' + urllib.parse.quote('可用区 A'), TA)
check('存储列表含新文件', any(x.get('name') == '模型权重-测试' for x in stl.get('items', [])))

# API 密钥 / 通知 / 工单
ak = req('POST', '/api-keys', TA, {'name': 'cli-key'})
check('API 密钥创建', bool(ak.get('key')), ak.get('id'))
aks = req('GET', '/api-keys', TA)
check('API 密钥列表', len(aks) >= 1)
if aks:
    req('DELETE', '/api-keys/' + aks[0]['id'], TA)
nt = req('GET', '/notifications', TA)
check('通知列表可读', isinstance(nt.get('items'), list), '共 %s 条，未读 %s' % (nt.get('total'), nt.get('unread')))
tk = req('POST', '/tickets', TA, {'subject': '测试工单', 'content': '全量测试'})
check('工单创建', bool(tk.get('id')), tk.get('id'))

# 释放结算
balA = float(tenant_of(TA, TID)['balance_yuan'])
rel = req('POST', '/instances/%s/release' % i1, TA)
balB = float(tenant_of(TA, TID)['balance_yuan'])
check('释放按秒结算并退款', rel.get('usedSeconds', 0) > 0 and abs(balB - balA - float(rel.get('refund', 0))) < 0.01,
      '用 %s 秒 结 %s 退 %s' % (rel.get('usedSeconds'), rel.get('actualAmount'), rel.get('refund')))

# ============ 管理端 admin ============
rA = login('admin')
AD = rA['token']
print('== 管理端 admin 登录 ==')

rns = req('GET', '/admin/realnames?status=pending', AD)
mine = next((x for x in rns if x.get('tenant_id') == TID), None)
if mine:
    req('POST', '/admin/realnames/%s/review' % mine['id'], AD, {'status': 'approved'})
rns2 = req('GET', '/admin/realnames?status=approved', AD)
check('实名审核通过', any(x.get('tenant_id') == TID and x.get('status') == 'approved' for x in rns2))

scs = req('GET', '/admin/student-certs?status=pending', AD)
minesc = next((x for x in scs if x.get('tenant_id') == TID), None)
if minesc:
    req('POST', '/admin/student-certs/%s/review' % minesc['id'], AD, {'status': 'approved'})
scs2 = req('GET', '/admin/student-certs?status=approved', AD)
check('学生认证审核通过', any(x.get('tenant_id') == TID and x.get('status') == 'approved' for x in scs2))

# 学生 9 折验证：认证通过后再下 1 单，预扣应为基础价 × 0.9（2.8 × 0.9 = 2.52 元/时）
o9 = req('POST', '/orders', AD, {'tenantId': TID, 'sku': '1', 'hours': 1, 'orderNo': 't-ord9-' + RUN})
i9 = o9['instanceId']
wait_inst(TA, TID, i9, 'running')
balA = float(tenant_of(AD, TID)['balance_yuan'])
o92 = req('POST', '/orders', AD, {'tenantId': TID, 'sku': '1', 'hours': 1, 'orderNo': 't-ord9b-' + RUN})
wait_inst(TA, TID, o92['instanceId'], 'running')
balB = float(tenant_of(AD, TID)['balance_yuan'])
cost9 = balA - balB
check('学生认证 9 折预扣', abs(cost9 - 2.8 * 0.9 * 1) < 0.01, '实扣 %.2f 期望 %.2f' % (cost9, 2.8 * 0.9))

# 券发放 + 租户兑换
cpn = req('POST', '/admin/coupons', AD, {'tenantId': TID, 'amountYuan': 8, 'title': '运营测试券', 'expireDays': 7})
check('管理员发券', bool(cpn.get('id')), cpn)
cps3 = req('GET', '/coupons', TA)
newcp = next((c for c in cps3 if c['id'] == cpn['id']), None)
check('租户可见新券', newcp is not None)
balA = float(tenant_of(TA, TID)['balance_yuan'])
req('POST', '/coupons/%s/redeem' % cpn['id'], TA)
balB = float(tenant_of(TA, TID)['balance_yuan'])
check('兑换券到账', abs(balB - balA - 8.0) < 0.01, '%.2f -> %.2f' % (balA, balB))
cps4 = req('GET', '/coupons', TA)
check('券已核销', next(c for c in cps4 if c['id'] == cpn['id']).get('status') == 'redeemed')

# 驱动矩阵 / 经营报表
dm = req('GET', '/admin/driver-matrix', AD)
check('驱动矩阵 4 行', len(dm) >= 4, [m['model'] for m in dm])
rp = req('GET', '/admin/report', AD)
check('经营报表：日收入序列', isinstance(rp.get('revenueByDay'), list))
check('经营报表：券汇总', isinstance(rp.get('couponSummary'), list), rp.get('couponSummary'))
check('经营报表：租户/订单统计', rp.get('tenantCount') is not None and rp.get('totalOrders') is not None,
      '租户 %s 余额 %s' % (rp.get('tenantCount'), rp.get('tenantBalance')))

# 巡检（含反挖矿规则）
insp = req('POST', '/admin/inspections/run', AD)
check('巡检可运行', insp.get('healthScore') is not None, insp.get('summary'))

# ============ 运维 ops ============
rO = login('ops')
OP = rO['token']
ov = req('GET', '/admin/overview', OP)
check('ops 可看集群总览', ov.get('nodeReady') is not None)
try:
    req('GET', '/admin/realnames?status=pending', OP)
    check('ops 可看认证审核接口', True)
except Exception as e:
    check('ops 可看认证审核接口', False, e)

# ============ 越权与安全 ============
try:
    req('GET', '/admin/realnames?status=pending', TA)
    check('租户访问管理接口被拒', False)
except Exception:
    check('租户访问管理接口被拒', True)
try:
    req('GET', '/instances?tenantId=t2', TA)
    check('租户跨租户查询被拒', False)
except Exception:
    check('租户跨租户查询被拒', True)

print('\n========== 结果：%d 通过 / %d 失败 ==========' % (len(PASS), len(FAIL)))
if FAIL:
    print('失败项：')
    for f in FAIL:
        print('  -', f)
    raise SystemExit(1)
