package org.geoserver.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.geoserver.mcp.http.GeoServerHttpClient;
import org.junit.jupiter.api.Test;

class PayloadsTest {
    @Test
    void imagePayloadComparesBytes() {
        Payloads.ImagePayload a = new Payloads.ImagePayload(new byte[] {1, 2}, "image/png");
        assertEquals(a, new Payloads.ImagePayload(new byte[] {1, 2}, "image/png"));
        assertEquals(a.hashCode(), new Payloads.ImagePayload(new byte[] {1, 2}, "image/png").hashCode());
        assertNotEquals(a, new Payloads.ImagePayload(new byte[] {1, 3}, "image/png"));
        assertNotEquals(a, new Payloads.ImagePayload(new byte[] {1, 2}, "image/jpeg"));
        assertNotEquals(a, "image/png");
        assertTrue(a.toString().contains("bytes=2"));
    }

    @Test
    void responseComparesBody() {
        GeoServerHttpClient.Response r = new GeoServerHttpClient.Response(200, "text/plain", new byte[] {65});
        assertEquals(r, new GeoServerHttpClient.Response(200, "text/plain", new byte[] {65}));
        assertEquals(r.hashCode(), new GeoServerHttpClient.Response(200, "text/plain", new byte[] {65}).hashCode());
        assertNotEquals(r, new GeoServerHttpClient.Response(404, "text/plain", new byte[] {65}));
        assertNotEquals(r, new GeoServerHttpClient.Response(200, "text/html", new byte[] {65}));
        assertNotEquals(r, new GeoServerHttpClient.Response(200, "text/plain", new byte[] {66}));
        assertNotEquals(r, "A");
        assertTrue(r.toString().contains("body=1 bytes"));
    }
}
