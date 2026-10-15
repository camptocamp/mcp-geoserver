package org.geoserver.mcp.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.Button;
import org.apache.wicket.markup.html.form.CheckBox;
import org.apache.wicket.markup.html.form.DropDownChoice;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.PropertyModel;
import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.geoserver.security.impl.GeoServerRole;
import org.geoserver.web.GeoServerSecuredPage;

public class McpAdminPage extends GeoServerSecuredPage {
    // Not "enabled": Wicket Component#isEnabled would shadow the field in PropertyModel.
    private boolean mcpEnabled;
    private boolean verbose;
    private final List<String> allowedRoles;
    private String newRole;
    private String internalBaseUrl;

    public McpAdminPage() {
        McpSettingsStore store = store();
        McpSettings s = store.load();
        mcpEnabled = s.enabled();
        verbose = s.verbose();
        allowedRoles = new ArrayList<>(s.allowedRoles());
        internalBaseUrl = s.internalBaseUrl();

        Form<Void> form = new Form<>("form");
        add(form);
        form.add(new Label("endpoint", endpointUrl()));
        form.add(new CheckBox("enabled", new PropertyModel<>(this, "mcpEnabled")));
        form.add(new CheckBox("verbose", new PropertyModel<>(this, "verbose")));
        form.add(new ListView<>("roles", allowedRoles) {
            @Override
            protected void populateItem(ListItem<String> item) {
                item.add(new Label("role", item.getModelObject()));
                item.add(new Button("remove") {
                    @Override
                    public void onSubmit() {
                        allowedRoles.remove(item.getModelObject());
                    }
                });
            }
        }.setReuseItems(false));
        form.add(new DropDownChoice<>("newRole", new PropertyModel<>(this, "newRole"), new LoadableDetachableModel<List<String>>() {
            @Override
            protected List<String> load() {
                List<String> choices = new ArrayList<>(existingRoles());
                choices.removeAll(allowedRoles);
                return choices;
            }
        }));
        form.add(new Button("addRole") {
            @Override
            public void onSubmit() {
                if (newRole != null && !allowedRoles.contains(newRole)) allowedRoles.add(newRole);
                newRole = null;
            }
        });
        form.add(new TextField<>("internalBaseUrl", new PropertyModel<>(this, "internalBaseUrl")));
        form.add(new Button("submit") {
            @Override
            public void onSubmit() {
                store().save(new McpSettings(mcpEnabled, List.copyOf(allowedRoles), verbose, internalBaseUrl));
                info(getString("McpAdminPage.saved"));
            }
        });
    }

    TreeSet<String> existingRoles() {
        TreeSet<String> roles = new TreeSet<>(List.of(GeoServerRole.ADMIN_ROLE.getAuthority(), GeoServerRole.AUTHENTICATED_ROLE.getAuthority()));
        try {
            for (GeoServerRole r : getGeoServerApplication().getSecurityManager().getActiveRoleService().getRoles()) {
                roles.add(r.getAuthority());
            }
        } catch (IOException e) {
            error(e.getMessage());
        }
        return roles;
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
