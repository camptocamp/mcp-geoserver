package org.geoserver.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaTest {
    @Test
    void buildsObjectSchemaWithRequiredList() {
        Map<String, Object> s = Schema.object()
                .string("name", "Layer name", true)
                .integer("max", "Max features", false)
                .stringArray("layers", "Layer names", true)
                .build();
        assertEquals("object", s.get("type"));
        assertEquals(List.of("name", "layers"), s.get("required"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) s.get("properties");
        assertEquals(Map.of("type", "string", "description", "Layer name"), props.get("name"));
        assertEquals("array", ((Map<?, ?>) props.get("layers")).get("type"));
        assertEquals(Boolean.FALSE, s.get("additionalProperties"));
    }
}
