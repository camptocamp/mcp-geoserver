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

