package org.geoserver.mcp.tools.catalog;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;

public class LayerTools {
    protected final Catalog catalog;

    public LayerTools(Catalog catalog) {
        this.catalog = catalog;
    }

    public List<GsTool> tools() {
        return List.of(list(), get());
    }

    GsTool list() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_list_layers", Schema.object().string("workspace", "Restrict to this workspace", false).build())
                        .description("List published layers as workspace:name with type and title").build();
            }
            public Object call(ToolContext ctx, Args args) {
                String ws = args.string("workspace", null);
                ctx.trace().curl("GET", ws == null ? "/rest/layers.json" : "/rest/workspaces/" + ws + "/layers.json", null, null);
                if (ws != null) Lookups.workspace(catalog, ws);
                List<Map<String, Object>> out = new ArrayList<>();
                for (LayerInfo l : catalog.getLayers()) {
                    String lws = l.getResource().getStore().getWorkspace().getName();
                    if (ws == null || ws.equals(lws)) out.add(Views.layerSummary(l));
                }
                return Map.of("layers", out);
            }
        };
    }

    GsTool get() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_get_layer", Schema.object().string("name", "Layer name, workspace:name or plain name", true).build())
                        .description("Describe a layer: store, native name, SRS, bounds, styles, attributes").build();
            }
            public Object call(ToolContext ctx, Args args) {
                LayerInfo l = Lookups.layer(catalog, args.string("name"));
                ctx.trace().curl("GET", "/rest/layers/" + l.prefixedName() + ".json", null, null);
                return Views.layer(l);
            }
        };
    }
}
