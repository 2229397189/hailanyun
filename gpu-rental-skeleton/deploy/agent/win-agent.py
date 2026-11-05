#!/usr/bin/env python3
"""TokenArk 本机状态采集 agent（Windows，仅标准库）

每 10 秒采集一次整机真实状态，POST 到平台 /api/agent/report：
  - GPU：型号、利用率、显存、温度、功耗、UUID（nvidia-smi）
  - CPU：型号、核心数、使用率（GetSystemTimes 差值，不依赖 psutil）
  - 内存：总量 / 已用（GlobalMemoryStatusEx）
  - 磁盘：总量 / 已用（shutil.disk_usage）
  - 系统：版本、运行时长

平台侧落库后：管理端「节点管理」出现「本机实采」节点并展示实时参数，
监控页曲线切换为真实数据（dataSource=host-agent）。

用法：
    python win-agent.py [平台地址] [agent令牌]
默认：http://localhost:8099 / hly-local-agent-2026（令牌可在 系统管理→系统参数 修改）
"""
import ctypes
import json
import os
import platform
import shutil
import socket
import subprocess
import sys
import time
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else os.environ.get('AGENT_URL', 'http://localhost:8099')
TOKEN = sys.argv[2] if len(sys.argv) > 2 else os.environ.get('AGENT_TOKEN', 'hly-local-agent-2026')
INTERVAL = 10


# ---------------------------------------------------------------- GPU
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


# ---------------------------------------------------------------- CPU
class _FILETIME(ctypes.Structure):
    _fields_ = [('dwLowDateTime', ctypes.c_uint32), ('dwHighDateTime', ctypes.c_uint32)]


def _ft(v):
    return (v.dwHighDateTime << 32) | v.dwLowDateTime


_prev_cpu = None


def cpu_percent():
    """用 GetSystemTimes 的两次差值算总 CPU 使用率；第一次调用返回 None"""
    global _prev_cpu
    idle, kern, user = _FILETIME(), _FILETIME(), _FILETIME()
    if not ctypes.windll.kernel32.GetSystemTimes(
            ctypes.byref(idle), ctypes.byref(kern), ctypes.byref(user)):
        return None
    cur = (_ft(idle), _ft(kern), _ft(user))
    prev, _prev_cpu = _prev_cpu, cur
    if prev is None:
        return None
    d_idle = cur[0] - prev[0]
    d_total = (cur[1] - prev[1]) + (cur[2] - prev[2])
    if d_total <= 0:
        return None
    # kernel 时间里已经包含 idle，所以 busy = total - idle
    return round(max(0.0, min(100.0, (1.0 - d_idle / d_total) * 100.0)), 1)


def cpu_info():
    name = ''
    try:
        import winreg
        k = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,
                           r'HARDWARE\DESCRIPTION\System\CentralProcessor\0')
        name = winreg.QueryValueEx(k, 'ProcessorNameString')[0].strip()
    except Exception:
        name = platform.processor() or 'unknown'
    return name, os.cpu_count() or 0


# ---------------------------------------------------------------- 内存 / 磁盘 / 运行时长
class _MEMSTATUS(ctypes.Structure):
    _fields_ = [('dwLength', ctypes.c_uint32), ('dwMemoryLoad', ctypes.c_uint32),
                ('ullTotalPhys', ctypes.c_uint64), ('ullAvailPhys', ctypes.c_uint64),
                ('ullTotalPageFile', ctypes.c_uint64), ('ullAvailPageFile', ctypes.c_uint64),
                ('ullTotalVirtual', ctypes.c_uint64), ('ullAvailVirtual', ctypes.c_uint64),
                ('ullAvailExtendedVirtual', ctypes.c_uint64)]


def mem():
    m = _MEMSTATUS()
    m.dwLength = ctypes.sizeof(_MEMSTATUS)
    ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
    return m.ullTotalPhys / 1073741824.0, (m.ullTotalPhys - m.ullAvailPhys) / 1073741824.0


def disk():
    root = os.environ.get('SystemDrive', 'C:') + '\\'
    u = shutil.disk_usage(root)
    return u.total / 1073741824.0, u.used / 1073741824.0


def uptime_seconds():
    try:
        return int(ctypes.windll.kernel32.GetTickCount64() / 1000)
    except Exception:
        return None


# ---------------------------------------------------------------- 上报
def report():
    gpus = smi()
    total, used = mem()
    dtotal, dused = disk()
    cpu_name, cores = cpu_info()
    payload = {
        'hostname': socket.gethostname(),
        'os': 'Windows',
        'osVersion': platform.platform(),
        'cpuModel': cpu_name,
        'cpuCores': cores,
        'cpuPercent': cpu_percent(),
        'memTotalGb': round(total, 1),
        'memUsedGb': round(used, 1),
        'diskTotalGb': round(dtotal, 1),
        'diskUsedGb': round(dused, 1),
        'uptimeSeconds': uptime_seconds(),
        'gpus': gpus,
    }
    req = urllib.request.Request(BASE.rstrip('/') + '/api/agent/report', method='POST')
    req.add_header('Content-Type', 'application/json')
    req.add_header('X-Agent-Token', TOKEN)
    with urllib.request.urlopen(req, json.dumps(payload).encode(), timeout=10) as r:
        return json.loads(r.read().decode())


if __name__ == '__main__':
    print(f'agent 启动：{BASE} · 每 {INTERVAL}s 上报一次（Ctrl+C 停止）')
    cpu_percent()  # 预热一次，第二次才有差值
    while True:
        try:
            r = report()
            print(f"[{time.strftime('%H:%M:%S')}] {r.get('message', r)}")
        except Exception as e:
            print(f"[{time.strftime('%H:%M:%S')}] 上报失败：{e}")
        time.sleep(INTERVAL)
