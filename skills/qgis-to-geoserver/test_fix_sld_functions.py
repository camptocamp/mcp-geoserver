#!/usr/bin/env python3
"""Self-check for fix-sld-functions.py. Run: python3 -I test_fix_sld_functions.py"""
import subprocess, sys, tempfile, os, re

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

# line labels: raise maxAngleDelta, add negative offset, repeat and group
LINE = (
    '<TextSymbolizer><Label><ogc:PropertyName>name</ogc:PropertyName></Label>'
    '<Font><CssParameter name="font-size">14</CssParameter></Font>'
    '<LabelPlacement><LinePlacement><PerpendicularOffset>0.0</PerpendicularOffset></LinePlacement></LabelPlacement>'
    '<VendorOption name="followLine">true</VendorOption>'
    '<VendorOption name="maxAngleDelta">25</VendorOption></TextSymbolizer>')
code, log, body = run([], LINE)
assert '"maxAngleDelta">90<' in body, body
assert '<PerpendicularOffset>-7</PerpendicularOffset>' in body, body
assert '"repeat">250<' in body and '"group">yes<' in body, body
# a line TextSymbolizer picks up maxAngleDelta but a point label must not
# (checked below via the point-marker case, which has no LinePlacement)

# font marker: ttf:// mark reconciled to a REAL font, not the logical SansSerif
MARKER = ('<PointSymbolizer><Graphic><Mark><WellKnownName>ttf://Sans#0x41</WellKnownName></Mark></Graphic></PointSymbolizer>'
          '<TextSymbolizer><Label><ogc:PropertyName>n</ogc:PropertyName></Label>'
          '<LabelPlacement><PointPlacement><AnchorPoint><AnchorPointX>0.5</AnchorPointX>'
          '<AnchorPointY>0.5</AnchorPointY></AnchorPoint><Displacement><DisplacementX>0.0</DisplacementX>'
          '<DisplacementY>0.0</DisplacementY></Displacement></PointPlacement></LabelPlacement>'
          '<Font><CssParameter name="font-family">Sans</CssParameter></Font></TextSymbolizer>')
code, log, body = run(["--available", "DejaVu Sans,SansSerif", "--font-fallback", "SansSerif"], MARKER)
assert "ttf://DejaVu Sans#0x41" in body, body
assert "ttf://SansSerif" not in body, body
# point label offset off the marker: anchor x -> 0, displacement x > 0
assert "<AnchorPointX>0</AnchorPointX>" in body and "<DisplacementX>0.0</DisplacementX>" not in body, body

# polygon label (no PointSymbolizer) stays centred
POLY = ('<PolygonSymbolizer><Fill/></PolygonSymbolizer>'
        '<TextSymbolizer><Label><ogc:PropertyName>n</ogc:PropertyName></Label>'
        '<LabelPlacement><PointPlacement><AnchorPoint><AnchorPointX>0.5</AnchorPointX>'
        '<AnchorPointY>0.5</AnchorPointY></AnchorPoint></PointPlacement></LabelPlacement></TextSymbolizer>')
code, log, body = run([], POLY)
assert "<AnchorPointX>0.5</AnchorPointX>" in body, body

# inline SVG: QGIS base64: href decoded to a bare .svg filename, query kept
B64 = '<ExternalGraphic><OnlineResource xlink:href="base64:PHN2Zy8+?fill=red"/></ExternalGraphic>'
code, log, body = run([], B64)
assert "base64:" not in body, body
assert re.search(r'xlink:href="[^"]+\.svg\?fill=red"', body), body
assert "inline SVG" in log, log

# se:-prefixed input (QGIS native shape): line label tuned, prefix preserved
SE_LINE = ('<se:TextSymbolizer><se:Label>x</se:Label>'
           '<se:LabelPlacement><se:LinePlacement><se:GeneralizeLine>true</se:GeneralizeLine></se:LinePlacement></se:LabelPlacement>'
           '<se:VendorOption name="followLine">true</se:VendorOption></se:TextSymbolizer>')
code, log, body = run([], SE_LINE)
assert '"maxAngleDelta">90<' in body and "<se:PerpendicularOffset>" in body, body

# se:-prefixed point label at the centred default is offset, prefix preserved
SE_POINT = ('<se:PointSymbolizer><se:Graphic/></se:PointSymbolizer>'
            '<se:TextSymbolizer><se:Label>x</se:Label><se:LabelPlacement><se:PointPlacement>'
            '<se:AnchorPoint><se:AnchorPointX>0.5</se:AnchorPointX><se:AnchorPointY>0.5</se:AnchorPointY></se:AnchorPoint>'
            '<se:Displacement><se:DisplacementX>0</se:DisplacementX><se:DisplacementY>0</se:DisplacementY></se:Displacement>'
            '</se:PointPlacement></se:LabelPlacement></se:TextSymbolizer>')
code, log, body = run([], SE_POINT)
assert "<se:AnchorPointX>0</se:AnchorPointX>" in body, body

# a point label with a real author offset is left untouched
PU = ('<PointSymbolizer><Graphic/></PointSymbolizer><TextSymbolizer><Label>x</Label><LabelPlacement><PointPlacement>'
      '<AnchorPoint><AnchorPointX>0.5</AnchorPointX><AnchorPointY>0.5</AnchorPointY></AnchorPoint>'
      '<Displacement><DisplacementX>5</DisplacementX><DisplacementY>0</DisplacementY></Displacement>'
      '</PointPlacement></LabelPlacement></TextSymbolizer>')
code, log, body = run([], PU)
assert "<AnchorPointX>0.5</AnchorPointX>" in body and "<DisplacementX>5</DisplacementX>" in body, body

# maxAngleDelta already above the floor is left alone
LINE90 = ('<TextSymbolizer><LabelPlacement><LinePlacement/></LabelPlacement>'
          '<VendorOption name="maxAngleDelta">120</VendorOption></TextSymbolizer>')
code, log, body = run([], LINE90)
assert '"maxAngleDelta">120<' in body, body

# --check must not write sidecar SVGs
d = tempfile.mkdtemp()
p = os.path.join(d, "x.sld"); open(p, "w").write(B64)
subprocess.run([sys.executable, "-I", SCRIPT, "--check", p], capture_output=True)
assert not [x for x in os.listdir(d) if x.endswith(".svg")], "check must not write sidecars"

print("ok")
