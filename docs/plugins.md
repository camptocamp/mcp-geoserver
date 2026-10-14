# Plugin coverage

Nothing in this file is hardcoded in the extension. The live answer for a given server is `gs_list_store_types` (store types the installed GeoTools plugins provide) and `gs_list_modules` (installed GeoServer modules). This file records which GeoServer 3.0.x extensions and community modules the `gs_` tools can drive once the plugin jars are installed globally (`WEB-INF/lib` on vanilla, the derived image on Cloud), and through which tool. Names follow the 3.0.1 release page and the 3.0.x community build.

How to read the `via` column:

- `store`: appears in `gs_list_store_types`, created with `gs_create_store`, published with `gs_publish_layer`
- `style`: consumed by `gs_put_style`
- `gwc`: reachable through `gs_gwc_*`
- `auth`: changes how a client authenticates to `/mcp`; the role gate is unchanged
- `module`: only visible in `gs_list_modules` and `gs_status`, no dedicated tool
- `n/a`: no tool surface in v1

Status: `verified` means exercised by the Testcontainers flow or the Cloud smoke test; `expected` means it goes through a generic path (GeoTools factory discovery, GWC REST) and has not been run yet.

## Core, no plugin needed

| Plugin | via | Status | Notes |
|---|---|---|---|
| PostGIS | store | expected | `dbtype=postgis`, passwords masked in tool output |
| Shapefile, Directory of shapefiles | store | expected | `url` param |
| GeoPackage (vector) | store | expected | `database` param |
| Properties | store | expected | used by the unit tests |
| GeoTIFF | store | expected | Testcontainers flow publishes a generated GeoTIFF |
| ImageMosaic | store | expected | `url` is the mosaic directory |
| WMS, WMTS cascading stores | n/a | - | read by `gs_get_store`, not creatable in v1 |
| GeoWebCache | gwc | expected | all `gs_gwc_*` tools over the GWC REST API |

## Extensions (3.0.1 release page)

| Plugin | via | Status | Notes |
|---|---|---|---|
| App Schema | n/a | - | needs mapping files, not a `gs_create_store` case |
| DB2, MySQL, Oracle, SQL Server | store | expected | JDBC params from `gs_list_store_types` |
| MongoDB | store | expected | |
| Pregeneralized Features | store | expected | |
| CSS Styling, YSLD Styling, MBStyle Styling | style | n/a in v1 | `gs_put_style` takes SLD only; see follow-up below |
| Chart Symbolizer | style | expected | plain SLD with chart URLs, nothing to add |
| Printing | n/a | - | |
| Control Flow, INSPIRE, Metadata, Monitor, Request parameters extractor, Resource Browser Tool | module | - | |
| Cross Layer Filtering | module | - | `gs_query_features` runs in-process, WMS-only functions do not apply |
| GWC S3 tile storage | gwc | n/a in v1 | blobstore configuration is a stated non-goal |
| IAU | store | expected | planetary SRS codes accepted by the `srs` argument |
| Importer | n/a | - | importer REST not wrapped |
| Key authentication, CAS, OIDC | auth | expected | `/mcp` sits in the default filter chain; a client must send what that chain accepts |
| ArcGrid, GDAL, GRIB, Image Pyramid, JPEG2K, NetCDF, Raster Attribute Table, WorldImage | store | expected | raster, `params.url` |
| Vector Tiles | gwc | expected | formats appear in `gs_gwc_set_tile_layer(formats)` |
| DXF, Excel, GeoPkg output, Image Map, JPEG Turbo, MapML, NetCDF output, OGR, XSLT | n/a | - | `gs_get_map` is PNG, `gs_query_features` is GeoJSON |
| OGC API Features, CSW, WCS, WPS, WMTS multi-dimensional, SLDService | module | - | listed by `gs_status` as enabled services |

## Community modules (3.0.x build)

| Plugin | via | Status | Notes |
|---|---|---|---|
| cog-http, cog-s3, cog-google, cog-azure | store | expected | `COG` type, `params.url`; vanilla needs the zip in `WEB-INF/lib` |
| s3-geotiff, vsi | store | expected | |
| flatgeobuf, geoparquet, duckdb, geopkg, gpx, ogr-datastore, mbtiles-store, pmtiles-store, vector-mosaic, stac-datastore, elasticsearch, solr, singlestore, mongodb-schemaless | store | expected | any `DataAccessFactory` on the classpath is listed |
| gwc-mbtiles, gwc-azure-blobstore, gwc-gcs-blobstore | gwc | n/a in v1 | blobstores not configurable, seeding works |
| jdbcconfig, jdbcstore, pgconfig (Cloud) | module | expected | settings live in `GeoServerInfo` metadata, so they persist in any backend |
| ogcapi-* | module | - | |
| everything else | n/a | - | |

## Follow-up

`gs_put_style` is the one tool with a hardcoded format. Replacing `new SLDHandler()` with `Styles.handler(format)` and an optional `format` argument (default `sld`) makes CSS, YSLD and MBStyle work whenever their jar is present, with no per-format code.
