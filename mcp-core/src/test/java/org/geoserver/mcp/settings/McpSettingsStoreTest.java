package org.geoserver.mcp.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.geoserver.catalog.MetadataMap;
import org.geoserver.config.GeoServer;
import org.geoserver.config.GeoServerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpSettingsStoreTest {
    GeoServer geoServer;
    GeoServerInfo global;
    MetadataMap metadata;
    McpSettingsStore store;

    @BeforeEach
    void setUp() {
        geoServer = mock(GeoServer.class);
        global = mock(GeoServerInfo.class);
        metadata = new MetadataMap();
        when(geoServer.getGlobal()).thenReturn(global);
        when(global.getMetadata()).thenReturn(metadata);
        store = new McpSettingsStore(geoServer);
    }

    @Test
    void loadReturnsDefaultsWhenNothingStored() {
        McpSettings s = store.load();
        assertFalse(s.enabled());
        assertEquals(List.of("ROLE_ADMINISTRATOR"), s.allowedRoles());
        assertFalse(s.verbose());
        assertEquals("", s.internalBaseUrl());
    }

    @Test
    void saveThenLoadRoundTrips() {
        store.save(new McpSettings(true, List.of("ROLE_ADMINISTRATOR", "ROLE_GIS"), true, "http://gateway:8080/geoserver"));
        verify(geoServer).save(global);
        McpSettings s = store.load();
        assertTrue(s.enabled());
        assertEquals(List.of("ROLE_ADMINISTRATOR", "ROLE_GIS"), s.allowedRoles());
        assertTrue(s.verbose());
        assertEquals("http://gateway:8080/geoserver", s.internalBaseUrl());
        assertEquals("true", metadata.get("mcp.enabled"));
        assertEquals("ROLE_ADMINISTRATOR,ROLE_GIS", metadata.get("mcp.allowedRoles"));
    }

    @Test
    void emptyRolesFallsBackToAdmin() {
        store.save(new McpSettings(true, List.of(), false, ""));
        assertEquals(List.of("ROLE_ADMINISTRATOR"), store.load().allowedRoles());
    }

    @Test
    void limitsAndReadOnlyRoundTripAndFallBack() {
        store.save(McpSettings.DEFAULTS.withLimits(true, 20, 300));
        McpSettings s = store.load();
        assertTrue(s.readOnly());
        assertEquals(20, s.maxListResults());
        assertEquals(300, s.maxFeatures());
        metadata.put("mcp.maxListResults", "lots");
        metadata.put("mcp.maxFeatures", "0");
        assertEquals(McpSettings.DEFAULT_MAX_LIST_RESULTS, store.load().maxListResults());
        assertEquals(McpSettings.DEFAULT_MAX_FEATURES, store.load().maxFeatures());
    }
}
