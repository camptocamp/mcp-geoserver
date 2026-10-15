package org.geoserver.mcp.tool;

import java.util.Arrays;
import java.util.Objects;

public final class Payloads {
    private Payloads() {}

    public record RawJson(String json) {}

    public record ImagePayload(byte[] bytes, String mimeType) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ImagePayload other && Arrays.equals(bytes, other.bytes) && Objects.equals(mimeType, other.mimeType);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(bytes) + Objects.hashCode(mimeType);
        }

        @Override
        public String toString() {
            return "ImagePayload[mimeType=" + mimeType + ", bytes=" + (bytes == null ? "null" : bytes.length) + "]";
        }
    }
}
