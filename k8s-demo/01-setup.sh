#!/usr/bin/env bash
# 01 · 起一个真 k3s 集群，并伪造 8 张 nvidia.com/gpu
# 用法：bash 01-setup.sh
set -euo pipefail
cd "$(dirname "$0")"

K3S_IMAGE="rancher/k3s:v1.31.4-k3s1"
NAME="k3s-test"

echo "==> 清理旧容器"
docker rm -f "$NAME" >/dev/null 2>&1 || true

echo "==> 启动 k3s（单节点，控制面 + worker 合一）"
docker run -d --name "$NAME" --privileged -p 6443:6443 "$K3S_IMAGE" \
  server --tls-san 127.0.0.1 >/dev/null

echo "==> 等待 Ready"
for i in $(seq 1 60); do
  if docker exec "$NAME" kubectl get nodes --no-headers 2>/dev/null | grep -q Ready; then
    echo "    就绪（${i}x2s）"
    break
  fi
  sleep 2
done

echo "==> 导出 kubeconfig 到 ./kubeconfig"
MSYS_NO_PATHCONV=1 docker exec "$NAME" cat /etc/rancher/k3s/k3s.yaml > kubeconfig

K="kubectl --kubeconfig $PWD/kubeconfig"
NODE=$($K get nodes -o jsonpath='{.items[0].metadata.name}')

echo "==> 给节点伪造 8 张 GPU（patch status.capacity / allocatable）"
echo "    说明：device plugin 正常是通过 Node status 上报的，这里直接写同样的字段，"
echo "    调度器读的就是这个字段，所以分配逻辑与真卡完全一致。"
$K patch node "$NODE" --subresource=status --type=json -p='[
  {"op":"add","path":"/status/capacity/nvidia.com~1gpu","value":"8"},
  {"op":"add","path":"/status/allocatable/nvidia.com~1gpu","value":"8"}
]' >/dev/null

echo
echo "==> 结果"
$K get nodes -o wide
echo
echo -n "节点可分配 GPU: "
$K get node "$NODE" -o jsonpath='{.status.allocatable.nvidia\.com/gpu}{"\n"}'
echo
echo "注意：kubelet 会周期性回写 Node status，伪造值可能被覆盖。"
echo "      被覆盖后重跑本脚本最后一步的 patch 即可（或用 fixture 常驻）。"
