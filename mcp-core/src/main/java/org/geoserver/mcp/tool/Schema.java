package org.geoserver.mcp.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Schema {
    private static final String STRING = "string";

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private Schema() {}

    public static Schema object() {
        return new Schema();
    }

    public Schema string(String name, String description, boolean req) {
        return add(name, prop(STRING, description), req);
    }

    public Schema integer(String name, String description, boolean req) {
        return add(name, prop("integer", description), req);
    }

    public Schema bool(String name, String description, boolean req) {
        return add(name, prop("boolean", description), req);
    }

    public Schema stringArray(String name, String description, boolean req) {
        return add(name, prop("array", description, "items", Map.of("type", STRING)), req);
    }

    public Schema numberArray(String name, String description, boolean req) {
        return add(name, prop("array", description, "items", Map.of("type", "number")), req);
    }

    public Schema map(String name, String description, boolean req) {
        return add(name, prop("object", description, "additionalProperties", true), req);
    }

    public Schema enumeration(String name, String description, boolean req, String... values) {
        return add(name, prop(STRING, description, "enum", List.of(values)), req);
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, Keys.DESCRIPTION, description);
    }

    private static Map<String, Object> prop(String type, String description, String key, Object value) {
        return Map.of("type", type, Keys.DESCRIPTION, description, key, value);
    }

    private Schema add(String name, Map<String, Object> def, boolean req) {
        properties.put(name, def);
        if (req) required.add(name);
        return this;
    }

    public Map<String, Object> build() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", new LinkedHashMap<>(properties));
        if (!required.isEmpty()) s.put("required", List.copyOf(required));
        return s;
    }
}
