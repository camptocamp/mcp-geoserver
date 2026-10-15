package org.geoserver.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PayloadsTest {
    @Test
    void imagePayloadComparesBytes() {
        Payloads.ImagePayload a = new Payloads.ImagePayload(new byte[] {1, 2}, "image/png");
        assertEquals(a, new Payloads.ImagePayload(new byte[] {1, 2}, "image/png"));
        assertEquals(a.hashCode(), new Payloads.ImagePayload(new byte[] {1, 2}, "image/png").hashCode());
        assertNotEquals(a, new Payloads.ImagePayload(new byte[] {1, 3}, "image/png"));
        assertNotEquals(a, new Payloads.ImagePayload(new byte[] {1, 2}, "image/jpeg"));
        assertNotEquals(null, a);
        assertTrue(a.toString().contains("bytes=2"));
        assertTrue(new Payloads.ImagePayload(null, "image/png").toString().contains("bytes=null"));
    }
}
