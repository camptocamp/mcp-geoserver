package org.geoserver.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class Version {
    private static final String DEV = "0.0.0-dev";
    private static final String VALUE = read();

    private Version() {}

    public static String current() {
        return VALUE;
    }

    private static String read() {
        Properties p = new Properties();
        try (InputStream in = Version.class.getResourceAsStream("/META-INF/mcp-version.properties")) {
            if (in != null) p.load(in);
        } catch (IOException e) {
            return DEV;
        }
        return p.getProperty("version", DEV);
    }
}
