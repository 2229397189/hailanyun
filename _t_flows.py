"""新契约回归：创建页 / 到货提醒 / 需求登记 / 下单越权 / 管理端可见性。
断言全部看服务端落库结果，不看 HTTP 200。
"""
import json
import subprocess
import urllib.error
import urllib.request

BASE = 'http://localhost:8099'
R = []


def ck(name, cond, extra=''):
    R.append((name, bool(cond), extra))


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={'Content-Type': 'application/json'})
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req) as f:
            return f.status, json.loads(f.read().decode() or '{}')
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw or '{}')
        except Exception:
            return e.code, {'raw': raw}


def sql(q):
    p = subprocess.run(['docker', 'exec', 'gpu-rental-pg', 'psql', '-U', 'rental', '-d', 'rental', '-t', '-A', '-c', q],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    return p.stdout.strip()


def kctl(*args):
    """集群侧真状态：数据盘是不是真的有一块 PVC、Pod 上有没有用户选的镜像"""
    p = subprocess.run(['docker', 'exec', 'k3s-test', 'kubectl', *args],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    return (p.stdout or '').strip()


def login(user, pwd='Hly@2026'):
    s, b = call('POST', '/api/auth/login', {'username': user, 'password': pwd})
    return b.get('token') if s == 200 else None


def wait_running(instance_id, timeout=60):
    """巡检线程每 5 秒把 provisioning 推到 running，Pod Ready 才算真开出来，不能立刻断言"""
    import time as _t
    deadline = _t.time() + timeout
    while _t.time() < deadline:
        st = sql("SELECT status FROM instances WHERE id='" + instance_id + "';")
        if st == 'running':
            return st
        if st not in ('provisioning', 'resetting'):
            return st
        _t.sleep(2)
    return sql("SELECT status FROM instances WHERE id='" + instance_id + "';")


A = login('tenant_a')          # t1
B = login('tenant_b')          # t2
ADM = login('admin')
ck('三个账号登录成功', A and B and ADM)

# ---------------- 1 创建页依赖的目录接口 ----------------
s, imgs = call('GET', '/api/catalog/images')
ck('镜像目录 200', s == 200)
fams = [f['name'] for f in imgs] if s == 200 else []
ck('镜像含参考图的 6 个发行版', all(x in fams for x in ('PyTorch', 'Miniconda', 'Ubuntu', 'ComfyUI', 'GROMACS', 'TensorFlow')), str(fams))
one = next((f for f in imgs if f['name'] == 'PyTorch'), {})
v0 = (one.get('versions') or [{}])[0]
ck('版本 label/ref 结构完整', all(k in v0 for k in ('ref', 'label', 'framework', 'cuda', 'python', 'os', 'gpuRequired')), json.dumps(v0, ensure_ascii=False)[:160])
ck('无 CUDA 的发行版标 gpuRequired=False',
   any(v['gpuRequired'] is False for f in imgs for v in f['versions']), '')

s, zs = call('GET', '/api/catalog/zones')
ck('地区目录 200 且带能力开关', s == 200 and all('fileStorage' in i and 'privateImage' in i for i in zs.get('items', [])))
ck('地区来自在售机型', len(zs.get('items', [])) >= 4, str([i['zone'] for i in zs.get('items', [])]))

# ---------------- 2 到货提醒：地区 + 阈值真的落库 ----------------
s, r = call('POST', '/api/stock-alerts', {'model': 'ZZ回归机型', 'zone': '山西1区', 'qty': 3}, A)
ck('到货提醒登记 200', s == 200, json.dumps(r, ensure_ascii=False)[:120])
sa_id = r.get('id')
row = sql("SELECT zone || '|' || qty || '|' || status FROM stock_alerts WHERE id='" + sa_id + "';")
ck('到货提醒 zone/qty 落库', row == '山西1区|3|open', row)
s, r2 = call('POST', '/api/stock-alerts', {'model': 'ZZ回归机型', 'zone': '山西1区', 'qty': 3}, A)
ck('重复登记幂等返回 reused', s == 200 and r2.get('reused') is True, json.dumps(r2, ensure_ascii=False)[:120])
# 浏览器上真的踩过这个坑：改地区/阈值再提交一次，命中的是去重分支，旧值一动不动
s, r2b = call('POST', '/api/stock-alerts', {'model': 'ZZ回归机型', 'zone': '福建2区', 'qty': 5}, A)
row2 = sql("SELECT zone || '|' || qty FROM stock_alerts WHERE id='" + sa_id + "';")
dup = sql("SELECT COUNT(*) FROM stock_alerts WHERE tenant_id='t1' AND model='ZZ回归机型' AND status='open';")
ck('重复登记会把新的地区与阈值覆盖上去', s == 200 and row2 == '福建2区|5' and dup == '1', f'{row2} 行数={dup}')
s, r3 = call('POST', '/api/stock-alerts', {'model': 'ZZ回归机型', 'qty': 999}, A)
ck('阈值越界被拒', s == 400, str(r3)[:120])

# ---------------- 3 需求登记：单位/学校真的落库并进管理端 ----------------
s, ld = call('POST', '/api/public/leads',
             {'source': '裸金属需求', 'model': 'RTX 5090', 'contactName': '回归-刘工',
              'contactPhone': '13900001234', 'org': '之江实验室 计算组', 'note': '需要 8 卡整机两周'}, None)
ck('未登录也能提交裸金属需求', s == 200 and ld.get('id'), json.dumps(ld, ensure_ascii=False)[:160])
org_db = sql("SELECT org FROM biz_leads WHERE id='" + ld.get('id', '') + "';")
ck('单位/学校落库', org_db == '之江实验室 计算组', org_db)
s, adm_leads = call('GET', '/api/admin/leads?status=new', None, ADM)
hit = [x for x in adm_leads.get('items', []) if x.get('id') == ld.get('id')]
ck('管理端商务需求列表能看到这条', s == 200 and hit and hit[0].get('org') == '之江实验室 计算组',
   json.dumps(hit, ensure_ascii=False)[:200])

s, f0 = call('POST', '/api/admin/leads/' + ld['id'] + '/follow', {'status': 'done'}, ADM)
ck('结单不写回复被拒（租户要能看到结论）', s == 400, json.dumps(f0, ensure_ascii=False)[:120])
s, f1 = call('POST', '/api/admin/leads/' + ld['id'] + '/follow',
             {'status': 'done', 'reply': '已排产，周四交付'}, ADM)
ck('结单成功且状态落库', s == 200 and sql("SELECT status FROM biz_leads WHERE id='" + ld['id'] + "';") == 'done', str(f1)[:140])

# 登录态需求：回复要推送到租户消息中心
s, my_ld = call('POST', '/api/leads', {'source': '拼团意向', 'model': 'RTX 4090', 'contactName': '回归-陈',
                                        'contactPhone': '13800001111', 'org': '哈工大', 'note': '拼 2 卡'}, A)
nid_before = int(sql("SELECT COUNT(*) FROM notifications WHERE tenant_id='t1';") or 0)
s, f2 = call('POST', '/api/admin/leads/' + my_ld['id'] + '/follow', {'status': 'following', 'reply': '商务稍后联系'}, ADM)
nid_after = int(sql("SELECT COUNT(*) FROM notifications WHERE tenant_id='t1';") or 0)
ck('跟进回复推送到租户消息中心', s == 200 and nid_after > nid_before, f'{nid_before} -> {nid_after}')

# ---------------- 4 下单：租户绑定 + 镜像/数据盘落库 ----------------
s, bad = call('POST', '/api/orders', {'sku': 'debug', 'hours': 1, 'orderNo': 'zz-probe-t2', 'tenantId': 't2'}, A)
ck('跨租户下单被拒（原越权口子）', s == 403, json.dumps(bad, ensure_ascii=False)[:120])
own = sql("SELECT COUNT(*) FROM instances WHERE tenant_id='t2' AND order_id IN (SELECT id FROM orders WHERE order_no='zz-probe-t2');")
ck('被拒后没有真的开出别人家实例', own == '0', own)

img_ref = 'pytorch:2.5.1-cuda12.4-py3.11-devel'
s, od = call('POST', '/api/orders', {'sku': 'debug', 'hours': 1, 'orderNo': 'zz-create-' + str(int(__import__('time').time())),
                                     'image': img_ref, 'dataDiskGb': 120}, A)
ck('本租户下单成功', s == 200 and od.get('instanceId'), json.dumps(od, ensure_ascii=False)[:160])
iid = od.get('instanceId', '')
# 预扣 = 主机费（单价×卡数×时长，学生认证会再打 9 折）+ 数据盘 120GB×0.02/GB/日×(1/24)日 = 主机费 + 0.10
host_part = float(od.get('unitPrice', 0)) * max(int(od.get('gpuCount', 1) or 1), 1) * 1
ck('数据盘费按 GB·日计入预扣', abs(float(od.get('prepaid', 0)) - host_part - 0.10) < 0.005,
   'prepaid=%s 主机费=%s' % (od.get('prepaid'), round(host_part, 4)))
cfg = sql("SELECT COALESCE(image_ref,'-') || '|' || data_disk_gb FROM instances WHERE id='" + iid + "';")
ck('镜像与数据盘选到实例行上', cfg == img_ref + '|120', cfg)

s, ai = call('GET', '/api/admin/instances?tenantId=t1', None, ADM)
row = next((x for x in ai.get('items', []) if x.get('id') == iid), None)
ck('管理端实例页能看到这台', s == 200 and row is not None)
if row:
    ck('管理端实例带租户名/规格/镜像',
       row.get('tenant_name') and row.get('spec') and row.get('image_ref') == img_ref,
       json.dumps({k: row.get(k) for k in ('tenant_name', 'spec', 'image_ref', 'data_disk_gb')}, ensure_ascii=False))

# 管理端代操作复用同一条业务路径
promoted = wait_running(iid)
ck('下单后巡检真的把实例开起来', promoted == 'running', promoted)

pod = sql("SELECT pod_name FROM instances WHERE id='" + iid + "';")
ck('数据盘在集群里真的有一块 PVC', 'data-' + pod in kctl('-n', 'tenant-t1', 'get', 'pvc', '--no-headers'),
   kctl('-n', 'tenant-t1', 'get', 'pvc', '--no-headers')[-200:])
ck('PVC 真的按选的容量开', '120Gi' in kctl('-n', 'tenant-t1', 'get', 'pvc', 'data-' + pod, '-o', 'jsonpath={.spec.resources.requests.storage}'),
   kctl('-n', 'tenant-t1', 'get', 'pvc', 'data-' + pod, '-o', 'jsonpath={.spec.resources.requests.storage}'))
ck('Pod 里挂的是 /data', '/data' in kctl('-n', 'tenant-t1', 'get', 'pod', pod, '-o', 'jsonpath={.spec.containers[0].volumeMounts}'),
   kctl('-n', 'tenant-t1', 'get', 'pod', pod, '-o', 'jsonpath={.spec.containers[0].volumeMounts}'))
ck('Pod 注解带上了用户选的镜像', img_ref in kctl('-n', 'tenant-t1', 'get', 'pod', pod, '-o', 'jsonpath={.metadata.annotations}'),
   kctl('-n', 'tenant-t1', 'get', 'pod', pod, '-o', 'jsonpath={.metadata.annotations}')[-200:])

s, po = call('POST', '/api/admin/instances/' + iid + '/poweroff', None, ADM)
st = sql("SELECT status FROM instances WHERE id='" + iid + "';")
ck('管理端关机真的改状态', s == 200 and st == 'stopped', f'{str(po)[:90]} status={st}')
ck('关机后数据盘还在（关机保留数据不是话术）', 'data-' + pod in kctl('-n', 'tenant-t1', 'get', 'pvc', '--no-headers'),
   kctl('-n', 'tenant-t1', 'get', 'pvc', '--no-headers')[-200:])
s, pon = call('POST', '/api/admin/instances/' + iid + '/poweron', None, ADM)
ck('管理端开机真的改状态', s == 200 and wait_running(iid) == 'running', str(pon)[:90])
ck('开机后挂回同一块数据盘', 'data-' + pod in kctl('-n', 'tenant-t1', 'get', 'pod', pod, '-o', 'jsonpath={.spec.volumes}'),
   kctl('-n', 'tenant-t1', 'get', 'pod', pod, '-o', 'jsonpath={.spec.volumes}')[-200:])
s, rl = call('POST', '/api/admin/instances/' + iid + '/release', None, ADM)
ck('管理端释放真的结算', s == 200 and sql("SELECT status FROM instances WHERE id='" + iid + "';") == 'released', str(rl)[:120])
ck('释放后数据盘一起销毁', 'data-' + pod not in kctl('-n', 'tenant-t1', 'get', 'pvc', '--no-headers'),
   kctl('-n', 'tenant-t1', 'get', 'pvc', '--no-headers')[-200:])
audit = sql("SELECT COUNT(*) FROM audit_logs WHERE action='INSTANCE_RELEASE' AND target='" + iid + "';")
ck('代操作留审计', audit != '0', audit)

# ---------------- 5 到货提醒完结要通知租户 ----------------
s, ful = call('POST', '/api/admin/stock-alerts/' + sa_id + '/fulfill', {'note': '线下已协调 3 卡'}, A if False else ADM)
sa_st = sql("SELECT status FROM stock_alerts WHERE id='" + sa_id + "';")
ck('到货提醒完结落库', s == 200 and sa_st == 'fulfilled', f'{str(ful)[:120]} status={sa_st}')
notif = sql("SELECT COUNT(*) FROM notifications WHERE tenant_id='t1' AND title LIKE '%到货提醒 · ZZ回归机型%';")
ck('完结同时通知租户', notif != '0', notif)
s, ful2 = call('POST', '/api/admin/stock-alerts/' + sa_id + '/fulfill', {}, ADM)
ck('重复完结被拒', s == 400, str(ful2)[:120])

# ---------------- 6 拼团管理端可见 ----------------
s, gc = call('GET', '/api/admin/group-cards', None, ADM)
ck('拼团目录带实时进度', s == 200 and isinstance(gc, list) and len(gc) > 0
   and all(k in gc[0] for k in ('cardKey', 'total', 'joined', 'left')), json.dumps(gc[:1], ensure_ascii=False)[:220])
s, gj = call('GET', '/api/admin/group-joins', None, ADM)
ck('拼单列表带租户名', s == 200 and 'items' in gj and all('tenantName' in x for x in gj['items']))

# ---------------- 7 租户仍不能看管理端 ----------------
s, forb = call('GET', '/api/admin/instances', None, A)
ck('租户访问管理端 403', s == 403, str(forb)[:100])

fails = [x for x in R if not x[1]]
print('\n'.join(('PASS ' if x[1] else 'FAIL ') + x[0] + ('  << ' + str(x[2]) if x[2] and not x[1] else '') for x in R))
print(f'\n合计 {len(R)} 条，失败 {len(fails)} 条')
