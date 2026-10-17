package org.geoserver.mcp.settings;

import java.util.List;

public record McpSettings(boolean enabled, List<String> allowedRoles, boolean verbose, String internalBaseUrl,
        boolean readOnly, int maxListResults, int maxFeatures) {

    public static final String ADMIN_ROLE = "ROLE_ADMINISTRATOR";
    public static final int DEFAULT_MAX_LIST_RESULTS = 100;
    public static final int DEFAULT_MAX_FEATURES = 10000;
    public static final McpSettings DEFAULTS = new McpSettings(false, List.of(ADMIN_ROLE), false, "");

    public McpSettings {
        allowedRoles = allowedRoles == null || allowedRoles.isEmpty() ? List.of(ADMIN_ROLE) : List.copyOf(allowedRoles);
        internalBaseUrl = internalBaseUrl == null ? "" : internalBaseUrl.trim();
        maxListResults = maxListResults < 1 ? DEFAULT_MAX_LIST_RESULTS : maxListResults;
        maxFeatures = maxFeatures < 1 ? DEFAULT_MAX_FEATURES : maxFeatures;
    }

    public McpSettings(boolean enabled, List<String> allowedRoles, boolean verbose, String internalBaseUrl) {
        this(enabled, allowedRoles, verbose, internalBaseUrl, false, DEFAULT_MAX_LIST_RESULTS, DEFAULT_MAX_FEATURES);
    }

    public McpSettings withEnabled(boolean value) {
        return new McpSettings(value, allowedRoles, verbose, internalBaseUrl, readOnly, maxListResults, maxFeatures);
    }

    public McpSettings withLimits(boolean readOnly, int maxListResults, int maxFeatures) {
        return new McpSettings(enabled, allowedRoles, verbose, internalBaseUrl, readOnly, maxListResults, maxFeatures);
    }
}
