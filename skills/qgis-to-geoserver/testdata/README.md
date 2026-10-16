# QGIS to GeoServer conversion fixture

Self-contained test data for the QGIS to GeoServer SLD conversion and `fix-sld-functions.py`.

- `fixtures.gpkg`: four layers, EPSG:4326, built with GDAL.
  - `cat_pts`: categorized renderer on `type` plus a simple label on `nom`.
  - `ddl_pts`: single symbol with data-defined size (`sz`) and rotation (`ang`).
  - `label_pts`: font marker plus an expression label `upper("nom")` with auto-wrap and a text buffer.
  - `zones`: polygon outline plus a label on `name`.
- `style_tests.qgz`: QGIS project over `fixtures.gpkg` carrying those styles.
- `sld/*.sld`: the SLD each layer exports (what QGIS produces, before any fix).

Known result on GeoServer 3.0.1: `cat_pts`, `ddl_pts`, `zones` upload clean (HTTP 201); `label_pts` fails (HTTP 500, `Unable to find function upper`) until `fix-sld-functions.py` renames `upper` to `strToUpperCase`, then 201. Regenerate `sld/` by re-exporting from the project in QGIS.

Caveat: GeoPackage has no array column type, so the array-label trap (a Postgres `text[]` labelled raw renders as `[Ljava.lang.String@...]`) is PostGIS-only and is not reproducible from this fixture; see SKILL.md.
