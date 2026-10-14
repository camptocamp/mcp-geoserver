package org.geoserver.mcp.tools.catalog;

import static org.junit.Assert.assertEquals;
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
        ToolException e = assertThrows(ToolException.class, () -> run(t, Map.of("name", "nope:nothing")));
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
}
