#!/usr/bin/env python3
"""Download QGIS Hub symbol-library styles into a local corpus dir (untrusted input).

Pulls recent entries from the QGIS Hub resources API, keeps the ones whose subtype
is a layer-applicable symbol (Marker/Line/Fill), and saves each as an .xml plus a
manifest.json. Run the corpus through hub_corpus_test.py inside QGIS afterwards.

    python3 -I fetch_hub_styles.py --out /tmp/hub_corpus --limit 20

Downloads are third-party and untrusted: keep them in their own dir, never execute
them, and do not commit them (styles carry their own licences).
"""
import argparse
import json
import os
import urllib.request

API = "https://hub.qgis.org/api/v1/resources/"


def subtypes(entry):
    return {s.strip().lower() for s in (entry.get("resource_subtypes") or "").split(",")}


def fetch(url):
    with urllib.request.urlopen(url, timeout=30) as r:  # nosec B310 - fixed https host
        return r.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=20, help="how many matching styles to keep")
    ap.add_argument("--subtypes", default="Marker,Line,Fill")
    args = ap.parse_args()
    want = {s.strip().lower() for s in args.subtypes.split(",")}
    os.makedirs(args.out, exist_ok=True)

    manifest, url = [], f"{API}?resource_type=Style&ordering=-upload_date&limit=50"
    while url and len(manifest) < args.limit:
        page = json.loads(fetch(url))
        for e in page.get("results", []):
            if len(manifest) >= args.limit:
                break
            if not (subtypes(e) & want):
                continue
            furl = e.get("file") or ""
            if not furl.lower().endswith(".xml"):
                continue
            name = "".join(c if c.isalnum() else "_" for c in e["name"])[:60]
            path = os.path.join(args.out, f"{len(manifest):03d}_{name}.xml")
            try:
                with open(path, "wb") as fh:
                    fh.write(fetch(furl))
            except Exception as ex:  # noqa: BLE001 - skip a bad download, keep going
                print(f"skip {e['name']}: {ex}")
                continue
            manifest.append({"name": e["name"], "subtypes": e.get("resource_subtypes"),
                             "url": furl, "file": os.path.basename(path)})
            print(f"got {e['name']} [{e.get('resource_subtypes')}]")
        url = page.get("next")

    with open(os.path.join(args.out, "manifest.json"), "w") as fh:
        json.dump(manifest, fh, indent=2)
    print(f"\n{len(manifest)} styles in {args.out}")


if __name__ == "__main__":
    main()
