package org.geoserver.mcp.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Schema {
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private Schema() {}

    public static Schema object() {
        return new Schema();
    }

    public Schema string(String name, String description, boolean req) {
        return add(name, Map.of("type", Keys.STRING, Keys.DESCRIPTION, description), req);
    }

    public Schema integer(String name, String description, boolean req) {
        return add(name, Map.of("type", "integer", Keys.DESCRIPTION, description), req);
    }

    public Schema bool(String name, String description, boolean req) {
        return add(name, Map.of("type", "boolean", Keys.DESCRIPTION, description), req);
    }

    public Schema stringArray(String name, String description, boolean req) {
        return add(name, Map.of("type", "array", Keys.DESCRIPTION, description, "items", Map.of("type", Keys.STRING)), req);
    }

    public Schema numberArray(String name, String description, boolean req) {
        return add(name, Map.of("type", "array", Keys.DESCRIPTION, description, "items", Map.of("type", "number")), req);
    }

    public Schema map(String name, String description, boolean req) {
        return add(name, Map.of("type", "object", Keys.DESCRIPTION, description, "additionalProperties", true), req);
    }

    public Schema enumeration(String name, String description, boolean req, String... values) {
        return add(name, Map.of("type", Keys.STRING, Keys.DESCRIPTION, description, "enum", List.of(values)), req);
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
        s.put("required", List.copyOf(required));
        s.put("additionalProperties", false);
        return s;
    }
}
