package org.geoserver.mcp.tools.catalog;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.geoserver.catalog.AttributeTypeInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.config.GeoServerDataDirectory;
import org.geoserver.platform.resource.Resource;
import org.geotools.api.style.Style;
import org.geotools.api.style.StyledLayerDescriptor;
import org.geotools.filter.FunctionFinder;
import org.geotools.renderer.style.StyleAttributeExtractor;
import org.geotools.styling.SLD;
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
    private static final Pattern SLD_11_ROOT = Pattern.compile(
            "<(?:\\w+:)?StyledLayerDescriptor\\b[^>]*?\\sversion\\s*=\\s*(['\"])1\\.1\\.0\\1");
    private static final Pattern FUNCTION = Pattern.compile("<(?:\\w+:)?Function\\b[^>]*?\\sname\\s*=\\s*(['\"])(.*?)\\1");
    private static final Pattern HREF = Pattern.compile("<(?:\\w+:)?OnlineResource\\b[^>]*?\\sxlink:href\\s*=\\s*(['\"])(.*?)\\1");
    protected final Catalog catalog;

    public StyleTools(Catalog catalog) {
        this.catalog = catalog;
    }

    public List<GsTool> tools() {
        return List.of(list(), get(), put(), validate());
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
                Version version = version(handler, body);
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

    GsTool validate() {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_validate_style", Schema.object()
                        .string("body", "Style document to check; omit when checking a stored style by name", false)
                        .string("name", "Stored style name to check instead of body", false)
                        .string("workspace", "Workspace of the style; relative graphics resolve in its styles directory", false)
                        .string("layer", "Layer, workspace:name; when given, attributes the style references are checked against it", false)
                        .string("format", "sld (default), css, ysld, mbstyle; ignored with name", false).build())
                        .description("Parse a style without writing anything and report findings: parse error, version, unknown functions, "
                                + "ExternalGraphic hrefs that do not resolve, attributes missing from the layer. Nothing is stored.").build();
            }
            public Object call(ToolContext ctx, Args args) throws Exception {
                String body;
                StyleHandler handler;
                WorkspaceInfo ws;
                if (args.has("body")) {
                    body = args.string("body");
                    handler = handler(args.string("format", SLDHandler.FORMAT));
                    ws = args.has("workspace") ? Lookups.workspace(catalog, args.string("workspace")) : null;
                } else if (args.has("name")) {
                    StyleInfo s = Lookups.style(catalog, args.string("workspace", null), args.string("name"));
                    ctx.trace().curl("GET", stylePath(s) + ".sld", null, null);
                    handler = handler(s.getFormat());
                    ws = s.getWorkspace();
                    try (BufferedReader r = catalog.getResourcePool().readStyle(s)) {
                        body = r.lines().collect(Collectors.joining("\n"));
                    }
                } else {
                    throw new ToolException("Pass body or name");
                }
                Version version = version(handler, body);
                GeoServerDataDirectory dd = new GeoServerDataDirectory(catalog.getResourceLoader());
                String parseError = null;
                StyledLayerDescriptor sld = null;
                try {
                    sld = handler.parse(new StringReader(body), version, ws == null ? dd.getResourceLocator() : dd.getResourceLocator(ws), null);
                    if (sld == null || sld.getStyledLayers().length == 0) parseError = "Style has no styled layers";
                } catch (Exception e) {
                    parseError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                List<String> unknownFunctions = unknownFunctions(body);
                List<String> unresolvedGraphics = unresolvedGraphics(body, dd, ws);
                List<String> missingAttributes = null;
                if (args.has("layer") && sld != null) {
                    LayerInfo layer = Lookups.layer(catalog, args.string("layer"));
                    if (layer.getResource() instanceof FeatureTypeInfo ft) missingAttributes = missingAttributes(sld, ft);
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("valid", parseError == null && unknownFunctions.isEmpty() && unresolvedGraphics.isEmpty()
                        && (missingAttributes == null || missingAttributes.isEmpty()));
                m.put("format", handler.getFormat());
                m.put("version", version.toString());
                if (parseError != null) m.put("parseError", parseError);
                m.put("unknownFunctions", unknownFunctions);
                m.put("unresolvedGraphics", unresolvedGraphics);
                if (missingAttributes != null) m.put("missingAttributes", missingAttributes);
                return m;
            }
        };
    }

    static Version version(StyleHandler handler, String body) {
        return SLDHandler.FORMAT.equals(handler.getFormat())
                ? (SLD_11_ROOT.matcher(body).find() ? SLDHandler.VERSION_11 : SLDHandler.VERSION_10)
                : handler.getVersions().get(0);
    }

    static List<String> unknownFunctions(String body) {
        FunctionFinder finder = new FunctionFinder(null);
        Set<String> out = new TreeSet<>();
        Matcher m = FUNCTION.matcher(body);
        while (m.find()) {
            String name = m.group(2);
            try {
                if (finder.findFunctionDescription(name) == null) out.add(name);
            } catch (RuntimeException e) {
                out.add(name);
            }
        }
        return new ArrayList<>(out);
    }

    static List<String> unresolvedGraphics(String body, GeoServerDataDirectory dd, WorkspaceInfo ws) {
        Set<String> out = new TreeSet<>();
        Matcher m = HREF.matcher(body);
        while (m.find()) {
            String href = m.group(2);
            if (!resolves(href.contains("?") ? href.substring(0, href.indexOf('?')) : href, dd, ws)) out.add(href);
        }
        return new ArrayList<>(out);
    }

    static boolean resolves(String ref, GeoServerDataDirectory dd, WorkspaceInfo ws) {
        if (ref.isBlank()) return false;
        if (ref.startsWith("file:")) return new File(URI.create(ref.replace(" ", "%20"))).exists();
        if (ref.contains("://")) return true;
        if (ref.startsWith("/")) return new File(ref).exists();
        try {
            Resource r = ws == null ? dd.getStyles(ref) : dd.getStyles(ws, ref);
            return r.getType() != Resource.Type.UNDEFINED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static List<String> missingAttributes(StyledLayerDescriptor sld, FeatureTypeInfo ft) throws IOException {
        Set<String> present = new java.util.HashSet<>();
        for (AttributeTypeInfo a : ft.attributes()) present.add(a.getName());
        StyleAttributeExtractor extractor = new StyleAttributeExtractor();
        for (Style s : SLD.styles(sld)) s.accept(extractor);
        Set<String> missing = new TreeSet<>(extractor.getAttributeNameSet());
        missing.removeAll(present);
        return new ArrayList<>(missing);
    }

    static StyleHandler handler(String format) {
        for (StyleHandler h : Styles.handlers()) if (h.getFormat().equalsIgnoreCase(format)) return h;
        String installed = Styles.handlers().stream().map(StyleHandler::getFormat).collect(Collectors.joining(", "));
        throw new ToolException("Unknown style format '" + format + "'. Installed: " + installed);
    }
}
