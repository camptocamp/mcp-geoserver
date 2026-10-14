package org.geoserver.mcp.tool;

public record ToolContext(boolean verbose, CommandTrace trace, String authorization, String requestBaseUrl) {
    public static final String AUTHORIZATION = "authorization";
    public static final String REQUEST_BASE_URL = "requestBaseUrl";
}
