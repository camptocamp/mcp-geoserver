package org.geoserver.mcp.tools.catalog;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;

public class StoreTools {
    protected final Catalog catalog;

    public StoreTools(Catalog catalog) {
        this.catalog = catalog;
    }

    public List<GsTool> tools() {
        return List.of(list(), get());
    }

    GsTool list() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_list_stores", Schema.object().string("workspace", "Restrict to this workspace", false).build())
                        .description("List vector, raster, WMS and WMTS stores").build();
            }
            public Object call(ToolContext ctx, Args args) {
                List<StoreInfo> stores;
                if (args.has("workspace")) {
                    WorkspaceInfo ws = Lookups.workspace(catalog, args.string("workspace"));
                    ctx.trace().curl("GET", "/rest/workspaces/" + ws.getName() + "/datastores.json", null, null);
                    ctx.trace().curl("GET", "/rest/workspaces/" + ws.getName() + "/coveragestores.json", null, null);
                    stores = catalog.getStoresByWorkspace(ws, StoreInfo.class);
                } else {
                    for (WorkspaceInfo ws : catalog.getWorkspaces()) {
                        ctx.trace().curl("GET", "/rest/workspaces/" + ws.getName() + "/datastores.json", null, null);
                        ctx.trace().curl("GET", "/rest/workspaces/" + ws.getName() + "/coveragestores.json", null, null);
                    }
                    stores = catalog.getStores(StoreInfo.class);
                }
                List<Map<String, Object>> out = new ArrayList<>();
                for (StoreInfo s : stores) {
                    Map<String, Object> v = Views.store(s);
                    v.remove("connectionParameters");
                    out.add(v);
                }
                return Map.of("stores", out);
            }
        };
    }

    GsTool get() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_get_store", Schema.object()
                        .string("workspace", "Workspace name", true)
                        .string("name", "Store name", true).build())
                        .description("Describe a store: type, connection parameters (secrets masked), published resources").build();
            }
            public Object call(ToolContext ctx, Args args) {
                StoreInfo s = Lookups.store(catalog, args.string("workspace"), args.string("name"));
                String kind = Views.storeKind(s).equals("raster") ? "coveragestores" : "datastores";
                ctx.trace().curl("GET", "/rest/workspaces/" + s.getWorkspace().getName() + "/" + kind + "/" + s.getName() + ".json", null, null);
                Map<String, Object> m = Views.store(s);
                List<String> resources = new ArrayList<>();
                for (ResourceInfo r : catalog.getResourcesByStore(s, ResourceInfo.class)) resources.add(r.getName());
                m.put("resources", resources);
                return m;
            }
        };
    }
}
