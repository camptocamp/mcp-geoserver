package org.geoserver.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpServerHolderTest {

    GsTool echo = new GsTool() {
        public McpSchema.Tool definition() {
            return McpSchema.Tool.builder("gs_echo", Schema.object().string("text", "Text", true).build())
                    .description("Echo").build();
        }
        public Object call(ToolContext ctx, Args args) {
            return Map.of("text", args.string("text"), "base", ctx.requestBaseUrl(), "auth", ctx.authorization());
        }
    };

    McpServerHolder holder() {
        McpSettingsStore s = mock(McpSettingsStore.class);
        when(s.load()).thenReturn(McpSettings.DEFAULTS.withEnabled(true));
        return new McpServerHolder(List.of(echo), s, new ObjectMapper());
    }

    MockHttpServletRequest post(String body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/geoserver/mcp");
        req.setContextPath("/geoserver");
        req.setServerName("localhost");
        req.setServerPort(8080);
        req.setScheme("http");
        req.setContentType("application/json");
        req.addHeader("Accept", "application/json, text/event-stream");
        req.addHeader("Authorization", "Basic YWRtaW46Z2Vvc2VydmVy");
        req.setContent(body.getBytes());
        return req;
    }

    @Test
    void toolsListOverServlet() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        holder().transport().service(post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"), resp);
        assertEquals(200, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"gs_echo\""), resp.getContentAsString());
    }

    @Test
    void toolsCallGetsRequestContext() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        holder().transport().service(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"gs_echo\",\"arguments\":{\"text\":\"hi\"}}}"), resp);
        assertEquals(200, resp.getStatus());
        String body = resp.getContentAsString();
        assertTrue(body.contains("http://localhost:8080/geoserver"), body);
        assertTrue(body.contains("Basic YWRtaW46Z2Vvc2VydmVy"), body);
    }

    @Test
    void getIsRejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/geoserver/mcp");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        holder().transport().service(req, resp);
        assertEquals(405, resp.getStatus());
    }
}
