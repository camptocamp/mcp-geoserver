# QGIS to GeoServer: converter options

Three ways to turn a QGIS style into GeoServer SLD. The skill can offer them as a choice (a `CONVERTER` toggle in the export step).

## The three

1. **QGIS native** - `layer.exportSldStyleV3()` (QGIS 3.44+) or `saveSldStyle()`. Built into QGIS, no dependency. This is the base the other option builds on.
2. **Ours** - QGIS native export, then `fix-sld-functions.py` as a post-processor (function-name renames, font reconciliation, and detection of the SVG / hatch / size cases). Built, no dependency beyond Python stdlib.
3. **GeoCat Bridge** - the `bridgestyle` library (QGIS symbology to GeoStyler to SLD), usually via the Bridge QGIS plugin. Not a converter only: Bridge publishes to GeoServer end to end, uploading SVG and icon resources for you. Not installed here (`bridgestyle` import fails, no Bridge plugin), so it needs `pip install bridgestyle` or the plugin first.

## Effort to adopt

| | Install | Integrate in the skill |
| --- | --- | --- |
| QGIS native | built in | zero (already the base) |
| Ours | built in (one script) | done |
| GeoCat Bridge | not installed: add `bridgestyle` or the plugin | low once installed: call `bridgestyle.qgis.layerStyleAsSld(layer)` from `execute_code`, ~a dozen lines |

Adding a `CONVERTER = "qgis" | "ours" | "bridge"` switch to the export step is ~20-30 lines. The upload and compare steps do not change; they just consume whatever SLD comes out.

## What each handles

QGIS native and ours share the same exporter, so they hit the same conversion gaps (documented and verified in `qgis-vs-geoserver-styling.md`); ours fixes the auto-fixable ones. The Bridge column is NOT verified here (not installed); it uses a different converter, so it must be tested head to head before trusting any cell.

| Case | QGIS native | Ours | GeoCat Bridge |
| --- | --- | --- | --- |
| Function names (`upper` ...) | breaks (HTTP 500) | renames safe, flags unsafe | own mapping, needs test |
| Fonts (`Sans`) | Unknown font | reconcile vs `/rest/fonts`, fallback | needs test |
| SVG marker/fill | local href, square | flags, manual upload | Bridge uploads the resource (its strength) |
| Hatch fill | blank (`horline`) | flags, rewrite `shape://...` | needs test |
| Data-defined / expression size, color | dropped or flattened | same (QGIS limit), re-inject documented | GeoStyler may differ, needs test |
| Gradient fill | dropped (QGIS cannot export) | same | likely same (no SLD gradient) |
| Scale / zoom rules | works | works | works |
| Publish + resources to GeoServer | manual (`upload-styles.sh`) | manual | end to end |

## Trade-offs

- **QGIS native**: nothing to install, but you ship the broken SLD (unknown functions, local SVG hrefs, blank hatch).
- **Ours**: no dependency, fixes the known gaps or flags them, works today. The gaps it cannot fix (dropped data-defined/gradient) are QGIS-export limitations that Bridge may or may not share.
- **GeoCat Bridge**: a different converter plus automatic resource upload and publishing, which is real value for SVG-heavy styles; the cost is an extra dependency version-coupled to QGIS, and its conversion quality on our gap list is unverified.

## Recommendation

Keep **ours** as the default: zero dependency, fixes or flags every gap we tested, already wired into the skill. Offer **bridge** as an opt-in converter for people who already run GeoCat Bridge and want its resource upload and GeoStyler conversion, gated on it being installed. Only a real head-to-head (install `bridgestyle`, run the fixture in `testdata/` through all three, compare renders) can fill the unverified cells; that is the next step if we want the comparison to be more than an estimate.
