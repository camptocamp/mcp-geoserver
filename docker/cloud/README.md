# GeoServer Cloud 3.0.1

GeoServer Cloud has no drop-in jar directory and publishes no Maven artifacts, so the extension ships as a derived restconfig image.

1. Build the image, from the repository root:

       ./mvnw -q package -DskipTests
       mkdir -p docker/cloud/libs && rm -f docker/cloud/libs/*.jar
       cp mcp-geoserver/target/libs/*.jar docker/cloud/libs/
       cp mcp-geoserver-cloud/target/gs-mcp-geoserver-cloud-*.jar docker/cloud/libs/
       docker build -f docker/cloud/Dockerfile.restconfig -t geoservercloud/geoserver-cloud-rest:3.0.1-mcp docker/cloud

2. Clone `geoserver/geoserver-cloud` at tag `v3.0.1`, then its `config` submodule.
3. Merge `gateway-route.yml` into `config/gateway.yml` inside `spring.cloud.gateway.server.webmvc.routes`, before the `webui` route.
4. Merge `geoserver-extension.yml` into `config/geoserver.yml`.
5. In `compose/compose.yml` set `restconfig.image` to `geoservercloud/geoserver-cloud-rest:3.0.1-mcp`.
6. `docker compose -f compose/compose.yml -f compose/catalog-datadir.yml up -d`
7. Enable at runtime and point WMS calls at the gateway service name:
   `curl -u admin:geoserver -X PUT -H 'Content-Type: application/json' -d '{"enabled":true,"internalBaseUrl":"http://gateway:8080/geoserver/cloud"}' http://localhost:9090/geoserver/cloud/rest/mcp/settings`
8. `./smoke.sh http://localhost:9090/geoserver/cloud admin geoserver`

The admin page is not available on Cloud (restconfig has no web UI). Use REST.
COG stores: the Cloud restconfig image bundles `gs-cog-*`; there is no COG store type, so `gs_create_store` with type `GeoTIFF` and `params.url` = `cog://https://...` works.
