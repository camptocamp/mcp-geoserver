#!/usr/bin/env python3
"""Self-check for fix-sld-functions.py. Run: python3 -I test_fix_sld_functions.py"""
import subprocess, sys, tempfile, os

SCRIPT = os.path.join(os.path.dirname(__file__), "fix-sld-functions.py")
SLD = (
    '<se:TextSymbolizer>'
    '<ogc:Function name="upper"><ogc:PropertyName>n</ogc:PropertyName></ogc:Function>'
    '<ogc:Function name="substr"><ogc:PropertyName>n</ogc:PropertyName></ogc:Function>'
    '<se:SvgParameter name="font-family">Sans</se:SvgParameter>'
    '<se:OnlineResource xlink:href="/usr/share/qgis/x.svg"/>'
    '</se:TextSymbolizer>'
)

def run(args, text):
    with tempfile.NamedTemporaryFile("w", suffix=".sld", delete=False) as f:
        f.write(text); p = f.name
    r = subprocess.run([sys.executable, "-I", SCRIPT, *args, p], capture_output=True, text=True)
    out = open(p).read(); os.unlink(p)
    return r.returncode, r.stdout, out

# --check: reports upper rename, refuses substr, flags svg, exits 2, writes nothing
code, log, body = run(["--check"], SLD)
assert code == 2, code
assert "upper" in log and "substr" in log.lower() and "SVG" in log, log
assert "strToUpperCase" not in body, "check must not write"

# rewrite: upper -> strToUpperCase in place; substr left alone
code, log, body = run([], SLD)
assert 'name="strToUpperCase"' in body and 'name="upper"' not in body, body
assert 'name="substr"' in body, "unsafe must not be renamed"

# font fallback: missing Sans -> SansSerif, available font kept
code, log, body = run(["--available", "DejaVu Sans,SansSerif", "--font-fallback", "SansSerif"],
                      '<se:SvgParameter name="font-family">Sans</se:SvgParameter>'
                      '<se:SvgParameter name="font-family">DejaVu Sans</se:SvgParameter>')
assert ">SansSerif<" in body and ">DejaVu Sans<" in body, body

print("ok")
