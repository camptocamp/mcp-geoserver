package org.geoserver.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.geoserver.mcp.settings.McpSettingsStore;

public final class ToolAdapter {
    private static final Logger COMMANDS = org.geotools.util.logging.Logging.getLogger("org.geoserver.mcp.commands");
    private static final Logger LOG = Logger.getLogger(ToolAdapter.class.getName());

    private ToolAdapter() {}

    public static McpStatelessServerFeatures.SyncToolSpecification spec(GsTool tool, McpSettingsStore settings, ObjectMapper json) {
        McpSchema.Tool def = withVerbose(tool.definition());
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(def)
                .callHandler((McpTransportContext tctx, McpSchema.CallToolRequest req) -> invoke(tool, settings, json, tctx, req))
                .build();
    }

    @SuppressWarnings("unchecked")
    static McpSchema.Tool withVerbose(McpSchema.Tool tool) {
        Map<String, Object> schema = new LinkedHashMap<>(tool.inputSchema());
        Map<String, Object> props = new LinkedHashMap<>((Map<String, Object>) schema.getOrDefault("properties", Map.of()));
        props.put(Keys.VERBOSE, Map.of("type", "boolean", "description", "Include the equivalent curl commands in the result"));
        schema.put("properties", props);
        return McpSchema.Tool.builder(tool.name(), schema)
                .title(tool.title())
                .description(tool.description())
                .annotations(tool.annotations() != null ? tool.annotations() : ToolHints.of(tool.name()))
                .build();
    }

    static McpSchema.CallToolResult invoke(GsTool tool, McpSettingsStore settings, ObjectMapper json,
            McpTransportContext tctx, McpSchema.CallToolRequest req) {
        Args args = new Args(req.arguments());
        boolean verbose = args.has(Keys.VERBOSE) ? args.bool(Keys.VERBOSE, false) : settings.load().verbose();
        String auth = (String) tctx.get(ToolContext.AUTHORIZATION_KEY);
        String base = (String) tctx.get(ToolContext.REQUEST_BASE_URL_KEY);
        ToolContext ctx = new ToolContext(verbose, new CommandTrace(), auth, base == null ? "" : base);
        try {
            Object payload = tool.call(ctx, args);
            List<McpSchema.Content> content = new ArrayList<>();
            content.add(toContent(payload, json));
            if (verbose && !ctx.trace().lines().isEmpty()) {
                String block = ctx.trace().render(ctx.requestBaseUrl());
                content.add(McpSchema.TextContent.builder(block).build());
                COMMANDS.info(() -> tool.definition().name() + "\n" + block);
            }
            return McpSchema.CallToolResult.builder().content(content).isError(false).build();
        } catch (ToolException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, e, () -> "Tool " + tool.definition().name() + " failed");
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return error(tool.definition().name() + " failed: " + msg);
        }
    }

    static McpSchema.Content toContent(Object payload, ObjectMapper json) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (payload instanceof Payloads.RawJson raw) return McpSchema.TextContent.builder(raw.json()).build();
        if (payload instanceof Payloads.ImagePayload img) {
            return McpSchema.ImageContent.builder(Base64.getEncoder().encodeToString(img.bytes()), img.mimeType()).build();
        }
        if (payload instanceof String s) return McpSchema.TextContent.builder(s).build();
        return McpSchema.TextContent.builder(json.writeValueAsString(payload)).build();
    }

    static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
    }
}
