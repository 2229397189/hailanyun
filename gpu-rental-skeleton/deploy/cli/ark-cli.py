#!/usr/bin/env python3
"""TokenArk 创元方舟 · 租户命令行工具（ark-cli，仅标准库，无第三方依赖）

覆盖需求 A-37（CLI 管理工具）：登录、实例列表、开机 / 关机 / 重启 / 释放 / 续费、余额与套餐查询。
全部走平台 REST API，与网页控制台同权限（租户隔离）。

用法：
    python ark-cli.py login                     # 交互式登录，token 保存到 ~/.ark-cli.json
    python ark-cli.py whoami                    # 当前账号与租户信息
    python ark-cli.py instances                 # 实例列表
    python ark-cli.py plans                     # 套餐（含闲时 / 无卡调试）
    python ark-cli.py skus                      # SKU 与单价
    python ark-cli.py balance                   # 账户余额 / 代金券
    python ark-cli.py poweroff <实例ID>          # 关机（7 天保留期）
    python ark-cli.py poweron  <实例ID>          # 开机
    python ark-cli.py restart  <实例ID>          # 重启
    python ark-cli.py release  <实例ID>          # 释放（按秒结算，差额退回）
    python ark-cli.py renew    <实例ID> <小时数>  # 续费

环境变量：ARK_URL（默认 http://localhost:8099）、ARK_TOKEN（跳过 login 直接用 token）、
ARK_USER / ARK_PASSWORD（非交互式登录，CI 或脚本场景）。
"""
import getpass
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get('ARK_URL', 'http://localhost:8099').rstrip('/')
CONF = os.path.join(os.path.expanduser('~'), '.ark-cli.json')


def load_conf():
    try:
        with open(CONF, encoding='utf-8') as f:
            return json.load(f)
    except Exception:
        return {}


def save_conf(conf):
    with open(CONF, 'w', encoding='utf-8') as f:
        json.dump(conf, f, ensure_ascii=False, indent=2)


def req(method, path, token, body=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    r = urllib.request.Request(BASE + '/api' + path, data=data, method=method)
    if token:
        r.add_header('Authorization', 'Bearer ' + token)
    if body is not None:
        r.add_header('Content-Type', 'application/json')
    try:
        with urllib.request.urlopen(r, timeout=15) as resp:
            return json.loads(resp.read().decode('utf-8') or '{}')
    except urllib.error.HTTPError as e:
        try:
            msg = json.loads(e.read().decode('utf-8')).get('message')
        except Exception:
            msg = None
        raise SystemExit('请求失败 HTTP %s：%s' % (e.code, msg or e.reason))
    except urllib.error.URLError as e:
        raise SystemExit('无法连接平台 %s：%s' % (BASE, e.reason))


def login():
    user = os.environ.get('ARK_USER') or input('账号: ').strip()
    pwd = os.environ.get('ARK_PASSWORD') or getpass.getpass('密码: ')
    r = req('POST', '/auth/login', None, {'username': user, 'password': pwd})
    conf = {'token': r['token'], 'user': r.get('user') or {'username': user}}
    save_conf(conf)
    print('登录成功：%s（租户 %s），token 已保存到 %s'
          % (conf['user'].get('username'), conf['user'].get('tenantId', '–'), CONF))


def main():
    argv = sys.argv[1:]
    if not argv:
        print(__doc__)
        return
    cmd = argv[0]
    conf = load_conf()
    token = os.environ.get('ARK_TOKEN') or conf.get('token') or ''

    if cmd == 'login':
        login()
        return
    if not token:
        raise SystemExit('尚未登录：请先执行  python ark-cli.py login（或设置 ARK_TOKEN）')
    if cmd == 'whoami':
        u = conf.get('user') or {}
        print('账号: %s\n角色: %s\n租户: %s' % (u.get('username', '–'), u.get('role', '–'), u.get('tenantId', '–')))
        return
    if cmd == 'instances':
        rows = req('GET', '/instances', token)
        if not rows:
            print('（暂无实例）')
            return
        st = {'provisioning': '创建中', 'running': '运行中', 'stopped': '已关机',
              'releasing': '回收中', 'released': '已释放', 'resetting': '重置中', 'failed': '失败'}
        for i in rows:
            spec = '整机8卡' if int(i.get('gpu_count') or 0) == 8 else (
                '无卡调试' if int(i.get('gpu_count') or 0) == 0 else str(i.get('gpu_count')) + '卡')
            print('%-12s %-6s %-8s %-10s 到期 %s' % (
                i.get('id'), st.get(i.get('status'), i.get('status')), spec,
                str(i.get('node_id') or '–'),
                str(i.get('expire_at') or '–').replace('T', ' ')[:16]))
        return
    if cmd == 'plans':
        for p in req('GET', '/plans', token):
            print('%-14s %-8s ¥%s/卡/时' % (p.get('label'), str(p.get('hours')) + 'h', p.get('unitPrice')))
        return
    if cmd == 'skus':
        for s in req('GET', '/skus', token):
            print('%-8s %-14s ¥%s/卡/时  库存 %s' % (
                s.get('code'), s.get('name'), s.get('unitPrice'), s.get('stock')))
        return
    if cmd == 'balance':
        ts = req('GET', '/tenants', token)
        u = conf.get('user') or {}
        me = next((t for t in ts if t.get('id') == u.get('tenantId')), {})
        coupons = req('GET', '/coupons', token)
        unused = sum(float(c.get('amount_yuan') or 0) for c in coupons if not c.get('used_at'))
        print('现金余额: ¥%.2f\n代金券:   ¥%.2f（%d 张可用）\nGPU 配额: %s 卡' % (
            float(me.get('balance_yuan') or 0), unused,
            len([c for c in coupons if not c.get('used_at')]), me.get('quota_gpus')))
        return
    if cmd == 'poweroff' and len(argv) == 2:
        print(req('POST', '/instances/%s/poweroff' % argv[1], token).get('message'))
        return
    if cmd == 'poweron' and len(argv) == 2:
        print(req('POST', '/instances/%s/poweron' % argv[1], token).get('message'))
        return
    if cmd == 'restart' and len(argv) == 2:
        print(req('POST', '/instances/%s/restart' % argv[1], token).get('message'))
        return
    if cmd == 'release' and len(argv) == 2:
        r = req('POST', '/instances/%s/release' % argv[1], token)
        print('已释放：使用 %s 秒，结算 ¥%s，退回 ¥%s' % (
            r.get('usedSeconds'), r.get('actualAmount'), r.get('refund')))
        return
    if cmd == 'renew' and len(argv) == 3:
        r = req('POST', '/instances/%s/renew' % argv[1], token, {'hours': int(argv[2])})
        print('%s（新到期 %s）' % (r.get('message'), str(r.get('expireAt') or '–').replace('T', ' ')[:16]))
        return
    print(__doc__)


if __name__ == '__main__':
    main()
