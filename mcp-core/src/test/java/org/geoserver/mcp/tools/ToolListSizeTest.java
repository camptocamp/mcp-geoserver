package org.geoserver.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.geoserver.catalog.Catalog;
import org.geoserver.config.GeoServer;
import org.geoserver.mcp.http.GeoServerHttpClient;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.geoserver.mcp.tool.ToolAdapter;
import org.junit.jupiter.api.Test;

class ToolListSizeTest {
    // ~2.7 chars per token for these JSON schemas (measured); raise deliberately when a new tool earns its place.
    static final int MAX_TOOLS_LIST_CHARS = 22_000;

    @Test
    void toolsListStaysUnderBudget() throws Exception {
        GeoServer geoServer = mock(GeoServer.class);
        McpSettingsStore settings = new McpSettingsStore(geoServer);
        ObjectMapper json = new ObjectMapper().setDefaultPropertyInclusion(
                JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));
        List<Object> tools = ToolRegistry.all(mock(Catalog.class), geoServer, new GeoServerHttpClient(geoServer, settings), json).stream()
                .map(t -> (Object) ToolAdapter.spec(t, settings, json).tool())
                .toList();
        int chars = json.writeValueAsString(tools).length();
        assertTrue(chars <= MAX_TOOLS_LIST_CHARS, "tools/list is " + chars + " chars, budget " + MAX_TOOLS_LIST_CHARS);
    }
}
