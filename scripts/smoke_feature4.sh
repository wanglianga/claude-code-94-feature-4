#!/usr/bin/env bash
# feature-4 端到端冒烟：异常冻结/多方补证/复盘/赔付审批/幂等并发。
# 用法：
#   export CC_PUBLISH_PORT=$(docker compose port app 8080 | cut -d: -f2)
#   bash scripts/smoke_feature4.sh
set -euo pipefail

export CC_PUBLISH_PORT="${CC_PUBLISH_PORT:-3194}"
export CC_BASE_URL="${CC_BASE_URL:-http://host.docker.internal:${CC_PUBLISH_PORT}}"

HERE="$(cd "$(dirname "$0")" && pwd)"
python3 "${HERE}/smoke_feature4.py"
