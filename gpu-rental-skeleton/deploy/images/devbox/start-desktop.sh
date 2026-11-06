#!/usr/bin/env bash
# 拉起一个最小图形桌面并通过 noVNC 暴露 6080
set -e
export DISPLAY=:1
Xvfb :1 -screen 0 1440x900x24 >/tmp/xvfb.log 2>&1 &
sleep 1
openbox >/tmp/openbox.log 2>&1 &
x11vnc -display :1 -forever -shared -nopw -rfbport 5901 >/tmp/x11vnc.log 2>&1 &
websockify --web=/usr/share/novnc 6080 localhost:5901 >/tmp/novnc.log 2>&1 &
echo "desktop ready: http://0.0.0.0:6080/vnc.html"
sleep infinity
