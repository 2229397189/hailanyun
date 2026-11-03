#!/usr/bin/env bash
# 02 · 租户配额验证：配额 4 卡，第 5 个 Pod 会被 ResourceQuota 拒绝
# 用法：bash 02-quota-demo.sh
set -euo pipefail
cd "$(dirname "$0")"
K="kubectl --kubeconfig $PWD/kubeconfig"

cat > tenant-a.yaml <<'EOF'
apiVersion: v1
kind: Namespace
metadata:
  name: tenant-a
---
apiVersion: v1
kind: ResourceQuota
metadata:
  name: gpu-quota
  namespace: tenant-a
spec:
  hard:
    requests.nvidia.com/gpu: "4"
    pods: "10"
EOF

echo "==> 创建租户命名空间 + 4 卡配额"
$K apply -f tenant-a.yaml

cat > pod-template.yaml <<'EOF'
apiVersion: v1
kind: Pod
metadata:
  name: PLACEHOLDER
  namespace: tenant-a
spec:
  containers:
  - name: cuda
    image: busybox:1.36
    command: ["sleep", "3600"]
    resources:
      requests:
        nvidia.com/gpu: 1
      limits:
        nvidia.com/gpu: 1
EOF

echo "==> 依次创建 4 个 Pod（各请求 1 卡）"
for i in 1 2 3 4; do
  sed "s/PLACEHOLDER/gpu-q-$i/" pod-template.yaml | $K apply -f - >/dev/null
  echo "    gpu-q-$i 已提交"
done
sleep 4
echo
echo "==> 配额使用"
$K get resourcequota gpu-quota -n tenant-a
echo
echo "==> 第 5 个（预期被拒）"
sed "s/PLACEHOLDER/gpu-q-5/" pod-template.yaml | $K apply -f - 2>&1 | tail -1 || true
echo
echo "==> Pod 列表"
$K get pods -n tenant-a -o wide
