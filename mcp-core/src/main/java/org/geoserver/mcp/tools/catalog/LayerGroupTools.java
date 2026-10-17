package org.geoserver.mcp.tools.catalog;

import org.geoserver.catalog.CatalogBuilder;
import org.geoserver.catalog.Keyword;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.PublishedInfo;
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
    private static final String WORKSPACE_OR_GLOBAL = "Workspace; omit for global";
    private static final String MODE = "mode";
    private static final String KEYWORDS = "keywords";
    private static final String ADD = "add";
    private static final String REMOVE = "remove";
    private static final String ADD_STYLES = "addStyles";
    private static final String[] MODES = {"SINGLE", "NAMED", "CONTAINER", "EO", "OPAQUE_CONTAINER"};

    protected final Catalog catalog;

    public LayerGroupTools(Catalog catalog) {
        this.catalog = catalog;
    }

    public List<GsTool> tools() {
        return List.of(list(), get(), create(), update());
    }

    static String groupPath(String workspace, String name) {
        return workspace == null ? "/rest/layergroups/" + name : Keys.REST_WORKSPACES + workspace + "/layergroups/" + name;
    }

    GsTool list() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_list_layer_groups", Schema.object().string(Keys.WORKSPACE, null, false).build())
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
                    v.remove("bounds");
                    out.add(Views.listRow(v, args.has(Keys.WORKSPACE)));
                }
                return Map.of("layerGroups", out);
            }
        };
    }

    GsTool get() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_get_layer_group", Schema.object()
                        .string("name", null, true)
                        .string(Keys.WORKSPACE, WORKSPACE_OR_GLOBAL, false).build())
                        .description("Describe a layer group: mode, ordered layers with styles, bounds").build();
            }
            public Object call(ToolContext ctx, Args args) {
                String ws = args.string(Keys.WORKSPACE, null);
                LayerGroupInfo g = Lookups.layerGroup(catalog, ws, args.string("name"));
                ctx.trace().curl("GET", groupPath(ws, g.getName()) + Keys.JSON_EXT, null, null);
                return Views.layerGroup(g);
            }
        };
    }

    GsTool create() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_create_layer_group", Schema.object()
                        .string("name", null, true)
                        .string(Keys.WORKSPACE, WORKSPACE_OR_GLOBAL, false)
                        .string(Keys.TITLE, null, false)
                        .stringArray(Keys.LAYERS, "Ordered layer names, bottom first", true)
                        .stringArray(Keys.STYLES, "Parallel style names; empty string means the layer's default", false)
                        .enumeration(MODE, null, false, MODES).build())
                        .description("Create a layer group and compute its bounds").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                String name = args.string("name");
                String workspace = args.string(Keys.WORKSPACE, null);
                WorkspaceInfo ws = workspace == null ? null : Lookups.workspace(catalog, workspace);
                LayerGroupInfo existing = find(ws, name);
                if (existing != null) {
                    Map<String, Object> m = Views.layerGroup(existing);
                    m.put(Keys.CREATED, false);
                    return m;
                }
                List<String> layers = args.strings(Keys.LAYERS);
                if (layers.isEmpty()) throw new ToolException("At least one layer is required");
                List<String> styles = args.strings(Keys.STYLES);
                LayerGroupInfo g = catalog.getFactory().createLayerGroup();
                g.setName(name);
                g.setWorkspace(ws);
                if (args.has(Keys.TITLE)) g.setTitle(args.string(Keys.TITLE));
                if (args.has(MODE)) g.setMode(LayerGroupInfo.Mode.valueOf(args.string(MODE)));
                addMembers(g, layers, styles);
                new CatalogBuilder(catalog).calculateLayerGroupBounds(g);
                ctx.trace().curl("POST", ws == null ? "/rest/layergroups" : Keys.REST_WORKSPACES + ws.getName() + "/layergroups",
                        Keys.APPLICATION_JSON, Views.json(Map.of("layerGroup", Map.of("name", name, Keys.LAYERS, Map.of(Keys.LAYER, layers)))));
                catalog.add(g);
                Map<String, Object> m = Views.layerGroup(find(ws, name));
                m.put(Keys.CREATED, true);
                return m;
            }
        };
    }

    GsTool update() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_update_layer_group", Schema.object()
                        .string("name", null, true)
                        .string(Keys.WORKSPACE, WORKSPACE_OR_GLOBAL, false)
                        .string(Keys.TITLE, null, false)
                        .string(Keys.ABSTRACT, null, false)
                        .enumeration(MODE, null, false, MODES)
                        .stringArray(KEYWORDS, "Replace the keywords", false)
                        .stringArray(ADD, "Layers or layer groups to add on top, in order", false)
                        .stringArray(ADD_STYLES, "Parallel styles for add; empty string means the default", false)
                        .stringArray(REMOVE, "Layers or layer groups to remove", false)
                        .stringArray(Keys.LAYERS, "Replace the whole list, bottom first; not combinable with add or remove", false)
                        .stringArray(Keys.STYLES, "Parallel styles for layers", false).build())
                        .description("Edit a layer group: title, abstract, mode, add or remove members, or replace the list. Bounds are recomputed.").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                String ws = args.string(Keys.WORKSPACE, null);
                LayerGroupInfo g = Lookups.layerGroup(catalog, ws, args.string("name"));
                if (args.has(Keys.TITLE)) g.setTitle(args.string(Keys.TITLE));
                if (args.has(Keys.ABSTRACT)) g.setAbstract(args.string(Keys.ABSTRACT));
                if (args.has(MODE)) g.setMode(LayerGroupInfo.Mode.valueOf(args.string(MODE)));
                if (args.has(KEYWORDS)) setKeywords(g, args.strings(KEYWORDS));
                applyMembership(g, args);
                if (g.getLayers().isEmpty()) throw new ToolException("A layer group needs at least one layer; use gs_delete to remove the group");
                new CatalogBuilder(catalog).calculateLayerGroupBounds(g);
                List<String> names = g.getLayers().stream().map(PublishedInfo::prefixedName).toList();
                ctx.trace().curl("PUT", groupPath(ws, g.getName()) + Keys.JSON_EXT, Keys.APPLICATION_JSON,
                        Views.json(Map.of("layerGroup", Map.of("name", g.getName(), Keys.LAYERS, Map.of(Keys.LAYER, names)))));
                catalog.save(g);
                Map<String, Object> m = Views.layerGroup(Lookups.layerGroup(catalog, ws, g.getName()));
                m.put("updated", true);
                return m;
            }
        };
    }

    static void setKeywords(LayerGroupInfo g, List<String> keywords) {
        g.getKeywords().clear();
        for (String k : keywords) g.getKeywords().add(new Keyword(k));
    }

    void applyMembership(LayerGroupInfo g, Args args) {
        boolean edit = args.has(ADD) || args.has(REMOVE);
        if (args.has(Keys.LAYERS)) {
            if (edit) throw new ToolException("Pass either layers, or add and remove, not both");
            g.getLayers().clear();
            g.getStyles().clear();
            addMembers(g, args.strings(Keys.LAYERS), args.strings(Keys.STYLES));
            return;
        }
        for (String name : args.strings(REMOVE)) removeMember(g, name);
        addMembers(g, args.strings(ADD), args.strings(ADD_STYLES));
    }

    void removeMember(LayerGroupInfo g, String name) {
        PublishedInfo target = published(name);
        boolean found = false;
        for (int i = g.getLayers().size() - 1; i >= 0; i--) {
            PublishedInfo p = g.getLayers().get(i);
            if (p != null && p.getId().equals(target.getId())) {
                g.getLayers().remove(i);
                if (i < g.getStyles().size()) g.getStyles().remove(i);
                found = true;
            }
        }
        if (!found) throw new ToolException("'" + name + "' is not in layer group '" + g.prefixedName() + "'");
    }

    PublishedInfo published(String name) {
        LayerInfo layer = catalog.getLayerByName(name);
        if (layer != null) return layer;
        LayerGroupInfo group = catalog.getLayerGroupByName(name);
        if (group != null) return group;
        throw new ToolException("Layer or layer group '" + name + "' not found");
    }

    LayerGroupInfo find(WorkspaceInfo ws, String name) {
        return ws == null ? catalog.getLayerGroupByName(name) : catalog.getLayerGroupByName(ws, name);
    }

    void addMembers(LayerGroupInfo g, List<String> layers, List<String> styles) {
        for (int i = 0; i < layers.size(); i++) {
            g.getLayers().add(published(layers.get(i)));
            g.getStyles().add(style(i < styles.size() ? styles.get(i) : ""));
        }
    }

    StyleInfo style(String name) {
        return name.isBlank() ? null : Lookups.style(catalog, null, name);
    }
}
