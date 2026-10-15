---
name: qgis-to-geoserver
description: Use when moving layer styles from a QGIS project to GeoServer, or when checking that GeoServer layers render like they do in QGIS. Needs the GeoServer MCP (gs_* tools) and the QGIS MCP (execute_code) connected. Trigger on "QGIS style to GeoServer", "export SLD from QGIS", "compare QGIS and GeoServer rendering".
---

# QGIS styles to GeoServer

QGIS converts the styles; a script uploads them; GeoServer validates and renders. Write no converter code. SLD bodies never enter the conversation: do not `Read` them, do not `cat` them, do not pass them to `gs_put_style`. Only names, counts, warnings and one-line errors.

## 1. Before loading a project: credentials

Never print a project's contents. Count credential markers only:

```bash
python3 - "$PROJECT" <<'EOF'
import re, sys, zipfile
p = sys.argv[1]
if p.endswith(".qgz"):
    z = zipfile.ZipFile(p)
    text = z.read(next(n for n in z.namelist() if n.endswith(".qgs"))).decode("utf-8", "replace")
else:
    text = open(p, encoding="utf-8", errors="replace").read()
for marker in ("password=", "authcfg=", "service="):
    n = len(re.findall(marker, text))
    if n:
        print(f"{marker} {n}")
EOF
```

Any output: stop. Show the user, in bold, `WARNING: this project contains credentials` followed by the markers and counts, and ask `Load it anyway? (y/n)`. Call `load_project` only after an explicit `y`. No output: load it.

## 2. Export every style with one `execute_code` call

File basename = GeoServer layer name. Fill `NAMES` with `{qgis layer name: geoserver layer name}` from `gs_list_layers`; leave it empty to export every vector layer under its QGIS name.

```python
import os
from qgis.core import Qgis, QgsProject, QgsMapLayer, QgsSldExportContext
OUT = "/tmp/sld"
NAMES = {}
os.makedirs(OUT, exist_ok=True)
layers = QgsProject.instance().mapLayers().values()
todo = {l.name(): NAMES.get(l.name(), l.name()) for l in layers
        if l.type() == QgsMapLayer.VectorLayer and (not NAMES or l.name() in NAMES)}
for qname, gname in todo.items():
    layer = QgsProject.instance().mapLayersByName(qname)[0]
    path = os.path.join(OUT, gname + ".sld")
    if Qgis.QGIS_VERSION_INT >= 34400:
        ctx = QgsSldExportContext()
        body = layer.exportSldStyleV3(ctx).toString()
        open(path, "w").write(body)
        issues = [str(e) for e in ctx.errors()] + [str(w) for w in ctx.warnings()]
    else:
        ok, msg = layer.saveSldStyle(path)
        issues = [] if ok else [msg]
    print(gname, len(open(path).read()), "; ".join(issues) or "ok")
```

Report every non-`ok` line to the user: those are parts QGIS could not export or changed. Data-defined properties and shapeburst fills are dropped without a message; name them if the user asks what was lost.

## 3. Upload with the script

`$GS_URL` is the GeoServer base URL without `/mcp` (for example `http://localhost:8080/geoserver`), `$GS_AUTH` is `user:password`. `-d` sets each style as the default style of layer `WS:<basename>`.

```bash
GS_URL=... GS_AUTH=... bash skills/qgis-to-geoserver/upload-styles.sh -d /tmp/sld WS
```

One line per style: `name POST|PUT status [first error line] [default status]`. A 2xx means GeoServer parsed and stored the style. Nothing else is needed for the styles that passed.

## 4. Fix only what failed, without reading the file

Locate the element named in the error line with `grep -n` on that one file, never the whole body. Known cases:

- `OnlineResource` href pointing at a QGIS path (`/usr/share/qgis/svg/...`, `gpsicons/...`, a path with spaces): GeoServer rejects the URI or silently draws a square. Resolve the href (without its `?` query) with `execute_code`:

  ```python
  import os
  from qgis.core import QgsProject, QgsSymbolLayerUtils
  path = QgsSymbolLayerUtils.svgSymbolNameToPath("HREF", QgsProject.instance().pathResolver())
  print(path, bool(path) and os.path.exists(path))
  ```

  If the file does not exist, QGIS itself draws a fallback marker: ask the user to pick a real SVG in QGIS, re-export, re-upload. Otherwise upload the SVG next to the style and rewrite the href to the bare file name plus any `?fill=...` query (verified on GeoServer 3.0.1: `param(fill)` in the SVG is honoured):
  `curl -u "$GS_AUTH" -X PUT -H 'Content-Type: image/svg+xml' --data-binary @"$SVG_PATH" "$GS_URL/rest/resource/workspaces/WS/styles/NAME.svg"`
  then `sed -i 's|xlink:href="OLD|xlink:href="NAME.svg|' /tmp/sld/LAYER.sld` and rerun the script on a directory holding only the fixed files.
- `Unable to find function X`: a QGIS expression function with no GeoServer equivalent (`array_to_string`, ...). Simplify the rule in QGIS and re-export; do not hand-write SLD.
- A `PropertyName` that is not a column of the GeoServer layer (QGIS join field, virtual field): GeoServer stores the style but every GetMap fails; `gs_check_layer` reports it in step 5. Either drop the rule in QGIS or publish a SQL view that carries the field, then restyle.

## 5. Check each layer with text, one layer per call

After upload, call `gs_check_layer(name)` for each layer: it does the GetMap and returns status, any ServiceException text and the share of painted pixels. Fix and recheck only the layers it flags. Do not call `gs_get_map` and do not `Read` PNG files for this: a picture costs image tokens and says less than the text check.

## 6. Image comparison, only on explicit user request

Only when the user asks to compare renders, and one layer at a time: rendering all layers in one QGIS job has crashed QGIS.

QGIS render, with `execute_code`:

```python
from qgis.core import QgsProject, QgsMapSettings, QgsMapRendererSequentialJob, QgsRectangle
from qgis.PyQt.QtCore import QSize
from qgis.PyQt.QtGui import QColor
W, H = 800, 500
layer = QgsProject.instance().mapLayersByName("LAYER")[0]
e = layer.extent()
w = max(e.width(), e.height() * W / H)
h = w * H / W
c = e.center()
ms = QgsMapSettings()
ms.setLayers([layer])
ms.setDestinationCrs(layer.crs())
ms.setExtent(QgsRectangle(c.x() - w / 2, c.y() - h / 2, c.x() + w / 2, c.y() + h / 2))
ms.setOutputSize(QSize(W, H))
ms.setOutputDpi(90.7)
ms.setBackgroundColor(QColor("white"))
job = QgsMapRendererSequentialJob(ms)
job.start()
job.waitForFinished()
job.renderedImage().save("/tmp/qgis_LAYER.png")
v = ms.visibleExtent()
print(layer.crs().authid(), v.xMinimum(), v.yMinimum(), v.xMaximum(), v.yMaximum())
```

GeoServer render, fetched to disk rather than into the conversation:
`curl -s -u "$GS_AUTH" -o /tmp/gs_LAYER.png "$GS_URL/wms?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=WS:LAYER&STYLES=&SRS=EPSG:XXXX&BBOX=minx,miny,maxx,maxy&WIDTH=800&HEIGHT=500&FORMAT=image/png"`
`compare -metric AE -fuzz 10% /tmp/qgis_LAYER.png /tmp/gs_LAYER.png /tmp/diff_LAYER.png`
Report the number. Matching styles gave 0 to 3% of pixels in the reference spike; a broken marker gave 15%. Look at the images only if the user asks to see them.

## Do not

- Read, cat or print an SLD body; grep for one element when an error names it.
- Send a style through `gs_put_style` when the script can upload it; `gs_put_style` is for a single small style written by hand.
- Render in QGIS by modifying the user's layers or their renderer symbols; render read-only as above, one layer per job.
- Run a network request inside `execute_code`: it blocks the QGIS window.
- Print connection strings, passwords or `authcfg` contents.
