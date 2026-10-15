package org.geoserver.mcp.tools.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.catalog.MetadataMap;
import org.geoserver.cog.CogSettings;
import org.geoserver.cog.CogSettingsStore;
import org.geoserver.mcp.tool.ToolException;
import org.junit.jupiter.api.Test;

class RemoteCogTest {
    @Test
    void onlyRemoteGeoTiffUrlsAreCog() {
        assertTrue(RemoteCog.applies("GeoTIFF", "s3://bucket/a.tif"));
        assertTrue(RemoteCog.applies("GeoTIFF", "gs://bucket/a.tif"));
        assertTrue(RemoteCog.applies("GeoTIFF", "HTTPS://host/a.tif"));
        assertTrue(RemoteCog.applies("GeoTIFF", "cog://http://host/a.tif"));
        assertFalse(RemoteCog.applies("GeoTIFF", "file:data/a.tif"));
        assertFalse(RemoteCog.applies("WorldImage", "s3://bucket/a.png"));
    }

    @Test
    void storeUrlIsPrefixedOnce() {
        assertEquals("cog://s3://bucket/a.tif", RemoteCog.storeUrl("s3://bucket/a.tif"));
        assertEquals("cog://s3://bucket/a.tif", RemoteCog.storeUrl("cog://s3://bucket/a.tif"));
    }

    @Test
    void readerFollowsScheme() {
        assertEquals("S3", RemoteCog.reader("s3://b/a.tif").type());
        assertEquals("cog-s3", RemoteCog.reader("cog://s3://b/a.tif").module());
        assertEquals("GS", RemoteCog.reader("gs://b/a.tif").type());
        assertEquals("HTTP", RemoteCog.reader("https://h/a.tif").type());
    }

    @Test
    void missingModuleIsNamed() {
        RemoteCog.Reader s3 = RemoteCog.reader("s3://b/a.tif");
        ClassLoader empty = new ClassLoader(null) {};
        ToolException e = assertThrows(ToolException.class, () -> RemoteCog.requireInstalled(s3, empty));
        assertTrue(e.getMessage().contains("cog-s3"), e.getMessage());
    }

    @Test
    void configureStoresRangeReader() {
        CoverageStoreInfo store = mock(CoverageStoreInfo.class);
        MetadataMap metadata = new MetadataMap();
        when(store.getMetadata()).thenReturn(metadata);
        RemoteCog.configure(store, RemoteCog.reader("s3://b/a.tif"));
        CogSettingsStore settings = (CogSettingsStore) metadata.get(CogSettings.COG_SETTINGS_KEY);
        assertEquals(CogSettings.RangeReaderType.S3, settings.getRangeReaderSettings());
    }
}
