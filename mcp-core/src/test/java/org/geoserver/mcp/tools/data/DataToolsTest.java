package org.geoserver.mcp.tools.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.geoserver.mcp.McpToolTestSupport;
import org.geoserver.mcp.StubHttpServer;
import org.geoserver.mcp.http.GeoServerHttpClient;
import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.geoserver.mcp.tool.Payloads;
import org.geoserver.mcp.tool.ToolException;
import org.junit.Test;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;


public class DataToolsTest extends McpToolTestSupport {

    DataTools tools(String base) {
        McpSettingsStore store = new McpSettingsStore(getGeoServer());
        store.save(new McpSettings(true, List.of("ROLE_ADMINISTRATOR"), false, base));
        return new DataTools(getCatalog(), new GeoServerHttpClient(getGeoServer(), store));
    }

    @Test
    public void queryFeaturesReturnsGeoJson() throws Exception {
        Object out = runRaw(tool(tools("").tools(), "gs_query_features"), ctx(), Map.of("layer", "cite:Lakes", "maxFeatures", 5));
        assertTrue(out instanceof Payloads.RawJson);
        JsonNode fc = json.readTree(((Payloads.RawJson) out).json());
        assertEquals("FeatureCollection", fc.get("type").asText());
        assertTrue(fc.get("features").size() > 0);
    }

    @Test
    public void queryFeaturesClampsMaxFeatures() throws Exception {
        for (int max : new int[] {0, 1000000}) {
            Object out = runRaw(tool(tools("").tools(), "gs_query_features"), ctx(), Map.of("layer", "cite:Lakes", "maxFeatures", max));
            JsonNode fc = json.readTree(((Payloads.RawJson) out).json());
            assertEquals("FeatureCollection", fc.get("type").asText());
            assertTrue(fc.get("features").size() >= 1);
        }
    }

    @Test
    public void queryFeaturesFlagsTruncationAtMaxFeatures() throws Exception {
        var t = tool(tools("").tools(), "gs_query_features");
        JsonNode cut = json.readTree(((Payloads.RawJson) runRaw(t, ctx(), Map.of("layer", "cite:Buildings", "maxFeatures", 1))).json());
        assertEquals(1, cut.get("features").size());
        assertEquals(1, cut.get("returned").asInt());
        assertTrue(cut.get("truncated").asBoolean());
        JsonNode all = json.readTree(((Payloads.RawJson) runRaw(t, ctx(), Map.of("layer", "cite:Buildings"))).json());
        assertEquals(2, all.get("returned").asInt());
        assertNull(all.get("truncated"));
    }

    @Test
    public void queryFeaturesStopsAtCharBudget() throws Exception {
        var fc = (org.geotools.data.simple.SimpleFeatureCollection) getCatalog().getFeatureTypeByName("cite:Buildings").getFeatureSource(null, null).getFeatures();
        JsonNode out = json.readTree(DataTools.featureCollection(fc, 20, 60));
        assertEquals(0, out.get("features").size());
        assertEquals(0, out.get("returned").asInt());
        assertTrue(out.get("truncated").asBoolean());
    }

    @Test
    public void queryFeaturesAppliesCqlAndProperties() throws Exception {
        Object out = runRaw(tool(tools("").tools(), "gs_query_features"), ctx(), Map.of(
                "layer", "Lakes", "cql", "NAME = 'Blue Lake'", "properties", List.of("NAME")));
        JsonNode fc = json.readTree(((Payloads.RawJson) out).json());
        assertEquals(1, fc.get("features").size());
        assertEquals("Blue Lake", fc.get("features").get(0).get("properties").get("NAME").asText());
    }

    @Test
    public void queryFeaturesBadCqlIsToolError() {
        var t = tool(tools("").tools(), "gs_query_features");
        Map<String, Object> args = Map.of("layer", "cite:Lakes", "cql", "NAME ==== 1");
        var context = ctx();
        ToolException e = assertThrows(ToolException.class, () -> runRaw(t, context, args));
        assertTrue(e.getMessage(), e.getMessage().startsWith("Invalid CQL"));
    }

    @Test
    public void queryFeaturesUnknownLayerIsToolError() {
        var t = tool(tools("").tools(), "gs_query_features");
        Map<String, Object> args = Map.of("layer", "nothing");
        var context = ctx();
        ToolException e = assertThrows(ToolException.class, () -> runRaw(t, context, args));
        assertEquals("Layer 'nothing' not found", e.getMessage());
    }

    @Test
    public void getMapReturnsImage() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0};
        try (StubHttpServer stub = new StubHttpServer(r -> new StubHttpServer.Reply(200, "image/png", png))) {
            var ctx = ctx();
            Object out = runRaw(tool(tools(stub.url()).tools(), "gs_get_map"), ctx, Map.of(
                    "layers", List.of("cite:Lakes"), "bbox", List.of(-180, -90, 180, 90), "width", 256, "height", 128));
            Payloads.ImagePayload img = (Payloads.ImagePayload) out;
            assertEquals("image/png", img.mimeType());
            assertEquals(8, img.bytes().length);
            String q = stub.received.get(0).query();
            assertTrue(q, q.contains("LAYERS=cite:Lakes") && q.contains("WIDTH=256") && q.contains("BBOX=-180.0,-90.0,180.0,90.0"));
            assertTrue(ctx.trace().lines().get(0).contains("/wms?"));
        }
    }

    @Test
    public void getMapRejectsHugeDimension() {
        var t = tool(tools("").tools(), "gs_get_map");
        Map<String, Object> args = Map.of(
                "layers", List.of("cite:Lakes"), "bbox", List.of(0, 0, 1, 1), "width", 100000, "height", 256);
        var context = ctx();
        ToolException e = assertThrows(ToolException.class, () -> runRaw(t, context, args));
        assertTrue(e.getMessage(), e.getMessage().contains("width must be between 1 and 8192"));
    }

    @Test
    public void getMapServiceExceptionIsToolError() throws Exception {
        String xml = "<ServiceExceptionReport><ServiceException>Could not find layer nope</ServiceException></ServiceExceptionReport>";
        try (StubHttpServer stub = new StubHttpServer(r -> StubHttpServer.Reply.of(200, "application/vnd.ogc.se_xml", xml))) {
            var t = tool(tools(stub.url()).tools(), "gs_get_map");
            Map<String, Object> args = Map.of(
                    "layers", List.of("nope"), "bbox", List.of(0, 0, 1, 1), "width", 10, "height", 10);
            var context = ctx();
            ToolException e = assertThrows(ToolException.class, () -> runRaw(t, context, args));
            assertTrue(e.getMessage(), e.getMessage().contains("Could not find layer nope"));
        }
    }

    static byte[] halfPaintedPng() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 2; x++) for (int y = 0; y < 4; y++) img.setRGB(x, y, 0xff0000ff);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    @Test
    public void checkLayerCountsPaintedPixelsAtNativeBbox() throws Exception {
        byte[] png = halfPaintedPng();
        try (StubHttpServer stub = new StubHttpServer(r -> new StubHttpServer.Reply(200, "image/png", png))) {
            var ctx = ctx();
            JsonNode r = json.readTree(json.writeValueAsString(runRaw(tool(tools(stub.url()).tools(), "gs_check_layer"), ctx, Map.of("name", "cite:Lakes"))));
            assertTrue(r.toString(), r.get("ok").asBoolean());
            assertEquals(200, r.get("status").asInt());
            assertEquals("image/png", r.get("contentType").asText());
            assertEquals(0.5, r.get("paintedRatio").asDouble(), 1e-9);
            assertTrue(r.get("elapsedMs").asLong() >= 0);
            assertNull(r.get("serviceException"));
            var bb = getCatalog().getLayerByName("cite:Lakes").getResource().getLatLonBoundingBox();
            String q = stub.received.get(0).query();
            assertTrue(q, q.contains("LAYERS=cite:Lakes") && q.contains("TRANSPARENT=true") && q.contains("SRS=EPSG:4326")
                    && q.contains("BBOX=" + bb.getMinX() + "," + bb.getMinY() + "," + bb.getMaxX() + "," + bb.getMaxY()));
            assertTrue(ctx.trace().lines().get(0).contains("/wms?"));
        }
    }

    @Test
    public void checkLayerReportsServiceExceptionAsText() throws Exception {
        String xml = "<ServiceExceptionReport><ServiceException>Property NOPE could not be found</ServiceException></ServiceExceptionReport>";
        try (StubHttpServer stub = new StubHttpServer(r -> StubHttpServer.Reply.of(200, "application/vnd.ogc.se_xml", xml))) {
            JsonNode r = json.readTree(json.writeValueAsString(runRaw(tool(tools(stub.url()).tools(), "gs_check_layer"), ctx(), Map.of(
                    "name", "cite:Lakes", "bbox", List.of(0, 0, 1, 1), "width", 10, "height", 20))));
            assertFalse(r.get("ok").asBoolean());
            assertTrue(r.get("serviceException").asText(), r.get("serviceException").asText().contains("Property NOPE could not be found"));
            assertNull(r.get("paintedRatio"));
            String q = stub.received.get(0).query();
            assertTrue(q, q.contains("BBOX=0.0,0.0,1.0,1.0") && q.contains("WIDTH=10") && q.contains("HEIGHT=20"));
        }
    }

    @Test
    public void checkLayerUnknownLayerIsToolError() {
        var t = tool(tools("").tools(), "gs_check_layer");
        Map<String, Object> args = Map.of("name", "nothing");
        var context = ctx();
        assertThrows(ToolException.class, () -> runRaw(t, context, args));
    }

    @Test
    public void renderSummaryKeepsOnlyTheVerdict() throws Exception {
        byte[] png = halfPaintedPng();
        try (StubHttpServer stub = new StubHttpServer(r -> new StubHttpServer.Reply(200, "image/png", png))) {
            Map<String, Object> r = tools(stub.url()).renderSummary(ctx(), getCatalog().getLayerByName("cite:Lakes"));
            assertEquals(Map.of("ok", true, "paintedRatio", 0.5), r);
            assertTrue(stub.received.get(0).query(), stub.received.get(0).query().contains("WIDTH=64"));
        }
        String xml = "<ServiceExceptionReport><ServiceException>boom</ServiceException></ServiceExceptionReport>";
        try (StubHttpServer stub = new StubHttpServer(r -> StubHttpServer.Reply.of(200, "application/vnd.ogc.se_xml", xml))) {
            Map<String, Object> r = tools(stub.url()).renderSummary(ctx(), getCatalog().getLayerByName("cite:Lakes"));
            assertEquals(false, r.get("ok"));
            assertEquals("boom", r.get("serviceException"));
        }
    }

    @Test
    public void renderSummaryWithoutBoundsSaysSo() {
        org.geoserver.catalog.LayerInfo layer = org.mockito.Mockito.mock(org.geoserver.catalog.LayerInfo.class);
        org.mockito.Mockito.when(layer.getResource()).thenReturn(org.mockito.Mockito.mock(org.geoserver.catalog.ResourceInfo.class));
        Map<String, Object> r = tools("").renderSummary(ctx(), layer);
        assertEquals(Map.of("ok", false, "error", "no lat/lon bounds"), r);
    }

    @Test
    public void renderSummaryReportsUnreachableServer() {
        Map<String, Object> r = tools("http://127.0.0.1:1").renderSummary(ctx(), getCatalog().getLayerByName("cite:Lakes"));
        assertEquals(false, r.get("ok"));
        assertNotNull(r.get("error"));
    }
}
