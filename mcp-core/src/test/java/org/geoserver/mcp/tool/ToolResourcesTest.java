package org.geoserver.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.junit.jupiter.api.Test;

class ToolResourcesTest {
    final McpTransportContext none = McpTransportContext.create(Map.of());

    GsTool tool(String name) {
        return new GsTool() {
            public McpSchema.Tool definition() {
                return McpSchema.Tool.builder(name, Schema.object().build()).description(name).build();
            }
            public Object call(ToolContext ctx, Args args) {
                if ("missing".equals(args.string("name", ""))) throw new ToolException("Layer 'missing' not found");
                return Map.of("tool", name, "name", args.string("name", ""));
            }
        };
    }

    ToolResources resources(String... names) {
        McpSettingsStore settings = mock(McpSettingsStore.class);
        when(settings.load()).thenReturn(McpSettings.DEFAULTS);
        return new ToolResources(List.of(names).stream().map(this::tool).toList(), settings, new ObjectMapper());
    }

    @Test
    void onlyResourcesBackedByAToolAreListed() {
        ToolResources r = resources("gs_list_layers", "gs_get_layer");
        assertEquals(List.of("geoserver://layers"), r.fixed().stream().map(s -> s.resource().uri()).toList());
        assertEquals(List.of("geoserver://layers/{name}"), r.templates().stream().map(s -> s.resourceTemplate().uriTemplate()).toList());
    }

    @Test
    void readingATemplateCallsTheToolWithTheDecodedName() {
        var spec = resources("gs_get_layer").templates().get(0);
        McpSchema.ReadResourceResult result = spec.readHandler().apply(none, new McpSchema.ReadResourceRequest("geoserver://layers/topp%3Astates"));
        McpSchema.TextResourceContents text = (McpSchema.TextResourceContents) result.contents().get(0);
        assertEquals("geoserver://layers/topp%3Astates", text.uri());
        assertTrue(text.text().contains("\"name\":\"topp:states\""), text.text());
    }

    @Test
    void toolErrorsAndBadUrisFail() {
        var spec = resources("gs_get_layer").templates().get(0);
        var missing = new McpSchema.ReadResourceRequest("geoserver://layers/missing");
        assertThrows(ToolException.class, () -> spec.readHandler().apply(none, missing));
        assertThrows(ToolException.class, () -> ToolResources.variable("geoserver://layers/a/b", "layers/"));
    }
}
