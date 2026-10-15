package org.geoserver.mcp.tools.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CatalogBuilder;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.LayerGroupInfo;
import org.geoserver.data.test.SystemTestData;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.mcp.McpToolTestSupport;
import org.geoserver.mcp.tool.ToolException;
import org.junit.Test;

public class CatalogReadToolsTest extends McpToolTestSupport {

    @Override
    protected void onSetUp(SystemTestData testData) throws Exception {
        super.onSetUp(testData);
        Catalog c = getCatalog();
        LayerGroupInfo lg = c.getFactory().createLayerGroup();
        lg.setName("basic-group");
        lg.getLayers().add(c.getLayerByName("cite:BasicPolygons"));
        lg.getStyles().add(null);
        c.add(lg);
    }

    List<String> names(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }

    @Test
    public void listWorkspaces() throws Exception {
        JsonNode r = run(tool(new WorkspaceTools(getCatalog()).tools(), "gs_list_workspaces"), Map.of());
        assertTrue(names(r.get("workspaces"), "name").contains("cite"));
    }

    @Test
    public void getWorkspace() throws Exception {
        JsonNode r = run(tool(new WorkspaceTools(getCatalog()).tools(), "gs_get_workspace"), Map.of("name", "cite"));
        assertEquals("cite", r.get("name").asText());
        assertTrue(r.get("namespaceUri").asText().startsWith("http"));
        assertTrue(r.get("layerCount").asInt() > 0);
    }

    @Test
    public void listStoresFilteredByWorkspace() throws Exception {
        JsonNode r = run(tool(new StoreTools(getCatalog()).tools(), "gs_list_stores"), Map.of("workspace", "wcs"));
        assertTrue(names(r.get("stores"), "name").contains("BlueMarble"));
        r.get("stores").forEach(s -> assertEquals("wcs", s.get("workspace").asText()));
    }

    @Test
    public void getStoreMasksPassword() throws Exception {
        Catalog c = getCatalog();
        CatalogBuilder b = new CatalogBuilder(c);
        b.setWorkspace(c.getWorkspaceByName("cite"));
        DataStoreInfo ds = b.buildDataStore("pg");
        ds.setType("PostGIS");
        ds.getConnectionParameters().putAll(Map.of("dbtype", "postgis", "host", "nowhere", "database", "db", "user", "bob", "passwd", "secret"));
        c.add(ds);
        JsonNode r = run(tool(new StoreTools(getCatalog()).tools(), "gs_get_store"), Map.of("workspace", "cite", "name", "pg"));
        assertEquals("vector", r.get("kind").asText());
        assertEquals("********", r.get("connectionParameters").get("passwd").asText());
        assertEquals("bob", r.get("connectionParameters").get("user").asText());
    }

    @Test
    public void listLayers() throws Exception {
        JsonNode r = run(tool(new LayerTools(getCatalog()).tools(), "gs_list_layers"), Map.of("workspace", "cite"));
        assertTrue(names(r.get("layers"), "name").contains("cite:BasicPolygons"));
    }

    @Test
    public void getLayerQualifiedAndPlain() throws Exception {
        var t = tool(new LayerTools(getCatalog()).tools(), "gs_get_layer");
        JsonNode q = run(t, Map.of("name", "cite:BasicPolygons"));
        assertEquals("EPSG:4326", q.get("srs").asText());
        assertEquals("vector", q.get("type").asText());
        assertTrue(q.get("attributes").size() > 0);
        JsonNode p = run(t, Map.of("name", "BasicPolygons"));
        assertEquals("cite:BasicPolygons", p.get("name").asText());
    }

    @Test
    public void getLayerUnknownIsToolError() {
        var t = tool(new LayerTools(getCatalog()).tools(), "gs_get_layer");
        Map<String, Object> args = Map.of("name", "nope:nothing");
        ToolException e = assertThrows(ToolException.class, () -> run(t, args));
        assertEquals("Layer 'nope:nothing' not found", e.getMessage());
    }

    @Test
    public void getRasterLayer() throws Exception {
        JsonNode r = run(tool(new LayerTools(getCatalog()).tools(), "gs_get_layer"), Map.of("name", "wcs:BlueMarble"));
        assertEquals("raster", r.get("type").asText());
    }

    @Test
    public void listAndGetStyles() throws Exception {
        var tools = new StyleTools(getCatalog()).tools();
        JsonNode list = run(tool(tools, "gs_list_styles"), Map.of());
        assertTrue(names(list.get("styles"), "name").contains("polygon"));
        JsonNode s = run(tool(tools, "gs_get_style"), Map.of("name", "polygon"));
        assertTrue(s.get("body").asText().contains("StyledLayerDescriptor"));
    }

    @Test
    public void listAndGetLayerGroups() throws Exception {
        var tools = new LayerGroupTools(getCatalog()).tools();
        JsonNode list = run(tool(tools, "gs_list_layer_groups"), Map.of());
        assertTrue(names(list.get("layerGroups"), "name").contains("basic-group"));
        JsonNode g = run(tool(tools, "gs_get_layer_group"), Map.of("name", "basic-group"));
        assertEquals("cite:BasicPolygons", g.get("layers").get(0).get("name").asText());
    }

    @Test
    public void statusReportsCounts() throws Exception {
        JsonNode r = run(tool(new AdminTools(getCatalog(), getGeoServer()).tools(), "gs_status"), Map.of());
        assertTrue(r.get("layerCount").asInt() > 0);
        assertTrue(r.has("mcpVersion"));
    }

    @Test
    public void readToolsRecordCurl() throws Exception {
        var ctx = ctx();
        runRaw(tool(new WorkspaceTools(getCatalog()).tools(), "gs_list_workspaces"), ctx, Map.of());
        assertEquals(1, ctx.trace().lines().size());
        assertTrue(ctx.trace().lines().get(0).endsWith("'/rest/workspaces.json'"));
    }

    static final String SLD10 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<StyledLayerDescriptor version=\"1.0.0\" xmlns=\"http://www.opengis.net/sld\" xmlns:ogc=\"http://www.opengis.net/ogc\""
            + " xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
            + "<NamedLayer><Name>t</Name><UserStyle><FeatureTypeStyle><Rule>"
            + "<ogc:Filter><ogc:PropertyIsEqualTo><ogc:PropertyName>NAME</ogc:PropertyName><ogc:Literal>Blue Lake</ogc:Literal></ogc:PropertyIsEqualTo></ogc:Filter>"
            + "<PointSymbolizer><Graphic><ExternalGraphic><OnlineResource xlink:type=\"simple\" xlink:href=\"HREF\"/>"
            + "<Format>image/svg+xml</Format></ExternalGraphic><Size>8</Size></Graphic></PointSymbolizer>"
            + "<TextSymbolizer><Label><ogc:PropertyName>LABELFIELD</ogc:PropertyName></Label></TextSymbolizer>"
            + "</Rule></FeatureTypeStyle></UserStyle></NamedLayer></StyledLayerDescriptor>";

    JsonNode validate(Map<String, Object> args) throws Exception {
        return run(tool(new StyleTools(getCatalog()).tools(), "gs_validate_style"), args);
    }

    @Test
    public void validateStyleReportsMissingAttributesAndUnresolvedGraphic() throws Exception {
        JsonNode r = validate(Map.of("body", SLD10.replace("HREF", "missing.svg").replace("LABELFIELD", "NOPE"),
                "workspace", "cite", "layer", "cite:Lakes"));
        assertEquals("1.0.0", r.get("version").asText());
        assertFalse(r.get("valid").asBoolean());
        assertEquals(List.of("NOPE"), names(r.get("missingAttributes")));
        assertEquals(List.of("missing.svg"), names(r.get("unresolvedGraphics")));
        assertEquals(0, r.get("unknownFunctions").size());
        assertNull(r.get("parseError"));
    }

    @Test
    public void validateStyleResolvesGraphicInWorkspaceStyleDir() throws Exception {
        WorkspaceInfo ws = getCatalog().getWorkspaceByName("cite");
        try (var out = getDataDirectory().getStyles(ws, "ok.svg").out()) {
            out.write("<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        JsonNode r = validate(Map.of("body", SLD10.replace("HREF", "ok.svg?fill=red").replace("LABELFIELD", "NAME"),
                "workspace", "cite", "layer", "cite:Lakes"));
        assertTrue(r.toString(), r.get("valid").asBoolean());
        assertEquals(0, r.get("unresolvedGraphics").size());
        assertEquals(0, r.get("missingAttributes").size());
    }

    @Test
    public void validateStyleFlagsAbsolutePathAndSkipsHttp() throws Exception {
        JsonNode r = validate(Map.of("body", SLD10.replace("HREF", "/no/such dir/icon.svg").replace("LABELFIELD", "NAME")));
        assertEquals(List.of("/no/such dir/icon.svg"), names(r.get("unresolvedGraphics")));
        JsonNode h = validate(Map.of("body", SLD10.replace("HREF", "http://example.com/x.svg").replace("LABELFIELD", "NAME")));
        assertEquals(0, h.get("unresolvedGraphics").size());
        assertNull(h.get("missingAttributes"));
    }

    @Test
    public void validateStyleNamesUnknownFunctionsEvenWhenParseFails() throws Exception {
        String body = SLD10.replace("<ogc:PropertyName>LABELFIELD</ogc:PropertyName>",
                "<ogc:Function name=\"array_to_string\"><ogc:PropertyName>NAME</ogc:PropertyName></ogc:Function>").replace("HREF", "x.svg");
        JsonNode r = validate(Map.of("body", body));
        assertFalse(r.get("valid").asBoolean());
        assertEquals(List.of("array_to_string"), names(r.get("unknownFunctions")));
    }

    @Test
    public void validateStyleReportsParseErrorWithoutThrowing() throws Exception {
        JsonNode r = validate(Map.of("body", "<StyledLayerDescriptor>"));
        assertFalse(r.get("valid").asBoolean());
        assertTrue(r.get("parseError").asText(), r.get("parseError").asText().length() > 0);
    }

    @Test
    public void validateStyleByNameReadsStoredBody() throws Exception {
        JsonNode r = validate(Map.of("name", "Lakes", "layer", "cite:Lakes"));
        assertTrue(r.toString(), r.get("valid").asBoolean());
        assertEquals(0, r.get("missingAttributes").size());
    }

    @Test
    public void validateStyleNeedsBodyOrName() {
        var t = tool(new StyleTools(getCatalog()).tools(), "gs_validate_style");
        Map<String, Object> args = Map.of("workspace", "cite");
        assertThrows(ToolException.class, () -> run(t, args));
    }

    List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
