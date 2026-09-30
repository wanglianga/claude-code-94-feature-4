#!/usr/bin/env bash
# feature-4-b 端到端冒烟（冷链事件/补证/复盘升级/赔付审批/并发幂等）。
# 用法：
#   export CC_PUBLISH_PORT=$(docker compose port app 8080 | cut -d: -f2)
#   bash scripts/smoke_feature4b.sh
set -euo pipefail

export CC_PUBLISH_PORT="${CC_PUBLISH_PORT:-8080}"
export CC_BASE_URL="${CC_BASE_URL:-http://host.docker.internal:${CC_PUBLISH_PORT}}"

HERE="$(cd "$(dirname "$0")" && pwd)"
python3 "${HERE}/smoke_feature4b.py"
