# <img src="https://raw.githubusercontent.com/geoserver/geoserver/main/src/web/core/src/main/java/org/geoserver/web/img/logo.png" alt="GeoServer" height="48" align="absmiddle"> GeoServer MCP

Not built yet: design and plan stage. An MCP (Model Context Protocol) server that runs inside GeoServer. Once built, point Claude Code or any MCP client at `/geoserver/mcp` and administer the catalog from chat: publish layers, write styles, query features, look at rendered maps, seed tile caches.

Works on vanilla GeoServer 3.0.x and GeoServer Cloud 3.0.x.

## Status

Work in progress: design and plan only, no code yet.

- Design: [docs/superpowers/specs/2026-09-25-geoserver-mcp-design.md](docs/superpowers/specs/2026-09-25-geoserver-mcp-design.md)
- Implementation plan: [docs/superpowers/plans/2026-09-25-geoserver-mcp.md](docs/superpowers/plans/2026-09-25-geoserver-mcp.md)

## Quick start (vanilla GeoServer 3.0.1 in Docker)

```bash
./mvnw -q package -DskipTests
docker compose -f docker/vanilla/compose.yml up -d
curl -u admin:geoserver -X PUT -H 'Content-Type: application/json' \
  -d '{"enabled":true}' http://localhost:8080/geoserver/rest/mcp/settings
claude mcp add --transport http geoserver http://localhost:8080/geoserver/mcp \
  --header "Authorization: Basic $(printf 'admin:geoserver' | base64)"
```

## Enable, disable, verbose

Three ways, all persisted in the GeoServer global settings:

1. Admin UI: Services, MCP Server (vanilla only).
2. REST: `GET` and `PUT /rest/mcp/settings` with `{"enabled":true,"allowedRoles":["ROLE_ADMINISTRATOR"],"verbose":false,"internalBaseUrl":""}`.
3. Any tool call with `"verbose": true` for that call only.

A disabled server answers HTTP 503 on `/mcp`. Callers without an allowed role get 403.

## Verbose mode

When verbose is on, every tool result carries a second block titled `Equivalent commands` with one `curl` line per REST call that would do the same thing, credentials masked as `$GS_AUTH`. The same lines are logged at INFO under `org.geoserver.mcp.commands`.

## Tools

All tools are prefixed `gs_` and accept an optional `verbose` boolean. `AGENTS.md` will carry the full list with arguments once code lands.

Read
- `gs_status`: versions, proxy base URL, counts
- `gs_list_workspaces`, `gs_get_workspace`
- `gs_list_stores`, `gs_get_store`
- `gs_list_layers`, `gs_get_layer`
- `gs_list_styles`, `gs_get_style` (returns the SLD body)
- `gs_list_layer_groups`, `gs_get_layer_group`

Discovery
- `gs_list_store_types`: runtime list from GeoTools, vector with parameter descriptors, raster with url
- `gs_list_modules`: installed GeoServer modules

Write
- `gs_create_workspace`, `gs_create_store`, `gs_publish_layer`, `gs_update_layer`
- `gs_put_style`, `gs_create_layer_group`
- `gs_delete`, `gs_reload`

Data
- `gs_query_features`: returns GeoJSON
- `gs_get_map`: returns a rendered image

GeoWebCache (via the GWC REST API)
- `gs_gwc_list_tile_layers`, `gs_gwc_get_tile_layer`, `gs_gwc_set_tile_layer`
- `gs_gwc_list_gridsets`
- `gs_gwc_seed`, `gs_gwc_list_tasks`, `gs_gwc_kill_tasks`

## Plugins

Store types and modules are discovered at runtime: `gs_list_store_types` and `gs_list_modules` show what this server has. [docs/plugins.md](docs/plugins.md) lists which GeoServer extensions and community modules each tool can drive, and which of them have been verified.

## Manual curl

```bash
curl -s -u admin:geoserver -X POST http://localhost:8080/geoserver/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2025-06-18' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

The `Accept` header must list both media types or the transport answers 400.

## GeoServer Cloud

See `docker/cloud/README.md`.

## Build

```bash
./mvnw verify            # unit tests
./mvnw verify -Pit       # plus Testcontainers integration test (needs Docker)
```
