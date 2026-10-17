# <img src="https://raw.githubusercontent.com/geoserver/geoserver/main/src/web/core/src/main/java/org/geoserver/web/img/logo.png" alt="GeoServer" height="48" align="absmiddle"> GeoServer MCP

[![Latest release](https://img.shields.io/github/v/release/camptocamp/mcp-geoserver?color=brightgreen&label=release)](https://github.com/camptocamp/mcp-geoserver/releases/latest)
[![License: GPL-2.0](https://img.shields.io/badge/license-GPL--2.0-blue)](LICENSE)
[![Tests](https://github.com/camptocamp/mcp-geoserver/actions/workflows/tests.yml/badge.svg?branch=master)](https://github.com/camptocamp/mcp-geoserver/actions/workflows/tests.yml)
[![Security](https://github.com/camptocamp/mcp-geoserver/actions/workflows/security.yml/badge.svg?branch=master)](https://github.com/camptocamp/mcp-geoserver/actions/workflows/security.yml)
[![Quality Gate Status](https://sast.camptocamp.net/api/project_badges/measure?project=camptocamp_mcp-geoserver_cfb24ca6-0f59-4d8a-b788-161c34a2140b&metric=alert_status&token=sqb_a32cf7a16c1d4d9c8db4bd704579c8d2414a80b8)](https://sast.camptocamp.net/dashboard?id=camptocamp_mcp-geoserver_cfb24ca6-0f59-4d8a-b788-161c34a2140b)
[![Coverage](https://sast.camptocamp.net/api/project_badges/measure?project=camptocamp_mcp-geoserver_cfb24ca6-0f59-4d8a-b788-161c34a2140b&metric=coverage&token=sqb_a32cf7a16c1d4d9c8db4bd704579c8d2414a80b8)](https://sast.camptocamp.net/dashboard?id=camptocamp_mcp-geoserver_cfb24ca6-0f59-4d8a-b788-161c34a2140b)
[![Security policy](https://img.shields.io/badge/security-policy-informational)](SECURITY.md)
[![Dependabot](https://img.shields.io/badge/dependabot-enabled-025E8C?logo=dependabot)](.github/dependabot.yml)

An MCP (Model Context Protocol) server that runs inside GeoServer. Point Claude Code or any MCP client at `/geoserver/mcp` and administer the catalog from chat: publish layers, write styles or bring them over from QGIS, query features, look at rendered maps, seed tile caches.

Works on vanilla GeoServer 3.0.x and GeoServer Cloud 3.0.x.

## Why this one

- **No extra process.** A jar dropped into `WEB-INF/lib`: no Python service, no sidecar container, nothing else to deploy, patch or monitor next to GeoServer.
- **Runs as the caller.** Every tool goes through GeoServer's own security with the caller's credentials and an allowed-roles gate. There is no shared admin account behind the chat.
- **Equivalent commands.** In verbose mode each result ends with the exact `curl` calls that do the same thing over REST, so an administrator can check what the AI did or paste the commands into deployment scripts.
- **Checks before writing.** JDBC stores must answer `select 1` before anything is saved, PostGIS stores report missing privileges, styles are parsed before they are stored, and `gs_validate_style` reports unknown functions, broken graphics and missing attributes without writing anything.
- **QGIS to production.** The [styles-to-geoserver](https://github.com/camptocamp/styles-to-geoserver) skill lets the agent carry a QGIS style to GeoServer, fix SLD validation errors and compare both renderings.
- **Built for GeoServer 3.** Jakarta and Spring 7, vanilla GeoServer 3.0.x, with a GeoServer Cloud module. Maintained by Camptocamp, a long-standing contributor to the GeoServer core.

## Status

| Target | State |
| --- | --- |
| Vanilla GeoServer 3.0.1 | Works. The extension loads, `/mcp` lists every tool, anonymous gets 403, disabled gives 503. The Testcontainers IT (`./mvnw -pl mcp-it -am verify -Pit`) publishes a generated GeoTIFF, styles it, renders it with `gs_get_map` and round-trips `gs_gwc_set_tile_layer`. |
| GeoServer Cloud 3.0.1 | Works. Verified with the upstream `v3.0.1` compose (datadir catalog) and the derived `geoserver-cloud-rest:3.0.1.1` image from `docker/cloud`: settings via `/rest/mcp/settings` persist across a restconfig restart, `/mcp` through the gateway lists 34 tools (verified before `gs_set_time_dimension` was added), anonymous gets 403, disabled gives 503, `docker/cloud/smoke.sh` passes. Exercised `gs_status`, `gs_list_workspaces`, `gs_create_workspace`, `gs_create_store` (local GeoTIFF and a public `https://` COG), `gs_publish_layer`, `gs_get_map` and `gs_check_layer` through `internalBaseUrl` on the wms service, `gs_query_features`, `gs_gwc_list_gridsets` on the gwc service, `gs_delete`. |

## Install

1. Download `geoserver-mcp-<version>-bin.zip` from the [latest GitHub release](https://github.com/camptocamp/mcp-geoserver/releases/latest) and check it against `SHA256SUMS.txt`.
2. Unzip the jars into GeoServer's `WEB-INF/lib` and restart GeoServer.
3. Open the MCP admin page, enable the server and pick the allowed roles.
4. Connect your MCP client as in step 4 of the quick start.

The zip targets vanilla GeoServer. For GeoServer Cloud, see `docker/cloud/README.md`.

## Connect to an existing GeoServer

The extension runs inside GeoServer, so there is no separate server to host and no token to mint: authentication is GeoServer's own, and the `Authorization` header you give the client is the login it runs as. Point the client at `<geoserver>/mcp` over HTTPS.

1. Install the extension (above) and confirm it under About & Status, Modules.

2. Enable it (it ships disabled), as an administrator:

   ```bash
   curl -u <user>:<pass> -X PUT -H 'Content-Type: application/json' \
     -d '{"enabled":true}' https://<host>/geoserver/rest/mcp/settings
   ```

3. Add it to your MCP client with your GeoServer credentials as a Basic header:

   ```bash
   claude mcp add --transport http geoserver https://<host>/geoserver/mcp \
     --header "Authorization: Basic $(printf '<user>:<pass>' | base64)"
   ```

Notes:

- The account you authenticate as decides what the tools can see and change: everything runs through GeoServer's secured catalog. `allowedRoles` (default `ROLE_ADMINISTRATOR`) gates who may reach `/mcp` at all; others get 403.
- Basic auth is base64, not encryption, so serve `/mcp` over TLS. To use an API key or bearer token instead of a password, front `/mcp` with the matching GeoServer authentication filter chain and send that header; the extension adds no token mechanism of its own.
- Behind a reverse proxy, set `internalBaseUrl` to GeoServer's own URL (same `PUT /rest/mcp/settings`) so `gs_get_map` and the `gs_gwc_*` tools resolve a trusted address. The request-derived fallback is accepted only for loopback, which blocks Host-header SSRF and credential forwarding to an untrusted host.

## Quick start (vanilla GeoServer 3.0.1 in Docker)

1. Build the plugin. This also copies the jars into `docker/vanilla/additional_libs/`.

   ```bash
   ./mvnw -q package -DskipTests
   ```

2. Start GeoServer with the plugin.

   ```bash
   docker compose -f docker/vanilla/compose.yml up -d
   ```

3. Enable the MCP server. It ships disabled.

   ```bash
   curl -u admin:geoserver -X PUT -H 'Content-Type: application/json' \
     -d '{"enabled":true}' http://localhost:8080/geoserver/rest/mcp/settings
   ```

4. Connect Claude Code.

   ```bash
   claude mcp add --transport http geoserver http://localhost:8080/geoserver/mcp \
     --header "Authorization: Basic $(printf 'admin:geoserver' | base64)"
   ```

## Enable, disable, verbose

Three ways, all persisted in the GeoServer global settings:

1. Admin UI: Services, MCP Server (vanilla only).
2. REST: `GET` and `PUT /rest/mcp/settings` with `{"enabled":true,"allowedRoles":["ROLE_ADMINISTRATOR"],"verbose":false,"internalBaseUrl":"","readOnly":false,"maxListResults":500,"maxFeatures":10000}`. Any subset of keys may be sent.
3. Any tool call with `"verbose": true` for that call only.

A disabled server answers HTTP 503 on `/mcp`. Callers without an allowed role get 403.

## Read-only mode and limits

- `readOnly`: only tools annotated read-only run; every other call answers an error naming the mode. Role setup is untouched.
- `maxListResults` (default 500): any list in a tool result is cut to this length and the result gets `"truncated": {"<key>": <total>}`.
- `maxFeatures` (default 10000): upper bound for `gs_query_features`, whatever the caller asks.

All three are on the admin page and in the REST settings.

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
- `gs_create_workspace`, `gs_create_store`, `gs_publish_layer`, `gs_publish_layers` (bulk, up to 50), `gs_create_sql_view`, `gs_update_layer`, `gs_set_time_dimension` (vector attribute or ImageMosaic time, presentation, default value)
- `gs_put_style`, `gs_generate_style` (categorized, equal interval, quantile or Jenks classes computed from the layer's data, colour ramps, optional default style)
- `gs_create_layer_group`, `gs_update_layer_group` (title, abstract, keywords, mode, add or remove members including nested groups, or replace the list)
- `gs_delete`, `gs_reload`

Data
- `gs_query_features`: returns GeoJSON
- `gs_get_map`: returns a rendered image

GeoWebCache (via the GWC REST API)
- `gs_gwc_list_tile_layers`, `gs_gwc_get_tile_layer`, `gs_gwc_set_tile_layer`
- `gs_gwc_list_gridsets`
- `gs_gwc_seed`, `gs_gwc_list_tasks`, `gs_gwc_kill_tasks`

## Resources

Read-only MCP resources mirror the read tools, for clients that browse resources instead of calling tools:

- `geoserver://status`, `geoserver://workspaces`, `geoserver://layers`, `geoserver://styles`, `geoserver://layergroups`
- `geoserver://workspaces/{name}`, `geoserver://layers/{name}` (`workspace:name`, URL-encoded), `geoserver://styles/{name}`

They run the matching tool with the caller's credentials, so permissions and limits are the same.

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

## Rasters on S3, Google Cloud Storage or HTTP

`gs_create_store` with type `GeoTIFF` and `params.url` set to `s3://bucket/key.tif`, `gs://bucket/key.tif` or `https://...` publishes the file as a Cloud Optimized GeoTIFF read in place; nothing is copied to the server. Then `gs_publish_layer` as usual.

- Install the matching GeoServer community module: `cog-s3`, `cog-google` or `cog-http`. Without it the tool says which one is missing.
- S3 credentials never go through the chat. Set them on GeoServer: `IIO_S3_AWS_USER`, `IIO_S3_AWS_PASSWORD`, `IIO_S3_AWS_REGION`, and `IIO_S3_AWS_ENDPOINT` for S3-compatible storage (MinIO, Exoscale SOS). Public buckets need nothing. Google Cloud reads `GOOGLE_APPLICATION_CREDENTIALS`.
- The file must be a COG (tiled, with overviews). Convert with `gdal_translate -of COG in.tif out.tif`.

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

## Releasing

Maintainers: `git tag vX.Y.Z && git push origin vX.Y.Z`. The release workflow sets the Maven version from the tag, runs the unit tests and attaches the zip and its checksum to a GitHub release.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). To report a vulnerability, see [SECURITY.md](SECURITY.md).

## License

GPL-2.0, like GeoServer. See [LICENSE](LICENSE).
