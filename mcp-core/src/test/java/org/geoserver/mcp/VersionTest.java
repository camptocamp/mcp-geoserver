package org.geoserver.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionTest {
    @Test
    void currentVersionIsReadFromBuild() {
        assertTrue(Version.current().matches("\\d+\\.\\d+\\.\\d+.*"), Version.current());
    }
}
