package org.geoserver.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.geoserver.mcp.security.McpGate;
import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.Schema;
import org.geoserver.mcp.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpControllerTest {
    McpSettingsStore settings = mock(McpSettingsStore.class);
    McpGate gate = mock(McpGate.class);
    GsTool ping = new GsTool() {
        public McpSchema.Tool definition() {
            return McpSchema.Tool.builder("gs_ping", Schema.object().build()).description("ping").build();
        }
        public Object call(ToolContext ctx, Args args) {
            return Map.of("ok", true);
        }
    };
    McpController controller = new McpController(settings, gate, new McpServerHolder(List.of(ping), settings, new ObjectMapper()));

    MockHttpServletRequest list() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/geoserver/mcp");
        req.setContextPath("/geoserver");
        req.setContentType("application/json");
        req.addHeader("Accept", "application/json, text/event-stream");
        req.setContent("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}".getBytes());
        return req;
    }

    @Test
    void disabledGives503() throws Exception {
        when(settings.load()).thenReturn(McpSettings.DEFAULTS);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.handle(list(), resp);
        assertEquals(503, resp.getStatus());
    }

    @Test
    void forbiddenGives403() throws Exception {
        when(settings.load()).thenReturn(McpSettings.DEFAULTS.withEnabled(true));
        when(gate.allowed(any())).thenReturn(false);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.handle(list(), resp);
        assertEquals(403, resp.getStatus());
    }

    @Test
    void allowedDelegatesToTransport() throws Exception {
        when(settings.load()).thenReturn(McpSettings.DEFAULTS.withEnabled(true));
        when(gate.allowed(any())).thenReturn(true);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.handle(list(), resp);
        assertEquals(200, resp.getStatus());
    }
}
