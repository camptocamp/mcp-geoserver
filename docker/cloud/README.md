# GeoServer Cloud

Two install paths depending on the Cloud version:

- **3.0.2+**: the image mounts extra jars from `/opt/additional_libs` (geoserver-cloud PR #920). No derived image, no `classpath.idx` edit, no `AOT_MODE` change. Use this.
- **3.0.1**: no drop-in jar directory, so the extension ships as a derived restconfig image. Kept below until 3.0.2 is released.

## 3.0.2+ (mount, recommended)

Verified against `geoservercloud/geoserver-cloud-rest:3.0.2-SNAPSHOT`: the entrypoint runs Spring Boot `PropertiesLauncher` with `LOADER_PATH=/opt/additional_libs` baked in, the `/opt/additional_libs` and `/opt/additional_fonts` dirs exist, and `AOT_MODE=auto` with the AOT cache trained under the same launcher (so added jars no longer need `AOT_MODE=off`).

1. Build and stage the jars, from the repository root. The base image already ships Jackson 2 and Reactor, so drop those to avoid duplicates on the classpath:

       ./mvnw -q package -DskipTests
       rm -f docker/cloud/additional_libs/*.jar
       cp mcp-geoserver/target/libs/*.jar docker/cloud/additional_libs/
       cp mcp-geoserver-cloud/target/gs-mcp-geoserver-cloud-*.jar docker/cloud/additional_libs/
       rm -f docker/cloud/additional_libs/jackson-*.jar \
             docker/cloud/additional_libs/reactor-*.jar \
             docker/cloud/additional_libs/reactive-streams-*.jar

2. Get `geoserver/geoserver-cloud` at the 3.0.2 tag and its `config` submodule (`git clone --branch <3.0.2 tag> --recurse-submodules https://github.com/geoserver/geoserver-cloud.git`).
3. Merge `gateway-route.yml` into `config/gateway.yml` inside `spring.cloud.gateway.server.webmvc.routes`, before the `webui` route.
4. Merge `geoserver-extension.yml` into `config/geoserver.yml`: add `mcp: {enabled: true}` under the existing `geoserver.extension` key.
5. Point the restconfig service at the base image and mount the jars. `restconfig-libs.yml` in this directory does both; it reads `MCP_LIBS`, an absolute path to `docker/cloud/additional_libs`. Optionally mount a fonts dir at `/opt/additional_fonts` for SLD label fonts.
6. Start from inside `compose/` (the compose files mount `$PWD/../config`):

       cd compose
       mkdir -p catalog-datadir
       MCP_LIBS=/abs/path/to/mcp/docker/cloud/additional_libs \
         docker compose -f compose.yml -f catalog-datadir.yml -f /abs/path/to/mcp/docker/cloud/restconfig-libs.yml \
         up -d gateway restconfig wms gwc

   `wms` serves `gs_get_map` and `gs_check_layer`, `gwc` serves the `gs_gwc_*` tools.
7. Enable at runtime and point WMS and GWC calls at the gateway service name:
   `curl -u admin:geoserver -X PUT -H 'Content-Type: application/json' -d '{"enabled":true,"internalBaseUrl":"http://gateway:8080/geoserver/cloud"}' http://localhost:9090/geoserver/cloud/rest/mcp/settings`
8. `./smoke.sh http://localhost:9090/geoserver/cloud admin geoserver`
9. `docker compose -f compose.yml -f catalog-datadir.yml -f /abs/path/to/mcp/docker/cloud/restconfig-libs.yml down -v` when done.

Not yet run end to end: the upstream 3.0.2 `compose`/`config` is only available once 3.0.2 is tagged. The mount mechanism is verified on the SNAPSHOT image; the live smoke is pending the release.

## 3.0.1 (derived image)

GeoServer Cloud 3.0.1 has no drop-in jar directory and publishes no Maven artifacts, so the extension ships as a derived restconfig image, built on `geoservercloud/geoserver-cloud-rest:3.0.1.1`.

1. Build the image, from the repository root:

       ./mvnw -q package -DskipTests
       rm -rf docker/cloud/libs && mkdir -p docker/cloud/libs
       cp mcp-geoserver/target/libs/*.jar docker/cloud/libs/
       cp mcp-geoserver-cloud/target/gs-mcp-geoserver-cloud-*.jar docker/cloud/libs/
       docker build -f docker/cloud/Dockerfile.restconfig -t geoservercloud/geoserver-cloud-rest:3.0.1-mcp docker/cloud

   The build skips jars the base image already ships in the same major version (Jackson 2, Reactor) and prints `skip <jar>` for each.

2. Get `geoserver/geoserver-cloud` at tag `v3.0.1` and its `config` submodule (`git clone --branch v3.0.1 --recurse-submodules https://github.com/geoserver/geoserver-cloud.git`).
3. Merge `gateway-route.yml` into `config/gateway.yml` inside `spring.cloud.gateway.server.webmvc.routes`, before the `webui` route.
4. Merge `geoserver-extension.yml` into `config/geoserver.yml`: add `mcp: {enabled: true}` under the existing `geoserver.extension` key.
5. In `compose/compose.yml` set `restconfig.image` to `geoservercloud/geoserver-cloud-rest:3.0.1-mcp`.
6. Start from inside `compose/`, since the compose files mount `$PWD/../config`. Only the services MCP needs are listed; their dependencies (config, consul, rabbitmq, acl, postgis) come along:

       cd compose
       mkdir -p catalog-datadir
       docker compose -f compose.yml -f catalog-datadir.yml up -d gateway restconfig wms gwc

   `wms` serves `gs_get_map` and `gs_check_layer`, `gwc` serves the `gs_gwc_*` tools.
7. Enable at runtime and point WMS and GWC calls at the gateway service name:
   `curl -u admin:geoserver -X PUT -H 'Content-Type: application/json' -d '{"enabled":true,"internalBaseUrl":"http://gateway:8080/geoserver/cloud"}' http://localhost:9090/geoserver/cloud/rest/mcp/settings`
8. `./smoke.sh http://localhost:9090/geoserver/cloud admin geoserver`
9. `docker compose -f compose.yml -f catalog-datadir.yml down -v` when done.

The admin page is not available on Cloud (restconfig has no web UI). Use REST.
COG stores: the Cloud restconfig image bundles `gs-cog-*`; there is no COG store type, so `gs_create_store` with type `GeoTIFF` and `params.url` = `cog://https://...` works.
