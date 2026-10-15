# Plugin coverage

Nothing here is hardcoded. The live answer for a server is `gs_list_store_types` and `gs_list_modules`. Plugin jars must be installed globally: `WEB-INF/lib` on vanilla, the derived image on Cloud. The Testcontainers IT exercised only a GeoTIFF store, `gs_list_store_types`, the `gs_*` publish, style and map flow, and a GWC tile layer update.

## Rules

- Any GeoTools `DataAccessFactory` or `GridFormat` on the classpath appears in `gs_list_store_types` and works with `gs_create_store` and `gs_publish_layer`. Fields the factory marks as passwords are masked in every tool output. This covers the JDBC extensions (DB2, MySQL, Oracle, SQL Server), MongoDB, Pregeneralized Features, the raster extensions (ArcGrid, GDAL, GRIB, Image Pyramid, JPEG2K, NetCDF, WorldImage) and the community datastores (flatgeobuf, geoparquet, duckdb, ogr-datastore, mbtiles-store, pmtiles-store, vector-mosaic, stac-datastore, elasticsearch, solr, singlestore, mongodb-schemaless, graticule, smart-data-loader, s3-geotiff, vsi).
- Any GeoServer `StyleHandler` works with `gs_put_style(format=...)`: `sld` in core, `css`, `ysld`, `mbstyle` with their extension.
- Any WMS GetMap output format works with `gs_get_map(format=...)`; Vector Tiles formats show up in `gs_gwc_set_tile_layer(formats)`. `gs_query_features` is GeoJSON only, so WFS output formats (DXF, Excel, GeoPkg, OGR, XSLT, GPX) have no tool surface.
- Authentication extensions (Key authentication, CAS, OIDC, jwt-headers, keycloak-roles) apply to `/mcp` through the default filter chain; the role gate is unchanged.
- Everything else is at most visible in `gs_list_modules` and `gs_status` (Control Flow, INSPIRE, Metadata, Monitor, Importer, Printing, CSW, WPS, OGC API modules, jdbcconfig, jdbcstore, pgconfig).

## Exceptions

- COG: there is no COG store type. Use type `GeoTIFF` or `ImageMosaic` with `url` = `cog://https://...`. The reader is in core; the cog-http, cog-s3, cog-google and cog-azure modules add the range readers.
- App Schema: listed as a store type, but `url` must point to a mapping file already on the server.
- WMS and WMTS cascading stores: readable with `gs_get_store`, not creatable in v1. WFS cascading (Web Feature Server NG) is a regular store type.
- IAU and Raster Attribute Table add no store. IAU adds `IAU:xxxx` codes for the `srs` arguments; RAT adds REST and UI only.
- Cross Layer Filtering functions (`querySingle`, `queryCollection`) work in the `cql` argument of `gs_query_features`.
- GWC blobstores (S3, Azure, GCS, MBTiles) are a stated non-goal; seeding through `gs_gwc_seed` is unaffected.

## Core 3.0.1

Vector: PostGIS, Shapefile, GeoPackage, Properties, WFS NG. Raster: GeoTIFF (with COG reader), ImageMosaic, GeoPackage. GWC REST for all `gs_gwc_*` tools.
