package org.geoserver.mcp.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.mcp.settings.McpSettingsStore;

public final class ToolAdapter {
    private static final Logger COMMANDS = org.geotools.util.logging.Logging.getLogger("org.geoserver.mcp.commands");
    private static final Logger LOG = Logger.getLogger(ToolAdapter.class.getName());

    private ToolAdapter() {}

    public static McpStatelessServerFeatures.SyncToolSpecification spec(GsTool tool, McpSettingsStore settings, ObjectMapper json) {
        McpSchema.Tool def = withHints(tool.definition());
        ObjectMapper out = json.copy().setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));
        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(def)
                .callHandler((McpTransportContext tctx, McpSchema.CallToolRequest req) -> invoke(tool, settings, out, tctx, req))
                .build();
    }

    static McpSchema.Tool withHints(McpSchema.Tool tool) {
        if (tool.annotations() != null) return tool;
        return McpSchema.Tool.builder(tool.name(), tool.inputSchema())
                .title(tool.title())
                .description(tool.description())
                .annotations(ToolHints.of(tool.name()))
                .build();
    }

    static McpSchema.CallToolResult invoke(GsTool tool, McpSettingsStore settings, ObjectMapper json,
            McpTransportContext tctx, McpSchema.CallToolRequest req) {
        Args args = new Args(req.arguments());
        McpSettings current = settings.load();
        String name = tool.definition().name();
        if (current.readOnly() && !isReadOnly(tool)) return error(name + " is disabled: the MCP server is in read-only mode");
        boolean verbose = args.has(Keys.VERBOSE) ? args.bool(Keys.VERBOSE, false) : current.verbose();
        String auth = (String) tctx.get(ToolContext.AUTHORIZATION_KEY);
        String base = (String) tctx.get(ToolContext.REQUEST_BASE_URL_KEY);
        ToolContext ctx = new ToolContext(verbose, new CommandTrace(), auth, base == null ? "" : base, current);
        try {
            Object payload = capLists(tool.call(ctx, args), current.maxListResults());
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

    static boolean isReadOnly(GsTool tool) {
        McpSchema.ToolAnnotations hints = tool.definition().annotations() != null ? tool.definition().annotations() : ToolHints.of(tool.definition().name());
        return hints != null && Boolean.TRUE.equals(hints.readOnlyHint());
    }

    static Object capLists(Object payload, int max) {
        if (!(payload instanceof Map<?, ?> map)) return payload;
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> truncated = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (v instanceof List<?> list && list.size() > max) {
                out.put(String.valueOf(k), list.subList(0, max));
                truncated.put(String.valueOf(k), list.size());
            } else {
                out.put(String.valueOf(k), v);
            }
        });
        if (truncated.isEmpty()) return payload;
        out.put("truncated", truncated);
        return out;
    }

    static McpSchema.Content toContent(Object payload, ObjectMapper json) throws JsonProcessingException {
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
