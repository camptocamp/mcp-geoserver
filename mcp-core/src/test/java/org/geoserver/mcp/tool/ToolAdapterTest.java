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
                ToolContext.AUTHORIZATION_KEY, "Basic abc", ToolContext.REQUEST_BASE_URL_KEY, "http://localhost:8080/geoserver"));
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

    @Test
    void unexpectedExceptionWithoutMessageNamesItsType() {
        GsTool broken = new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_broken", Schema.object().build()).description("Broken").build();
            }
            public Object call(ToolContext ctx, Args args) {
                throw new IllegalStateException();
            }
        };
        McpSchema.CallToolResult r = ToolAdapter.invoke(broken, settings(false), json, McpTransportContext.create(Map.of()),
                McpSchema.CallToolRequest.builder("gs_broken").arguments(Map.of()).build());
        assertTrue(r.isError());
        assertEquals("gs_broken failed: IllegalStateException", ((McpSchema.TextContent) r.content().get(0)).text());
    }

    @Test
    void rawJsonAndStringPayloadsPassThrough() throws Exception {
        assertEquals("{\"a\":1}", ((McpSchema.TextContent) ToolAdapter.toContent(new Payloads.RawJson("{\"a\":1}"), json)).text());
        assertEquals("plain", ((McpSchema.TextContent) ToolAdapter.toContent("plain", json)).text());
    }

    @Test
    void explicitAnnotationsAreKept() {
        McpSchema.ToolAnnotations a = ToolHints.of("gs_delete");
        McpSchema.Tool t = ToolAdapter.withVerbose(McpSchema.Tool.builder("gs_custom", Schema.object().build()).annotations(a).build());
        assertEquals(a, t.annotations());
    }

    @Test
    void readOnlyModeRefusesWriteToolsOnly() {
        McpSettingsStore s = mock(McpSettingsStore.class);
        when(s.load()).thenReturn(new McpSettings(true, List.of("ROLE_ADMINISTRATOR"), false, "").withLimits(true, 500, 10000));
        GsTool write = new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_delete", Schema.object().build()).description("Delete").build();
            }
            public Object call(ToolContext ctx, Args args) {
                return Map.of("deleted", true);
            }
        };
        GsTool read = new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder("gs_status", Schema.object().build()).description("Status").build();
            }
            public Object call(ToolContext ctx, Args args) {
                return Map.of("ok", true);
            }
        };
        McpTransportContext none = McpTransportContext.create(Map.of());
        McpSchema.CallToolResult refused = ToolAdapter.invoke(write, s, json, none, McpSchema.CallToolRequest.builder("gs_delete").arguments(Map.of()).build());
        assertTrue(refused.isError());
        assertTrue(((McpSchema.TextContent) refused.content().get(0)).text().contains("read-only"));
        assertFalse(ToolAdapter.invoke(read, s, json, none, McpSchema.CallToolRequest.builder("gs_status").arguments(Map.of()).build()).isError());
    }

    @Test
    void longListsAreCappedAndReported() {
        Object capped = ToolAdapter.capLists(Map.of("layers", List.of(1, 2, 3), "name", "x"), 2);
        assertEquals(Map.of("layers", List.of(1, 2), "name", "x", "truncated", Map.of("layers", 3)), capped);
        Map<String, Object> small = Map.of("layers", List.of(1));
        assertEquals(small, ToolAdapter.capLists(small, 2));
        assertEquals("text", ToolAdapter.capLists("text", 2));
    }
}
