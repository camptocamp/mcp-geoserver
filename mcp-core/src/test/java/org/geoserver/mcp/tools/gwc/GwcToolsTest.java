package org.geoserver.mcp.tools.gwc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.geoserver.config.GeoServer;
import org.geoserver.config.GeoServerInfo;
import org.geoserver.config.SettingsInfo;
import org.geoserver.mcp.StubHttpServer;
import org.geoserver.mcp.http.GeoServerHttpClient;
import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.CommandTrace;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.ToolContext;
import org.junit.jupiter.api.Test;

class GwcToolsTest {
    ObjectMapper json = new ObjectMapper();

    GwcTools tools(String base) {
        GeoServer gs = mock(GeoServer.class);
        GeoServerInfo global = mock(GeoServerInfo.class);
        SettingsInfo si = mock(SettingsInfo.class);
        when(gs.getGlobal()).thenReturn(global);
        when(global.getSettings()).thenReturn(si);
        McpSettingsStore store = mock(McpSettingsStore.class);
        when(store.load()).thenReturn(new McpSettings(true, List.of("ROLE_ADMINISTRATOR"), false, base));
        return new GwcTools(new GeoServerHttpClient(gs, store), json);
    }

    GsTool tool(GwcTools t, String name) {
        return t.tools().stream().filter(x -> x.definition().name().equals(name)).findFirst().orElseThrow();
    }

    JsonNode run(GwcTools t, String name, Map<String, Object> args) throws Exception {
        Object out = tool(t, name).call(new ToolContext(false, new CommandTrace(), "Basic abc", ""), new Args(args));
        return json.readTree(json.writeValueAsString(out));
    }

    @Test
    void listTileLayers() throws Exception {
        try (StubHttpServer stub = new StubHttpServer(r -> StubHttpServer.Reply.json("[\"topp:states\",\"sf:roads\"]"))) {
            JsonNode r = run(tools(stub.url()), "gs_gwc_list_tile_layers", Map.of());
            assertEquals(2, r.get("tileLayers").size());
            assertEquals("/geoserver/gwc/rest/layers.json", stub.received.get(0).path());
        }
    }

    @Test
    void getTileLayerNormalises() throws Exception {
        String body = "{\"GeoServerLayer\":{\"name\":\"topp:states\",\"enabled\":true,\"mimeFormats\":[\"image/png\"],"
                + "\"gridSubsets\":[{\"gridSetName\":\"EPSG:900913\",\"zoomStart\":0,\"zoomStop\":14}]}}";
        try (StubHttpServer stub = new StubHttpServer(r -> StubHttpServer.Reply.json(body))) {
            JsonNode r = run(tools(stub.url()), "gs_gwc_get_tile_layer", Map.of("layer", "topp:states"));
            assertEquals("topp:states", r.get("name").asText());
            assertEquals("EPSG:900913", r.get("gridSubsets").get(0).get("gridSetName").asText());
            assertEquals("/geoserver/gwc/rest/layers/topp:states.json", stub.received.get(0).path());
        }
    }

    @Test
    void setTileLayerPostsMergedDocument() throws Exception {
        String current = "{\"GeoServerLayer\":{\"name\":\"topp:states\",\"enabled\":true,\"mimeFormats\":[\"image/png\"],"
                + "\"gridSubsets\":[{\"gridSetName\":\"EPSG:900913\"}]}}";
        try (StubHttpServer stub = new StubHttpServer(r -> r.method().equals("GET")
                ? StubHttpServer.Reply.json(current) : StubHttpServer.Reply.of(200, "text/plain", ""))) {
            JsonNode r = run(tools(stub.url()), "gs_gwc_set_tile_layer", Map.of(
                    "layer", "topp:states", "enabled", false, "gridsets", List.of("EPSG:4326"), "formats", List.of("image/png", "image/jpeg")));
            assertTrue(r.get("updated").asBoolean());
            StubHttpServer.Received post = stub.received.get(1);
            assertEquals("POST", post.method());
            JsonNode sent = json.readTree(post.body()).get("GeoServerLayer");
            assertEquals(false, sent.get("enabled").asBoolean());
            assertEquals("EPSG:4326", sent.get("gridSubsets").get(0).get("gridSetName").asText());
            assertEquals(2, sent.get("mimeFormats").size());
        }
    }

    @Test
    void seedPostsSeedRequest() throws Exception {
        try (StubHttpServer stub = new StubHttpServer(r -> StubHttpServer.Reply.of(200, "text/plain", ""))) {
            JsonNode r = run(tools(stub.url()), "gs_gwc_seed", Map.of(
                    "layer", "topp:states", "gridset", "EPSG:4326", "zoomStart", 0, "zoomStop", 4,
                    "format", "image/png", "operation", "reseed", "threads", 2));
            assertTrue(r.get("submitted").asBoolean());
            JsonNode sent = json.readTree(stub.received.get(0).body()).get("seedRequest");
            assertEquals("reseed", sent.get("type").asText());
            assertEquals("EPSG:4326", sent.get("gridSetId").asText());
            assertEquals(2, sent.get("threadCount").asInt());
            assertEquals("/geoserver/gwc/rest/seed/topp:states.json", stub.received.get(0).path());
        }
    }

    @Test
    void listTasksDecodesLongArrays() throws Exception {
        try (StubHttpServer stub = new StubHttpServer(r -> StubHttpServer.Reply.json("{\"long-array-array\":[[100,1000,30,7,1],[0,50,0,8,0]]}"))) {
            JsonNode r = run(tools(stub.url()), "gs_gwc_list_tasks", Map.of());
            assertEquals(2, r.get("tasks").size());
            JsonNode t = r.get("tasks").get(0);
            assertEquals(7, t.get("taskId").asInt());
            assertEquals("RUNNING", t.get("status").asText());
            assertEquals(100, t.get("tilesDone").asInt());
        }
    }

    @Test
    void killTasksPostsForm() throws Exception {
        try (StubHttpServer stub = new StubHttpServer(r -> StubHttpServer.Reply.of(200, "text/plain", "")) ) {
            run(tools(stub.url()), "gs_gwc_kill_tasks", Map.of("layer", "topp:states"));
            StubHttpServer.Received p = stub.received.get(0);
            assertEquals("/geoserver/gwc/rest/seed/topp:states", p.path());
            assertEquals("kill_all=all", p.body());
        }
    }

    @Test
    void gridsetsListed() throws Exception {
        try (StubHttpServer stub = new StubHttpServer(r -> StubHttpServer.Reply.json("[\"EPSG:4326\",\"EPSG:900913\"]"))) {
            JsonNode r = run(tools(stub.url()), "gs_gwc_list_gridsets", Map.of());
            assertEquals(2, r.get("gridsets").size());
        }
    }

    @Test
    void httpErrorIsToolError() throws Exception {
        try (StubHttpServer stub = new StubHttpServer(r -> StubHttpServer.Reply.of(404, "text/plain", "Unknown layer: x"))) {
            var e = org.junit.jupiter.api.Assertions.assertThrows(org.geoserver.mcp.tool.ToolException.class,
                    () -> run(tools(stub.url()), "gs_gwc_get_tile_layer", Map.of("layer", "x")));
            assertTrue(e.getMessage().contains("404"), e.getMessage());
            assertTrue(e.getMessage().contains("Unknown layer: x"), e.getMessage());
        }
    }
}
