#!/usr/bin/env bash
set -euo pipefail

GS_URL=${GS_URL:-http://localhost:8080/geoserver}
GS_AUTH=${GS_AUTH:-admin:geoserver}
MODEL=${MODEL:-claude-haiku-5-5}
BUDGET=${BUDGET:-0.50}
WS=scn$(date +%s)

host=$(sed -E 's#^[a-z]+://([^/:]+).*#\1#' <<<"$GS_URL")
case $host in
  localhost | 127.* | ::1) ;;
  *) echo "refusing: scenarios delete and create data, $GS_URL is not local" >&2; exit 2 ;;
esac

rest() { curl -sf -u "$GS_AUTH" "$@"; }

rest -X PUT -H 'Content-Type: application/json' -d '{"enabled":true}' "$GS_URL/rest/mcp/settings" >/dev/null

work=$(mktemp -d)
trap 'rest -X DELETE "$GS_URL/rest/workspaces/$WS?recurse=true" >/dev/null || true; rm -rf "$work"' EXIT
cat >"$work/mcp.json" <<EOF
{"mcpServers":{"gs":{"type":"http","url":"$GS_URL/mcp","headers":{"Authorization":"Basic $(printf %s "$GS_AUTH" | base64)"}}}}
EOF

check_publish() { rest "$GS_URL/rest/layers/$WS:states.json" >/dev/null; }
check_style() { rest "$GS_URL/rest/layers/$WS:states.json" | jq -e '.layer.defaultStyle.name | test("polygon$") | not' >/dev/null; }
check_group() { rest "$GS_URL/rest/workspaces/$WS/layergroups/base.json" | jq -e '.layerGroup.publishables' >/dev/null; }

scenarios=(
  "publish|Publish the US states shapefile at file:data/shapefiles/states.shp as a layer named states, in a new workspace $WS."
  "style|Style layer $WS:states as a choropleth on the PERSONS attribute with 5 classes and make it the layer's default style."
  "group|Create a layer group named base in workspace $WS that contains the layer $WS:states."
)

failed=0
printf '%-8s %-5s %6s %9s %8s\n' scenario ok turns tokens usd
for s in "${scenarios[@]}"; do
  name=${s%%|*} prompt=${s#*|}
  # --tools "" removes Bash/WebFetch: otherwise the model can curl REST and pass without using MCP.
  out=$(cd "$work" && claude -p "$prompt" --output-format json --model "$MODEL" \
    ${ANTHROPIC_API_KEY:+--bare} --setting-sources "" --tools "" \
    --strict-mcp-config --mcp-config "$work/mcp.json" --allowedTools "mcp__gs__*" \
    --max-budget-usd "$BUDGET" 2>/dev/null) || out={}
  ok=no
  "check_$name" && ok=yes || failed=1
  jq -r --arg n "$name" --arg ok "$ok" \
    '[$n, $ok, (.num_turns // 0), ((.usage.input_tokens // 0) + (.usage.cache_read_input_tokens // 0) + (.usage.cache_creation_input_tokens // 0) + (.usage.output_tokens // 0)), (.total_cost_usd // 0)] | @tsv' \
    <<<"$out" | awk -F'\t' '{printf "%-8s %-5s %6s %9s %8.4f\n",$1,$2,$3,$4,$5}'
done
exit $failed
