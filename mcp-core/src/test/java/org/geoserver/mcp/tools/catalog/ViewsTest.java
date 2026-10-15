package org.geoserver.mcp.tools.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerGroupInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.PublishedInfo;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WMSStoreInfo;
import org.geoserver.catalog.WMTSStoreInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geotools.util.Version;
import org.junit.jupiter.api.Test;

class ViewsTest {
    @Test
    void workspaceWithoutDefaultOrNamespace() {
        WorkspaceInfo ws = mock(WorkspaceInfo.class);
        when(ws.getName()).thenReturn("w");
        Map<String, Object> m = Views.workspace(ws, mock(Catalog.class));
        assertEquals(false, m.get("default"));
        assertNull(m.get("namespaceUri"));
    }

    @Test
    void storeKinds() {
        assertEquals("wmts", Views.storeKind(mock(WMTSStoreInfo.class)));
        assertEquals("wms", Views.storeKind(mock(WMSStoreInfo.class)));
        assertEquals("unknown", Views.storeKind(mock(StoreInfo.class)));
    }

    @Test
    void layerWithoutTypeStyleOrReadableAttributes() throws Exception {
        WorkspaceInfo ws = mock(WorkspaceInfo.class);
        when(ws.getName()).thenReturn("w");
        DataStoreInfo store = mock(DataStoreInfo.class);
        when(store.getWorkspace()).thenReturn(ws);
        FeatureTypeInfo ft = mock(FeatureTypeInfo.class);
        when(ft.getStore()).thenReturn(store);
        when(ft.attributes()).thenThrow(new java.io.IOException("offline"));
        StyleInfo extra = mock(StyleInfo.class);
        when(extra.prefixedName()).thenReturn("w:extra");
        LayerInfo l = mock(LayerInfo.class);
        when(l.getResource()).thenReturn(ft);
        when(l.getStyles()).thenReturn(new LinkedHashSet<>(Set.of(extra)));
        Map<String, Object> m = Views.layer(l);
        assertEquals("unknown", m.get("type"));
        assertNull(m.get("defaultStyle"));
        assertEquals(List.of("w:extra"), m.get("styles"));
        assertEquals(List.of(Map.of("error", "offline")), m.get("attributes"));
    }

    @Test
    void styleFormatVersion() {
        StyleInfo s = mock(StyleInfo.class);
        when(s.getFormatVersion()).thenReturn(new Version("1.0.0"));
        assertEquals("1.0.0", Views.style(s).get("formatVersion"));
    }

    @Test
    void layerGroupWithMissingEntriesAndFewerStyles() {
        LayerGroupInfo g = mock(LayerGroupInfo.class);
        PublishedInfo p = mock(PublishedInfo.class);
        when(p.prefixedName()).thenReturn("w:a");
        when(g.getLayers()).thenReturn(new ArrayList<>(Arrays.asList(p, null)));
        when(g.getStyles()).thenReturn(new ArrayList<>());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> layers = (List<Map<String, Object>>) Views.layerGroup(g).get("layers");
        assertEquals("w:a", layers.get(0).get("name"));
        assertNull(layers.get(0).get("style"));
        assertNull(layers.get(1).get("name"));
    }
}
