package org.geoserver.mcp.tools.catalog;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.LayerGroupInfo;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;

public class LayerGroupTools {
    protected final Catalog catalog;

    public LayerGroupTools(Catalog catalog) {
        this.catalog = catalog;
    }

    public List<GsTool> tools() {
        return List.of(list(), get());
    }

    static String groupPath(String workspace, String name) {
        return workspace == null ? "/rest/layergroups/" + name : "/rest/workspaces/" + workspace + "/layergroups/" + name;
    }

    GsTool list() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_list_layer_groups", Schema.object().string("workspace", "Restrict to this workspace", false).build())
                        .description("List layer groups").build();
            }
            public Object call(ToolContext ctx, Args args) {
                List<LayerGroupInfo> groups;
                if (args.has("workspace")) {
                    var ws = Lookups.workspace(catalog, args.string("workspace"));
                    ctx.trace().curl("GET", "/rest/workspaces/" + ws.getName() + "/layergroups.json", null, null);
                    groups = catalog.getLayerGroupsByWorkspace(ws);
                } else {
                    ctx.trace().curl("GET", "/rest/layergroups.json", null, null);
                    groups = catalog.getLayerGroups();
                }
                List<Map<String, Object>> out = new ArrayList<>();
                for (LayerGroupInfo g : groups) {
                    Map<String, Object> v = Views.layerGroup(g);
                    v.put("layerCount", g.getLayers().size());
                    v.remove("layers");
                    out.add(v);
                }
                return Map.of("layerGroups", out);
            }
        };
    }

    GsTool get() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_get_layer_group", Schema.object()
                        .string("name", "Layer group name", true)
                        .string("workspace", "Workspace; omit for global", false).build())
                        .description("Describe a layer group: mode, ordered layers with styles, bounds").build();
            }
            public Object call(ToolContext ctx, Args args) {
                String ws = args.string("workspace", null);
                LayerGroupInfo g = Lookups.layerGroup(catalog, ws, args.string("name"));
                ctx.trace().curl("GET", groupPath(ws, g.getName()) + ".json", null, null);
                return Views.layerGroup(g);
            }
        };
    }
}
