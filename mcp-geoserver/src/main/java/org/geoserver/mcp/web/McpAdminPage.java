package org.geoserver.mcp.web;

import java.util.Arrays;
import java.util.List;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.Button;
import org.apache.wicket.markup.html.form.CheckBox;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.model.PropertyModel;
import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.geoserver.web.GeoServerSecuredPage;

public class McpAdminPage extends GeoServerSecuredPage {
    // Not "enabled": Wicket Component#isEnabled would shadow the field in PropertyModel.
    private boolean mcpEnabled;
    private boolean verbose;
    private String allowedRoles;
    private String internalBaseUrl;

    public McpAdminPage() {
        McpSettingsStore store = store();
        McpSettings s = store.load();
        mcpEnabled = s.enabled();
        verbose = s.verbose();
        allowedRoles = String.join(", ", s.allowedRoles());
        internalBaseUrl = s.internalBaseUrl();

        Form<Void> form = new Form<>("form");
        add(form);
        form.add(new Label("endpoint", endpointUrl()));
        form.add(new CheckBox("enabled", new PropertyModel<>(this, "mcpEnabled")));
        form.add(new CheckBox("verbose", new PropertyModel<>(this, "verbose")));
        form.add(new TextField<>("allowedRoles", new PropertyModel<>(this, "allowedRoles")));
        form.add(new TextField<>("internalBaseUrl", new PropertyModel<>(this, "internalBaseUrl")));
        form.add(new Button("submit") {
            @Override
            public void onSubmit() {
                List<String> roles = Arrays.stream(allowedRoles == null ? new String[0] : allowedRoles.split(","))
                        .map(String::trim)
                        .filter(r -> !r.isEmpty())
                        .toList();
                store().save(new McpSettings(mcpEnabled, roles, verbose, internalBaseUrl));
                info(getString("McpAdminPage.saved"));
            }
        });
    }

    McpSettingsStore store() {
        return getGeoServerApplication().getBeanOfType(McpSettingsStore.class);
    }

    String endpointUrl() {
        String proxy = getGeoServer().getGlobal().getSettings().getProxyBaseUrl();
        String base = proxy == null || proxy.isBlank() ? "<server>/geoserver" : proxy.replaceAll("/+$", "");
        return base + "/mcp";
    }
}
