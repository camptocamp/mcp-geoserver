package org.geoserver.mcp.tool;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Set;

public final class ToolHints {
    private static final Set<String> READ = Set.of(
            "gs_status", "gs_list_workspaces", "gs_get_workspace", "gs_list_stores", "gs_get_store",
            "gs_list_layers", "gs_get_layer", "gs_list_styles", "gs_get_style", "gs_list_layer_groups",
            "gs_get_layer_group", "gs_list_store_types", "gs_list_modules", "gs_query_features", "gs_get_map",
            "gs_gwc_list_tile_layers", "gs_gwc_get_tile_layer", "gs_gwc_list_gridsets", "gs_gwc_list_tasks");
    private static final Set<String> ADDITIVE = Set.of(
            "gs_create_workspace", "gs_create_store", "gs_publish_layer", "gs_update_layer",
            "gs_create_layer_group", "gs_gwc_set_tile_layer", "gs_reload");
    private static final Set<String> DESTRUCTIVE = Set.of("gs_delete", "gs_put_style", "gs_gwc_seed", "gs_gwc_kill_tasks");

    private ToolHints() {}

    public static McpSchema.ToolAnnotations of(String name) {
        if (READ.contains(name)) return hints(true, false, true);
        if (ADDITIVE.contains(name)) return hints(false, false, true);
        if (DESTRUCTIVE.contains(name)) return hints(false, true, !name.equals("gs_gwc_seed"));
        return null;
    }

    private static McpSchema.ToolAnnotations hints(boolean readOnly, boolean destructive, boolean idempotent) {
        return new McpSchema.ToolAnnotations(null, readOnly, destructive, idempotent, false, null);
    }
}
