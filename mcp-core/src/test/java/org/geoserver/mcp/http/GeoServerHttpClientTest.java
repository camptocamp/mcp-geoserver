package org.geoserver.mcp.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.geoserver.mcp.tool.ToolException;

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
        assertEquals("http://internal", client("http://internal/", "http://proxy").baseUrl(ctx("http://evil.example.com")));
        assertEquals("http://proxy", client("", "http://proxy/").baseUrl(ctx("http://evil.example.com")));
        assertEquals("http://localhost:8080/geoserver", client("", null).baseUrl(ctx("http://localhost:8080/geoserver")));
        assertEquals("http://127.0.0.1:8080", client("", null).baseUrl(ctx("http://127.0.0.1:8080")));
    }

    @Test
    void requestFallbackRefusesNonLoopbackHost() {
        ToolException e = assertThrows(ToolException.class, () -> client("", null).baseUrl(ctx("http://192.168.176.1:9110/geoserver")));
        assertTrue(e.getMessage().contains("mcp.internalBaseUrl"), e.getMessage());
        assertThrows(ToolException.class, () -> client("", null).baseUrl(ctx("http://evil.example.com")));
        assertThrows(ToolException.class, () -> client("", "   ").baseUrl(ctx("http://169.254.169.254")));
    }

    @Test
    void isLoopbackAcceptsOnlyLoopbackLiteralsAndLocalhost() {
        assertTrue(GeoServerHttpClient.isLoopback("localhost"));
        assertTrue(GeoServerHttpClient.isLoopback("127.0.0.1"));
        assertTrue(GeoServerHttpClient.isLoopback("127.5.6.7"));
        assertTrue(GeoServerHttpClient.isLoopback("[::1]"));
        assertFalse(GeoServerHttpClient.isLoopback("192.168.1.1"));
        assertFalse(GeoServerHttpClient.isLoopback("evil.example.com"));
        assertFalse(GeoServerHttpClient.isLoopback(null));
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

    @Test
    void responseComparesBody() {
        GeoServerHttpClient.Response r = new GeoServerHttpClient.Response(200, "text/plain", new byte[] {65});
        assertEquals(r, new GeoServerHttpClient.Response(200, "text/plain", new byte[] {65}));
        assertEquals(r.hashCode(), new GeoServerHttpClient.Response(200, "text/plain", new byte[] {65}).hashCode());
        assertNotEquals(r, new GeoServerHttpClient.Response(404, "text/plain", new byte[] {65}));
        assertNotEquals(r, new GeoServerHttpClient.Response(200, "text/html", new byte[] {65}));
        assertNotEquals(r, new GeoServerHttpClient.Response(200, "text/plain", new byte[] {66}));
        assertNotEquals(null, r);
        assertTrue(r.toString().contains("body=1 bytes"));
        assertTrue(new GeoServerHttpClient.Response(204, null, null).toString().contains("body=null"));
    }
}
