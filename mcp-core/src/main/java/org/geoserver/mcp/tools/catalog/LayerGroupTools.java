package org.geoserver.mcp.tools.catalog;

import org.geoserver.catalog.CatalogBuilder;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.mcp.tool.ToolException;
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
import org.geoserver.mcp.tool.Keys;

public class LayerGroupTools {
    protected final Catalog catalog;

    public LayerGroupTools(Catalog catalog) {
        this.catalog = catalog;
    }

    public List<GsTool> tools() {
        return List.of(list(), get(), create());
    }

    static String groupPath(String workspace, String name) {
        return workspace == null ? "/rest/layergroups/" + name : Keys.REST_WORKSPACES + workspace + "/layergroups/" + name;
    }

    GsTool list() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_list_layer_groups", Schema.object().string(Keys.WORKSPACE, "Restrict to this workspace", false).build())
                        .description("List layer groups").build();
            }
            public Object call(ToolContext ctx, Args args) {
                List<LayerGroupInfo> groups;
                if (args.has(Keys.WORKSPACE)) {
                    var ws = Lookups.workspace(catalog, args.string(Keys.WORKSPACE));
                    ctx.trace().curl("GET", Keys.REST_WORKSPACES + ws.getName() + "/layergroups.json", null, null);
                    groups = catalog.getLayerGroupsByWorkspace(ws);
                } else {
                    ctx.trace().curl("GET", "/rest/layergroups.json", null, null);
                    groups = catalog.getLayerGroups();
                }
                List<Map<String, Object>> out = new ArrayList<>();
                for (LayerGroupInfo g : groups) {
                    Map<String, Object> v = Views.layerGroup(g);
                    v.put("layerCount", g.getLayers().size());
                    v.remove(Keys.LAYERS);
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
                        .string(Keys.WORKSPACE, "Workspace; omit for global", false).build())
                        .description("Describe a layer group: mode, ordered layers with styles, bounds").build();
            }
            public Object call(ToolContext ctx, Args args) {
                String ws = args.string(Keys.WORKSPACE, null);
                LayerGroupInfo g = Lookups.layerGroup(catalog, ws, args.string("name"));
                ctx.trace().curl("GET", groupPath(ws, g.getName()) + ".json", null, null);
                return Views.layerGroup(g);
            }
        };
    }

    GsTool create() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_create_layer_group", Schema.object()
                        .string("name", "Layer group name", true)
                        .string(Keys.WORKSPACE, "Workspace; omit for global", false)
                        .string(Keys.TITLE, "Title", false)
                        .stringArray(Keys.LAYERS, "Ordered layer names, bottom first", true)
                        .stringArray("styles", "Parallel style names; empty string means the layer's default", false)
                        .enumeration("mode", "Group mode", false, "SINGLE", "NAMED", "CONTAINER", "EO", "OPAQUE_CONTAINER").build())
                        .description("Create a layer group and compute its bounds").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                String name = args.string("name");
                String workspace = args.string(Keys.WORKSPACE, null);
                WorkspaceInfo ws = workspace == null ? null : Lookups.workspace(catalog, workspace);
                LayerGroupInfo existing = find(ws, name);
                if (existing != null) {
                    Map<String, Object> m = Views.layerGroup(existing);
                    m.put("created", false);
                    return m;
                }
                List<String> layers = args.strings(Keys.LAYERS);
                if (layers.isEmpty()) throw new ToolException("At least one layer is required");
                List<String> styles = args.strings("styles");
                LayerGroupInfo g = catalog.getFactory().createLayerGroup();
                g.setName(name);
                g.setWorkspace(ws);
                if (args.has(Keys.TITLE)) g.setTitle(args.string(Keys.TITLE));
                if (args.has("mode")) g.setMode(LayerGroupInfo.Mode.valueOf(args.string("mode")));
                addMembers(g, layers, styles);
                new CatalogBuilder(catalog).calculateLayerGroupBounds(g);
                ctx.trace().curl("POST", ws == null ? "/rest/layergroups" : Keys.REST_WORKSPACES + ws.getName() + "/layergroups",
                        "application/json", Views.json(Map.of("layerGroup", Map.of("name", name, Keys.LAYERS, Map.of("layer", layers)))));
                catalog.add(g);
                Map<String, Object> m = Views.layerGroup(find(ws, name));
                m.put("created", true);
                return m;
            }
        };
    }

    LayerGroupInfo find(WorkspaceInfo ws, String name) {
        return ws == null ? catalog.getLayerGroupByName(name) : catalog.getLayerGroupByName(ws, name);
    }

    void addMembers(LayerGroupInfo g, List<String> layers, List<String> styles) {
        for (int i = 0; i < layers.size(); i++) {
            g.getLayers().add(Lookups.layer(catalog, layers.get(i)));
            g.getStyles().add(style(i < styles.size() ? styles.get(i) : ""));
        }
    }

    StyleInfo style(String name) {
        if (name.isBlank()) return null;
        StyleInfo s = catalog.getStyleByName(name);
        if (s == null) throw new ToolException("Style '" + name + "' not found");
        return s;
    }
}
