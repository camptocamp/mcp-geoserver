#!/usr/bin/env python3
"""Fix QGIS-exported SLD for GeoServer: function names and missing fonts.

QGIS exports a label or filter expression using its own function names, which
GeoServer often cannot resolve; one unknown function makes GeoServer reject the
whole style ("Unable to find function X", HTTP 500). It also writes the author's
font name (often a fontconfig alias such as "Sans") which the GeoServer JVM may
not have, so the layer fails to render with "Unknown font".

This rewrites the safe 1:1 function renames, refuses on functions whose GeoTools
form differs in arguments or arity (fix those in QGIS), flags external-graphic
hrefs that are not a bare filename, and optionally rewrites fonts the server does
not have to a chosen fallback.

Usage:
    python3 -I fix-sld-functions.py FILE.sld [...]              # rewrite functions in place
    python3 -I fix-sld-functions.py --check DIR                 # report only, no write
    python3 -I fix-sld-functions.py --available "DejaVu Sans,SansSerif" \\
            --font-fallback SansSerif FILE.sld [...]            # also rewrite missing fonts

Get the server's font list for --available from: GET /rest/fonts.json. SansSerif,
Serif and Monospaced are Java logical fonts, always present on any GeoServer JVM.
"""
import argparse
import os
import re
import sys

# Source function name -> GeoTools name. Pure renames: same semantics, same args.
# Covers both QGIS native export names and GeoCat Bridge names (strToUpper, strToLower),
# so the fixer cleans either converter's output.
SAFE_RENAMES = {
    "upper": "strToUpperCase",
    "lower": "strToLowerCase",
    "strToUpper": "strToUpperCase",   # GeoCat Bridge emits this; GeoServer has no strToUpper
    "strToLower": "strToLowerCase",   # GeoCat Bridge emits this
    "trim": "strTrim",
    "length": "strLength",
    "concat": "Concatenate",
    "title": "strCapitalize",  # approximate: strCapitalize upper-cases the first letter of each word
}

# QGIS name -> why it is NOT a safe rename. These need the expression reworked in QGIS.
UNSAFE = {
    "substr": "QGIS substr(s,start,len) is 1-based; GeoTools strSubstring(s,begin,end) is 0-based, end-exclusive",
    "replace": "GeoTools strReplace(s,search,replace,global) needs a 4th boolean arg; QGIS replace also accepts maps/arrays",
    "coalesce": "no GeoTools equivalent; rewrite as if_then_else(isNull(x), y, x)",
    "round": "GeoTools round(x) returns a long with no decimals arg",
    "to_string": "no GeoTools equivalent; rely on implicit string coercion or Concatenate",
    "left": "no GeoTools equivalent; use strSubstring(s, 0, n)",
    "right": "no GeoTools equivalent; use strSubstring(s, strLength(s) - n, strLength(s))",
    "strpos": "QGIS strpos is 1-based; GeoTools strIndexOf is 0-based",
    "format_number": "GeoTools numberFormat(pattern, number) has a different signature",
}

FUNC = re.compile(r'(<ogc:Function\s+name=")([^"]+)(")')
HREF = re.compile(r'xlink:href="([^"]+)"')
FONT_FAMILY = re.compile(r'(name="font-family">)([^<]+)(</)')
TTF = re.compile(r'(ttf://)([^#"\s]+)')


def scan_svg(text):
    bad = []
    for href in HREF.findall(text):
        h = href.split("?", 1)[0]
        if h.startswith(("http://", "https://", "ttf://")):
            continue  # a real URL, or GeoServer's ttf:// font-mark scheme, is fine
        if "/" in h or "\\" in h or h.startswith("file:"):
            bad.append(href)
    return sorted(set(bad))


def fonts_used(text):
    out = set()
    out.update(m.group(2).strip() for m in FONT_FAMILY.finditer(text))
    out.update(m.group(2).strip() for m in TTF.finditer(text))
    return sorted(out)


def rename_functions(text):
    renamed, blocked = [], []

    def sub(m):
        name = m.group(2)
        if name in SAFE_RENAMES:
            renamed.append(name)
            return m.group(1) + SAFE_RENAMES[name] + m.group(3)
        if name in UNSAFE:
            blocked.append(name)
        return m.group(0)

    return FUNC.sub(sub, text), renamed, blocked


def apply_font_fallback(text, available, fallback):
    changed = []

    def ff(m):
        fam = m.group(2).strip()
        if fam not in available:
            changed.append(fam)
            return m.group(1) + fallback + m.group(3)
        return m.group(0)

    def tf(m):
        fam = m.group(2).strip()
        if fam not in available:
            changed.append(fam)
            return m.group(1) + fallback
        return m.group(0)

    return TTF.sub(tf, FONT_FAMILY.sub(ff, text)), sorted(set(changed))


def main(argv):
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("--check", action="store_true", help="report only, do not write")
    ap.add_argument("--available", default=None, help="comma-separated fonts GeoServer has (from /rest/fonts.json)")
    ap.add_argument("--font-fallback", default=None, help="rewrite fonts not in --available to this font")
    ap.add_argument("paths", nargs="*")
    args = ap.parse_args(argv)
    if not args.paths:
        print(__doc__)
        return 1
    available = {s.strip() for s in args.available.split(",")} if args.available else None

    files = []
    for p in args.paths:
        if os.path.isdir(p):
            files += [os.path.join(p, f) for f in sorted(os.listdir(p)) if f.endswith(".sld")]
        else:
            files.append(p)

    attention = False
    for f in files:
        with open(f, encoding="utf-8") as fh:
            text = fh.read()
        base = os.path.basename(f)
        new, renamed, blocked = rename_functions(text)
        svg = scan_svg(text)
        font_changes = []
        if args.font_fallback and available is not None:
            new, font_changes = apply_font_fallback(new, available, args.font_fallback)

        flagged = False
        for name in sorted(set(blocked)):
            flagged = True
            print(f"{base}: UNSAFE {name}: {UNSAFE[name]}")
        for href in svg:
            flagged = True
            print(f"{base}: SVG {href}: not a bare filename; upload the SVG and rewrite href to the bare name (SKILL.md step 4)")
        if available is not None and not args.font_fallback:
            for fam in fonts_used(text):
                if fam not in available:
                    flagged = True
                    print(f"{base}: FONT {fam}: not installed on GeoServer; install it or pass --font-fallback")
        attention = attention or flagged

        wrote = (renamed or font_changes) and not args.check
        if wrote:
            with open(f, "w", encoding="utf-8") as fh:
                fh.write(new)
        verb = "would change" if args.check else "changed"
        bits = []
        if renamed:
            bits.append("functions " + ", ".join(sorted(set(renamed))))
        if font_changes:
            bits.append("fonts " + ", ".join(font_changes) + " -> " + args.font_fallback)
        if bits:
            print(f"{base}: {verb} {'; '.join(bits)}")
        elif not flagged:
            print(f"{base}: ok")
    return 2 if attention else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
