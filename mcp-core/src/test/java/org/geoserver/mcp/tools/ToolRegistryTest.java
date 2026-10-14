package org.geoserver.mcp.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.geoserver.mcp.McpToolTestSupport;
import org.geoserver.mcp.http.GeoServerHttpClient;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.ToolAdapter;
import org.geoserver.mcp.tool.ToolHints;
import org.junit.Test;

public class ToolRegistryTest extends McpToolTestSupport {
    @Test
    public void everyToolHasUniqueNameAndDescription() {
        List<GsTool> all = ToolRegistry.all(getCatalog(), getGeoServer(),
                new GeoServerHttpClient(getGeoServer(), new McpSettingsStore(getGeoServer())), new ObjectMapper());
        Set<String> names = new HashSet<>();
        for (GsTool t : all) {
            assertTrue(t.definition().name(), t.definition().name().startsWith("gs_"));
            assertTrue(t.definition().name(), names.add(t.definition().name()));
            assertTrue(t.definition().name(), t.definition().description() != null && !t.definition().description().isBlank());
        }
        assertEquals(30, all.size());
    }

    @Test
    public void everyToolCarriesAnnotations() {
        McpSettingsStore settings = new McpSettingsStore(getGeoServer());
        List<GsTool> all = ToolRegistry.all(getCatalog(), getGeoServer(), new GeoServerHttpClient(getGeoServer(), settings), new ObjectMapper());
        int readOnly = 0;
        for (GsTool t : all) {
            McpSchema.ToolAnnotations a = ToolAdapter.spec(t, settings, new ObjectMapper()).tool().annotations();
            assertNotNull(t.definition().name(), a);
            if (a.readOnlyHint()) readOnly++;
        }
        assertEquals(19, readOnly);
        assertTrue(ToolHints.of("gs_delete").destructiveHint());
        assertTrue(ToolHints.of("gs_gwc_seed").destructiveHint());
        assertFalse(ToolHints.of("gs_create_store").destructiveHint());
    }
}
