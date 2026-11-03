@echo off
chcp 65001 >nul
cd /d "%~dp0"
title TokenArk 算力平台 - 停止服务

echo.
echo   正在停止服务...
echo.
docker compose down
echo.
echo   已停止。
echo   数据保留在 Docker 卷里，下次启动仍然存在。
echo.
pause
