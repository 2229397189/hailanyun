#!/usr/bin/env bash
# 04 · 把本机 NVIDIA 卡直通进 k3s（Windows + WSL2 + Docker Desktop）
#
# 背景：k3s 跑在 Docker 里，拿不到 Docker Desktop 给容器做的 GPU 注入，
#       所以 Pod 里只有 /dev/dxg 是不够的，还缺 DXCore 桥接库和 CUDA 驱动库。
#       本脚本把这些库放进 k3s 容器的 /opt/gpulib，并把节点 GPU 数改成真实值。
#
# 用法：bash 04-gpu-passthrough.sh
# 前提：k3s 容器启动时带了 --gpus all（01-setup.sh 会自动判断）
set -euo pipefail
cd "$(dirname "$0")"

NAME="k3s-test"
CUDA_IMAGE="nvidia/cuda:12.4.0-base-ubuntu22.04"

echo "==> 1/5 检查 k3s 容器"
docker inspect "$NAME" >/dev/null 2>&1 || {
  echo "    k3s 容器不存在，先跑：bash 01-setup.sh"
  exit 1
}

echo "==> 2/5 检查容器内的 GPU 设备节点"
if ! MSYS_NO_PATHCONV=1 docker exec "$NAME" test -e /dev/dxg; then
  cat <<'EOF'
    容器里没有 /dev/dxg —— k3s 启动时没带 --gpus all。
    重建命令（会丢集群内数据，数据库在 PG 里不受影响）：
      docker rm -f k3s-test
      docker run -d --name k3s-test --privileged --gpus all -p 6443:6443 \
        rancher/k3s:v1.31.4-k3s1 server --tls-san 127.0.0.1 --tls-san host.docker.internal
    然后再跑一次本脚本。
EOF
  exit 1
fi

echo "==> 3/5 准备 /opt/gpulib（DXCore + CUDA 驱动库）"
MSYS_NO_PATHCONV=1 docker exec "$NAME" mkdir -p /opt/gpulib

TMPD=$(mktemp -d)
TMPW=$(cygpath -m "$TMPD" 2>/dev/null || echo "$TMPD")
MSYS_NO_PATHCONV=1 docker run --rm --gpus all -v "$TMPW:/out" "$CUDA_IMAGE" \
  cp /usr/lib/x86_64-linux-gnu/libdxcore.so /out/libdxcore.so >/dev/null
MSYS_NO_PATHCONV=1 docker cp "$TMPW/libdxcore.so" "$NAME:/opt/gpulib/libdxcore.so" >/dev/null
rm -f "$TMPD/libdxcore.so" 2>/dev/null || true
rmdir "$TMPD" 2>/dev/null || true

MSYS_NO_PATHCONV=1 docker exec "$NAME" sh -c '
  D=$(ls -d /usr/lib/wsl/drivers/nvam.inf_amd64_* 2>/dev/null | head -1)
  if [ -z "$D" ]; then echo "    找不到 /usr/lib/wsl/drivers/nvam.inf_amd64_*"; exit 1; fi
  cp -f "$D"/libnvidia-ml.so.1 /opt/gpulib/ 2>/dev/null || true
  cp -f "$D"/libcuda.so.1.1 /opt/gpulib/ 2>/dev/null || true
  cp -f "$D"/libnvidia-ptxjitcompiler.so.1 /opt/gpulib/ 2>/dev/null || true
  cd /opt/gpulib
  ln -sf libcuda.so.1.1 libcuda.so.1
  ln -sf libnvidia-ml.so.1 libnvidia-ml.so
  ls -1 /opt/gpulib
'

echo "==> 4/5 把节点 GPU 数改成真实值（本机 1 张 RTX 4060 Laptop）"
K="kubectl"
if [ -f ./kubeconfig ]; then K="kubectl --kubeconfig $PWD/kubeconfig"; fi
NODE=$(MSYS_NO_PATHCONV=1 docker exec "$NAME" kubectl get nodes -o jsonpath='{.items[0].metadata.name}')
MSYS_NO_PATHCONV=1 docker exec "$NAME" kubectl patch node "$NODE" --subresource=status --type=json \
  -p='[{"op":"add","path":"/status/capacity/nvidia.com~1gpu","value":"1"},
       {"op":"replace","path":"/status/allocatable/nvidia.com~1gpu","value":"1"}]' >/dev/null
MSYS_NO_PATHCONV=1 docker exec "$NAME" kubectl label node "$NODE" \
  nvidia.com/gpu.product=NVIDIA-GeForce-RTX-4060-Laptop-GPU \
  nvidia.com/gpu.count=1 nvidia.com/gpu.present=true \
  gpu.real=true gpu.model=rtx4060 --overwrite >/dev/null
MSYS_NO_PATHCONV=1 docker exec "$NAME" kubectl annotate node "$NODE" \
  gpu.real-hardware=true gpu.mig-support=false --overwrite >/dev/null
echo "    节点 $NODE：GPU=$(MSYS_NO_PATHCONV=1 docker exec "$NAME" kubectl get node "$NODE" -o jsonpath='{.status.allocatable.nvidia\.com/gpu}')"

echo "==> 5/5 起一个验证 Pod 跑 nvidia-smi"
MSYS_NO_PATHCONV=1 docker exec "$NAME" kubectl delete pod gpu-verify --grace-period=0 --force >/dev/null 2>&1 || true
cat <<'EOF' | MSYS_NO_PATHCONV=1 docker exec -i "$NAME" kubectl apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: gpu-verify
spec:
  restartPolicy: Never
  containers:
  - name: c
    image: nvidia/cuda:12.4.0-base-ubuntu22.04
    imagePullPolicy: IfNotPresent
    command: ["sleep", "600"]
    securityContext:
      privileged: true
    env:
    - {name: LD_LIBRARY_PATH, value: "/opt/gpulib:/usr/lib/x86_64-linux-gnu"}
    volumeMounts:
    - {name: dxg, mountPath: /dev/dxg}
    - {name: wsl, mountPath: /usr/lib/wsl}
    - {name: smi, mountPath: /usr/bin/nvidia-smi}
    - {name: gpulib, mountPath: /opt/gpulib}
  volumes:
  - {name: dxg, hostPath: {path: /dev/dxg}}
  - {name: wsl, hostPath: {path: /usr/lib/wsl}}
  - {name: smi, hostPath: {path: /usr/bin/nvidia-smi}}
  - {name: gpulib, hostPath: {path: /opt/gpulib}}
EOF

for i in $(seq 1 30); do
  PHASE=$(MSYS_NO_PATHCONV=1 docker exec "$NAME" kubectl get pod gpu-verify -o jsonpath='{.status.phase}' 2>/dev/null || true)
  [ "$PHASE" = "Running" ] && break
  sleep 2
done
MSYS_NO_PATHCONV=1 docker exec "$NAME" kubectl exec gpu-verify -- nvidia-smi -L

echo
echo "完成。用户下单的 GPU 实例会自动带上同一套挂载（RealK8s 已内置）。"
echo "验证后清理：docker exec k3s-test kubectl delete pod gpu-verify"
