package org.geoserver.mcp.tool;

public final class Payloads {
    private Payloads() {}

    public record RawJson(String json) {}

    public record ImagePayload(byte[] bytes, String mimeType) {}
}
