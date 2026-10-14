package org.geoserver.mcp.tools.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.geoserver.mcp.McpToolTestSupport;
import org.junit.Test;

public class DiscoveryToolsTest extends McpToolTestSupport {

    List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("name").asText()));
        return out;
    }

    @Test
    public void storeTypesIncludeShapefileAndGeoTiff() throws Exception {
        JsonNode r = run(tool(new DiscoveryTools().tools(), "gs_list_store_types"), Map.of());
        assertTrue(names(r.get("vector")).contains("Shapefile"));
        assertTrue(names(r.get("raster")).contains("GeoTIFF"));
        JsonNode shp = null;
        for (JsonNode n : r.get("vector")) if (n.get("name").asText().equals("Shapefile")) shp = n;
        assertTrue(shp.get("parameters").size() > 0);
        JsonNode p = shp.get("parameters").get(0);
        assertTrue(p.has("key") && p.has("type") && p.has("required"));
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
        assertTrue(m.has("available") && m.has("enabled") && m.has("category"));
    }

    @Test
    public void factoryLookupIsCaseInsensitive() {
        assertTrue(DiscoveryTools.vectorFactory("shapefile").isPresent());
        assertTrue(DiscoveryTools.rasterFormat("geotiff").isPresent());
        assertTrue(DiscoveryTools.vectorFactory("nope").isEmpty());
    }
}
