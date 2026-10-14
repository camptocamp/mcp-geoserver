package org.geoserver.mcp.web;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.wicket.util.tester.FormTester;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.geoserver.web.GeoServerWicketTestSupport;
import org.junit.Test;

public class McpAdminPageTest extends GeoServerWicketTestSupport {

    @Test
    public void enableThroughForm() {
        login();
        tester.startPage(McpAdminPage.class);
        tester.assertRenderedPage(McpAdminPage.class);
        assertFalse(new McpSettingsStore(getGeoServer()).load().enabled());
        FormTester form = tester.newFormTester("form");
        form.setValue("enabled", true);
        form.setValue("verbose", true);
        form.setValue("allowedRoles", "ROLE_ADMINISTRATOR, ROLE_GIS");
        form.submit("submit");
        tester.assertNoErrorMessage();
        var s = new McpSettingsStore(getGeoServer()).load();
        assertTrue(s.enabled());
        assertTrue(s.verbose());
        assertTrue(s.allowedRoles().contains("ROLE_GIS"));
    }
}
