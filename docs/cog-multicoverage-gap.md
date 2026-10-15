# COG multi-coverage and glob gap audit

Scope: does our MCP extension cover the GeoServer-core store-config browse dialog for object storage (S3, GCS, Azure) that supports multiple coverages per store and glob selection of a folder or file set. COG / remote raster stores.

## Today

What works through the MCP tools:

- `gs_create_store(workspace, name, type, params)` builds a `CoverageStoreInfo` for any raster format installed in GeoTools. `type` comes from `gs_list_store_types` (raster), matched at runtime. Raster `params` is a single `{"url": ...}`. `StoreTools.java:242-265`, `StoreTools.java:164-168`.
- Remote COG: when `type` is `GeoTIFF` and the url scheme is `s3://`, `gs://`, `http://`, `https://` (or already `cog://`), the store is rewritten to `cog://...`, a `CogSettingsStore` range reader is configured (S3, GS or HTTP), and the matching `cog-s3` / `cog-google` / `cog-http` community module is required. S3 credentials come from the server. `RemoteCog.java:17-57`, `StoreTools.java:251-257`.
- `gs_create_store` does not publish any layer; it only creates the store. `StoreTools.java:242-265`.
- Publishing is a separate step, `gs_publish_layer(workspace, store, nativeName?, ...)`. For a coverage store it calls `builder.buildCoverage(nativeName)` when `nativeName` is given, else `builder.buildCoverage()` (the default coverage). So a store that exposes several coverages can have more than one published by repeated calls with distinct `nativeName`. `LayerTools.java:107`, `LayerTools.java:89`.

Net: one store is not hard-wired to exactly one coverage; the model allows several, and `nativeName` targets a specific one. But everything assumes a single explicit file url and the caller already knowing each coverage name.

## Gaps

- No glob or folder selection. Raster `params` is a single `url` string; `createRaster` reads only `params.get("url")` and rejects a store with no url. There is no `glob`, `wildcard`, `filter` or directory-set argument. `StoreTools.java:242-244`, schema at `StoreTools.java:168`.
- Remote-storage wiring is single-file GeoTIFF only. `RemoteCog.applies` returns true only for format `GeoTIFF` with a scheme-prefixed single url; there is no ImageMosaic / mosaic-of-remote-COG path, so a glob over `s3://bucket/path/*.tif` cannot be turned into a mosaic store with COG range readers. `RemoteCog.java:17-21`.
- No discovery of the coverages a store exposes. `gs_get_store` returns the `available` (unpublished) list only for vector stores; it returns early for a `CoverageStoreInfo`, so the names needed to publish a second or third coverage are not listed anywhere. `StoreTools.java:95`.
- No multi-coverage publish. `gs_publish_layer` publishes one coverage per call; `gs_publish_layers` is vector-only and throws on a coverage store. Publishing N coverages means N manual `gs_publish_layer` calls with names the tools do not surface (see above). `LayerTools.java:107`, `LayerTools.java:144`.
- `gs_create_store` has no `coverages[]` argument: a store cannot be created with its coverages described or selected in one call. Schema is `workspace, name, type, params` only. `StoreTools.java:164-168`.

Note: ImageMosaic itself is not missing from the raster type list. `gs_list_store_types` raster is the runtime `GridFormatFinder` set, so ImageMosaic appears when its plugin is installed and `gs_create_store` would accept its directory url. The gaps are the glob input, the remote-COG mosaic wiring, and coverage discovery / bulk publish, not the type.

## Smallest viable MCP additions

- `gs_get_store`: list available (unpublished) coverages for a coverage store, parity with the vector `available` list.
- `gs_create_store` raster: accept a `glob` (or directory) url and wire remote-COG detection for mosaic stores.
- `gs_publish_coverages`: bulk coverage publish mirroring `gs_publish_layers`.
