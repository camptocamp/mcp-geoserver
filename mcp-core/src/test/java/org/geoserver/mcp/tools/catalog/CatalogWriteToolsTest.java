package org.geoserver.mcp.tools.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.util.List;
import java.util.Map;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.mcp.McpToolTestSupport;
import org.geoserver.mcp.tool.ToolException;
import org.junit.Test;

public class CatalogWriteToolsTest extends McpToolTestSupport {

    static final String SLD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<StyledLayerDescriptor version=\"1.0.0\" xmlns=\"http://www.opengis.net/sld\" xmlns:ogc=\"http://www.opengis.net/ogc\">"
            + "<NamedLayer><Name>t</Name><UserStyle><FeatureTypeStyle><Rule><PolygonSymbolizer><Fill>"
            + "<CssParameter name=\"fill\">#ff0000</CssParameter></Fill></PolygonSymbolizer></Rule></FeatureTypeStyle>"
            + "</UserStyle></NamedLayer></StyledLayerDescriptor>";

    @Test
    public void createWorkspaceIsIdempotent() throws Exception {
        var t = tool(new WorkspaceTools(getCatalog()).tools(), "gs_create_workspace");
        JsonNode r = run(t, Map.of("name", "mcpws"));
        assertTrue(r.get("created").asBoolean());
        assertEquals("http://mcpws", r.get("namespaceUri").asText());
        assertNotNull(getCatalog().getNamespaceByPrefix("mcpws"));
        JsonNode again = run(t, Map.of("name", "mcpws"));
        assertFalse(again.get("created").asBoolean());
    }

    @Test
    public void createVectorStoreCoercesParams() throws Exception {
        run(tool(new WorkspaceTools(getCatalog()).tools(), "gs_create_workspace"), Map.of("name", "vec"));
        File dir = new File(getTestData().getDataDirectoryRoot(), "data/vec");
        dir.mkdirs();
        JsonNode r = run(tool(new StoreTools(getCatalog()).tools(), "gs_create_store"), Map.of(
                "workspace", "vec", "name", "props", "type", "Properties",
                "params", Map.of("directory", dir.getAbsolutePath(), "namespace", "http://vec")));
        assertTrue(r.get("created").asBoolean());
        assertEquals("vector", r.get("kind").asText());
        DataStoreInfo ds = getCatalog().getDataStoreByName("vec", "props");
        assertEquals(dir.getAbsolutePath(), String.valueOf(ds.getConnectionParameters().get("directory")));
        assertEquals("Properties", ds.getType());
    }

    @Test
    public void createVectorStoreRejectsUnknownKeyAndCreatesNothing() throws Exception {
        run(tool(new WorkspaceTools(getCatalog()).tools(), "gs_create_workspace"), Map.of("name", "unk"));
        var t = tool(new StoreTools(getCatalog()).tools(), "gs_create_store");
        ToolException e = assertThrows(ToolException.class, () -> run(t, Map.of(
                "workspace", "unk", "name", "props", "type", "Properties",
                "params", Map.of("directory", "/tmp", "bogus", "x"))));
        assertTrue(e.getMessage(), e.getMessage().contains("unknown keys [bogus]"));
        assertNull(getCatalog().getDataStoreByName("unk", "props"));
    }

    @Test
    public void createVectorStoreRejectsMissingRequiredKey() throws Exception {
        run(tool(new WorkspaceTools(getCatalog()).tools(), "gs_create_workspace"), Map.of("name", "mis"));
        var t = tool(new StoreTools(getCatalog()).tools(), "gs_create_store");
        ToolException e = assertThrows(ToolException.class, () -> run(t, Map.of(
                "workspace", "mis", "name", "props", "type", "Properties", "params", Map.of())));
        assertTrue(e.getMessage(), e.getMessage().contains("missing required keys [directory]"));
        assertNull(getCatalog().getDataStoreByName("mis", "props"));
    }

    @Test
    public void createRasterStoreAndPublish() throws Exception {
        CoverageStoreInfo existing = getCatalog().getCoverageStoreByName("wcs", "BlueMarble");
        String url = existing.getURL();
        run(tool(new WorkspaceTools(getCatalog()).tools(), "gs_create_workspace"), Map.of("name", "ras"));
        JsonNode r = run(tool(new StoreTools(getCatalog()).tools(), "gs_create_store"), Map.of(
                "workspace", "ras", "name", "bm", "type", "GeoTIFF", "params", Map.of("url", url)));
        assertEquals("raster", r.get("kind").asText());
        JsonNode l = run(tool(new LayerTools(getCatalog()).tools(), "gs_publish_layer"), Map.of(
                "workspace", "ras", "store", "bm", "name", "bluemarble"));
        assertEquals("ras:bluemarble", l.get("name").asText());
        assertEquals("raster", l.get("type").asText());
        assertNotNull(getCatalog().getLayerByName("ras:bluemarble"));
    }

    @Test
    public void unknownStoreTypeIsError() throws Exception {
        run(tool(new WorkspaceTools(getCatalog()).tools(), "gs_create_workspace"), Map.of("name", "bad"));
        var t = tool(new StoreTools(getCatalog()).tools(), "gs_create_store");
        ToolException e = assertThrows(ToolException.class, () -> run(t, Map.of(
                "workspace", "bad", "name", "x", "type", "NoSuchFormat", "params", Map.of())));
        assertTrue(e.getMessage(), e.getMessage().startsWith("Unknown store type 'NoSuchFormat'"));
    }

    @Test
    public void publishVectorLayerFromExistingStore() throws Exception {
        Catalog c = getCatalog();
        JsonNode l = run(tool(new LayerTools(c).tools(), "gs_publish_layer"), Map.of(
                "workspace", "cite", "store", "cite", "nativeName", "BasicPolygons",
                "name", "BasicPolygonsCopy", "title", "Basic"));
        assertEquals("cite:BasicPolygonsCopy", l.get("name").asText());
        assertEquals("Basic", l.get("title").asText());
        assertNotNull(l.get("latLonBoundingBox"));
    }

    @Test
    public void updateLayerTitleAndStyle() throws Exception {
        JsonNode l = run(tool(new LayerTools(getCatalog()).tools(), "gs_update_layer"), Map.of(
                "name", "cite:Lakes", "title", "Lakes!", "defaultStyle", "polygon", "enabled", false));
        assertEquals("Lakes!", l.get("title").asText());
        assertEquals("polygon", l.get("defaultStyle").asText());
        assertFalse(l.get("enabled").asBoolean());
    }

    @Test
    public void putStyleCreatesThenReplaces() throws Exception {
        var t = tool(new StyleTools(getCatalog()).tools(), "gs_put_style");
        JsonNode r = run(t, Map.of("name", "red", "workspace", "cite", "body", SLD));
        assertTrue(r.get("created").asBoolean());
        StyleInfo s = getCatalog().getStyleByName("cite", "red");
        assertNotNull(s);
        assertEquals("sld", s.getFormat());
        JsonNode again = run(t, Map.of("name", "red", "workspace", "cite", "body", SLD.replace("#ff0000", "#00ff00")));
        assertFalse(again.get("created").asBoolean());
        assertTrue(getCatalog().getResourcePool().readStyle(s).lines().anyMatch(line -> line.contains("#00ff00")));
    }

    @Test
    public void putStyleDetectsSld11WithSingleQuotes() throws Exception {
        String sld11 = "<?xml version=\"1.0\"?><StyledLayerDescriptor version = '1.1.0' xmlns=\"http://www.opengis.net/sld\""
                + " xmlns:se=\"http://www.opengis.net/se\"><NamedLayer><se:Name>t</se:Name><UserStyle><se:FeatureTypeStyle><se:Rule>"
                + "<se:PolygonSymbolizer><se:Fill><se:SvgParameter name=\"fill\">#ff0000</se:SvgParameter></se:Fill></se:PolygonSymbolizer>"
                + "</se:Rule></se:FeatureTypeStyle></UserStyle></NamedLayer></StyledLayerDescriptor>";
        run(tool(new StyleTools(getCatalog()).tools(), "gs_put_style"), Map.of("name", "v11", "workspace", "cite", "body", sld11));
        assertEquals("1.1.0", getCatalog().getStyleByName("cite", "v11").getFormatVersion().toString());
    }

    @Test
    public void putStyleRejectsMalformedSld() {
        var t = tool(new StyleTools(getCatalog()).tools(), "gs_put_style");
        assertThrows(ToolException.class, () -> run(t, Map.of("name", "broken", "body", "<StyledLayerDescriptor>")));
        assertNull(getCatalog().getStyleByName("broken"));
    }

    @Test
    public void putStyleUnknownFormatNamesInstalledFormats() {
        var t = tool(new StyleTools(getCatalog()).tools(), "gs_put_style");
        ToolException e = assertThrows(ToolException.class,
                () -> run(t, Map.of("name", "c", "body", "* { fill: red; }", "format", "nosuchformat")));
        assertTrue(e.getMessage(), e.getMessage().contains("sld"));
        assertNull(getCatalog().getStyleByName("c"));
    }

    @Test
    public void createPostgisStoreWarnsWhenCheckCannotRun() throws Exception {
        JsonNode r = run(tool(new StoreTools(getCatalog()).tools(), "gs_create_store"), Map.of(
                "workspace", "cite", "name", "pgdown", "type", "PostGIS",
                "params", Map.of("dbtype", "postgis", "host", "127.0.0.1", "port", 1, "database", "db",
                        "user", "bob", "passwd", "secret", "schema", "public")));
        assertTrue(r.get("created").asBoolean());
        assertNotNull(getCatalog().getDataStoreByName("cite", "pgdown"));
        assertTrue(r.get("warnings").toString(), r.get("warnings").get(0).asText().startsWith("Privilege check skipped"));
        assertEquals("********", r.get("connectionParameters").get("passwd").asText());
    }

    @Test
    public void createLayerGroupWithStyles() throws Exception {
        JsonNode g = run(tool(new LayerGroupTools(getCatalog()).tools(), "gs_create_layer_group"), Map.of(
                "name", "grp", "workspace", "cite",
                "layers", List.of("cite:Lakes", "cite:BasicPolygons"), "styles", List.of("polygon", "")));
        assertEquals(2, g.get("layers").size());
        assertEquals("polygon", g.get("layers").get(0).get("style").asText());
        assertNotNull(g.get("bounds"));
        assertNotNull(getCatalog().getLayerGroupByName("cite", "grp"));
    }

    @Test
    public void deleteLayerThenStoreRecursively() throws Exception {
        String url = getCatalog().getCoverageStoreByName("wcs", "BlueMarble").getURL();
        run(tool(new WorkspaceTools(getCatalog()).tools(), "gs_create_workspace"), Map.of("name", "del"));
        run(tool(new StoreTools(getCatalog()).tools(), "gs_create_store"), Map.of(
                "workspace", "del", "name", "bm", "type", "GeoTIFF", "params", Map.of("url", url)));
        var publish = tool(new LayerTools(getCatalog()).tools(), "gs_publish_layer");
        run(publish, Map.of("workspace", "del", "store", "bm", "name", "one"));
        run(publish, Map.of("workspace", "del", "store", "bm", "name", "two"));
        var t = tool(new AdminTools(getCatalog(), getGeoServer()).tools(), "gs_delete");
        run(t, Map.of("kind", "layer", "name", "del:one"));
        assertNull(getCatalog().getLayerByName("del:one"));
        assertNull(getCatalog().getCoverageByName("del", "one"));
        ToolException e = assertThrows(ToolException.class, () -> run(t, Map.of("kind", "store", "workspace", "del", "name", "bm")));
        assertTrue(e.getMessage(), e.getMessage().contains("recurse"));
        run(t, Map.of("kind", "store", "workspace", "del", "name", "bm", "recurse", true));
        assertNull(getCatalog().getCoverageStoreByName("del", "bm"));
        assertNull(getCatalog().getLayerByName("del:two"));
    }

    @Test
    public void reloadRunsWithoutError() throws Exception {
        JsonNode r = run(tool(new AdminTools(getCatalog(), getGeoServer()).tools(), "gs_reload"), Map.of());
        assertTrue(r.get("reloaded").asBoolean());
    }

    @Test
    public void publishLayerIsIdempotent() throws Exception {
        var publish = tool(new LayerTools(getCatalog()).tools(), "gs_publish_layer");
        Map<String, Object> args = Map.of("workspace", "cite", "store", "cite", "nativeName", "Buildings", "name", "BuildingsOnce");
        assertTrue(run(publish, args).get("created").asBoolean());
        int layers = getCatalog().getLayers().size();
        JsonNode again = run(publish, args);
        assertFalse(again.get("created").asBoolean());
        assertEquals("cite:BuildingsOnce", again.get("name").asText());
        assertEquals(layers, getCatalog().getLayers().size());
    }

    @Test
    public void createLayerGroupIsIdempotent() throws Exception {
        var create = tool(new LayerGroupTools(getCatalog()).tools(), "gs_create_layer_group");
        Map<String, Object> args = Map.of("name", "grpOnce", "workspace", "cite", "layers", List.of("cite:Lakes"));
        assertTrue(run(create, args).get("created").asBoolean());
        int groups = getCatalog().getLayerGroups().size();
        JsonNode again = run(create, args);
        assertFalse(again.get("created").asBoolean());
        assertEquals(groups, getCatalog().getLayerGroups().size());
    }
}
