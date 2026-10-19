#!/usr/bin/env bash
set -euo pipefail

GS_URL=${GS_URL:-http://localhost:8080/geoserver}
GS_AUTH=${GS_AUTH:-admin:geoserver}
MODEL=${MODEL:-claude-haiku-5-5}
BUDGET_EUR=${BUDGET_EUR:-0.50}
RUNS=${RUNS:-1}
# The claude CLI reports and caps cost in USD only.
EUR_PER_USD=${EUR_PER_USD:-0.86}
BASE=scn$(date +%s)

host=$(sed -E 's#^[a-z]+://([^/:]+).*#\1#' <<<"$GS_URL")
case $host in
  localhost | 127.* | ::1) ;;
  *) echo "refusing: scenarios delete and create data, $GS_URL is not local" >&2; exit 2 ;;
esac

rest() { curl -sf -u "$GS_AUTH" "$@"; }

rest -X PUT -H 'Content-Type: application/json' -d '{"enabled":true}' "$GS_URL/rest/mcp/settings" >/dev/null

work=$(mktemp -d)
created=()
trap 'for w in "${created[@]}"; do rest -X DELETE "$GS_URL/rest/workspaces/$w?recurse=true" >/dev/null || true; done; rm -rf "$work"' EXIT
cat >"$work/mcp.json" <<EOF
{"mcpServers":{"gs":{"type":"http","url":"$GS_URL/mcp","headers":{"Authorization":"Basic $(printf %s "$GS_AUTH" | base64)"}}}}
EOF

check_publish() { rest "$GS_URL/rest/layers/$WS:states.json" >/dev/null; }
check_style() { rest "$GS_URL/rest/layers/$WS:states.json" | jq -e '.layer.defaultStyle.name | test("polygon$") | not' >/dev/null; }
check_bulk() { [ "$(rest "$GS_URL/rest/workspaces/${WS}b/layers.json" | jq '.layers.layer | length')" = 5 ]; }
check_group() { rest "$GS_URL/rest/workspaces/$WS/layergroups/base.json" | jq -e '.layerGroup.publishables' >/dev/null; }

failed=0
printf '%-8s %-5s %6s %9s %8s %9s  %-11s %s\n' scenario ok turns tokens eur eur/unit unit tools
for run in $(seq "$RUNS"); do
  WS=${BASE}r$run
  created+=("$WS" "${WS}b")
  scenarios=(
    "publish|layer|1|Publish the US states shapefile at file:data/shapefiles/states.shp as a layer named states, in a new workspace $WS."
    "style|style|1|Style layer $WS:states as a choropleth on the PERSONS attribute with 5 classes, store the style in workspace $WS, and make it the layer's default style."
    "bulk|layer|5|Publish every shapefile in the directory file:data/sf as layers in a new workspace ${WS}b."
    "group|layer group|1|Create a layer group named base in workspace $WS that contains the layer $WS:states."
  )
  for s in "${scenarios[@]}"; do
    IFS='|' read -r name unit count prompt <<<"$s"
    # --tools "" removes Bash/WebFetch: otherwise the model can curl REST and pass without using MCP.
    out=$(cd "$work" && claude -p "$prompt" --output-format stream-json --verbose --model "$MODEL" \
      ${ANTHROPIC_API_KEY:+--bare} --setting-sources "" --tools "" \
      --strict-mcp-config --mcp-config "$work/mcp.json" --allowedTools "mcp__gs__*" \
      --max-budget-usd "$(awk -v e="$BUDGET_EUR" -v r="$EUR_PER_USD" 'BEGIN{print e/r}')" 2>/dev/null) || true
    ok=no
    "check_$name" && ok=yes || failed=1
    tools=$(jq -rs '[.[] | select(.type == "assistant") | .message.content[]? | select(.type == "tool_use") | .name | sub("^mcp__gs__gs_"; "")] | join(",")' <<<"$out")
    jq -rs --arg n "$name" --arg ok "$ok" --arg u "$unit" --argjson c "$count" --argjson r "$EUR_PER_USD" \
      '(map(select(.type == "result")) | last // {}) | [$n, $ok, (.num_turns // 0), ((.usage.input_tokens // 0) + (.usage.cache_read_input_tokens // 0) + (.usage.cache_creation_input_tokens // 0) + (.usage.output_tokens // 0)), ((.total_cost_usd // 0) * $r), ((.total_cost_usd // 0) * $r / $c), $u] | @tsv' \
      <<<"$out" | awk -F'\t' -v t="$tools" '{printf "%-8s %-5s %6s %9s %8.4f %9.4f  %-11s %s\n",$1,$2,$3,$4,$5,$6,$7,t}'
  done
done
exit $failed
