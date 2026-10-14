package org.geoserver.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.geoserver.data.test.SystemTestData;
import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.geoserver.security.GeoServerSecurityFilterChainProxy;
import org.geoserver.security.impl.GeoServerUser;
import org.geoserver.security.GeoServerUserGroupService;
import org.geoserver.security.GeoServerUserGroupStore;
import org.geoserver.test.GeoServerSystemTestSupport;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class McpEndpointTest extends GeoServerSystemTestSupport {
    ObjectMapper json = new ObjectMapper();

    @Override
    protected List<Filter> getFilters() {
        return List.of(applicationContext.getBean(GeoServerSecurityFilterChainProxy.class));
    }

    @Override
    protected void onSetUp(SystemTestData testData) throws Exception {
        super.onSetUp(testData);
        new McpSettingsStore(getGeoServer()).save(McpSettings.DEFAULTS.withEnabled(true));
    }

    MockHttpServletResponse mcp(String body, String user, String password) throws Exception {
        MockHttpServletRequest req = createRequest("mcp");
        req.setMethod("POST");
        req.setContentType("application/json");
        req.addHeader("Accept", "application/json, text/event-stream");
        if (user != null) {
            String token = java.util.Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            req.addHeader("Authorization", "Basic " + token);
        }
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        return dispatch(req);
    }

    @Test
    public void toolsListAsAdmin() throws Exception {
        MockHttpServletResponse r = mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}", "admin", "geoserver");
        assertEquals(r.getContentAsString(), 200, r.getStatus());
        JsonNode doc = json.readTree(r.getContentAsString());
        assertTrue(doc.get("result").get("tools").size() >= 30);
    }

    @Test
    public void toolsCallListsWorkspaces() throws Exception {
        MockHttpServletResponse r = mcp("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"gs_list_workspaces\",\"arguments\":{}}}", "admin", "geoserver");
        assertEquals(200, r.getStatus());
        JsonNode result = json.readTree(r.getContentAsString()).get("result");
        assertFalse(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("\"name\":\"cite\""));
    }

    @Test
    public void anonymousIsForbidden() throws Exception {
        MockHttpServletResponse r = mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}", null, null);
        assertEquals(403, r.getStatus());
    }

    @Test
    public void authenticatedUserWithoutAllowedRoleIsForbidden() throws Exception {
        GeoServerUserGroupService ugService = getSecurityManager().loadUserGroupService("default");
        GeoServerUserGroupStore store = ugService.createStore();
        GeoServerUser user = store.createUserObject("plain", "plainpw", true);
        store.addUser(user);
        store.store();
        MockHttpServletResponse r = mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}", "plain", "plainpw");
        assertEquals(403, r.getStatus());
    }

    @Test
    public void disabledIsUnavailable() throws Exception {
        new McpSettingsStore(getGeoServer()).save(McpSettings.DEFAULTS);
        try {
            MockHttpServletResponse r = mcp("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}", "admin", "geoserver");
            assertEquals(503, r.getStatus());
        } finally {
            new McpSettingsStore(getGeoServer()).save(McpSettings.DEFAULTS.withEnabled(true));
        }
    }

    MockHttpServletResponse rest(String method, String body) throws Exception {
        MockHttpServletRequest req = createRequest("rest/mcp/settings");
        req.setMethod(method);
        req.addHeader("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString("admin:geoserver".getBytes(StandardCharsets.UTF_8)));
        if (body != null) {
            req.setContentType("application/json");
            req.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        return dispatch(req);
    }

    @Test
    public void restSettingsRoundTrip() throws Exception {
        MockHttpServletResponse get = rest("GET", null);
        assertEquals(200, get.getStatus());
        assertTrue(get.getContentAsString().contains("\"enabled\":true"));
        MockHttpServletResponse put = rest("PUT", "{\"verbose\":true}");
        assertEquals(200, put.getStatus());
        assertTrue(new McpSettingsStore(getGeoServer()).load().verbose());
    }
}
