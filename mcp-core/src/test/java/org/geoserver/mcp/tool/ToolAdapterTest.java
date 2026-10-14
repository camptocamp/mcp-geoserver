package org.geoserver.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.junit.jupiter.api.Test;

class ToolAdapterTest {
    ObjectMapper json = new ObjectMapper();

    McpSettingsStore settings(boolean verbose) {
        McpSettingsStore s = mock(McpSettingsStore.class);
        when(s.load()).thenReturn(new McpSettings(true, List.of("ROLE_ADMINISTRATOR"), verbose, ""));
        return s;
    }

    GsTool echo = new GsTool() {
        public McpSchema.Tool definition() {
            return McpSchema.Tool.builder("gs_echo", Schema.object().string("text", "Text", true).build())
                    .description("Echo").build();
        }
        public Object call(ToolContext ctx, Args args) {
            ctx.trace().curl("GET", "/rest/about/version.json", null, null);
            return Map.of("text", args.string("text"));
        }
    };

    GsTool failing = new GsTool() {
        public McpSchema.Tool definition() {
            return McpSchema.Tool.builder("gs_fail", Schema.object().build()).description("Fails").build();
        }
        public Object call(ToolContext ctx, Args args) {
            throw new ToolException("Workspace 'x' not found");
        }
    };

    McpSchema.CallToolResult call(GsTool tool, boolean verboseSetting, Map<String, Object> args) {
        McpStatelessServerFeatures.SyncToolSpecification spec = ToolAdapter.spec(tool, settings(verboseSetting), json);
        McpTransportContext ctx = McpTransportContext.create(Map.of(
                ToolContext.AUTHORIZATION, "Basic abc", ToolContext.REQUEST_BASE_URL, "http://localhost:8080/geoserver"));
        return spec.callHandler().apply(ctx, McpSchema.CallToolRequest.builder("x").arguments(args).build());
    }

    @Test
    void verboseArgumentIsAddedToEverySchema() {
        McpSchema.Tool t = ToolAdapter.spec(echo, settings(false), json).tool();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) t.inputSchema().get("properties");
        assertTrue(props.containsKey("verbose"));
    }

    @Test
    void successReturnsJsonText() {
        McpSchema.CallToolResult r = call(echo, false, Map.of("text", "hi"));
        assertFalse(r.isError());
        assertEquals(1, r.content().size());
        assertEquals("{\"text\":\"hi\"}", ((McpSchema.TextContent) r.content().get(0)).text());
    }

    @Test
    void verboseSettingAppendsEquivalentCommands() {
        McpSchema.CallToolResult r = call(echo, true, Map.of("text", "hi"));
        assertEquals(2, r.content().size());
        String block = ((McpSchema.TextContent) r.content().get(1)).text();
        assertTrue(block.startsWith("Equivalent commands:\n"), block);
        assertTrue(block.contains("curl -u \"$GS_AUTH\" -X GET 'http://localhost:8080/geoserver/rest/about/version.json'"), block);
    }

    @Test
    void verboseArgumentOverridesSetting() {
        McpSchema.CallToolResult r = call(echo, false, Map.of("text", "hi", "verbose", true));
        assertEquals(2, r.content().size());
    }

    @Test
    void toolExceptionBecomesErrorResult() {
        McpSchema.CallToolResult r = call(failing, false, Map.of());
        assertTrue(r.isError());
        assertEquals("Workspace 'x' not found", ((McpSchema.TextContent) r.content().get(0)).text());
    }

    @Test
    void imagePayloadBecomesImageContent() {
        GsTool img = new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_img", Schema.object().build()).description("img").build();
            }
            public Object call(ToolContext ctx, Args args) {
                return new Payloads.ImagePayload(new byte[] {1, 2, 3}, "image/png");
            }
        };
        McpSchema.CallToolResult r = call(img, false, Map.of());
        McpSchema.ImageContent c = (McpSchema.ImageContent) r.content().get(0);
        assertEquals("image/png", c.mimeType());
        assertEquals("AQID", c.data());
    }
}
