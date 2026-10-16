# QGIS Hub corpus test

Harness that runs real QGIS styles from the [community hub](https://hub.qgis.org/styles/)
through the three converters (native, GeoCat Bridge, ours) and scores them on
GeoServer. The fixtures are small and hand-made; this exercises symbology they
never cover.

## Tools (`skills/qgis-to-geoserver/tools/`)

- `fetch_hub_styles.py` - pull N recent styles from the hub API into a local dir.
- `hub_corpus_test.py` - run **inside QGIS**: apply each symbol to a fixture layer
  of the matching geometry and export native / bridge / ours SLDs + a manifest.
- `hub_corpus_score.py` - run on the **host**: upload each style (with any sidecar
  SVGs the fixer extracted) to a scratch GeoServer workspace, render it, and write a
  scorecard plus a `native | bridge | ours` montage per symbol.

## Run

```
python3 -I fetch_hub_styles.py --out /tmp/hub_corpus --limit 12
# in the QGIS Python console:
#   from hub_corpus_test import convert
#   convert("/tmp/hub_corpus", "/tmp/hub_sld", "<testdata>/fixtures.gpkg",
#           "<skill>/fix-sld-functions.py", available_fonts="<GET /rest/fonts.json>")
python3 -I hub_corpus_score.py --sld-dir /tmp/hub_sld \
        --gpkg <testdata>/fixtures.gpkg --report /tmp/hub_report.md --img-dir /tmp/hub_img
```

The corpus and the rendered montages are third-party styles (licences vary); keep
them local, do not commit them.

## Sample run (12 recent styles, 1 symbol each)

| converter | renders |
| --- | --- |
| native | 5/12 |
| bridge | 3/12 |
| **ours** | **11/12** |

These hub resources are mostly symbol libraries (texture / SVG line and fill packs),
so inline SVGs dominate. `ours` leads because it picks the base per symbol: native +
`fix-sld-functions.py` when the style has inline SVGs or when Bridge crashes, Bridge
otherwise. (`renders` = GeoServer returned a non-blank image; blank detection is an
approximate grey-mean heuristic, so sparse line/point renders can be under-counted.)

## Findings fed back into the skill

- **bridgestyle crashed on 6/12 symbols** (`list index out of range`,
  `could not convert string to float: ''`, `NoneType object is not subscriptable`);
  QGIS native produced an SLD for all. `ours` falls back to native + fixer when
  bridgestyle raises.
- **Inline SVGs render only through native + fixer.** QGIS native inlines SVGs as
  `xlink:href="base64:...?fill=..."`; GeoServer rejects the `base64:` scheme (and
  `data:`), so the fixer decodes each to a sidecar `.svg`, rewrites the href to the
  bare filename, and keeps the `?fill` query, which GeoServer then renders in colour.
- **Bridge inlines SVGs as `<WellKnownName>base64:...</WellKnownName>`** with no fill
  query: unrenderable, and converting a Mark to an ExternalGraphic would also lose
  the colours. So `ours` prefers native + fixer whenever the style has an inline SVG
  (`"base64:" in native`), and takes Bridge only otherwise.
