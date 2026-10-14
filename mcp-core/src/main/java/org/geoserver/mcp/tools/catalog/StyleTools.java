package org.geoserver.mcp.tools.catalog;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.geoserver.catalog.SLDHandler;
import org.geoserver.catalog.StyleHandler;
import org.geoserver.catalog.Styles;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.mcp.tool.ToolException;
import org.geotools.util.Version;
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
        return List.of(list(), get(), put());
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

    GsTool put() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_put_style", Schema.object()
                        .string("name", "Style name", true)
                        .string("workspace", "Workspace; omit for a global style", false)
                        .string("body", "Full style document in the given format", true)
                        .string("format", "sld (default), or css, ysld, mbstyle when that extension is installed", false).build())
                        .description("Create or replace a style. The body is parsed before anything is written.").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                String name = args.string("name");
                String workspace = args.string("workspace", null);
                String body = args.string("body");
                StyleHandler handler = handler(args.string("format", SLDHandler.FORMAT));
                Version version = SLDHandler.FORMAT.equals(handler.getFormat())
                        ? (body.contains("version=\"1.1.0\"") ? SLDHandler.VERSION_11 : SLDHandler.VERSION_10)
                        : handler.getVersions().get(0);
                try {
                    var parsed = handler.parse(new StringReader(body), version, null, null);
                    if (parsed == null || parsed.getStyledLayers().length == 0) throw new ToolException("Style has no styled layers");
                } catch (ToolException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ToolException("Invalid " + handler.getFormat() + ": " + e.getMessage());
                }
                WorkspaceInfo ws = workspace == null ? null : Lookups.workspace(catalog, workspace);
                StyleInfo existing = ws == null ? catalog.getStyleByName((WorkspaceInfo) null, name) : catalog.getStyleByName(ws, name);
                boolean created = existing == null;
                StyleInfo style = existing;
                if (created) {
                    style = catalog.getFactory().createStyle();
                    style.setName(name);
                    style.setWorkspace(ws);
                    style.setFilename(name + "." + handler.getFileExtension());
                }
                style.setFormat(handler.getFormat());
                style.setFormatVersion(version);
                String path = ws == null ? "/rest/styles" : "/rest/workspaces/" + ws.getName() + "/styles";
                ctx.trace().curl(created ? "POST" : "PUT", created ? path + "?name=" + name : path + "/" + name,
                        handler.mimeType(version), body);
                catalog.getResourcePool().writeStyle(style, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
                if (created) catalog.add(style);
                else catalog.save(style);
                Map<String, Object> m = Views.style(ws == null ? catalog.getStyleByName((WorkspaceInfo) null, name) : catalog.getStyleByName(ws, name));
                m.put("created", created);
                return m;
            }
        };
    }

    static StyleHandler handler(String format) {
        for (StyleHandler h : Styles.handlers()) if (h.getFormat().equalsIgnoreCase(format)) return h;
        String installed = Styles.handlers().stream().map(StyleHandler::getFormat).collect(Collectors.joining(", "));
        throw new ToolException("Unknown style format '" + format + "'. Installed: " + installed);
    }
}
