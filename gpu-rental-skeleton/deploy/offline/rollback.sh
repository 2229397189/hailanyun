#!/usr/bin/env bash
# 回滚：把镜像 tag 指回上一版并重启（上一版镜像需仍在本地）
#
#   bash rollback.sh [上一版镜像tag]
# 默认回滚到 <app>:previous —— upgrade.sh 之前建议手动 docker tag 备份
set -euo pipefail
PREV="${1:-gpu-rental-skeleton-app:previous}"
if ! docker image inspect "$PREV" >/dev/null 2>&1; then
  echo "找不到镜像 $PREV，无法自动回滚。" >&2
  echo "可用镜像：docker images | grep gpu-rental" >&2
  exit 1
fi
docker tag "$PREV" gpu-rental-skeleton-app:latest
cd "${2:-/opt/tokenark}"
docker compose up -d app
for i in $(seq 1 20); do
  curl -sf http://localhost:8099/api/health >/dev/null 2>&1 && { echo "回滚完成：$(curl -s http://localhost:8099/api/health)"; exit 0; }
  sleep 3
done
echo "回滚后健康检查仍未通过，请人工介入" >&2
exit 1
