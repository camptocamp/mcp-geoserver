package org.geoserver.mcp.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.geoserver.config.GeoServer;
import org.geoserver.config.GeoServerInfo;
import org.geoserver.config.SettingsInfo;
import org.geoserver.mcp.StubHttpServer;
import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.geoserver.mcp.tool.CommandTrace;
import org.geoserver.mcp.tool.ToolContext;
import org.junit.jupiter.api.Test;

class GeoServerHttpClientTest {
    GeoServer geoServer = mock(GeoServer.class);
    GeoServerInfo global = mock(GeoServerInfo.class);
    SettingsInfo settingsInfo = mock(SettingsInfo.class);
    McpSettingsStore store = mock(McpSettingsStore.class);

    GeoServerHttpClient client(String internal, String proxy) {
        when(geoServer.getGlobal()).thenReturn(global);
        when(global.getSettings()).thenReturn(settingsInfo);
        when(settingsInfo.getProxyBaseUrl()).thenReturn(proxy);
        when(store.load()).thenReturn(new McpSettings(true, List.of("ROLE_ADMINISTRATOR"), false, internal));
        return new GeoServerHttpClient(geoServer, store);
    }

    ToolContext ctx(String requestBase) {
        return new ToolContext(false, new CommandTrace(), "Basic abc", requestBase);
    }

    @Test
    void baseUrlPrefersInternalThenProxyThenRequest() {
        assertEquals("http://internal", client("http://internal/", "http://proxy").baseUrl(ctx("http://req")));
        assertEquals("http://proxy", client("", "http://proxy/").baseUrl(ctx("http://req")));
        assertEquals("http://req", client("", null).baseUrl(ctx("http://req")));
    }

    @Test
    void getForwardsAuthAndRecordsTrace() throws Exception {
        try (StubHttpServer stub = new StubHttpServer(r -> StubHttpServer.Reply.json("{\"ok\":true}"))) {
            GeoServerHttpClient c = client(stub.url(), null);
            ToolContext ctx = ctx("http://ignored");
            GeoServerHttpClient.Response resp = c.get(ctx, "/rest/about/version.json");
            assertEquals(200, resp.status());
            assertTrue(resp.contentType().startsWith("application/json"));
            assertEquals("Basic abc", stub.received.get(0).authorization());
            assertEquals("/geoserver/rest/about/version.json", stub.received.get(0).path());
            assertTrue(ctx.trace().lines().get(0).contains("'/rest/about/version.json'"), ctx.trace().lines().get(0));
        }
    }
}
