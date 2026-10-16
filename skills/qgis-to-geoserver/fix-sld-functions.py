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

# QGIS exports line labels with followLine=true but maxAngleDelta=25 and no offset,
# so GeoServer keeps them straight, drops the ones it cannot fit, and sits them on
# the line. Raise maxAngleDelta to follow bends, lift them off the line, and let
# multi-segment lines repeat/group. (followLine still cannot kink at a sharp vertex.)
MAX_ANGLE_DELTA = 90
TEXTSYM = re.compile(r'<(\w+:)?TextSymbolizer>.*?</(?:\w+:)?TextSymbolizer>', re.S)
LINEPLACEMENT = re.compile(r'<(?:\w+:)?LinePlacement>')
FONT_SIZE = re.compile(r'font-size">\s*([\d.]+)')
PERP_OFFSET = re.compile(r'<(?:\w+:)?PerpendicularOffset>[^<]*</(?:\w+:)?PerpendicularOffset>')
LINE_END = re.compile(r'</(?:\w+:)?LinePlacement>')
SYM_END = re.compile(r'</(?:\w+:)?TextSymbolizer>')


def _vopt(name):
    return re.compile(r'<(?:\w+:)?VendorOption name="%s">([^<]*)</(?:\w+:)?VendorOption>' % name)


def tune_line_labels(text):
    tuned = [False]

    def repl(m):
        block = m.group(0)
        if not LINEPLACEMENT.search(block):
            return block
        p = m.group(1) or ""
        orig = block
        fs = FONT_SIZE.search(block)
        offset = -max(1, round(float(fs.group(1)) / 2)) if fs else -5
        if PERP_OFFSET.search(block):
            block = PERP_OFFSET.sub(f"<{p}PerpendicularOffset>{offset}</{p}PerpendicularOffset>", block, count=1)
        else:
            block = LINE_END.sub(f"<{p}PerpendicularOffset>{offset}</{p}PerpendicularOffset></{p}LinePlacement>", block, count=1)
        mad = _vopt("maxAngleDelta").search(block)
        if mad:
            if float(mad.group(1)) < MAX_ANGLE_DELTA:
                block = _vopt("maxAngleDelta").sub(f'<{p}VendorOption name="maxAngleDelta">{MAX_ANGLE_DELTA}</{p}VendorOption>', block, count=1)
        else:
            block = SYM_END.sub(f'<{p}VendorOption name="maxAngleDelta">{MAX_ANGLE_DELTA}</{p}VendorOption></{p}TextSymbolizer>', block, count=1)
        for name, val in (("repeat", "250"), ("group", "yes")):
            if not _vopt(name).search(block):
                block = SYM_END.sub(f'<{p}VendorOption name="{name}">{val}</{p}VendorOption></{p}TextSymbolizer>', block, count=1)
        if block != orig:
            tuned[0] = True
        return block

    return TEXTSYM.sub(repl, text), tuned[0]


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


def apply_font_fallback(text, available, fallback, ttf_fallback=None):
    ttf_fallback = ttf_fallback or fallback
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


# Logical fonts always exist on the JVM but have no TTF file, so a ttf:// font mark
# rendered with one draws nothing; a ttf:// mark needs a real installed font.
LOGICAL_FONTS = {"SansSerif", "Serif", "Monospaced", "Dialog", "DialogInput"}


def pick_ttf_fallback(available, fallback):
    if fallback not in LOGICAL_FONTS:
        return fallback
    for pref in ("DejaVu Sans", "Liberation Sans", "Noto Sans", "Arial"):
        if pref in available:
            return pref
    real = [f for f in sorted(available) if f not in LOGICAL_FONTS and "." not in f]
    return real[0] if real else fallback


# QGIS "around point" label placement auto-offsets labels off the marker; SLD export
# centres them (AnchorPoint 0.5/0.5, zero displacement) so the label sits on the dot.
# Only touch styles that draw point markers, so polygon/line labels stay centred.
POINTSYM = re.compile(r'<(?:\w+:)?PointSymbolizer>')
LABEL_PLACEMENT_END = re.compile(r'</(?:\w+:)?LabelPlacement>')
ANCHOR_BLOCK = re.compile(r'<(?:\w+:)?AnchorPoint>.*?</(?:\w+:)?AnchorPoint>', re.S)
DISPL_BLOCK = re.compile(r'<(?:\w+:)?Displacement>.*?</(?:\w+:)?Displacement>', re.S)
POINTPLACE_OPEN = re.compile(r'<(?:\w+:)?PointPlacement>')


def offset_point_labels(text):
    if not POINTSYM.search(text):
        return text, False
    moved = [False]

    def repl(m):
        block = m.group(0)
        if LINEPLACEMENT.search(block):
            return block
        p = m.group(1) or ""
        fs = FONT_SIZE.search(block)
        dx = max(8, round(float(fs.group(1)) / 2) + 2) if fs else 8
        anchor = f"<{p}AnchorPoint><{p}AnchorPointX>0</{p}AnchorPointX><{p}AnchorPointY>0.5</{p}AnchorPointY></{p}AnchorPoint>"
        displ = f"<{p}Displacement><{p}DisplacementX>{dx}</{p}DisplacementX><{p}DisplacementY>0</{p}DisplacementY></{p}Displacement>"
        orig = block
        if POINTPLACE_OPEN.search(block):
            if ANCHOR_BLOCK.search(block):
                block = ANCHOR_BLOCK.sub(anchor, block, count=1)
            else:
                block = POINTPLACE_OPEN.sub(lambda mm: mm.group(0) + anchor, block, count=1)
            if DISPL_BLOCK.search(block):
                block = DISPL_BLOCK.sub(displ, block, count=1)
            else:
                block = re.sub(r'(</(?:\w+:)?AnchorPoint>)', r"\1" + displ, block, count=1)
        else:
            placement = f"<{p}LabelPlacement><{p}PointPlacement>{anchor}{displ}</{p}PointPlacement></{p}LabelPlacement>"
            block = re.sub(r'(</(?:\w+:)?Label>)', r"\1" + placement, block, count=1)
        if block != orig:
            moved[0] = True
        return block

    return TEXTSYM.sub(repl, text), moved[0]


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
        new, line_tuned = tune_line_labels(new)
        new, labels_offset = offset_point_labels(new)
        svg = scan_svg(text)
        font_changes = []
        if args.font_fallback and available is not None:
            ttf_fallback = pick_ttf_fallback(available, args.font_fallback)
            new, font_changes = apply_font_fallback(new, available, args.font_fallback, ttf_fallback)

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

        wrote = (renamed or font_changes or line_tuned or labels_offset) and not args.check
        if wrote:
            with open(f, "w", encoding="utf-8") as fh:
                fh.write(new)
        verb = "would change" if args.check else "changed"
        bits = []
        if renamed:
            bits.append("functions " + ", ".join(sorted(set(renamed))))
        if font_changes:
            bits.append("fonts " + ", ".join(font_changes) + " -> " + args.font_fallback)
        if line_tuned:
            bits.append("line labels (maxAngleDelta, offset, repeat, group)")
        if labels_offset:
            bits.append("point labels offset off the marker")
        if bits:
            print(f"{base}: {verb} {'; '.join(bits)}")
        elif not flagged:
            print(f"{base}: ok")
    return 2 if attention else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
