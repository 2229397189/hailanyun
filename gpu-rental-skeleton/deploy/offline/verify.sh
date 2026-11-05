#!/usr/bin/env bash
# 离线包完整性校验：逐个比对 SHA256SUMS，任何一个不匹配就失败
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f SHA256SUMS ] || { echo "缺少 SHA256SUMS" >&2; exit 1; }
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum -c SHA256SUMS --quiet
else
  shasum -a 256 -c SHA256SUMS
fi
echo "校验通过：$(wc -l < SHA256SUMS | tr -d ' ') 个文件"
