package org.geoserver.mcp.tools.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.geoserver.catalog.AttributeTypeInfo;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.DimensionDefaultValueSetting;
import org.geoserver.catalog.DimensionInfo;
import org.geoserver.catalog.DimensionPresentation;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.MetadataMap;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.mcp.tool.Args;
import org.geoserver.mcp.tool.CommandTrace;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tool.ToolContext;
import org.geoserver.mcp.tool.ToolException;
import org.junit.jupiter.api.Test;

class DimensionToolsTest {
    final Catalog catalog = mock(Catalog.class);
    final MetadataMap metadata = new MetadataMap();
    final CommandTrace trace = new CommandTrace();
    final GsTool tool = new DimensionTools(catalog).tools().get(0);

    FeatureTypeInfo vector() throws Exception {
        WorkspaceInfo ws = mock(WorkspaceInfo.class);
        when(ws.getName()).thenReturn("w");
        DataStoreInfo store = mock(DataStoreInfo.class);
        when(store.getWorkspace()).thenReturn(ws);
        when(store.getName()).thenReturn("s");
        AttributeTypeInfo date = mock(AttributeTypeInfo.class);
        when(date.getName()).thenReturn("obs_date");
        FeatureTypeInfo ft = mock(FeatureTypeInfo.class);
        when(ft.getStore()).thenReturn(store);
        when(ft.getName()).thenReturn("obs");
        when(ft.prefixedName()).thenReturn("w:obs");
        when(ft.getMetadata()).thenReturn(metadata);
        when(ft.attributes()).thenReturn(List.of(date));
        LayerInfo layer = mock(LayerInfo.class);
        when(layer.getResource()).thenReturn(ft);
        when(layer.prefixedName()).thenReturn("w:obs");
        when(catalog.getLayerByName("w:obs")).thenReturn(layer);
        return ft;
    }

    Object call(Map<String, Object> args) throws Exception {
        return tool.call(new ToolContext(true, trace, null, ""), new Args(args));
    }

    @Test
    void vectorTimeIsStoredAndSaved() throws Exception {
        FeatureTypeInfo ft = vector();
        call(Map.of("name", "w:obs", "attribute", "obs_date", "presentation", "DISCRETE_INTERVAL", "resolution", "86400000",
                "defaultValue", "NEAREST", "referenceValue", "current", "nearestMatch", true));
        DimensionInfo time = (DimensionInfo) metadata.get(ResourceInfo.TIME);
        assertEquals("obs_date", time.getAttribute());
        assertEquals(DimensionPresentation.DISCRETE_INTERVAL, time.getPresentation());
        assertEquals(new BigDecimal("86400000"), time.getResolution());
        assertEquals(DimensionDefaultValueSetting.Strategy.NEAREST, time.getDefaultValue().getStrategyType());
        assertTrue(time.isNearestMatchEnabled());
        verify(catalog).save(ft);
        assertTrue(trace.lines().get(0).contains("/rest/workspaces/w/datastores/s/featuretypes/obs.json"), trace.lines().get(0));
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        vector();
        Map<String, Object> unknownAttr = Map.of("name", "w:obs", "attribute", "nope");
        assertThrows(ToolException.class, () -> call(unknownAttr));
        Map<String, Object> noResolution = Map.of("name", "w:obs", "attribute", "obs_date", "presentation", "DISCRETE_INTERVAL");
        assertThrows(ToolException.class, () -> call(noResolution));
        Map<String, Object> badResolution = Map.of("name", "w:obs", "attribute", "obs_date", "resolution", "a day");
        assertThrows(ToolException.class, () -> call(badResolution));
    }

    @Test
    void rasterTimeNeedsNoAttribute() throws Exception {
        WorkspaceInfo ws = mock(WorkspaceInfo.class);
        when(ws.getName()).thenReturn("w");
        CoverageStoreInfo store = mock(CoverageStoreInfo.class);
        when(store.getWorkspace()).thenReturn(ws);
        when(store.getName()).thenReturn("mosaic");
        CoverageInfo cov = mock(CoverageInfo.class);
        when(cov.getStore()).thenReturn(store);
        when(cov.getName()).thenReturn("sst");
        when(cov.getMetadata()).thenReturn(metadata);
        LayerInfo layer = mock(LayerInfo.class);
        when(layer.getResource()).thenReturn(cov);
        when(layer.prefixedName()).thenReturn("w:sst");
        when(catalog.getLayerByName("w:sst")).thenReturn(layer);
        call(Map.of("name", "w:sst"));
        DimensionInfo time = (DimensionInfo) metadata.get(ResourceInfo.TIME);
        assertEquals(DimensionPresentation.LIST, time.getPresentation());
        assertEquals(DimensionDefaultValueSetting.Strategy.MINIMUM, time.getDefaultValue().getStrategyType());
        assertTrue(trace.lines().get(0).contains("/coveragestores/mosaic/coverages/sst.json"), trace.lines().get(0));
    }
}
