# GeoServer Cloud 3.0.1

GeoServer Cloud has no drop-in jar directory and publishes no Maven artifacts, so the extension ships as a derived restconfig image, built on `geoservercloud/geoserver-cloud-rest:3.0.1.1`.

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
