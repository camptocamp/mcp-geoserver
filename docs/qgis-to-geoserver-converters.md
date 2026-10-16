# QGIS to GeoServer: converter options

Three ways to turn a QGIS style into GeoServer SLD. The skill can offer them as a choice (a `CONVERTER` toggle in the export step).

## The three

1. **QGIS native** - `layer.exportSldStyleV3()` (QGIS 3.44+) or `saveSldStyle()`. Built into QGIS, no dependency. Outputs SLD 1.1 (`SvgParameter`). The base the other option builds on.
2. **Ours** - QGIS native export, then `fix-sld-functions.py` (function-name renames, font reconciliation, and detection of the SVG / hatch / size cases). No dependency beyond Python stdlib.
3. **GeoCat Bridge** - the `bridgestyle` library (QGIS to GeoStyler to SLD), bundled in the Bridge QGIS plugin (`geocatbridge.libs.bridgestyle.qgis.layerStyleAsSld`). Outputs SLD 1.0 (`CssParameter`) and returns the icon resources to upload. Bridge is also an end-to-end publisher (uploads SVGs, creates layers), not a converter only.

## Head-to-head (verified)

Run on the `testdata/` fixture against GeoServer 3.0.1 with GeoCat Bridge installed. "Parse" is the upload HTTP status (201 ok, 500 rejected).

| Case | QGIS native | Ours | GeoCat Bridge |
| --- | --- | --- | --- |
| Categorized / polygon | 201 | 201 | 201 |
| Scale / zoom rules | works | works | works |
| Label function `upper()` | `upper` -> 500 | `strToUpperCase` -> 201 | `strToUpper` -> 500 |
| Font (`Ubuntu Sans`/`Sans`) | keeps name | reconciles vs `/rest/fonts`, fallback | keeps name |
| Hatch fill | `horline` -> blank | flags, rewrite `shape://` | `shape://slash` -> renders |
| SVG marker/fill | local href -> square | local href, flagged | returns the icon to upload, bare href |
| Data-defined / expression size | dropped (static) | dropped (re-inject documented) | dropped (warns) |
| Gradient fill | dropped (warns) | dropped | dropped (warns) |
| SLD version / upload type | 1.1, `se+xml` | 1.1, `se+xml` | 1.0, `sld+xml` |
| Publish + upload resources | manual | manual | end to end |

Nobody wins outright:

- **Ours** is the only one that gets function names right (`strToUpperCase`); Bridge's `strToUpper` is just as broken as QGIS's `upper`. Ours is also the only one that reconciles fonts.
- **Bridge** gets the hatch right (`shape://slash`, which native and ours have to patch), and it collects SVG/icon resources and uploads them, which is real value for icon-heavy styles.
- **All three** drop data-defined/expression size and gradient fill: those are QGIS-export limits, not converter-specific.

## Effort

| | Install | Integrate in the skill |
| --- | --- | --- |
| QGIS native | built in | zero (already the base) |
| Ours | built in (one script) | done |
| GeoCat Bridge | the Bridge plugin (installed here) | low: `from geocatbridge.libs.bridgestyle import qgis as bs; sld, icons, warnings = bs.layerStyleAsSld(layer)`, then upload the `icons` and the SLD as `sld+xml` |

A `CONVERTER = "qgis" | "ours" | "bridge"` switch in the export step is ~20-30 lines. Upload and compare steps do not change, except Bridge needs the `sld+xml` content type and uploads its `icons`.

## Recommendation

- Default to **ours**: zero dependency, correct function names and fonts, fixes or flags every gap.
- If Bridge is installed and the style is icon-heavy or uses hatches, **Bridge then our `fix-sld-functions.py` on its output** is the best of both: Bridge's resource upload and `shape://` hatch, plus our function fix. One caveat: our fixer maps `upper`, not Bridge's `strToUpper`, so add `strToUpper -> strToUpperCase` (and the other `strToX` names) to `SAFE_RENAMES` before running it on Bridge output.
- All three still need the data-defined-size re-injection and a gradient workaround (SVG `linearGradient` GraphicFill); those are not converter-specific.
