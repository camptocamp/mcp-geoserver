package org.geoserver.mcp.tools.catalog;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;

public class StyleTools {
    protected final Catalog catalog;

    public StyleTools(Catalog catalog) {
        this.catalog = catalog;
    }

    public List<GsTool> tools() {
        return List.of(list(), get());
    }

    static String stylePath(StyleInfo s) {
        return s.getWorkspace() == null
                ? "/rest/styles/" + s.getName()
                : "/rest/workspaces/" + s.getWorkspace().getName() + "/styles/" + s.getName();
    }

    GsTool list() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_list_styles", Schema.object().string("workspace", "Restrict to this workspace; omit for global styles and all workspaces", false).build())
                        .description("List styles").build();
            }
            public Object call(ToolContext ctx, Args args) {
                List<StyleInfo> styles;
                if (args.has("workspace")) {
                    var ws = Lookups.workspace(catalog, args.string("workspace"));
                    ctx.trace().curl("GET", "/rest/workspaces/" + ws.getName() + "/styles.json", null, null);
                    styles = catalog.getStylesByWorkspace(ws);
                } else {
                    ctx.trace().curl("GET", "/rest/styles.json", null, null);
                    styles = catalog.getStyles();
                }
                List<Map<String, Object>> out = new ArrayList<>();
                for (StyleInfo s : styles) out.add(Views.style(s));
                return Map.of("styles", out);
            }
        };
    }

    GsTool get() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_get_style", Schema.object()
                        .string("name", "Style name", true)
                        .string("workspace", "Workspace of the style; omit for global", false).build())
                        .description("Get a style's metadata and its body (SLD, or CSS/YSLD/MBStyle per its format)").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                StyleInfo s = Lookups.style(catalog, args.string("workspace", null), args.string("name"));
                ctx.trace().curl("GET", stylePath(s) + ".sld", null, null);
                Map<String, Object> m = Views.style(s);
                try (BufferedReader r = catalog.getResourcePool().readStyle(s)) {
                    m.put("body", r.lines().collect(Collectors.joining("\n")));
                }
                return m;
            }
        };
    }
}
