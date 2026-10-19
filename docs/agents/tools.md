# Tool catalogue

Every tool also accepts `verbose: boolean` (announced in the server instructions, not in each schema) and declares MCP annotations (read-only, destructive, idempotent) from `ToolHints`.

How tools reach GeoServer:

- Over HTTP (`GeoServerHttpClient`, caller's `Authorization` forwarded): `gs_get_map`, `gs_check_layer`, `gs_gwc_*`.
- Everything else: in-process `Catalog` / `GeoServer` API, no HTTP call to GeoServer; the `curl` trace is only the REST equivalent.

Results omit null fields. List rows also omit `enabled` / `available` when true, and `workspace` when the list is filtered by workspace.

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
- `gs_list_store_types(kind?, type?)`: runtime list from GeoTools, names with required parameter keys; `type` returns every parameter of that one type
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
- `gs_generate_style(layer, attribute, method, classes?, ramp?, name?, workspace?, setDefault?)`: method categorized (up to 50 values) or equalInterval, quantile, jenks (numeric, 2 to 20 classes); stored through gs_put_style; returns rules, plus the SLD in verbose mode
- `gs_delete(kind, workspace?, name, recurse?)`
- `gs_reload()`

Data
- `gs_query_features(layer, cql?, bbox[]?, properties[]?, maxFeatures?)` returns GeoJSON in EPSG:4326, default 20 features, stops at 40,000 chars; `returned` count and `truncated: true` when cut
- `gs_get_map(layers[], bbox[], width, height, srs?, styles[]?, format?, transparent?)` returns an image

GeoWebCache (via GWC REST over HTTP)
- `gs_gwc_list_tile_layers()`, `gs_gwc_get_tile_layer(layer)`, `gs_gwc_set_tile_layer(layer, enabled?, gridsets[]?, formats[]?)`
- `gs_gwc_list_gridsets()`
- `gs_gwc_seed(layer, gridset, zoomStart, zoomStop, format, operation?, threads?, bbox[]?)`
- `gs_gwc_list_tasks(layer?)`, `gs_gwc_kill_tasks(layer?, which?)`
