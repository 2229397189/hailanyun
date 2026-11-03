@echo off
chcp 65001 >nul
cd /d "%~dp0"
title TokenArk 算力平台 - 演示启动器
color 0B

echo.
echo   ============================================
echo      TokenArk 创元方舟 . 算力运营平台
echo      GPU 租赁管理系统 - 演示环境
echo   ============================================
echo.

docker version >nul 2>&1
if errorlevel 1 (
    color 0C
    echo   [错误] 没有检测到 Docker Desktop
    echo.
    echo   请先安装 Docker Desktop（免费）：
    echo   https://www.docker.com/products/docker-desktop/
    echo.
    echo   安装完成后重启电脑，再双击本文件。
    echo.
    pause
    exit /b 1
)

echo   [1/3] 正在构建镜像（首次约 3-5 分钟，请耐心等待）...
docker compose build >nul 2>&1
if errorlevel 1 (
    color 0C
    echo   [错误] 镜像构建失败，请截图本窗口发给技术同事。
    pause
    exit /b 1
)

echo   [2/3] 正在启动 8 个服务...
docker compose up -d
if errorlevel 1 (
    color 0C
    echo   [错误] 启动失败，请截图本窗口发给技术同事。
    pause
    exit /b 1
)

echo   [3/3] 等待服务就绪（约 30 秒）...
echo.
set /a n=0
:wait
timeout /t 3 /nobreak >nul
set /a n+=1
curl -sk https://localhost/login.html >nul 2>&1
if not errorlevel 1 goto ready
if %n% lss 20 goto wait

:ready
color 0A
echo   ============================================
echo      启动完成
echo   ============================================
echo.
echo   地址：https://localhost
echo   账号：admin
echo   密码：Hly@2026
echo.
echo   提示：
echo   1. 浏览器会提示"证书不安全"，点【高级】-【继续前往】
echo   2. 这是自签证书，内网演示正常现象，不是故障
echo   3. 关闭本窗口不会停止服务
echo.
start https://localhost
echo.
pause
