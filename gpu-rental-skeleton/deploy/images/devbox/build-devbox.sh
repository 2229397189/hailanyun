#!/usr/bin/env bash
# 构建 TokenArk 开发机镜像并导入 k3s 集群（A-10 / A-11 / A-12）
#
# 为什么不用 docker build：本机 buildkit 无法访问 Debian/HTTPS 源（apt exit 100），
# 而 docker run/exec 的 HTTP Debian 源可用。因此采用「容器内安装 → commit → 导入 containerd」。
#
# 前置：Docker Desktop 已运行；k3s-test 容器已启动且 Ready。
# 用法：bash build-devbox.sh
set -euo pipefail

IMAGE=tokenark/devbox:1.0
BUILDER=devbox-build
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "==> 1/6 启动构建容器（代理仅用于宿主侧排障，apt 走 HTTP Debian 源直连）"
docker rm -f "$BUILDER" >/dev/null 2>&1 || true
docker run -d --name "$BUILDER" \
  -e HTTP_PROXY=http://host.docker.internal:59111 \
  -e HTTPS_PROXY=http://host.docker.internal:59111 \
  python:3.11-slim-bookworm sleep infinity >/dev/null

echo "==> 2/6 安装 SSH / Xvfb / x11vnc / noVNC"
docker exec "$BUILDER" sh -c '
  apt-get update -qq &&
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    openssh-server xvfb x11vnc openbox novnc websockify net-tools curl procps vim-tiny'

echo "==> 3/6 安装 Jupyter（Debian 官方 jupyter-notebook 6.4 + ipykernel）"
# 注意：pip 源在本环境不可达（HTTPS 被拦、HTTP 镜像 403），所以走 apt。
# 如需 JupyterLab，需先把 jupyter_events 等依赖的 Linux wheel 离线备齐。
docker exec "$BUILDER" sh -c '
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends jupyter-notebook python3-ipykernel'

echo "==> 4/6 配置 SSH 与桌面启动脚本"
docker exec "$BUILDER" sh -c '
  mkdir -p /run/sshd /root/.vnc
  echo "root:tokenark" | chpasswd
  sed -i "s/^#\?PermitRootLogin.*/PermitRootLogin yes/" /etc/ssh/sshd_config
  sed -i "s/^#\?PasswordAuthentication.*/PasswordAuthentication yes/" /etc/ssh/sshd_config'
docker cp "$HERE/start-desktop.sh" "$BUILDER:/usr/local/bin/start-desktop.sh"
MSYS_NO_PATHCONV=1 docker exec "$BUILDER" chmod +x /usr/local/bin/start-desktop.sh

echo "==> 5/6 固化镜像"
docker commit \
  -c 'CMD ["sleep","infinity"]' \
  -c 'ENV HTTP_PROXY=' -c 'ENV HTTPS_PROXY=' -c 'ENV NO_PROXY=' \
  -c 'EXPOSE 22 8888 6080' \
  "$BUILDER" "$IMAGE"

echo "==> 6/6 导入 k3s 的 containerd（Pod 用的是集群镜像库，不是宿主机 Docker）"
docker save "$IMAGE" | docker exec -i k3s-test ctr -n k8s.io images import -
docker exec k3s-test ctr -n k8s.io images ls | grep devbox

echo
echo "完成。镜像 $IMAGE 已在集群可用。"
echo "把镜像映射写入系统参数：image.map = {\"devbox\":\"$IMAGE\"}"
echo "验证：bash ../../../../_t_a1012.py（需先启动平台服务）"
echo
echo "安全提示：开发镜像默认 root/tokenark 为演示口令，生产环境必须改为按实例随机生成。"
