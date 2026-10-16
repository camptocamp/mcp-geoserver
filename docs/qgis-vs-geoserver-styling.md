# QGIS styling vs GeoServer SLD

What survives the QGIS to GeoServer SLD conversion, what degrades, and the fix. Every row below was checked by rendering the SAME layer in QGIS and in GeoServer and comparing the images, not just by parsing the SLD. Verified on GeoServer 3.0.1 (GeoTools 35.1) and QGIS 4.2.3 (`exportSldStyleV3`) with the fixture in `skills/qgis-to-geoserver/testdata/` (`fixtures.gpkg` published to GeoServer, SLDs from `style_tests.qgz`). "Parse" is the HTTP status when the SLD is uploaded as a style (201 parsed, 500 rejected); "render" is whether the GetMap image matches QGIS.

## Summary

The symbology path mostly holds; the breakage is in labels, expressions, data-defined size, and anything that depends on a resource (font, SVG) the GeoServer container does not have.

| QGIS technique | Parse | Render vs QGIS | Status | Fix |
| --- | --- | --- | --- | --- |
| Single symbol, categorized renderer | 201 | matches (minor marker-size and label-offset diffs) | works | - |
| Rule-based renderer with filters | 201 | matches | works | - |
| Polygon fill and outline + label | 201 | matches | works | - |
| Simple field label | 201 | matches | works | - |
| Data-defined rotation | 201 | matches (orientations vary) | works | - |
| Data-defined size | 201 | does NOT match: QGIS varies size, GeoServer renders them uniform | degrades | set the size unit explicitly in QGIS; verify, do not assume |
| Expression label with a string function | 500 | n/a | breaks | `fix-sld-functions.py` (rename or rework) |
| Font marker | 201 | render fails: `Unknown font <name>` when the font is not in the container | breaks at render | install the font in GeoServer (Cloud 3.0.2: `/opt/additional_fonts`) |
| Label on Postgres `text[]`, one value | 201 | matches | works | - |
| Label on Postgres `text[]`, many values | 201 | `[Ljava.lang.String@...]` | breaks (visual) | join to a scalar before labelling |
| SVG marker from a local QGIS path | varies | href GeoServer cannot resolve, draws a square | breaks | upload the SVG, rewrite href to the bare name |
| Blend modes, layer opacity | - | no SLD equivalent, dropped | not portable | - |

Two of these only show up when you render, never when you parse: data-defined size and the font marker both upload as 201 and then render wrong or fail. Parse-only validation (`gs_check_layer` on an empty style, or an upload 201) is not enough; render and compare.

## Complex examples

### Expression label: function names do not match

QGIS exports the expression tree with its own function names; GeoTools uses different ones, and one unknown function makes GeoServer reject the whole style.

QGIS `upper("nom")` exports `<ogc:Function name="upper">...`, which GeoServer rejects: `HTTP 500, Unable to find function upper`. After `fix-sld-functions.py` rewrites it to `strToUpperCase`: `HTTP 201`.

Safe 1:1 renames the script applies: `upper`, `lower`, `trim`, `length`, `concat`, `title`. Functions whose GeoTools form differs in arguments or arity, which the script refuses so they get reworked in QGIS: `substr`, `replace`, `coalesce`, `round`, `strpos`, `left`, `right`, `to_string`, `format_number`.

### Data-defined size renders uniform

`ddl_pts` carries a triangle with data-defined size (`sz` = 4, 8, 12) and rotation (`ang` = 0, 45, 90). In QGIS the three triangles are clearly small, medium, large and rotated. GeoServer reproduces the rotation but renders all three the same small size: the data-defined size did not carry across at the same scale. Check the symbol-size unit in QGIS and render both sides before trusting it.

### Font marker needs the font in GeoServer

`label_pts` uses a font marker. The SLD parses (201), but the GetMap fails with `Rendering process failed ... Unknown font Sans`: GeoServer renders fonts from the fonts installed in its own JVM/container, not from the author's machine. Install the font in the GeoServer image, or on GeoServer Cloud 3.0.2 mount it at `/opt/additional_fonts`.

### Array column label

A Postgres array column labels cleanly only when a feature holds one value; multiple values render as `[Ljava.lang.String@...]`. GeoServer has no `array_to_string`, `item()` or `col[1]` in SLD or CQL. Fix in the data: a generated column `array_to_string(col, ', ')` (needs an IMMUTABLE wrapper, the stock function is only STABLE) or a SQL view, then label that scalar. (GeoPackage has no array type, so this one is PostGIS only and not in the fixture.)

## Reproduce

Publish `fixtures.gpkg` to a scratch workspace, upload the SLDs (run `fix-sld-functions.py` first), then GetMap each layer and compare against a QGIS render of the same layer and extent. See `skills/qgis-to-geoserver/SKILL.md` for the full workflow and the one-layer-per-job rendering rule.
