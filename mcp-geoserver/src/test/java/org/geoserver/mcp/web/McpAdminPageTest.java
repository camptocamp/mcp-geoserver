package org.geoserver.mcp.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.apache.wicket.markup.html.form.DropDownChoice;
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
        form.submit("submit");
        tester.assertNoErrorMessage();
        var s = new McpSettingsStore(getGeoServer()).load();
        assertTrue(s.enabled());
        assertTrue(s.verbose());
    }

    @Test
    public void addAndRemoveRolesFromExistingOnes() {
        login();
        tester.startPage(McpAdminPage.class);
        var choices = ((DropDownChoice<?>) tester.getComponentFromLastRenderedPage("form:newRole")).getChoices();
        assertTrue(choices.toString(), choices.contains("ROLE_AUTHENTICATED"));
        assertFalse(choices.toString(), choices.contains("ROLE_ADMINISTRATOR"));

        FormTester form = tester.newFormTester("form");
        form.select("newRole", choices.indexOf("ROLE_AUTHENTICATED"));
        form.submit("addRole");
        tester.assertLabel("form:roles:0:role", "ROLE_ADMINISTRATOR");
        tester.assertLabel("form:roles:1:role", "ROLE_AUTHENTICATED");

        tester.newFormTester("form").submit("roles:0:remove");
        tester.newFormTester("form").submit("submit");
        tester.assertNoErrorMessage();
        assertEquals(List.of("ROLE_AUTHENTICATED"), new McpSettingsStore(getGeoServer()).load().allowedRoles());
    }

    @Test
    public void readOnlyAndLimitsThroughForm() {
        login();
        tester.startPage(McpAdminPage.class);
        FormTester form = tester.newFormTester("form");
        form.setValue("readOnly", true);
        form.setValue("maxListResults", "40");
        form.setValue("maxFeatures", "250");
        form.submit("submit");
        tester.assertNoErrorMessage();
        var s = new McpSettingsStore(getGeoServer()).load();
        assertTrue(s.readOnly());
        assertEquals(40, s.maxListResults());
        assertEquals(250, s.maxFeatures());
    }
}
