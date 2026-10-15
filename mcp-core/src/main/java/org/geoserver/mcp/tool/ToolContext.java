package org.geoserver.mcp.tool;

public record ToolContext(boolean verbose, CommandTrace trace, String authorization, String requestBaseUrl) {
    public static final String AUTHORIZATION_KEY = "authorization";
    public static final String REQUEST_BASE_URL_KEY = "requestBaseUrl";
}
