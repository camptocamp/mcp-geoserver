package org.geoserver.mcp.tools.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.geoserver.mcp.tool.ToolException;
import org.junit.jupiter.api.Test;

class StyleBuildingTest {
    @Test
    void categoricalRampCyclesAndSequentialRampSpansEnds() {
        List<String> cat = ColorRamps.colors("categorical", 12);
        assertEquals(cat.get(0), cat.get(10));
        List<String> blues = ColorRamps.colors("blues", 3);
        assertEquals(List.of("#deebf7", blues.get(1), "#08306b"), blues);
        assertEquals(List.of("#440154"), ColorRamps.colors("viridis", 1));
        assertThrows(ToolException.class, () -> ColorRamps.colors("rainbow", 3));
    }

    @Test
    void filtersEscapeAndHandleNullAndLastClass() {
        assertEquals("<ogc:PropertyIsNull><ogc:PropertyName>kind</ogc:PropertyName></ogc:PropertyIsNull>", SldRules.equalTo("kind", null));
        assertTrue(SldRules.equalTo("kind", "a<b&c").contains("<ogc:Literal>a&lt;b&amp;c</ogc:Literal>"));
        assertTrue(SldRules.range("pop", 1, 5, false).contains("PropertyIsLessThan>"));
        assertTrue(SldRules.range("pop", 1, 5, true).contains("PropertyIsLessThanOrEqualTo"));
    }

    @Test
    void symbolizerFollowsGeometry() {
        assertTrue(SldRules.symbolizer(SldRules.Geometry.POINT, "#000000").startsWith("<PointSymbolizer>"));
        assertTrue(SldRules.symbolizer(SldRules.Geometry.LINE, "#000000").startsWith("<LineSymbolizer>"));
        String doc = SldRules.document("s", SldRules.Geometry.POLYGON, List.of(new SldRules.Rule("A", SldRules.equalTo("k", "A"), "#ff0000")));
        assertTrue(doc.contains("<PolygonSymbolizer><Fill><CssParameter name=\"fill\">#ff0000</CssParameter>"), doc);
    }
}
