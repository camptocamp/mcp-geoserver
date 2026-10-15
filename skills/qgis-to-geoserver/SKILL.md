---
name: qgis-to-geoserver
description: Use when moving a layer style from a QGIS project to GeoServer, or when checking that a GeoServer layer renders like it does in QGIS. Needs the GeoServer MCP (gs_* tools) and the QGIS MCP (execute_code) connected. Trigger on "QGIS style to GeoServer", "export SLD from QGIS", "compare QGIS and GeoServer rendering".
---

# QGIS style to GeoServer

QGIS converts the style; GeoServer stores and renders it; you compare the two renders. Write no converter code.

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

## 2. Export the style from QGIS

With `execute_code`, for each layer to move:

```python
from qgis.core import Qgis, QgsProject, QgsSldExportContext
layer = QgsProject.instance().mapLayersByName("LAYER")[0]
if Qgis.QGIS_VERSION_INT >= 34400:
    ctx = QgsSldExportContext()
    body = layer.exportSldStyleV3(ctx).toString()
    print(ctx.errors(), ctx.warnings())
else:
    layer.saveSldStyle("/tmp/LAYER.sld")
    body = open("/tmp/LAYER.sld").read()
open("/tmp/LAYER.sld", "w").write(body)
```

Report every entry of `ctx.errors()` and `ctx.warnings()` to the user: these are parts QGIS could not export or changed.

## 3. Fix what QGIS writes and GeoServer cannot use

Read `/tmp/LAYER.sld` and check:

- `OnlineResource xlink:href` pointing at an SVG by QGIS path (`/usr/share/qgis/svg/...`, `gpsicons/...`): GeoServer silently draws a square instead. First resolve each href (without its `?` query) to a file and check it exists, with `execute_code`:

  ```python
  import os
  from qgis.core import QgsProject, QgsSymbolLayerUtils
  path = QgsSymbolLayerUtils.svgSymbolNameToPath("HREF", QgsProject.instance().pathResolver())
  print(path, bool(path) and os.path.exists(path))
  ```

  If the file does not exist, QGIS itself is drawing a fallback marker: stop and ask the user to pick a real SVG in QGIS. Otherwise upload that file next to the style, named after the SVG's file name, and set the href to the bare file name plus any `?fill=...` query (verified on GeoServer 3.0.1: `param(fill)` in the SVG is honoured):
  `$GS_URL` is the GeoServer base URL without `/mcp` (for example `http://localhost:8080/geoserver`) and `$GS_AUTH` is `user:password`:
  `curl -u "$GS_AUTH" -X PUT -H 'Content-Type: image/svg+xml' --data-binary @"$SVG_PATH" "$GS_URL/rest/resource/styles/NAME.svg"`
  For a workspace style, use `/rest/resource/workspaces/WS/styles/NAME.svg`.
- Every `ogc:PropertyName` must exist on the GeoServer layer (`gs_get_layer`). QGIS ignores unknown fields; GeoServer rejects the whole GetMap.
- Data-defined properties and shapeburst fills are dropped without a message. Tell the user which ones the QGIS style used.

## 4. Push

`gs_put_style(name, workspace, body)`, then `gs_update_layer(name, defaultStyle)`. For a workspace style, `defaultStyle` must be `workspace:name`. GeoServer layer names may differ from QGIS layer names: look the layer up with `gs_list_layers` first. On an `Invalid sld` error, fix the named element and push again.

## 5. Compare, always

GeoServer validation does not catch broken SVG references, so compare after every push.

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

GeoServer render: `gs_get_map` with the printed CRS and bbox, width 800, height 500. Look at both images side by side and name every difference you see. Expect small label placement and antialiasing differences; anything else is a real difference.

For a number, fetch the GetMap PNG with the same bbox, SRS and size, then run the comparison:
`curl -s -u "$GS_AUTH" -o /tmp/gs_LAYER.png "$GS_URL/wms?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=WS:LAYER&STYLES=&SRS=EPSG:XXXX&BBOX=minx,miny,maxx,maxy&WIDTH=800&HEIGHT=500&FORMAT=image/png"`
`compare -metric AE -fuzz 10% /tmp/qgis_LAYER.png /tmp/gs_LAYER.png /tmp/diff_LAYER.png`
In the reference spike, matching styles gave 0 to 3% of pixels; a broken marker gave 15%.

## Do not

- Render in QGIS by modifying the user's layers or their renderer symbols; render read-only as above.
- Run a network request inside `execute_code`: it blocks the QGIS window.
- Print connection strings, passwords or `authcfg` contents.
