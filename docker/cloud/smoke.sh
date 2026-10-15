#!/usr/bin/env bash
set -euo pipefail
BASE=${1:-http://localhost:9090/geoserver/cloud}
USER=${2:-admin}
PASS=${3:-geoserver}
mcp() {
  curl -sf -u "$USER:$PASS" -X POST "$BASE/mcp" \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2025-06-18' -d "$1"
}
echo "tools/list"
mcp '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' | grep -o '"name":"gs_[a-z_]*"' | wc -l
echo "gs_status"
mcp '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"gs_status","arguments":{"verbose":true}}}'
echo
echo "gs_list_store_types raster"
mcp '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"gs_list_store_types","arguments":{"kind":"raster"}}}' | grep -o '"name":"[A-Za-z]*"' | sort -u
