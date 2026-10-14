package org.geoserver.mcp.cloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.geoserver.catalog.Catalog;
import org.geoserver.config.GeoServer;
import org.geoserver.mcp.server.McpController;
import org.geoserver.security.GeoServerSecurityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class McpAutoConfigurationTest {
    final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(McpAutoConfiguration.class))
            .withBean("catalog", Catalog.class, () -> mock(Catalog.class))
            .withBean("geoServer", GeoServer.class, () -> mock(GeoServer.class))
            .withBean("geoServerSecurityManager", GeoServerSecurityManager.class, () -> mock(GeoServerSecurityManager.class));

    @Test
    void disabledByDefault() {
        runner.withPropertyValues("geoserver.service.restconfig.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(McpController.class));
    }

    @Test
    void notInOtherServices() {
        runner.withPropertyValues("geoserver.extension.mcp.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(McpController.class));
    }

    @Test
    void enabledInRestconfig() {
        runner.withPropertyValues("geoserver.service.restconfig.enabled=true", "geoserver.extension.mcp.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(McpController.class);
                    assertThat(ctx).hasBean("mcpSettingsController");
                    assertThat(ctx).hasBean("mcpModuleStatus");
                });
    }
}
