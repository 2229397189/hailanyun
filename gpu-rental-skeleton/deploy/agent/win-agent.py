#!/usr/bin/env python3
"""TokenArk 本机 GPU 采集 agent（Windows，仅标准库）

每 10 秒执行一次 nvidia-smi，把整机真实 GPU 指标 POST 到平台 /api/agent/report。
平台侧落库后：监控页曲线自动切换为真实数据（dataSource=host-agent），
节点管理页出现「本机实采」节点。

用法：
    python win-agent.py [平台地址] [agent令牌]
默认：http://localhost:8099 / hly-local-agent-2026（令牌可在 系统管理→系统参数 修改）
"""
import json
import os
import socket
import subprocess
import sys
import time
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else os.environ.get('AGENT_URL', 'http://localhost:8099')
TOKEN = sys.argv[2] if len(sys.argv) > 2 else os.environ.get('AGENT_TOKEN', 'hly-local-agent-2026')
INTERVAL = 10


def smi():
    out = subprocess.run(
        ['nvidia-smi',
         '--query-gpu=index,name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw,uuid',
         '--format=csv,noheader,nounits'],
        capture_output=True, text=True, timeout=10).stdout
    gpus = []
    for line in out.strip().splitlines():
        p = [x.strip() for x in line.split(',')]
        if len(p) < 8:
            continue
        gpus.append({'idx': int(p[0]), 'name': p[1], 'util': float(p[2]),
                     'memUsedMb': float(p[3]), 'memTotalMb': float(p[4]),
                     'tempC': float(p[5]), 'powerW': float(p[6]), 'uuid': p[7]})
    return gpus


def mem():
    import ctypes

    class MS(ctypes.Structure):
        _fields_ = [('dwLength', ctypes.c_uint32), ('dwMemoryLoad', ctypes.c_uint32),
                    ('ullTotalPhys', ctypes.c_uint64), ('ullAvailPhys', ctypes.c_uint64),
                    ('ullTotalPageFile', ctypes.c_uint64), ('ullAvailPageFile', ctypes.c_uint64),
                    ('ullTotalVirtual', ctypes.c_uint64), ('ullAvailVirtual', ctypes.c_uint64),
                    ('ullAvailExtendedVirtual', ctypes.c_uint64)]

    m = MS()
    m.dwLength = ctypes.sizeof(MS)
    ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
    return m.ullTotalPhys / 1073741824.0, (m.ullTotalPhys - m.ullAvailPhys) / 1073741824.0


def report():
    gpus = smi()
    total, used = mem()
    payload = {'hostname': socket.gethostname(), 'os': 'Windows', 'gpus': gpus,
               'memTotalGb': round(total, 1), 'memUsedGb': round(used, 1)}
    req = urllib.request.Request(BASE.rstrip('/') + '/api/agent/report', method='POST')
    req.add_header('Content-Type', 'application/json')
    req.add_header('X-Agent-Token', TOKEN)
    with urllib.request.urlopen(req, json.dumps(payload).encode(), timeout=10) as r:
        return json.loads(r.read().decode())


if __name__ == '__main__':
    print(f'agent 启动：{BASE} · 每 {INTERVAL}s 上报一次（Ctrl+C 停止）')
    while True:
        try:
            r = report()
            print(f"[{time.strftime('%H:%M:%S')}] {r.get('message', r)}")
        except Exception as e:
            print(f"[{time.strftime('%H:%M:%S')}] 上报失败：{e}")
        time.sleep(INTERVAL)
