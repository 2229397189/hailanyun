#!/usr/bin/env bash
# 03 · 节点容量耗尽验证：8 张卡占满后，第 9 个 Pod Pending
# 用法：bash 03-capacity-demo.sh   （需先跑过 01、02）
set -euo pipefail
cd "$(dirname "$0")"
K="kubectl --kubeconfig $PWD/kubeconfig"

echo "==> 把租户配额放宽到 10 卡，让节点容量成为唯一瓶颈"
$K patch resourcequota gpu-quota -n tenant-a --type=merge \
  -p '{"spec":{"hard":{"requests.nvidia.com/gpu":"10"}}}' >/dev/null

echo "==> 补齐到 8 个 Pod（占满 8 张卡）"
for i in 1 2 3 4 5 6 7 8; do
  sed "s/PLACEHOLDER/gpu-c-$i/" pod-template.yaml | $K apply -f - >/dev/null 2>&1 || true
done
sleep 5
echo -n "Running 数量: "
$K get pods -n tenant-a --no-headers 2>/dev/null | grep -c Running || true

echo
echo "==> 第 9 个（预期 Pending）"
sed "s/PLACEHOLDER/gpu-c-9/" pod-template.yaml | $K apply -f - >/dev/null
sleep 4
$K get pod gpu-c-9 -n tenant-a
echo
echo "==> 调度失败原因"
$K describe pod gpu-c-9 -n tenant-a | grep -A 2 "Events:" | tail -2
