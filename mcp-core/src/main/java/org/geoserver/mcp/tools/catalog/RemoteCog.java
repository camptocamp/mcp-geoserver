package org.geoserver.mcp.tools.catalog;

import java.util.Locale;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.cog.CogSettings;
import org.geoserver.cog.CogSettingsStore;
import org.geoserver.mcp.tool.ToolException;

final class RemoteCog {
    private static final String COG = "cog://";
    private static final String READER_PACKAGE = "it.geosolutions.imageioimpl.plugins.cog.";

    private RemoteCog() {}

    record Reader(String type, String className, String module) {}

    static boolean applies(String format, String url) {
        if (!"GeoTIFF".equals(format)) return false;
        String u = strip(url).toLowerCase(Locale.ROOT);
        return u.startsWith("s3://") || u.startsWith("gs://") || u.startsWith("http://") || u.startsWith("https://");
    }

    static String strip(String url) {
        return url.startsWith(COG) ? url.substring(COG.length()) : url;
    }

    static String storeUrl(String url) {
        return COG + strip(url);
    }

    static Reader reader(String url) {
        String u = strip(url).toLowerCase(Locale.ROOT);
        if (u.startsWith("s3://")) return new Reader("S3", READER_PACKAGE + "S3RangeReader", "cog-s3");
        if (u.startsWith("gs://")) return new Reader("GS", READER_PACKAGE + "GSRangeReader", "cog-google");
        return new Reader("HTTP", READER_PACKAGE + "HttpRangeReader", "cog-http");
    }

    static void requireInstalled(Reader reader, ClassLoader loader) {
        if (!present("org.geoserver.cog.CogSettingsStore", loader) || !present(reader.className(), loader)) {
            throw new ToolException("Reading a remote COG needs the GeoServer " + reader.module() + " community module; install it and restart GeoServer");
        }
    }

    static boolean present(String className, ClassLoader loader) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    static void configure(CoverageStoreInfo store, Reader reader) {
        CogSettingsStore settings = new CogSettingsStore();
        settings.setRangeReaderSettings(CogSettings.RangeReaderType.valueOf(reader.type()));
        store.getMetadata().put(CogSettings.COG_SETTINGS_KEY, settings);
    }
}
