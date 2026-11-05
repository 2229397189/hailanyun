#!/usr/bin/env bash
# 离线升级包打包（B-36）
#
# 产物：tokenark-offline-<版本>.tar.gz
#   ├── images/*.tar          镜像（docker save）
#   ├── compose/              docker-compose.yml + real 覆盖层 + nginx/prom 配置
#   ├── helm/                 私有化部署 Chart
#   ├── scripts/              upgrade.sh / rollback.sh / verify.sh
#   ├── MANIFEST               版本、构建时间、镜像清单
#   └── SHA256SUMS             全量校验和
#
# 用法：
#   bash pack.sh                  # 完整包（含镜像，体积大）
#   bash pack.sh --no-images      # 只打配置与脚本（自检/小版本用）
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${VERSION:-$(date +%Y%m%d%H%M)}"
ROOT="$(cd ../.. && pwd)"
OUT="$(pwd)/tokenark-offline-${VERSION}.tar.gz"
STAGE="$(mktemp -d)"
BUNDLE="$STAGE/tokenark-offline-${VERSION}"
WITH_IMAGES=1
[ "${1:-}" = "--no-images" ] && WITH_IMAGES=0

IMAGES=(
  "gpu-rental-skeleton-app:latest"
  "postgres:15-alpine"
  "nginx:1.27-alpine"
)

echo "==> 1/5 准备目录 $BUNDLE"
mkdir -p "$BUNDLE"/{images,compose,helm,scripts}

echo "==> 2/5 收集编排文件与配置"
cp "$ROOT/docker-compose.yml" "$BUNDLE/compose/" 2>/dev/null || true
cp "$ROOT/docker-compose.real.yml" "$BUNDLE/compose/" 2>/dev/null || true
cp -r "$ROOT/deploy/nginx" "$BUNDLE/compose/" 2>/dev/null || true
cp -r "$ROOT/deploy/prometheus" "$BUNDLE/compose/" 2>/dev/null || true
cp -r "$ROOT/deploy/helm/." "$BUNDLE/helm/" 2>/dev/null || true
cp -r "$ROOT/deploy/sdk" "$BUNDLE/" 2>/dev/null || true

echo "==> 3/5 导出镜像"
if [ "$WITH_IMAGES" = "1" ]; then
  for img in "${IMAGES[@]}"; do
    name=$(echo "$img" | tr '/:' '__')
    if docker image inspect "$img" >/dev/null 2>&1; then
      echo "    save $img"
      docker save "$img" -o "$BUNDLE/images/$name.tar"
    else
      echo "    跳过（本机没有）：$img"
    fi
  done
else
  echo "    --no-images：跳过镜像导出"
fi

echo "==> 4/5 生成 MANIFEST 与脚本"
cat > "$BUNDLE/MANIFEST" <<EOF
product=TokenArk 算力平台
version=${VERSION}
built_at=$(date -Iseconds)
built_on=$(uname -s)-$(uname -m)
with_images=${WITH_IMAGES}
images=$(printf '%s ' "${IMAGES[@]}")
EOF

cp "$(dirname "$0")/upgrade.sh" "$BUNDLE/scripts/" 2>/dev/null || true
cp "$(dirname "$0")/rollback.sh" "$BUNDLE/scripts/" 2>/dev/null || true
cp "$(dirname "$0")/verify.sh" "$BUNDLE/scripts/" 2>/dev/null || true

echo "==> 5/5 计算校验和并打包"
( cd "$BUNDLE" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS )
tar -czf "$OUT" -C "$STAGE" "tokenark-offline-${VERSION}"
find "$STAGE" -mindepth 1 -delete 2>/dev/null || true
rmdir "$STAGE" 2>/dev/null || true

echo
echo "完成：$OUT"
echo "体积：$(du -h "$OUT" | cut -f1)"
echo "校验：tar -tzf '$OUT' | head"
