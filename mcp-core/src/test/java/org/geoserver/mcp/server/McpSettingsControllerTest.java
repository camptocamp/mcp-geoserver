package org.geoserver.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.geoserver.mcp.security.McpGate;
import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class McpSettingsControllerTest {
    McpSettingsStore settings = mock(McpSettingsStore.class);
    McpGate gate = mock(McpGate.class);
    McpSettingsController controller = new McpSettingsController(settings, gate, new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(settings.load()).thenReturn(McpSettings.DEFAULTS);
    }

    @Test
    void getRequiresAdmin() throws Exception {
        when(gate.isAdmin()).thenReturn(false);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.get(resp);
        assertEquals(403, resp.getStatus());
    }

    @Test
    void getReturnsJson() throws Exception {
        when(gate.isAdmin()).thenReturn(true);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.get(resp);
        assertEquals(200, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"enabled\":false"), resp.getContentAsString());
        assertTrue(resp.getContentAsString().contains("\"allowedRoles\":[\"ROLE_ADMINISTRATOR\"]"), resp.getContentAsString());
    }

    @Test
    void putMergesPartialBody() throws Exception {
        when(gate.isAdmin()).thenReturn(true);
        MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/geoserver/rest/mcp/settings");
        req.setContentType("application/json");
        req.setContent("{\"enabled\":true,\"verbose\":true}".getBytes());
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.put(req, resp);
        assertEquals(200, resp.getStatus());
        ArgumentCaptor<McpSettings> saved = ArgumentCaptor.forClass(McpSettings.class);
        verify(settings).save(saved.capture());
        assertTrue(saved.getValue().enabled());
        assertTrue(saved.getValue().verbose());
        assertEquals(List.of("ROLE_ADMINISTRATOR"), saved.getValue().allowedRoles());
    }

    @Test
    void putRequiresAdmin() throws Exception {
        when(gate.isAdmin()).thenReturn(false);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.put(new MockHttpServletRequest("PUT", "/geoserver/rest/mcp/settings"), resp);
        assertEquals(403, resp.getStatus());
    }

    @Test
    void putReplacesAllowedRoles() throws Exception {
        when(gate.isAdmin()).thenReturn(true);
        MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/geoserver/rest/mcp/settings");
        req.setContent("{\"allowedRoles\":[\"ROLE_A\",\"ROLE_B\"]}".getBytes());
        controller.put(req, new MockHttpServletResponse());
        ArgumentCaptor<McpSettings> saved = ArgumentCaptor.forClass(McpSettings.class);
        verify(settings).save(saved.capture());
        assertEquals(List.of("ROLE_A", "ROLE_B"), saved.getValue().allowedRoles());
    }
}
