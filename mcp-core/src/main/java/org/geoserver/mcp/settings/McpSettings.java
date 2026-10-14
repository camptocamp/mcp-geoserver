package org.geoserver.mcp.settings;

import java.util.List;

public record McpSettings(boolean enabled, List<String> allowedRoles, boolean verbose, String internalBaseUrl) {

    public static final String ADMIN_ROLE = "ROLE_ADMINISTRATOR";
    public static final McpSettings DEFAULTS = new McpSettings(false, List.of(ADMIN_ROLE), false, "");

    public McpSettings {
        allowedRoles = allowedRoles == null || allowedRoles.isEmpty() ? List.of(ADMIN_ROLE) : List.copyOf(allowedRoles);
        internalBaseUrl = internalBaseUrl == null ? "" : internalBaseUrl.trim();
    }

    public McpSettings withEnabled(boolean value) {
        return new McpSettings(value, allowedRoles, verbose, internalBaseUrl);
    }
}
