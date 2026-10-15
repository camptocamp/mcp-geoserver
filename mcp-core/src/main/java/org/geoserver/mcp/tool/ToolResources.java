package org.geoserver.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.geoserver.mcp.settings.McpSettingsStore;

public final class ToolResources {
    static final String SCHEME = "geoserver://";

    record Fixed(String path, String tool, String description) {}

    record Template(String prefix, String tool, String arg, String description) {}

    static final List<Fixed> FIXED = List.of(
            new Fixed("status", "gs_status", "GeoServer version, proxy base URL and catalog counts"),
            new Fixed("workspaces", "gs_list_workspaces", "All workspaces"),
            new Fixed(Keys.LAYERS, "gs_list_layers", "All layers"),
            new Fixed(Keys.STYLES, "gs_list_styles", "All styles"),
            new Fixed("layergroups", "gs_list_layer_groups", "All layer groups"));

    static final List<Template> TEMPLATES = List.of(
            new Template("workspaces/", "gs_get_workspace", "name", "One workspace"),
            new Template(Keys.LAYERS + "/", "gs_get_layer", "name", "One layer with attributes and styles, name as workspace:name"),
            new Template(Keys.STYLES + "/", "gs_get_style", "name", "One global style with its body"));

    private final Map<String, GsTool> tools;
    private final McpSettingsStore settings;
    private final ObjectMapper json;

    public ToolResources(List<GsTool> tools, McpSettingsStore settings, ObjectMapper json) {
        this.tools = tools.stream().collect(Collectors.toMap(t -> t.definition().name(), t -> t));
        this.settings = settings;
        this.json = json;
    }

    public List<SyncResourceSpecification> fixed() {
        return FIXED.stream().filter(f -> tools.containsKey(f.tool())).map(f -> new SyncResourceSpecification(
                McpSchema.Resource.builder(SCHEME + f.path(), f.path()).description(f.description()).mimeType(Keys.APPLICATION_JSON).build(),
                (ctx, req) -> read(ctx, req.uri(), f.tool(), Map.of()))).toList();
    }

    public List<SyncResourceTemplateSpecification> templates() {
        return TEMPLATES.stream().filter(t -> tools.containsKey(t.tool())).map(t -> new SyncResourceTemplateSpecification(
                McpSchema.ResourceTemplate.builder(SCHEME + t.prefix() + "{" + t.arg() + "}", t.prefix() + "{" + t.arg() + "}")
                        .description(t.description()).mimeType(Keys.APPLICATION_JSON).build(),
                (ctx, req) -> read(ctx, req.uri(), t.tool(), Map.of(t.arg(), variable(req.uri(), t.prefix()))))).toList();
    }

    static String variable(String uri, String prefix) {
        String value = uri.substring((SCHEME + prefix).length());
        if (value.isBlank() || value.contains("/")) throw new ToolException("Unknown resource " + uri);
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    McpSchema.ReadResourceResult read(McpTransportContext ctx, String uri, String tool, Map<String, Object> args) {
        McpSchema.CallToolResult result = ToolAdapter.invoke(tools.get(tool), settings, json, ctx,
                McpSchema.CallToolRequest.builder(tool).arguments(args).build());
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        if (Boolean.TRUE.equals(result.isError())) throw new ToolException(text);
        return new McpSchema.ReadResourceResult(List.of(new McpSchema.TextResourceContents(uri, Keys.APPLICATION_JSON, text)));
    }
}
