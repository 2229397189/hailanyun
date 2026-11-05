#!/usr/bin/env bash
# 离线升级（在客户内网执行）
#
#   1) 解包：tar -xzf tokenark-offline-<版本>.tar.gz && cd tokenark-offline-<版本>
#   2) 校验：bash scripts/verify.sh
#   3) 升级：bash scripts/upgrade.sh /opt/tokenark
#
# 顺序：校验 → 备份数据库 → 导入镜像 → 起新版本 → 健康检查
# 任一步失败立即停，rollback.sh 可以退回上一版镜像。
set -euo pipefail
cd "$(dirname "$0")/.."

TARGET="${1:-/opt/tokenark}"
COMPOSE_DIR="$TARGET"

echo "==> 1/5 校验离线包完整性"
bash scripts/verify.sh

echo "==> 2/5 备份数据库（失败即中止，不带备份不升级）"
if docker ps --format '{{.Names}}' | grep -q '^gpu-rental-pg$'; then
  BK="$TARGET/backup-$(date +%Y%m%d%H%M).sql"
  mkdir -p "$TARGET"
  docker exec gpu-rental-pg pg_dump -U rental -d rental > "$BK"
  echo "    备份：$BK ($(du -h "$BK" | cut -f1))"
else
  echo "    未发现运行中的 gpu-rental-pg，跳过备份（首次安装属正常）"
fi

echo "==> 3/5 导入镜像"
for f in images/*.tar; do
  [ -e "$f" ] || continue
  echo "    load $f"
  docker load -i "$f"
done

echo "==> 4/5 更新编排文件并启动新版本"
mkdir -p "$COMPOSE_DIR"
cp -r compose/. "$COMPOSE_DIR/"
cd "$COMPOSE_DIR"
if [ -f docker-compose.real.yml ]; then
  docker compose -f docker-compose.yml -f docker-compose.real.yml up -d
else
  docker compose up -d
fi

echo "==> 5/5 健康检查（最多等 90s）"
for i in $(seq 1 30); do
  if curl -sf http://localhost:8099/api/health >/dev/null 2>&1; then
    echo "    健康检查通过：$(curl -s http://localhost:8099/api/health)"
    echo
    echo "升级完成。版本信息见 MANIFEST。"
    exit 0
  fi
  sleep 3
done

echo "    健康检查未通过，请执行 scripts/rollback.sh 回滚" >&2
exit 1
