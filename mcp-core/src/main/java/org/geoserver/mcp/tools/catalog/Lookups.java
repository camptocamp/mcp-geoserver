package org.geoserver.mcp.tools.catalog;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.LayerGroupInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.mcp.tool.ToolException;

public final class Lookups {
    private Lookups() {}

    public static WorkspaceInfo workspace(Catalog catalog, String name) {
        WorkspaceInfo ws = catalog.getWorkspaceByName(name);
        if (ws == null) throw new ToolException("Workspace '" + name + "' not found");
        return ws;
    }

    public static StoreInfo store(Catalog catalog, String workspace, String name) {
        WorkspaceInfo ws = workspace(catalog, workspace);
        StoreInfo store = catalog.getStoreByName(ws, name, StoreInfo.class);
        if (store == null) throw new ToolException("Store '" + workspace + ":" + name + "' not found");
        return store;
    }

    public static LayerInfo layer(Catalog catalog, String name) {
        LayerInfo layer = catalog.getLayerByName(name);
        if (layer == null) throw new ToolException("Layer '" + name + "' not found");
        return layer;
    }

    public static StyleInfo style(Catalog catalog, String workspace, String name) {
        StyleInfo style = workspace == null || workspace.isBlank()
                ? catalog.getStyleByName(name)
                : catalog.getStyleByName(workspace(catalog, workspace), name);
        if (style == null) throw new ToolException("Style '" + qualified(workspace, name) + "' not found");
        return style;
    }

    public static LayerGroupInfo layerGroup(Catalog catalog, String workspace, String name) {
        LayerGroupInfo lg = workspace == null || workspace.isBlank()
                ? catalog.getLayerGroupByName(name)
                : catalog.getLayerGroupByName(workspace(catalog, workspace), name);
        if (lg == null) throw new ToolException("Layer group '" + qualified(workspace, name) + "' not found");
        return lg;
    }

    public static String qualified(String workspace, String name) {
        return workspace == null || workspace.isBlank() ? name : workspace + ":" + name;
    }
}
