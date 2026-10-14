package org.geoserver.mcp.settings;

import java.util.Arrays;
import java.util.List;
import org.geoserver.catalog.MetadataMap;
import org.geoserver.config.GeoServer;
import org.geoserver.config.GeoServerInfo;

public class McpSettingsStore {
    static final String ENABLED = "mcp.enabled";
    static final String ROLES = "mcp.allowedRoles";
    static final String VERBOSE = "mcp.verbose";
    static final String INTERNAL_BASE_URL = "mcp.internalBaseUrl";

    private final GeoServer geoServer;

    public McpSettingsStore(GeoServer geoServer) {
        this.geoServer = geoServer;
    }

    public McpSettings load() {
        MetadataMap md = geoServer.getGlobal().getMetadata();
        if (md == null) return McpSettings.DEFAULTS;
        boolean enabled = Boolean.parseBoolean(string(md, ENABLED, "false"));
        List<String> roles = Arrays.stream(string(md, ROLES, "").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        boolean verbose = Boolean.parseBoolean(string(md, VERBOSE, "false"));
        String base = string(md, INTERNAL_BASE_URL, "");
        return new McpSettings(enabled, roles, verbose, base);
    }

    public void save(McpSettings settings) {
        GeoServerInfo global = geoServer.getGlobal();
        MetadataMap md = global.getMetadata();
        md.put(ENABLED, Boolean.toString(settings.enabled()));
        md.put(ROLES, String.join(",", settings.allowedRoles()));
        md.put(VERBOSE, Boolean.toString(settings.verbose()));
        md.put(INTERNAL_BASE_URL, settings.internalBaseUrl());
        geoServer.save(global);
    }

    private static String string(MetadataMap md, String key, String fallback) {
        Object v = md.get(key);
        return v == null ? fallback : v.toString();
    }
}
