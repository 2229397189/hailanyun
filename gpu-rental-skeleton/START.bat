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

echo   [2/3] 正在启动服务...
docker ps --format "{{.Names}}" | findstr /x "k3s-test" >nul 2>&1
if not errorlevel 1 (
    echo         检测到 k3s 集群，按 real 模式启动（真 K8s 调度）
    docker compose -f docker-compose.yml -f docker-compose.real.yml up -d
) else (
    echo         未检测到 k3s 集群，按 fake 模式启动（模拟调度，不依赖集群）
    docker compose up -d
)
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
curl -s http://localhost:8099/api/health >nul 2>&1
if not errorlevel 1 goto ready
if %n% lss 20 goto wait

:ready
color 0A
echo   ============================================
echo      启动完成
echo   ============================================
echo.
echo   地址：http://localhost:8099
echo.
echo   租户账号：tenant_a    密码：Hly@2026
echo   管理账号：admin       密码：Hly@2026
echo.
echo   常用页面：
echo     首页        http://localhost:8099/index.html
echo     容器云      http://localhost:8099/portal.html
echo     拼租卡      http://localhost:8099/group.html
echo     裸金属      http://localhost:8099/baremetal.html
echo     控制台      http://localhost:8099/console.html
echo     运维台      http://localhost:8099/admin.html
echo     运维大屏    http://localhost:8099/screen.html
echo.
echo   提示：
echo   1. 请用 http:// 开头访问，不要用 https
echo      （https 走自签证书，浏览器会拦一道警告页）
echo   2. 关闭本窗口不会停止服务，停止请双击 STOP.bat
echo   3. 数据持久化在 Docker 卷里，重启不会丢
echo.
start http://localhost:8099/index.html
echo.
pause
