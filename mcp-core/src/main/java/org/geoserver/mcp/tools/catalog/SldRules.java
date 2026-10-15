package org.geoserver.mcp.tools.catalog;

import java.util.List;

final class SldRules {
    enum Geometry { POINT, LINE, POLYGON }

    record Rule(String title, String filter, String color) {}

    private static final String PROPERTY = "<ogc:PropertyName>%s</ogc:PropertyName>";
    private static final String STROKE = "<CssParameter name=\"stroke\">#333333</CssParameter><CssParameter name=\"stroke-width\">0.5</CssParameter>";

    private SldRules() {}

    static String equalTo(String attribute, Object value) {
        if (value == null) return "<ogc:PropertyIsNull>" + PROPERTY.formatted(xml(attribute)) + "</ogc:PropertyIsNull>";
        return "<ogc:PropertyIsEqualTo>" + PROPERTY.formatted(xml(attribute)) + literal(value) + "</ogc:PropertyIsEqualTo>";
    }

    static String range(String attribute, Object min, Object max, boolean includeMax) {
        String upper = includeMax ? "PropertyIsLessThanOrEqualTo" : "PropertyIsLessThan";
        return "<ogc:And><ogc:PropertyIsGreaterThanOrEqualTo>" + PROPERTY.formatted(xml(attribute)) + literal(min)
                + "</ogc:PropertyIsGreaterThanOrEqualTo><ogc:" + upper + ">" + PROPERTY.formatted(xml(attribute)) + literal(max)
                + "</ogc:" + upper + "></ogc:And>";
    }

    static String document(String name, Geometry geometry, List<Rule> rules) {
        StringBuilder sld = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<StyledLayerDescriptor version=\"1.0.0\" xmlns=\"http://www.opengis.net/sld\" xmlns:ogc=\"http://www.opengis.net/ogc\">"
                + "<NamedLayer><Name>" + xml(name) + "</Name><UserStyle><Title>" + xml(name) + "</Title><FeatureTypeStyle>");
        for (Rule r : rules) {
            sld.append("<Rule><Name>").append(xml(r.title())).append("</Name><Title>").append(xml(r.title())).append("</Title>")
                    .append("<ogc:Filter>").append(r.filter()).append("</ogc:Filter>").append(symbolizer(geometry, r.color())).append("</Rule>");
        }
        return sld.append("</FeatureTypeStyle></UserStyle></NamedLayer></StyledLayerDescriptor>").toString();
    }

    static String symbolizer(Geometry geometry, String color) {
        String fill = "<Fill><CssParameter name=\"fill\">" + color + "</CssParameter></Fill>";
        return switch (geometry) {
            case POINT -> "<PointSymbolizer><Graphic><Mark><WellKnownName>circle</WellKnownName>" + fill + "<Stroke>" + STROKE + "</Stroke></Mark><Size>8</Size></Graphic></PointSymbolizer>";
            case LINE -> "<LineSymbolizer><Stroke><CssParameter name=\"stroke\">" + color + "</CssParameter><CssParameter name=\"stroke-width\">2</CssParameter></Stroke></LineSymbolizer>";
            case POLYGON -> "<PolygonSymbolizer>" + fill + "<Stroke>" + STROKE + "</Stroke></PolygonSymbolizer>";
        };
    }

    static String literal(Object value) {
        return "<ogc:Literal>" + xml(String.valueOf(value)) + "</ogc:Literal>";
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
