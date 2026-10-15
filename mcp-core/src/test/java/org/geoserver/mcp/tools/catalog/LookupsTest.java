package org.geoserver.mcp.tools.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.LayerGroupInfo;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.mcp.tool.ToolException;
import org.junit.jupiter.api.Test;

class LookupsTest {
    final Catalog catalog = mock(Catalog.class);
    final WorkspaceInfo ws = mock(WorkspaceInfo.class);

    @Test
    void missingObjectsThrowWithQualifiedName() {
        assertThrows(ToolException.class, () -> Lookups.workspace(catalog, "nope"));
        when(catalog.getWorkspaceByName("w")).thenReturn(ws);
        ToolException e = assertThrows(ToolException.class, () -> Lookups.store(catalog, "w", "s"));
        assertEquals("Store 'w:s' not found", e.getMessage());
        e = assertThrows(ToolException.class, () -> Lookups.style(catalog, "w", "st"));
        assertEquals("Style 'w:st' not found", e.getMessage());
        e = assertThrows(ToolException.class, () -> Lookups.layerGroup(catalog, "", "g"));
        assertEquals("Layer group 'g' not found", e.getMessage());
        assertThrows(ToolException.class, () -> Lookups.layerGroup(catalog, "w", "g"));
    }

    @Test
    void globalAndWorkspacedLookups() {
        StyleInfo global = mock(StyleInfo.class);
        StyleInfo local = mock(StyleInfo.class);
        LayerGroupInfo lg = mock(LayerGroupInfo.class);
        StoreInfo store = mock(StoreInfo.class);
        when(catalog.getWorkspaceByName("w")).thenReturn(ws);
        when(catalog.getStyleByName("st")).thenReturn(global);
        when(catalog.getStyleByName(ws, "st")).thenReturn(local);
        when(catalog.getLayerGroupByName("g")).thenReturn(lg);
        when(catalog.getStoreByName(ws, "s", StoreInfo.class)).thenReturn(store);
        assertSame(global, Lookups.style(catalog, null, "st"));
        assertSame(local, Lookups.style(catalog, "w", "st"));
        assertSame(lg, Lookups.layerGroup(catalog, null, "g"));
        assertSame(store, Lookups.store(catalog, "w", "s"));
        assertEquals("g", Lookups.qualified(" ", "g"));
    }
}
