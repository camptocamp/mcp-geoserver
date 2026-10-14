package org.geoserver.mcp.tools.data;

import static org.junit.Assert.assertEquals;
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
        ToolException e = assertThrows(ToolException.class, () -> runRaw(t, ctx(), Map.of("layer", "cite:Lakes", "cql", "NAME ==== 1")));
        assertTrue(e.getMessage(), e.getMessage().startsWith("Invalid CQL"));
    }

    @Test
    public void queryFeaturesUnknownLayerIsToolError() {
        var t = tool(tools("").tools(), "gs_query_features");
        ToolException e = assertThrows(ToolException.class, () -> runRaw(t, ctx(), Map.of("layer", "nothing")));
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
    public void getMapServiceExceptionIsToolError() throws Exception {
        String xml = "<ServiceExceptionReport><ServiceException>Could not find layer nope</ServiceException></ServiceExceptionReport>";
        try (StubHttpServer stub = new StubHttpServer(r -> StubHttpServer.Reply.of(200, "application/vnd.ogc.se_xml", xml))) {
            var t = tool(tools(stub.url()).tools(), "gs_get_map");
            ToolException e = assertThrows(ToolException.class, () -> runRaw(t, ctx(), Map.of(
                    "layers", List.of("nope"), "bbox", List.of(0, 0, 1, 1), "width", 10, "height", 10)));
            assertTrue(e.getMessage(), e.getMessage().contains("Could not find layer nope"));
        }
    }
}
