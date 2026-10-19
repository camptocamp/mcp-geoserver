package org.geoserver.mcp.tools.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.geoserver.mcp.McpToolTestSupport;
import org.geoserver.mcp.tool.ToolException;
import org.geoserver.platform.ModuleStatus;
import org.junit.Test;

public class DiscoveryToolsTest extends McpToolTestSupport {

    static final int MAX_DEFAULT_CHARS = 3_000;

    JsonNode entry(JsonNode array, String name) {
        for (JsonNode n : array) if (n.get("name").asText().equals(name)) return n;
        throw new AssertionError("no entry " + name);
    }

    List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("name").asText()));
        return out;
    }

    @Test
    public void storeTypesListNamesAndRequiredKeysOnly() throws Exception {
        JsonNode r = run(tool(new DiscoveryTools().tools(), "gs_list_store_types"), Map.of());
        assertTrue(names(r.get("vector")).contains("Shapefile"));
        assertTrue(names(r.get("raster")).contains("GeoTIFF"));
        for (JsonNode n : r.get("vector")) assertFalse(n.has("parameters"));
        assertEquals("[\"url\"]", entry(r.get("vector"), "Shapefile").get("required").toString());
        assertEquals("[\"url\"]", entry(r.get("raster"), "GeoTIFF").get("required").toString());
        assertTrue(r.toString().length() < MAX_DEFAULT_CHARS);
    }

    @Test
    public void storeTypeDetailListsEveryParameterWithoutLongSamples() throws Exception {
        JsonNode r = run(tool(new DiscoveryTools().tools(), "gs_list_store_types"), Map.of("type", "shapefile"));
        assertEquals(1, r.get("vector").size());
        assertEquals(0, r.get("raster").size());
        JsonNode shp = r.get("vector").get(0);
        assertTrue(shp.get("parameters").size() > 1);
        for (JsonNode p : shp.get("parameters")) {
            assertTrue(p.has("key") && p.has("required"));
            assertFalse(p.has("type"));
            assertFalse(p.path("sample").asText("").length() > 40);
        }
        JsonNode tiff = run(tool(new DiscoveryTools().tools(), "gs_list_store_types"), Map.of("type", "GeoTIFF"));
        assertEquals("url", tiff.get("raster").get(0).get("parameters").get(0).get("key").asText());
    }

    @Test
    public void storeTypeDetailRejectsUnknownType() {
        ToolException e = assertThrows(ToolException.class,
                () -> run(tool(new DiscoveryTools().tools(), "gs_list_store_types"), Map.of("type", "nope")));
        assertTrue(e.getMessage().contains("nope"));
    }

    @Test
    public void storeTypesFilterByKind() throws Exception {
        JsonNode r = run(tool(new DiscoveryTools().tools(), "gs_list_store_types"), Map.of("kind", "raster"));
        assertEquals(0, r.get("vector").size());
        assertTrue(r.get("raster").size() > 0);
    }

    @Test
    public void modulesListed() throws Exception {
        JsonNode r = run(tool(new DiscoveryTools().tools(), "gs_list_modules"), Map.of());
        assertTrue(names(r.get("modules")).stream().anyMatch(n -> n.startsWith("gs-main")));
        JsonNode m = r.get("modules").get(0);
        assertTrue(m.has("category"));
        r.get("modules").forEach(x -> {
            assertFalse(x.path("available").asBoolean(false));
            assertFalse(x.path("enabled").asBoolean(false));
        });
    }

    @Test
    public void moduleShowsAvailableAndEnabledOnlyWhenFalse() {
        ModuleStatus s = mock(ModuleStatus.class);
        when(s.getModule()).thenReturn("gs-x");
        when(s.getVersion()).thenReturn(Optional.empty());
        when(s.getMessage()).thenReturn(Optional.of("missing native lib"));
        Map<String, Object> off = DiscoveryTools.module(s);
        assertEquals(false, off.get("available"));
        assertEquals(false, off.get("enabled"));
        when(s.isAvailable()).thenReturn(true);
        when(s.isEnabled()).thenReturn(true);
        Map<String, Object> on = DiscoveryTools.module(s);
        assertFalse(on.containsKey("available") || on.containsKey("enabled"));
    }

    @Test
    public void factoryLookupIsCaseInsensitive() {
        assertTrue(DiscoveryTools.vectorFactory("shapefile").isPresent());
        assertTrue(DiscoveryTools.rasterFormat("geotiff").isPresent());
        assertTrue(DiscoveryTools.vectorFactory("nope").isEmpty());
    }

    @Test
    public void storeTypeMatchesExactNameFirstThenAUniquePrefix() {
        assertEquals("Directory of spatial files (shapefiles)", DiscoveryTools.vectorFactory("directory").orElseThrow().getDisplayName());
        assertEquals("PostGIS", DiscoveryTools.vectorFactory("PostGIS").orElseThrow().getDisplayName());
        assertFalse(DiscoveryTools.vectorFactory("Post").isPresent());
        assertEquals("GeoTIFF", DiscoveryTools.rasterFormat("geotiff").orElseThrow().getName());
    }
}
