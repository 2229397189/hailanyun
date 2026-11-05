@echo off
rem TokenArk 本机状态采集 agent —— 双击即可启动，关窗口即停止
rem 加到开机启动：Win+R 输入 shell:startup，把本文件的快捷方式丢进去
cd /d "%~dp0"
title TokenArk Agent
python "%~dp0win-agent.py" http://localhost:8099 hly-local-agent-2026
pause
