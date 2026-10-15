package org.geoserver.mcp.tools.catalog;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;
import org.geoserver.mcp.tool.Keys;

public class WorkspaceTools {
    protected final Catalog catalog;

    public WorkspaceTools(Catalog catalog) {
        this.catalog = catalog;
    }

    public List<GsTool> tools() {
        return List.of(list(), get(), create());
    }

    GsTool list() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_list_workspaces", Schema.object().build())
                        .description("List all workspaces with their namespace URI and default flag").build();
            }
            public Object call(ToolContext ctx, Args args) {
                ctx.trace().curl("GET", "/rest/workspaces.json", null, null);
                List<Map<String, Object>> out = new ArrayList<>();
                for (WorkspaceInfo ws : catalog.getWorkspaces()) out.add(Views.workspace(ws, catalog));
                return Map.of("workspaces", out);
            }
        };
    }

    GsTool get() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_get_workspace", Schema.object().string("name", Keys.DESC_WORKSPACE_NAME, true).build())
                        .description("Describe one workspace: namespace, stores, layer count").build();
            }
            public Object call(ToolContext ctx, Args args) {
                String name = args.string("name");
                ctx.trace().curl("GET", Keys.REST_WORKSPACES + name + Keys.JSON_EXT, null, null);
                WorkspaceInfo ws = Lookups.workspace(catalog, name);
                Map<String, Object> m = Views.workspace(ws, catalog);
                List<String> stores = new ArrayList<>();
                catalog.getStoresByWorkspace(ws, org.geoserver.catalog.StoreInfo.class).forEach(s -> stores.add(s.getName()));
                m.put("stores", stores);
                m.put("layerCount", catalog.getLayers().stream()
                        .filter(l -> ws.getName().equals(l.getResource().getStore().getWorkspace().getName())).count());
                return m;
            }
        };
    }

    GsTool create() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_create_workspace", Schema.object()
                        .string("name", "Workspace name (also the namespace prefix)", true)
                        .string("namespaceUri", "Namespace URI, defaults to http://<name>", false)
                        .bool(Keys.ISOLATED, "Isolated workspace", false).build())
                        .description("Create a workspace and its namespace. Returns the existing one with created=false if it already exists.").build();
            }
            public Object call(ToolContext ctx, Args args) {
                String name = args.string("name");
                String uri = args.string("namespaceUri", "http://" + name);
                ctx.trace().curl("POST", "/rest/workspaces", Keys.APPLICATION_JSON,
                        "{\"workspace\":{\"name\":\"" + name + "\",\"isolated\":" + args.bool(Keys.ISOLATED, false) + "}}");
                WorkspaceInfo existing = catalog.getWorkspaceByName(name);
                if (existing != null) {
                    Map<String, Object> m = Views.workspace(existing, catalog);
                    m.put(Keys.CREATED, false);
                    return m;
                }
                WorkspaceInfo ws = catalog.getFactory().createWorkspace();
                ws.setName(name);
                ws.setIsolated(args.bool(Keys.ISOLATED, false));
                org.geoserver.catalog.NamespaceInfo ns = catalog.getFactory().createNamespace();
                ns.setPrefix(name);
                ns.setURI(uri);
                ns.setIsolated(ws.isIsolated());
                catalog.add(ws);
                catalog.add(ns);
                Map<String, Object> m = Views.workspace(ws, catalog);
                m.put(Keys.CREATED, true);
                return m;
            }
        };
    }
}
