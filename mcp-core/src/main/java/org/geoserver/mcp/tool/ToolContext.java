package org.geoserver.mcp.tool;

import org.geoserver.mcp.settings.McpSettings;

public record ToolContext(boolean verbose, CommandTrace trace, String authorization, String requestBaseUrl, McpSettings settings) {
    public static final String AUTHORIZATION_KEY = "authorization";
    public static final String REQUEST_BASE_URL_KEY = "requestBaseUrl";

    public ToolContext(boolean verbose, CommandTrace trace, String authorization, String requestBaseUrl) {
        this(verbose, trace, authorization, requestBaseUrl, McpSettings.DEFAULTS);
    }
}
