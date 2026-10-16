#!/usr/bin/env python3
"""Rewrite QGIS expression function names in an exported SLD to their GeoTools names.

QGIS exports a label/filter expression as an <ogc:Function name="..."> tree using the
QGIS function name, which GeoServer often cannot resolve. A single unknown function
makes GeoServer reject the whole style ("Unable to find function X", HTTP 500).

This rewrites the safe 1:1 renames and refuses (exit 2) on functions whose GeoTools
equivalent differs in argument order or arity, because a blind rename there produces a
style that parses but renders wrong. Those must be fixed in the QGIS expression and
re-exported.

Usage:
    python3 -I fix-sld-functions.py FILE.sld [FILE2.sld ...]   # rewrite in place
    python3 -I fix-sld-functions.py --check DIR                 # report only, no write
"""
import re
import sys

# QGIS name -> GeoTools name. Pure renames: same semantics, same args.
SAFE_RENAMES = {
    "upper": "strToUpperCase",
    "lower": "strToLowerCase",
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


def process(text):
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


def main(argv):
    check = argv and argv[0] == "--check"
    paths = argv[1:] if check else argv
    if not paths:
        print(__doc__)
        return 1
    import os
    files = []
    for p in paths:
        if os.path.isdir(p):
            files += [os.path.join(p, f) for f in os.listdir(p) if f.endswith(".sld")]
        else:
            files.append(p)
    unsafe_found = False
    for f in files:
        with open(f, encoding="utf-8") as fh:
            text = fh.read()
        new, renamed, blocked = process(text)
        if blocked:
            unsafe_found = True
            for name in sorted(set(blocked)):
                print(f"{os.path.basename(f)}: UNSAFE {name}: {UNSAFE[name]}")
        if renamed and not check:
            with open(f, "w", encoding="utf-8") as fh:
                fh.write(new)
        tag = "would rename" if check else "renamed"
        if renamed:
            print(f"{os.path.basename(f)}: {tag} {', '.join(sorted(set(renamed)))}")
        if not renamed and not blocked:
            print(f"{os.path.basename(f)}: ok")
    return 2 if unsafe_found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
