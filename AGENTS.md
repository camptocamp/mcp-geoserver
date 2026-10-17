# AGENTS.md

Canonical instruction file for every AI agent tool. The per-tool files (`CLAUDE.md`, `GEMINI.md`, `.github/copilot-instructions.md`) only point here; do not duplicate this content.

A GeoServer extension exposing an MCP server at `/mcp`.

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
./mvnw -q clean package -DskipTests    # produces mcp-geoserver/target/geoserver-mcp-<version>-bin.zip and fills docker/vanilla/additional_libs/
```

Tests extending `GeoServerSystemTestSupport` are JUnit 4 (`org.junit.Test`). Everything else is JUnit 5.

## Conventions

- Java 17 source level. Records for data, no Lombok.
- Tool names `gs_<verb>_<noun>`. One class per tool group under `org.geoserver.mcp.tools`.
- A tool is a `GsTool`: `definition()` returns the `McpSchema.Tool`, `call(ToolContext, Args)` returns a payload (`Map`, record, `RawJson`, or `ImagePayload`). Throw `ToolException` with a one-line message for user errors.
- Every tool that maps to a REST call records it with `ctx.trace().curl(...)` so verbose mode works. `verbose` is honoured on every call but not declared in tool schemas; the server instructions in `McpServerHolder` announce it.
- Settings live in `GeoServerInfo` metadata, keys `mcp.*`. Never add a `ServiceInfo` subtype (breaks GeoServer Cloud pgconfig).
- Settings reach tools through `ToolContext.settings()`. `ToolAdapter` enforces `readOnly` from the tool's read-only hint and caps lists to `maxListResults`, so a new read tool must be in `ToolHints.READ`.
- MCP resources (`ToolResources`) are thin URIs over read tools; add one there rather than duplicating tool logic.
- JSON via Jackson 2 `ObjectMapper` passed explicitly. Never `McpJsonDefaults`.
- No code comments except for hidden constraints. No em dashes anywhere.
- Commit after every task. Messages: `feat|fix|test|docs|chore: <what>`.

## Quality gate (SonarQube)

CI fails on the quality gate for new code:

- A string literal of 5+ characters repeated 3 or more times becomes a constant: `org.geoserver.mcp.tool.Keys` if shared across files (reuse an existing one first), else a `private static final` in the file.
- Cognitive complexity of 15 or below per method; extract helpers on the tool group class.
- XML goes through `SafeXml.parse` and `SafeXml.write`; never create `DocumentBuilderFactory` or `TransformerFactory` elsewhere.
- A constant must not differ from a field or record component only by case; suffix context-map keys with `_KEY`.
- Coverage on new code at 90% or above; prefer Mockito or `StubHttpServer` over the slow `GeoServerSystemTestSupport`.
- Only `java:S110` (Wicket pages) and `java:S112` (`GsTool.call`) are excluded in `pom.xml`. Add no others.

## Gotchas

- `tools/call` runs on the servlet thread (`immediateExecution(true)`), so `SecurityContextHolder` and the secured catalog work. Do not remove that flag.
- `gs_get_map`, `gs_check_layer` and `gs_gwc_*` go over HTTP even on vanilla, with the caller's `Authorization` forwarded. Base URL order: `mcp.internalBaseUrl`, proxy base URL, request URL. The request-URL fallback is accepted only when it resolves to loopback (blocks Host-header SSRF); set `mcp.internalBaseUrl` behind a proxy.
- `gs_gwc_set_tile_layer` sends GWC's XML representation; GWC 2.0.1 rejects multi-element JSON updates. `gs_gwc_seed` uses JSON.
- The MCP stateless transport answers 400 unless `Accept` contains both `application/json` and `text/event-stream`.
- GeoServer 3.0.1 ships Jackson 3; the zip carries its own Jackson 2 core and databind.
- GeoServer Cloud artifacts are not published. The MCP stateless transport is Jakarta-only and needs no async servlet support.
- The GeoServer test harness is JUnit 4; new tool tests extend `McpToolTestSupport`.
- Cloud image jar installation differs between 3.0.1 and 3.0.2+: see `docker/cloud/README.md`.

## More

- Tool catalogue and how tools reach GeoServer: `docs/agents/tools.md`
- Security CI gates: `docs/agents/security.md`
- Versions: properties in `pom.xml`
