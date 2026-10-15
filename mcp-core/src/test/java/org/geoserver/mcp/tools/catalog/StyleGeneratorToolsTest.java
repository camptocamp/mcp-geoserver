package org.geoserver.mcp.tools.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.data.test.SystemTestData;
import org.geoserver.mcp.McpToolTestSupport;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.ToolException;
import org.junit.Test;

public class StyleGeneratorToolsTest extends McpToolTestSupport {
    static final String ZONES = "_=geom:Polygon:srid=4326,kind:String,pop:Integer\n"
            + "z.1=POLYGON ((0 0, 1 0, 1 1, 0 0))|forest|10\n"
            + "z.2=POLYGON ((0 0, 1 0, 1 1, 0 0))|water|20\n"
            + "z.3=POLYGON ((0 0, 1 0, 1 1, 0 0))|forest|30\n"
            + "z.4=POLYGON ((0 0, 1 0, 1 1, 0 0))|urban|40\n"
            + "z.5=POLYGON ((0 0, 1 0, 1 1, 0 0))|water|50\n"
            + "z.6=POLYGON ((0 0, 1 0, 1 1, 0 0))|urban|60\n";

    @Override
    protected void onSetUp(SystemTestData testData) throws Exception {
        super.onSetUp(testData);
        File dir = new File(getTestData().getDataDirectoryRoot(), "data/zones");
        dir.mkdirs();
        Files.writeString(new File(dir, "Zones.properties").toPath(), ZONES, StandardCharsets.UTF_8);
        run(tool(new StoreTools(getCatalog()).tools(), "gs_create_store"), Map.of(
                "workspace", "cite", "name", "zones", "type", "Properties", "params", Map.of("directory", dir.getAbsolutePath())));
        run(tool(new LayerTools(getCatalog()).tools(), "gs_publish_layer"), Map.of("workspace", "cite", "store", "zones", "nativeName", "Zones"));
    }

    GsTool generate() {
        return tool(new StyleGeneratorTools(getCatalog()).tools(), "gs_generate_style");
    }

    @Test
    public void categorizedStoresOneRulePerValueAndSetsDefault() throws Exception {
        JsonNode r = run(generate(), Map.of("layer", "cite:Zones", "attribute", "kind", "method", "categorized", "setDefault", true));
        assertEquals(3, r.get("rules").size());
        assertEquals("forest", r.get("rules").get(0).get("title").asText());
        StyleInfo style = getCatalog().getStyleByName("Zones_kind_categorized");
        assertEquals(style.getName(), getCatalog().getLayerByName("cite:Zones").getDefaultStyle().getName());
        assertTrue(r.get("sld").asText().contains("<ogc:Literal>water</ogc:Literal>"));
    }

    @Test
    public void numericClassesCoverTheRange() throws Exception {
        JsonNode r = run(generate(), Map.of("layer", "cite:Zones", "attribute", "pop", "method", "equalInterval", "classes", 3,
                "ramp", "reds", "name", "zones_pop", "workspace", "cite"));
        assertEquals(3, r.get("rules").size());
        assertTrue(r.get("rules").get(0).get("title").asText(), r.get("rules").get(0).get("title").asText().startsWith("10"));
        assertTrue(r.get("sld").asText().contains("PropertyIsLessThanOrEqualTo"));
        assertEquals("zones_pop", getCatalog().getStyleByName("cite", "zones_pop").getName());
    }

    @Test
    public void badRequestsAreRejected() throws Exception {
        GsTool generate = generate();
        Map<String, Object> text = Map.of("layer", "cite:Zones", "attribute", "kind", "method", "quantile");
        assertTrue(assertThrows(ToolException.class, () -> run(generate, text)).getMessage().contains("not numeric"));
        Map<String, Object> missing = Map.of("layer", "cite:Zones", "attribute", "nope", "method", "categorized");
        assertThrows(ToolException.class, () -> run(generate, missing));
        Map<String, Object> raster = Map.of("layer", "wcs:World", "attribute", "x", "method", "categorized");
        assertTrue(assertThrows(ToolException.class, () -> run(generate, raster)).getMessage().contains("not a vector layer"));
        Map<String, Object> tooMany = Map.of("layer", "cite:Zones", "attribute", "pop", "method", "jenks", "classes", 30);
        assertThrows(ToolException.class, () -> run(generate, tooMany));
    }
}
