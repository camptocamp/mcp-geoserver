#!/usr/bin/env python3
"""Score converted Hub SLDs on GeoServer: a report plus native|bridge|ours montages.

Reads the SLDs and manifest.json from hub_corpus_test.convert(). For each converter
it uploads the style (bundling any sidecar SVGs the fixer extracted) to a scratch
workspace, renders it, and records ok / blank / a failure category. Writes a markdown
scorecard and, with --img-dir, a comparison montage per symbol.

    python3 -I hub_corpus_score.py --sld-dir /tmp/hub_sld --gpkg <testdata>/fixtures.gpkg \
            --report /tmp/hub_report.md --img-dir /tmp/hub_img

REST writes run inside the container (loopback is whitelisted, so no brute-force
throttling). Needs docker and ImageMagick (montage/convert) on the host.
"""
import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import zipfile


def dx(c, *cmd):
    return subprocess.run(["docker", "exec", c, *cmd], capture_output=True, text=True)


def curl(c, *a):
    return dx(c, "curl", "-s", "-u", "admin:geoserver", *a).stdout


def categorise(msg):
    m = msg.lower()
    if "unable to find function" in m:
        fn = re.search(r"find function (\w+)", msg)
        return f"unknown-function:{fn.group(1) if fn else '?'}"
    if "unknown font" in m:
        return "unknown-font"
    if "colormap" in m:
        return "colormap"
    if "externalgraphic" in m or "could not" in m or "unknown protocol" in m:
        return "external-graphic"
    if "parsing failed" in m or "invalid" in m:
        return "parse-error"
    return "other"


CONVS = ("native", "bridge", "ours")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sld-dir", required=True)
    ap.add_argument("--gpkg", required=True)
    ap.add_argument("--report", required=True)
    ap.add_argument("--img-dir")
    ap.add_argument("--container", default="vanilla-geoserver-1")
    ap.add_argument("--ws", default="hubscore")
    ap.add_argument("--gs", default="http://localhost:8080/geoserver")
    a = ap.parse_args()
    c, ws, B = a.container, a.ws, f"{a.gs}/rest"
    manifest = json.load(open(os.path.join(a.sld_dir, "manifest.json")))

    # bundle each style with any sidecar SVGs the fixer extracted
    zdir = os.path.join(a.sld_dir, "_zips"); shutil.rmtree(zdir, ignore_errors=True); os.makedirs(zdir)
    for e in manifest:
        for conv in CONVS:
            sld = os.path.join(a.sld_dir, f"{e['idx']}_{conv}.sld")
            if not os.path.exists(sld):
                continue
            with zipfile.ZipFile(os.path.join(zdir, f"{e['idx']}_{conv}.zip"), "w") as z:
                z.write(sld, os.path.basename(sld))
                for svg in glob.glob(os.path.join(a.sld_dir, f"{e['idx']}_{conv}_*.svg")):
                    z.write(svg, os.path.basename(svg))

    dx(c, "mkdir", "-p", f"/tmp/{ws}/out")
    subprocess.run(["docker", "cp", a.gpkg, f"{c}:/tmp/{ws}/fixtures.gpkg"], capture_output=True)
    subprocess.run(["docker", "cp", f"{zdir}/.", f"{c}:/tmp/{ws}/"], capture_output=True)
    curl(c, "-XDELETE", f"{B}/workspaces/{ws}?recurse=true")
    curl(c, "-XPOST", f"{B}/workspaces", "-H", "Content-Type: application/json", "-d", f'{{"workspace":{{"name":"{ws}"}}}}')
    curl(c, "-XPOST", f"{B}/workspaces/{ws}/datastores", "-H", "Content-Type: application/json",
         "-d", ('{"dataStore":{"name":"fx","connectionParameters":{"entry":'
                '[{"@key":"database","$":"/tmp/%s/fixtures.gpkg"},{"@key":"dbtype","$":"geopkg"}]}}}' % ws))
    for fx in {e["fixture"] for e in manifest}:
        curl(c, "-XPOST", f"{B}/workspaces/{ws}/datastores/fx/featuretypes", "-H", "Content-Type: application/json",
             "-d", f'{{"featureType":{{"name":"{fx}","nativeName":"{fx}","srs":"EPSG:4326"}}}}')

    for e in manifest:
        for conv in CONVS:
            if not os.path.exists(os.path.join(zdir, f"{e['idx']}_{conv}.zip")):
                continue
            st = f"s_{e['idx']}_{conv}"
            curl(c, "-XPOST", f"{B}/workspaces/{ws}/styles?name={st}", "-H", "Content-Type: application/zip",
                 "--data-binary", f"@/tmp/{ws}/{e['idx']}_{conv}.zip")
            curl(c, "-XGET", f"{a.gs}/{ws}/wms?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap"
                 f"&LAYERS={ws}:{e['fixture']}&STYLES={ws}:{st}&SRS=EPSG:4326&BBOX={e['bbox']}"
                 "&WIDTH=200&HEIGHT=150&FORMAT=image/png&BGCOLOR=0xFFFFFF",
                 "-o", f"/tmp/{ws}/out/{e['idx']}_{conv}.png")

    localout = os.path.join(a.sld_dir, "_out"); shutil.rmtree(localout, ignore_errors=True)
    subprocess.run(["docker", "cp", f"{c}:/tmp/{ws}/out/.", localout + "/"], capture_output=True)
    if a.img_dir:
        os.makedirs(a.img_dir, exist_ok=True)

    rows, tally = [], {}
    for e in manifest:
        row = {"style": e["style"], "symbol": e["symbol"], "geom": e["geom"]}
        panels = []
        for conv in CONVS:
            png = os.path.join(localout, f"{e['idx']}_{conv}.png")
            if not os.path.exists(png):
                row[conv] = "n/a"; panels.append(("n/a", None)); continue
            head = open(png, "rb").read(4)
            if head.startswith(b"\x89PNG"):
                # mean grey of the panel; ~1.0 is white. A fill that renders covers the
                # polygon (well below 0.98); a near-white panel is effectively blank.
                # Sparse line/point renders sit high too, so this over-reports blank for
                # them: an approximate signal, not proof.
                mean = subprocess.run(["convert", png, "-colorspace", "Gray", "-format", "%[fx:mean]", "info:"],
                                      capture_output=True, text=True).stdout.strip()
                res = "blank" if (mean and float(mean) > 0.98) else "ok"
            else:
                m = re.search(r"<ServiceException[^>]*>(.*?)</ServiceException>", open(png).read(), re.S)
                res = categorise(m.group(1) if m else "")
            row[conv] = res
            panels.append((res, png if res in ("ok", "blank") else None))
            tally[res] = tally.get(res, 0) + 1
        rows.append(row)
        if a.img_dir:
            args_m = []
            for conv, (res, png) in zip(CONVS, panels):
                args_m += ["-label", f"{conv}: {res}"]
                args_m += [png] if png else ["-size", "200x150", f"caption:{res}"]
            subprocess.run(["montage", *args_m, "-tile", "3x1", "-geometry", "+3+3", "-pointsize", "11",
                            os.path.join(a.img_dir, f"{e['idx']}_{e['symbol'].replace(' ', '_')}.png")],
                           capture_output=True)

    curl(c, "-XDELETE", f"{B}/workspaces/{ws}?recurse=true")
    dx(c, "rm", "-rf", f"/tmp/{ws}")

    out = ["# QGIS Hub corpus: converter scorecard", "",
           f"{len(rows)} symbols. ok = renders; blank = parsed but nothing drawn; else the failure category.",
           "", "| style | symbol | geom | native | bridge | ours |", "| --- | --- | --- | --- | --- | --- |"]
    out += [f"| {r['style']} | {r['symbol']} | {r['geom']} | {r['native']} | {r['bridge']} | {r['ours']} |" for r in rows]
    out += ["", "## Tally", ""] + [f"- {k}: {v}" for k, v in sorted(tally.items(), key=lambda kv: -kv[1])]
    out += [f"- {conv} ok: {sum(1 for r in rows if r[conv] == 'ok')}/{len(rows)}" for conv in CONVS]
    open(a.report, "w").write("\n".join(out) + "\n")
    print(f"wrote {a.report}: {len(rows)} symbols" + (f"; montages in {a.img_dir}" if a.img_dir else ""))


if __name__ == "__main__":
    main()
