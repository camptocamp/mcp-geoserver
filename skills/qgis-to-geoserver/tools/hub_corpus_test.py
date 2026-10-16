"""Convert a QGIS Hub symbol corpus to SLD three ways, for scoring on GeoServer.

Run INSIDE QGIS (needs qgis.core and the GeoCat Bridge plugin). For every symbol in
every library it applies the symbol to a fixture layer of the matching geometry and
exports the style three ways: native (`exportSldStyleV3`), bridge (`bridgestyle`),
and ours (bridge + `fix-sld-functions.py`). It writes the SLDs and a manifest.json;
score them with hub_corpus_score.py on the host (QGIS here cannot reach GeoServer).

    from hub_corpus_test import convert
    convert("/tmp/hub_corpus", "/tmp/hub_sld", "<testdata>/fixtures.gpkg",
            "<skill>/fix-sld-functions.py", available_fonts="DejaVu Sans,SansSerif,...")

QGIS only converts here (cheap, no rendering). Corpus files are untrusted.
"""
import glob
import json
import os
import subprocess

from qgis.core import (QgsVectorLayer, QgsSingleSymbolRenderer, QgsSldExportContext,
                       QgsRectangle, QgsStyle)

FIXTURE = {"Marker": "cat_pts", "Line": "lines", "Fill": "zones"}  # geometry carriers


def convert(corpus_dir, out_dir, gpkg, fixer, available_fonts="SansSerif", max_symbols=2):
    from geocatbridge.libs.bridgestyle import qgis as bs
    os.makedirs(out_dir, exist_ok=True)
    bbox = {}
    for geom, fx in FIXTURE.items():
        l = QgsVectorLayer(f"{gpkg}|layername={fx}", fx, "ogr")
        e = QgsRectangle(l.extent()); e.scale(1.1)
        bbox[fx] = f"{e.xMinimum()},{e.yMinimum()},{e.xMaximum()},{e.yMaximum()}"

    manifest = []
    for path in sorted(glob.glob(os.path.join(corpus_dir, "*.xml"))):
        st = QgsStyle(); st.createMemoryDatabase(); st.importXml(path)
        for name in st.symbolNames()[:max_symbols]:
            sym = st.symbol(name)
            geom = {0: "Marker", 1: "Line", 2: "Fill"}.get(int(sym.type()))
            fx = FIXTURE.get(geom)
            if not fx:
                continue
            idx = len(manifest)
            layer = QgsVectorLayer(f"{gpkg}|layername={fx}", fx, "ogr")
            layer.setRenderer(QgsSingleSymbolRenderer(sym.clone()))
            native = layer.exportSldStyleV3(QgsSldExportContext()).toString()
            open(os.path.join(out_dir, f"{idx:03d}_native.sld"), "w").write(native)
            bridge = None
            try:
                bridge, _, _ = bs.layerStyleAsSld(layer)
                open(os.path.join(out_dir, f"{idx:03d}_bridge.sld"), "w").write(bridge)
            except Exception as ex:  # noqa: BLE001 - bridgestyle crashed; ours falls back to native
                open(os.path.join(out_dir, f"{idx:03d}_bridge.err"), "w").write(str(ex))
            # ours base: native when Bridge failed, or when the style has inline SVGs
            # (native keeps the SVG and its ?fill params for the fixer to extract;
            # Bridge inlines them as an unrenderable <WellKnownName>base64:).
            ours_src = native if (bridge is None or "base64:" in native) else bridge
            ours = os.path.join(out_dir, f"{idx:03d}_ours.sld")
            open(ours, "w").write(ours_src)
            subprocess.run(["python3", "-I", fixer, "--available", available_fonts,
                            "--font-fallback", "SansSerif", ours], capture_output=True)
            manifest.append({"idx": f"{idx:03d}", "style": os.path.basename(path),
                             "symbol": name, "geom": geom, "fixture": fx, "bbox": bbox[fx]})
    json.dump(manifest, open(os.path.join(out_dir, "manifest.json"), "w"), indent=2)
    print(f"converted {len(manifest)} symbols into {out_dir}")
    return len(manifest)
