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
    static final String READ_ONLY = "mcp.readOnly";
    static final String MAX_LIST_RESULTS = "mcp.maxListResults";
    static final String MAX_FEATURES = "mcp.maxFeatures";
    private static final String FALSE = "false";

    private final GeoServer geoServer;

    public McpSettingsStore(GeoServer geoServer) {
        this.geoServer = geoServer;
    }

    public McpSettings load() {
        MetadataMap md = geoServer.getGlobal().getMetadata();
        if (md == null) return McpSettings.DEFAULTS;
        boolean enabled = Boolean.parseBoolean(string(md, ENABLED, FALSE));
        List<String> roles = Arrays.stream(string(md, ROLES, "").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        boolean verbose = Boolean.parseBoolean(string(md, VERBOSE, FALSE));
        String base = string(md, INTERNAL_BASE_URL, "");
        boolean readOnly = Boolean.parseBoolean(string(md, READ_ONLY, FALSE));
        int maxList = integer(md, MAX_LIST_RESULTS, McpSettings.DEFAULT_MAX_LIST_RESULTS);
        int maxFeatures = integer(md, MAX_FEATURES, McpSettings.DEFAULT_MAX_FEATURES);
        return new McpSettings(enabled, roles, verbose, base, readOnly, maxList, maxFeatures);
    }

    public void save(McpSettings settings) {
        GeoServerInfo global = geoServer.getGlobal();
        MetadataMap md = global.getMetadata();
        md.put(ENABLED, Boolean.toString(settings.enabled()));
        md.put(ROLES, String.join(",", settings.allowedRoles()));
        md.put(VERBOSE, Boolean.toString(settings.verbose()));
        md.put(INTERNAL_BASE_URL, settings.internalBaseUrl());
        md.put(READ_ONLY, Boolean.toString(settings.readOnly()));
        md.put(MAX_LIST_RESULTS, Integer.toString(settings.maxListResults()));
        md.put(MAX_FEATURES, Integer.toString(settings.maxFeatures()));
        geoServer.save(global);
    }

    private static int integer(MetadataMap md, String key, int fallback) {
        try {
            return Integer.parseInt(string(md, key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String string(MetadataMap md, String key, String fallback) {
        Object v = md.get(key);
        return v == null ? fallback : v.toString();
    }
}
