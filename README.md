# <img src="https://raw.githubusercontent.com/geoserver/geoserver/main/src/web/core/src/main/java/org/geoserver/web/img/logo.png" alt="GeoServer" height="48" align="absmiddle"> GeoServer MCP

An MCP (Model Context Protocol) server that runs inside GeoServer. Point Claude Code or any MCP client at `/geoserver/mcp` and administer the catalog from chat: publish layers, write styles or bring them over from QGIS, query features, look at rendered maps, seed tile caches.

Works on vanilla GeoServer 3.0.x and GeoServer Cloud 3.0.x.

## Status

| Target | State |
| --- | --- |
| Vanilla GeoServer 3.0.1 | Works. The extension loads, `/mcp` lists 34 tools, anonymous gets 403, disabled gives 503. The Testcontainers IT (`./mvnw -pl mcp-it -am verify -Pit`) publishes a generated GeoTIFF, styles it, renders it with `gs_get_map` and round-trips `gs_gwc_set_tile_layer`. |
| GeoServer Cloud 3.0.1 | Files exist in `docker/cloud`; not built or run yet. |

## Install

1. Download `geoserver-mcp-<version>-bin.zip` from the [latest GitHub release](https://github.com/camptocamp/mcp-geoserver/releases/latest) and check it against `SHA256SUMS.txt`.
2. Unzip the jars into GeoServer's `WEB-INF/lib` and restart GeoServer.
3. Open the MCP admin page, enable the server and pick the allowed roles.

The zip targets vanilla GeoServer. For GeoServer Cloud, see `docker/cloud/README.md`.

## Releasing

Maintainers: `git tag vX.Y.Z && git push origin vX.Y.Z`. The release workflow sets the Maven version from the tag, runs the unit tests and attaches the zip and its checksum to a GitHub release.

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

All tools are prefixed `gs_` and accept an optional `verbose` boolean. `AGENTS.md` lists every tool with its arguments.

Read
- `gs_status`: versions, proxy base URL, counts
- `gs_list_workspaces`, `gs_get_workspace`
- `gs_list_stores`, `gs_get_store`
- `gs_list_layers`, `gs_get_layer`, `gs_check_layer` (text-only render check)
- `gs_list_styles`, `gs_get_style` (returns the SLD body), `gs_validate_style` (parse and report findings, writes nothing)
- `gs_list_layer_groups`, `gs_get_layer_group`

Discovery
- `gs_list_store_types`: runtime list from GeoTools, vector with parameter descriptors, raster with url
- `gs_list_modules`: installed GeoServer modules

Write
- `gs_create_workspace`, `gs_create_store`, `gs_publish_layer`, `gs_publish_layers` (bulk, up to 50), `gs_create_sql_view`, `gs_update_layer`
- `gs_put_style`, `gs_create_layer_group`
- `gs_delete`, `gs_reload`

Data
- `gs_query_features`: returns GeoJSON
- `gs_get_map`: returns a rendered image

GeoWebCache (via the GWC REST API)
- `gs_gwc_list_tile_layers`, `gs_gwc_get_tile_layer`, `gs_gwc_set_tile_layer`
- `gs_gwc_list_gridsets`
- `gs_gwc_seed`, `gs_gwc_list_tasks`, `gs_gwc_kill_tasks`

## Example session

```
> Publish /data/ortho.tif as ortho:2026 with a 60% opacity style and show me the result.
```

Claude calls `gs_create_workspace`, `gs_create_store` (type GeoTIFF), `gs_publish_layer`, `gs_put_style`, `gs_update_layer`, then `gs_get_map` and looks at the PNG. With verbose on, each step also prints the curl equivalent.

## Security notes

- The default allowed role is `ROLE_ADMINISTRATOR`.
- Use HTTP Basic over TLS in production.
- The endpoint is off by default.
- `internalBaseUrl` is called with the caller's credentials.

## QGIS styles to GeoServer

Style a layer in QGIS, then ask the agent to publish that style on GeoServer. The agent drives two MCP servers, this one and [qgis-mcp](https://github.com/nkarasiak/qgis-mcp); the GeoServer extension never talks to QGIS. The workflow lives in one skill file, `skills/qgis-to-geoserver/SKILL.md`. Install for Claude Code with `ln -s "$PWD/skills/qgis-to-geoserver" ~/.claude/skills/`.

1. Export: QGIS's own SLD exporter, called through qgis-mcp `execute_code` (`exportSldStyleV3` on QGIS 3.44 and later, `saveSldStyle` before). Output is SLD 1.1.0.
2. Push: `gs_put_style`. On a validation error the agent fixes the SLD and retries.
3. Compare: the agent renders the same extent in QGIS at 90.7 DPI and with `gs_get_map`, then looks at both images.

The comparison is mandatory because GeoServer accepts some broken styles without complaint. Known gaps:

- SVG markers: QGIS writes QGIS-relative paths, and GeoServer draws default squares instead. The skill checks that QGIS can resolve the SVG, uploads it next to the style through the REST resource API, and sets the href to the bare file name (verified on 3.0.1, `?fill=` parameters included).
- Data-defined properties and shapeburst fills can drop silently.
- Symbol sizes shift with DPI (QGIS 96, GeoServer 90.7).

Before opening a QGIS project, the agent scans it for stored credentials (`password=`, `authcfg=`, `service=`) and asks before loading it.

## Plugins

Store types and modules are discovered at runtime: `gs_list_store_types` and `gs_list_modules` show what this server has. [docs/plugins.md](docs/plugins.md) states which GeoServer extensions and community modules each tool can drive, and the exceptions.

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
