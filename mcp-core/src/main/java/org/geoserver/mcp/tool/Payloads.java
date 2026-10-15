package org.geoserver.mcp.tool;

public final class Payloads {
    private Payloads() {}

    public record RawJson(String json) {}

    public record ImagePayload(byte[] bytes, String mimeType) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ImagePayload other && java.util.Arrays.equals(bytes, other.bytes) && java.util.Objects.equals(mimeType, other.mimeType);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Arrays.hashCode(bytes) + java.util.Objects.hashCode(mimeType);
        }

        @Override
        public String toString() {
            return "ImagePayload[mimeType=" + mimeType + ", bytes=" + bytes.length + "]";
        }
    }
}
