package org.geoserver.mcp.tools.catalog;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.geoserver.catalog.Catalog;
import org.geoserver.config.GeoServer;
import org.geoserver.mcp.Version;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;
import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.platform.ModuleStatus;

public class AdminTools {
    protected final Catalog catalog;
    protected final GeoServer geoServer;

    public AdminTools(Catalog catalog, GeoServer geoServer) {
        this.catalog = catalog;
        this.geoServer = geoServer;
    }

    public List<GsTool> tools() {
        return List.of(status());
    }

    GsTool status() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_status", Schema.object().build())
                        .description("Server overview: versions, proxy base URL, catalog counts, installed module count").build();
            }
            public Object call(ToolContext ctx, Args args) {
                ctx.trace().curl("GET", "/rest/about/version.json", null, null);
                ctx.trace().curl("GET", "/rest/about/status.json", null, null);
                Map<String, Object> m = new LinkedHashMap<>();
                String gsVersion = GeoServer.class.getPackage().getImplementationVersion();
                m.put("geoserverVersion", gsVersion == null ? "unknown" : gsVersion);
                m.put("mcpVersion", Version.current());
                m.put("proxyBaseUrl", geoServer.getGlobal().getSettings().getProxyBaseUrl());
                m.put("workspaceCount", catalog.getWorkspaces().size());
                m.put("storeCount", catalog.getStores(org.geoserver.catalog.StoreInfo.class).size());
                m.put("layerCount", catalog.getLayers().size());
                m.put("layerGroupCount", catalog.getLayerGroups().size());
                m.put("styleCount", catalog.getStyles().size());
                m.put("moduleCount", GeoServerExtensions.extensions(ModuleStatus.class).size());
                return m;
            }
        };
    }
}
