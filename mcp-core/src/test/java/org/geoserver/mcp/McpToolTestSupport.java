package org.geoserver.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.geoserver.data.test.SystemTestData;
import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.CommandTrace;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.ToolContext;
import org.geoserver.test.GeoServerSystemTestSupport;

public abstract class McpToolTestSupport extends GeoServerSystemTestSupport {
    protected final ObjectMapper json = new ObjectMapper();

    @Override
    protected void setUpTestData(SystemTestData testData) throws Exception {
        super.setUpTestData(testData);
        testData.setUpDefaultRasterLayers();
    }

    @Override
    protected void onSetUp(SystemTestData testData) throws Exception {
        super.onSetUp(testData);
        new McpSettingsStore(getGeoServer()).save(McpSettings.DEFAULTS.withEnabled(true));
        login("admin", "geoserver", "ROLE_ADMINISTRATOR");
    }

    protected ToolContext ctx() {
        return new ToolContext(true, new CommandTrace(), "Basic YWRtaW46Z2Vvc2VydmVy", "http://localhost:8080/geoserver");
    }

    protected GsTool tool(List<GsTool> tools, String name) {
        return tools.stream().filter(t -> t.definition().name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no tool " + name));
    }

    protected JsonNode run(GsTool tool, Map<String, Object> args) throws Exception {
        Object payload = tool.call(ctx(), new Args(args));
        return json.readTree(json.writeValueAsString(payload));
    }

    protected Object runRaw(GsTool tool, ToolContext ctx, Map<String, Object> args) throws Exception {
        return tool.call(ctx, new Args(args));
    }
}
