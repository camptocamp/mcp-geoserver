#!/usr/bin/env python3
"""Fix a QGIS-exported SLD for GeoServer.

In-place passes: rename expression functions to GeoTools names (refuse the unsafe
ones), tune line labels (follow bends, lift off the line, repeat/group), offset
point labels off the marker, extract inline base64 SVGs to sidecar files GeoServer
can load, and optionally reconcile fonts the server lacks to a fallback.

Usage:
    python3 -I fix-sld-functions.py FILE.sld [...]              # fix in place
    python3 -I fix-sld-functions.py --check DIR                 # report only
    python3 -I fix-sld-functions.py --available "DejaVu Sans,SansSerif" \\
            --font-fallback SansSerif FILE.sld [...]            # also fix fonts

--available comes from GET /rest/fonts.json. SansSerif, Serif and Monospaced are
Java logical fonts, always present; a ttf:// marker needs a real font, so the
marker is mapped to an installed TTF while labels use the logical fallback.
"""
import argparse
import base64
import hashlib
import os
import re
import sys

# Source function name -> GeoTools name. Pure renames: same semantics, same args.
# Covers QGIS native names and GeoCat Bridge names (strToUpper, strToLower).
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

# Logical fonts always exist on the JVM but have no TTF file, so a ttf:// font mark
# rendered with one draws nothing; such a mark needs a real installed font.
LOGICAL_FONTS = {"SansSerif", "Serif", "Monospaced", "Dialog", "DialogInput"}
MAX_ANGLE_DELTA = 90

FUNC = re.compile(r'(<ogc:Function\s+name=")([^"]+)(")')
HREF = re.compile(r'xlink:href="([^"]+)"')
FONT_FAMILY = re.compile(r'(name="font-family">)([^<]+)(</)')
TTF = re.compile(r'(ttf://)([^#"\s]+)')
FONT_SIZE = re.compile(r'font-size">\s*([\d.]+)')
TEXTSYM = re.compile(r'<(\w+:)?TextSymbolizer(?:\s[^>]*)?>.*?</(?:\w+:)?TextSymbolizer>', re.S)
# QGIS inlines SVGs as xlink:href="base64:<b64>?param=..."; GeoServer rejects that
# scheme (and data: URIs). Decode to a sidecar .svg and use the bare filename.
B64_HREF = re.compile(r'xlink:href="base64:([^"?]+)(\?[^"]*)?"')


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


def _float(block, tag):
    m = re.search(rf'<(?:\w+:)?{tag}>\s*([-\d.]+)\s*</(?:\w+:)?{tag}>', block)
    return float(m.group(1)) if m else None


def _tune_line_label(block, p):
    fs = FONT_SIZE.search(block)
    off = -max(1, round(float(fs.group(1)) / 2)) if fs else -5
    po = _float(block, "PerpendicularOffset")
    if po is None:
        block = re.sub(r'(</(?:\w+:)?LinePlacement>)',
                       f'<{p}PerpendicularOffset>{off}</{p}PerpendicularOffset>\\1', block, count=1)
    elif po == 0.0:
        block = re.sub(r'(<(?:\w+:)?PerpendicularOffset>)[^<]*(</)', rf'\g<1>{off}\g<2>', block, count=1)
    mad = re.search(r'<(?:\w+:)?VendorOption name="maxAngleDelta">\s*([-\d.]+)', block)
    if mad is None:
        block = re.sub(r'(</(?:\w+:)?TextSymbolizer>)',
                       f'<{p}VendorOption name="maxAngleDelta">{MAX_ANGLE_DELTA}</{p}VendorOption>\\1', block, count=1)
    elif float(mad.group(1)) < MAX_ANGLE_DELTA:
        block = re.sub(r'(<(?:\w+:)?VendorOption name="maxAngleDelta">)[^<]*(</)',
                       rf'\g<1>{MAX_ANGLE_DELTA}\g<2>', block, count=1)
    for name, val in (("repeat", "250"), ("group", "yes")):
        if not re.search(rf'<(?:\w+:)?VendorOption name="{name}">', block):
            block = re.sub(r'(</(?:\w+:)?TextSymbolizer>)',
                           f'<{p}VendorOption name="{name}">{val}</{p}VendorOption>\\1', block, count=1)
    return block


def _offset_point_label(block):
    # only the centred default (what QGIS/Bridge export); never stomp a real offset
    if not (_float(block, "AnchorPointX") == 0.5 and _float(block, "AnchorPointY") == 0.5
            and _float(block, "DisplacementX") == 0.0 and _float(block, "DisplacementY") == 0.0):
        return block
    fs = FONT_SIZE.search(block)
    dx = max(8, round(float(fs.group(1)) / 2) + 2) if fs else 8
    block = re.sub(r'(<(?:\w+:)?AnchorPointX>)[^<]*(</)', r'\g<1>0\g<2>', block, count=1)
    return re.sub(r'(<(?:\w+:)?DisplacementX>)[^<]*(</)', rf'\g<1>{dx}\g<2>', block, count=1)


def fix_text_symbolizers(text):
    # QGIS exports line labels straight on the line (followLine, maxAngleDelta=25) and
    # point labels centred on the marker; GeoServer needs the bends followed and the
    # label lifted off. A layer has one geometry, so a PointSymbolizer in the file
    # means the labels are point labels.
    has_point = bool(re.search(r'<(?:\w+:)?PointSymbolizer[ >]', text))

    def repl(m):
        block, p = m.group(0), m.group(1) or ""
        if re.search(r'<(?:\w+:)?LinePlacement>', block):
            return _tune_line_label(block, p)
        if has_point:
            return _offset_point_label(block)
        return block

    return TEXTSYM.sub(repl, text)


def extract_inline_svgs(text, sld_path, write):
    out_dir = os.path.dirname(os.path.abspath(sld_path))
    base = os.path.splitext(os.path.basename(sld_path))[0]
    written = []

    def repl(m):
        b64, query = m.group(1), m.group(2) or ""
        try:
            data = base64.b64decode(b64)
        except Exception:  # noqa: BLE001 - leave a href we cannot decode untouched
            return m.group(0)
        fname = f"{base}_{hashlib.md5(data).hexdigest()[:12]}.svg"  # nosec B324 - filename only
        if write:
            with open(os.path.join(out_dir, fname), "wb") as fh:
                fh.write(data)
        written.append(fname)
        return f'xlink:href="{fname}{query}"'

    return B64_HREF.sub(repl, text), sorted(set(written))


def pick_ttf_fallback(available, fallback):
    if fallback not in LOGICAL_FONTS:
        return fallback
    for pref in ("DejaVu Sans", "Liberation Sans", "Noto Sans", "Arial"):
        if pref in available:
            return pref
    real = [f for f in sorted(available) if f not in LOGICAL_FONTS and "." not in f]
    return real[0] if real else fallback


def apply_font_fallback(text, available, fallback):
    ttf_fallback = pick_ttf_fallback(available, fallback)
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
            return m.group(1) + ttf_fallback  # a ttf:// mark needs a real TTF, not a logical font
        return m.group(0)

    return TTF.sub(tf, FONT_FAMILY.sub(ff, text)), sorted(set(changed))


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
    if args.font_fallback and not args.available:
        ap.error("--font-fallback needs --available (the server's font list)")
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
        before = new
        new = fix_text_symbolizers(new)
        labels_tuned = new != before
        new, inline_svgs = extract_inline_svgs(new, f, write=not args.check)
        font_changes = []
        if args.font_fallback:
            new, font_changes = apply_font_fallback(new, available, args.font_fallback)

        flagged = False
        for name in sorted(set(blocked)):
            flagged = True
            print(f"{base}: UNSAFE {name}: {UNSAFE[name]}")
        for href in scan_svg(new):
            flagged = True
            print(f"{base}: SVG {href}: not a bare filename; upload the SVG and rewrite href to the bare name (SKILL.md step 4)")
        if available is not None and not args.font_fallback:
            for fam in fonts_used(text):
                if fam not in available:
                    flagged = True
                    print(f"{base}: FONT {fam}: not installed on GeoServer; install it or pass --font-fallback")
        attention = attention or flagged

        if new != text and not args.check:
            with open(f, "w", encoding="utf-8") as fh:
                fh.write(new)
        verb = "would change" if args.check else "changed"
        bits = []
        if renamed:
            bits.append("functions " + ", ".join(sorted(set(renamed))))
        if font_changes:
            bits.append("fonts " + ", ".join(font_changes) + " -> " + args.font_fallback)
        if inline_svgs:
            bits.append(f"extracted {len(inline_svgs)} inline SVG(s) to upload")
        if labels_tuned:
            bits.append("label placement/vendor options")
        if bits:
            print(f"{base}: {verb} {'; '.join(bits)}")
        elif not flagged:
            print(f"{base}: ok")
    return 2 if attention else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
