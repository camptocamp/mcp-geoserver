# AGENTS.md

Instructions for AI agents and humans working in this repository.

## What this is

A GeoServer extension exposing an MCP server at `/mcp`. Before changing architecture, read the design spec `docs/superpowers/specs/2026-09-25-geoserver-mcp-design.md`; it is kept local and is not in the repository. Both "Amendments" sections win over earlier sections.

## Layout

- `mcp-core`: tools, settings, HTTP endpoint. No Spring Boot, no Wicket. Everything testable without a servlet container.
- `mcp-geoserver`: vanilla adapter. `applicationContext.xml`, Wicket admin page, zip assembly.
- `mcp-geoserver-cloud`: Spring Boot autoconfiguration for the GeoServer Cloud restconfig service.
- `mcp-it`: Testcontainers integration test, profile `it`.
- `docker/`: compose stacks and Cloud image recipe.

## Build and test

```bash
./mvnw -q verify                 # all unit tests
./mvnw -q -pl :gs-mcp-core test      # one module
./mvnw -q verify -Pit            # integration test, needs Docker
./mvnw -q package -DskipTests    # produces mcp-geoserver/target/geoserver-mcp-<version>-bin.zip and fills docker/vanilla/additional_libs/
```

Tests extending `GeoServerSystemTestSupport` are JUnit 4 (`org.junit.Test`). Everything else is JUnit 5.

## Conventions

- Java 17 source level. Records for data, no Lombok.
- Tool names `gs_<verb>_<noun>`. One class per tool group under `org.geoserver.mcp.tools`.
- A tool is a `GsTool`: `definition()` returns the `McpSchema.Tool`, `call(ToolContext, Args)` returns a payload (`Map`, record, `RawJson`, or `ImagePayload`). Throw `ToolException` with a one-line message for user errors.
- Every tool that maps to a REST call records it with `ctx.trace().curl(...)` so verbose mode works.
- Settings live in `GeoServerInfo` metadata, keys `mcp.*`. Never add a `ServiceInfo` subtype (breaks GeoServer Cloud pgconfig).
- Settings reach tools through `ToolContext.settings()`. `ToolAdapter` enforces `readOnly` from the tool's read-only hint and caps lists to `maxListResults`, so a new read tool must be in `ToolHints.READ`.
- MCP resources (`ToolResources`) are thin URIs over read tools; add one there rather than duplicating tool logic.
- JSON via Jackson 2 `ObjectMapper` passed explicitly. Never `McpJsonDefaults`.
- No code comments except for hidden constraints. No em dashes anywhere.
- Commit after every task. Messages: `feat|fix|test|docs|chore: <what>`.

## Quality gate (SonarQube)

CI fails when the SonarQube quality gate fails on new code. Write code that passes it:

- A string literal of 5+ characters repeated 3 or more times becomes a constant. Shared across files: `org.geoserver.mcp.tool.Keys` (JSON keys, argument names, REST path prefixes, repeated descriptions). Repeated only inside one file: a `private static final` in that file. Reuse an existing `Keys` constant before adding one or writing the literal again.
- Cognitive complexity stays at 15 or below per method. When a tool's `call` grows, extract helpers on the tool group class (validation, building, tracing) rather than nesting more branches.
- XML goes through `org.geoserver.mcp.tool.SafeXml.parse` and `SafeXml.write`, which disable DTDs and external entities; do not create `DocumentBuilderFactory` or `TransformerFactory` elsewhere.
- A constant must not differ from a field or record component only by case (e.g. `AUTHORIZATION` next to `authorization`); suffix context-map keys with `_KEY`.
- Coverage on new code stays at 90% or above: every new tool or branch comes with a test. Prefer Mockito or `StubHttpServer` unit tests over `GeoServerSystemTestSupport`, which is slow.
- Two rules are excluded in `pom.xml` and stay excluded: `java:S110` for Wicket pages (the inheritance depth comes from GeoServer) and `java:S112` on `GsTool.call` (any failure is turned into an MCP error by `ToolAdapter`). Do not add exclusions for anything else; fix the code.

## Security testing

CI runs `.github/workflows/security.yml` on every PR and on push to master, with three jobs:

- `dependency-review` (`actions/dependency-review-action`): PR-only, fails on a dependency vulnerability of `high` severity or above.
- `snyk` (`snyk/actions/maven-3-jdk-17`): `test --maven-aggregate-project --severity-threshold=high`. Gated on the `SNYK_TOKEN` secret (`if: env.SNYK_TOKEN != ''`), so it no-ops on Dependabot and fork PRs.
- `osv` (`google/osv-scanner-action`): builds a CycloneDX SBOM (`cyclonedx:makeAggregateBom`, provided and test scope excluded in pom.xml) and scans it against the OSV database (osv.dev). Needs no token and no CVE download, so it runs and gates even on Dependabot and fork PRs. Fails when any known vulnerability is found. Scope is the bundled/app deps only; the GeoServer/GeoTools/Spring stack is `provided` and shipped by the host, so it is deliberately out of scope here.

The SonarQube quality gate (`.github/workflows/sonarqube.yml`) also gates new code; see "Quality gate (SonarQube)" above. The release workflow (`.github/workflows/release.yml`) runs `snyk monitor` to post the dependency snapshot to Snyk, again gated on `SNYK_TOKEN`.

Secrets (`gh secret list -R camptocamp/mcp-geoserver` shows which are set):

- `SNYK_TOKEN`: Snyk test in CI and `snyk monitor` on release.
- `SONAR_TOKEN`: SonarQube scan.

Run locally:

- Snyk, if installed and authed: `snyk test --maven-aggregate-project --severity-threshold=high` (same as CI). Covers bundled and transitive deps.
- OSV (needs no token): `./mvnw -q -DskipTests cyclonedx:makeAggregateBom` then `osv-scanner scan source --lockfile=./target/bom.json`. Same scope as CI (app deps, provided excluded); non-zero exit means a vulnerability was found.
- `/security-review` skill reviews the pending branch changes; run it before merging security-relevant work.

Live assessment (hands-on test of a running server) house rules:

- Target only localhost (local GeoServer at `http://localhost:8080/geoserver`, MCP at `/mcp`). Never a shared or remote instance.
- Non-destructive: no delete or modify of real catalog objects.
- Confine any test objects to a scratch workspace and remove it after.
- Prove SSRF only against a local listener you control.
- Never run `gs_reload` on a shared instance.

## Tool catalogue

Every tool also accepts `verbose: boolean` and declares MCP annotations (read-only, destructive, idempotent) from `ToolHints`.

Read
- `gs_status()`: versions, proxy base URL, counts
- `gs_list_workspaces()`, `gs_get_workspace(name)`
- `gs_list_stores(workspace?)`, `gs_get_store(workspace, name, names[]?, detail?)`: vector stores also list `available` feature types; names or detail add geometry columns, types, SRIDs
- `gs_list_layers(workspace?)`, `gs_get_layer(name)`
- `gs_check_layer(name, bbox[]?, width?, height?)`: GetMap over HTTP, text only: status, contentType, serviceException, paintedRatio, elapsedMs
- `gs_list_styles(workspace?)`, `gs_get_style(name, workspace?)` returns the style body
- `gs_validate_style(body | name, workspace?, layer?, format?)`: parse only; parseError, version, unknownFunctions, unresolvedGraphics, missingAttributes when layer given
- `gs_list_layer_groups(workspace?)`, `gs_get_layer_group(name, workspace?)`

Discovery
- `gs_list_store_types(kind?)`: runtime list from GeoTools, vector with parameter descriptors, raster with url
- `gs_list_modules()`: ModuleStatus beans

Write
- `gs_create_workspace(name, namespaceUri?, isolated?)`
- `gs_create_store(workspace, name, type, params)`: JDBC stores must answer `select 1` or nothing is saved; PostGIS stores also return privilege `warnings`
- `gs_publish_layer(workspace, store, nativeName?, name?, title?, srs?)`: compact summary, gs_get_layer has attributes
- `gs_publish_layers(workspace, store, names[] | all, includeNonSpatial?)`: up to 50 per call, per-item result with warnings or error, geometryless skipped
- `gs_create_sql_view(workspace, store, name, sql, geometry, geometryType, srid, keyColumn?)`: SQL view on a JDBC store, published as a layer
- `gs_update_layer(name, title?, abstract?, defaultStyle?, styles[]?, enabled?, advertised?)`
- `gs_set_time_dimension(name, enabled?, attribute?, endAttribute?, presentation?, resolution?, defaultValue?, referenceValue?, nearestMatch?)`: vector layers need `attribute`; DISCRETE_INTERVAL needs `resolution` in ms
- `gs_put_style(name, workspace?, body, format?)`: format `sld` by default, `css`, `ysld`, `mbstyle` with their extension
- `gs_create_layer_group(name, workspace?, title?, layers[], styles[]?, mode?)`
- `gs_update_layer_group(name, workspace?, title?, abstract?, mode?, keywords[]?, add[]?, addStyles[]?, remove[]?, layers[]?, styles[]?)`: members may be layers or groups; `layers` replaces the list and excludes add/remove; a group cannot become empty
- `gs_generate_style(layer, attribute, method, classes?, ramp?, name?, workspace?, setDefault?)`: method categorized (up to 50 values) or equalInterval, quantile, jenks (numeric, 2 to 20 classes); stored through gs_put_style; returns rules and the SLD
- `gs_delete(kind, workspace?, name, recurse?)`
- `gs_reload()`

Data
- `gs_query_features(layer, cql?, bbox[]?, properties[]?, maxFeatures?)` returns GeoJSON
- `gs_get_map(layers[], bbox[], width, height, srs?, styles[]?, format?, transparent?)` returns an image

GeoWebCache (via GWC REST over HTTP)
- `gs_gwc_list_tile_layers()`, `gs_gwc_get_tile_layer(layer)`, `gs_gwc_set_tile_layer(layer, enabled?, gridsets[]?, formats[]?)`
- `gs_gwc_list_gridsets()`
- `gs_gwc_seed(layer, gridset, zoomStart, zoomStop, format, operation?, threads?, bbox[]?)`
- `gs_gwc_list_tasks(layer?)`, `gs_gwc_kill_tasks(layer?, which?)`

## Gotchas

- `tools/call` runs on the servlet thread (`immediateExecution(true)`), so `SecurityContextHolder` and the secured catalog work. Do not remove that flag.
- `gs_get_map`, `gs_check_layer` and `gs_gwc_*` go over HTTP even on vanilla, with the caller's `Authorization` header forwarded. Base URL order: `mcp.internalBaseUrl`, proxy base URL, request URL. The request-URL fallback is derived from the client `Host` header, so it is accepted only when it resolves to loopback; forwarding credentials to any other request-derived host is refused (set `mcp.internalBaseUrl` behind a proxy). This blocks Host-header SSRF and credential exfiltration.
- `gs_gwc_set_tile_layer` sends GWC's XML representation; GWC 2.0.1 rejects multi-element JSON updates. `gs_gwc_seed` uses JSON.
- The MCP stateless transport answers 400 unless `Accept` contains both `application/json` and `text/event-stream`.
- GeoServer 3.0.1 ships Jackson 3; the zip carries its own Jackson 2.22 core and databind.
- Cloud image, 3.0.1: jars must be appended to `BOOT-INF/classpath.idx`, and `AOT_MODE=off` (derived image). 3.0.2+ (geoserver-cloud PR #920): mount jars at `/opt/additional_libs` instead; the image runs `PropertiesLauncher` with `LOADER_PATH` set and `AOT_MODE=auto`, so no derived image and no `AOT_MODE=off`. See `docker/cloud/README.md`.
- The GeoServer test harness is JUnit 4; new tool tests extend `McpToolTestSupport`.

## Version facts (verified 2026-09-25)

GeoServer 3.0.1 = Spring 7.0.8, Jakarta Servlet 6.1, Wicket 10.10, GeoTools 35.1, GWC 2.0.1. GeoServer Cloud 3.0.1 = same GeoServer, Spring Boot 4.0.7, artifacts not published. MCP Java SDK 2.0.1, Jakarta-only servlet transport, stateless transport needs no async servlet support.
