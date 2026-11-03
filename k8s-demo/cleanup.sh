#!/usr/bin/env bash
# 回收本目录创建的所有资源
set -uo pipefail
cd "$(dirname "$0")"

K="kubectl --kubeconfig $PWD/kubeconfig"

echo "==> 删除验证用命名空间"
if [ -f kubeconfig ]; then
  $K delete namespace tenant-a --ignore-not-found --timeout=60s 2>/dev/null || true
fi

echo "==> 删除 k3s 容器"
docker rm -f k3s-test >/dev/null 2>&1 || true

echo "==> 删除本地 kubeconfig"
rm -f kubeconfig

echo "完成。"
