package org.geoserver.mcp.tools.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.LayerGroupInfo;
import org.geoserver.catalog.NamespaceInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.config.GeoServer;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.CommandTrace;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.ToolContext;
import org.geoserver.mcp.tool.ToolException;
import org.junit.jupiter.api.Test;

class AdminToolsDeleteTest {
    final Catalog catalog = mock(Catalog.class);
    final WorkspaceInfo ws = mock(WorkspaceInfo.class);
    final CommandTrace trace = new CommandTrace();
    final GsTool delete = new AdminTools(catalog, mock(GeoServer.class)).tools().stream()
            .filter(t -> t.definition().name().equals("gs_delete")).findFirst().orElseThrow();

    AdminToolsDeleteTest() {
        when(ws.getName()).thenReturn("w");
        when(catalog.getWorkspaceByName("w")).thenReturn(ws);
    }

    Object call(Map<String, Object> args) throws Exception {
        return delete.call(new ToolContext(true, trace, null, ""), new Args(args));
    }

    @Test
    void deletesWorkspaceAndItsNamespace() throws Exception {
        NamespaceInfo ns = mock(NamespaceInfo.class);
        when(catalog.getNamespaceByPrefix("w")).thenReturn(ns);
        call(Map.of("kind", "workspace", "name", "w"));
        verify(catalog).remove(ws);
        verify(catalog).remove(ns);
        assertTrue(trace.lines().get(0).contains("/rest/workspaces/w?recurse=false"));
    }

    @Test
    void deletesGlobalAndWorkspacedStyle() throws Exception {
        StyleInfo global = mock(StyleInfo.class);
        StyleInfo local = mock(StyleInfo.class);
        when(catalog.getStyleByName("s")).thenReturn(global);
        when(catalog.getStyleByName(ws, "s")).thenReturn(local);
        call(Map.of("kind", "style", "name", "s"));
        call(Map.of("kind", "style", "workspace", "w", "name", "s"));
        verify(catalog).remove(global);
        verify(catalog).remove(local);
        assertTrue(trace.lines().get(0).contains("/rest/styles/s"));
        assertTrue(trace.lines().get(1).contains("/rest/workspaces/w/styles/s"));
    }

    @Test
    void deletesLayerGroup() throws Exception {
        LayerGroupInfo lg = mock(LayerGroupInfo.class);
        when(catalog.getLayerGroupByName(ws, "g")).thenReturn(lg);
        call(Map.of("kind", "layergroup", "workspace", "w", "name", "g"));
        verify(catalog).remove(lg);
        assertTrue(trace.lines().get(0).contains("/rest/workspaces/w/layergroups/g"));
    }

    @Test
    void deletesEmptyStoreAndRefusesNonEmptyOne() throws Exception {
        DataStoreInfo store = mock(DataStoreInfo.class);
        when(store.getName()).thenReturn("s");
        when(catalog.getStoreByName(ws, "s", org.geoserver.catalog.StoreInfo.class)).thenReturn(store);
        when(catalog.getResourcesByStore(store, ResourceInfo.class)).thenReturn(List.of());
        call(Map.of("kind", "store", "workspace", "w", "name", "s"));
        verify(catalog).remove(store);

        when(catalog.getResourcesByStore(store, ResourceInfo.class)).thenReturn(List.of(mock(ResourceInfo.class)));
        Map<String, Object> args = Map.of("kind", "store", "workspace", "w", "name", "s");
        ToolException e = assertThrows(ToolException.class, () -> call(args));
        assertTrue(e.getMessage().contains("recurse=true"));
    }

    @Test
    void catalogFailureBecomesToolError() {
        StyleInfo style = mock(StyleInfo.class);
        when(catalog.getStyleByName("s")).thenReturn(style);
        doThrow(new IllegalArgumentException("in use")).when(catalog).remove(style);
        Map<String, Object> args = Map.of("kind", "style", "name", "s");
        ToolException e = assertThrows(ToolException.class, () -> call(args));
        assertEquals("Cannot delete style 's': in use. Pass recurse=true to cascade.", e.getMessage());
    }

    @Test
    void unknownKindIsRejected() {
        Map<String, Object> args = Map.of("kind", "map", "name", "x");
        assertThrows(ToolException.class, () -> call(args));
    }

    @Test
    void restPathPerKind() {
        assertEquals("/rest/workspaces/w/coveragestores/c", AdminTools.restPath("store", "w", "c", mock(CoverageStoreInfo.class)));
        assertEquals("/rest/workspaces/w/datastores/d", AdminTools.restPath("store", "w", "d", mock(DataStoreInfo.class)));
        assertEquals("/rest/layergroups/g", AdminTools.restPath("layergroup", null, "g", null));
    }
}
