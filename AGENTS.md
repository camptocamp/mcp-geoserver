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
- JSON via Jackson 2 `ObjectMapper` passed explicitly. Never `McpJsonDefaults`.
- No code comments except for hidden constraints. No em dashes anywhere.
- Commit after every task. Messages: `feat|fix|test|docs|chore: <what>`.

## Tool catalogue

Filled in as tools land. Format: name, arguments, returns.

## Version facts (verified 2026-09-25)

GeoServer 3.0.1 = Spring 7.0.8, Jakarta Servlet 6.1, Wicket 10.10, GeoTools 35.1, GWC 2.0.1. GeoServer Cloud 3.0.1 = same GeoServer, Spring Boot 4.0.7, artifacts not published. MCP Java SDK 2.0.1, Jakarta-only servlet transport, stateless transport needs no async servlet support.
