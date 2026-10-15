package org.geoserver.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.geoserver.catalog.Catalog;
import org.geoserver.config.GeoServer;
import org.geoserver.mcp.http.GeoServerHttpClient;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tools.catalog.AdminTools;
import org.geoserver.mcp.tools.catalog.DimensionTools;
import org.geoserver.mcp.tools.catalog.LayerGroupTools;
import org.geoserver.mcp.tools.catalog.LayerTools;
import org.geoserver.mcp.tools.catalog.StoreTools;
import org.geoserver.mcp.tools.catalog.StyleGeneratorTools;
import org.geoserver.mcp.tools.catalog.StyleTools;
import org.geoserver.mcp.tools.catalog.WorkspaceTools;
import org.geoserver.mcp.tools.data.DataTools;
import org.geoserver.mcp.tools.discovery.DiscoveryTools;
import org.geoserver.mcp.tools.gwc.GwcTools;

public final class ToolRegistry {
    private ToolRegistry() {}

    public static List<GsTool> all(Catalog catalog, GeoServer geoServer, GeoServerHttpClient http, ObjectMapper json) {
        List<GsTool> all = new ArrayList<>();
        all.addAll(new AdminTools(catalog, geoServer).tools());
        all.addAll(new WorkspaceTools(catalog).tools());
        all.addAll(new StoreTools(catalog).tools());
        all.addAll(new LayerTools(catalog).tools());
        all.addAll(new DimensionTools(catalog).tools());
        all.addAll(new StyleTools(catalog).tools());
        all.addAll(new StyleGeneratorTools(catalog).tools());
        all.addAll(new LayerGroupTools(catalog).tools());
        all.addAll(new DiscoveryTools().tools());
        all.addAll(new DataTools(catalog, http).tools());
        all.addAll(new GwcTools(http, json).tools());
        return List.copyOf(all);
    }
}
