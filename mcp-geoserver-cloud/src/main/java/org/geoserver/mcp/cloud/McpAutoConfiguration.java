package org.geoserver.mcp.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.geoserver.catalog.Catalog;
import org.geoserver.config.GeoServer;
import org.geoserver.mcp.http.GeoServerHttpClient;
import org.geoserver.mcp.security.McpGate;
import org.geoserver.mcp.server.McpController;
import org.geoserver.mcp.server.McpServerHolder;
import org.geoserver.mcp.server.McpSettingsController;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.geoserver.mcp.tool.GsTool;
import org.geoserver.mcp.tools.ToolRegistry;
import org.geoserver.platform.ModuleStatus;
import org.geoserver.platform.ModuleStatusImpl;
import org.geoserver.security.GeoServerSecurityManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnClass(name = "org.geoserver.config.GeoServer")
@ConditionalOnProperty(name = {"geoserver.service.restconfig.enabled", "geoserver.extension.mcp.enabled"}, havingValue = "true")
public class McpAutoConfiguration {

    @Bean
    ModuleStatus mcpModuleStatus() {
        ModuleStatusImpl s = new ModuleStatusImpl("gs-mcp", "GeoServer MCP server");
        s.setCategory(ModuleStatus.Category.EXTENSION);
        return s;
    }

    @Bean
    ObjectMapper mcpObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    McpSettingsStore mcpSettingsStore(GeoServer geoServer) {
        return new McpSettingsStore(geoServer);
    }

    @Bean
    McpGate mcpGate(GeoServerSecurityManager securityManager) {
        return new McpGate(securityManager);
    }

    @Bean
    GeoServerHttpClient mcpHttpClient(GeoServer geoServer, McpSettingsStore mcpSettingsStore) {
        return new GeoServerHttpClient(geoServer, mcpSettingsStore);
    }

    @Bean
    List<GsTool> mcpTools(
            @Qualifier("catalog") Catalog catalog,
            GeoServer geoServer,
            @Qualifier("mcpHttpClient") GeoServerHttpClient mcpHttpClient,
            @Qualifier("mcpObjectMapper") ObjectMapper mcpObjectMapper) {
        return ToolRegistry.all(catalog, geoServer, mcpHttpClient, mcpObjectMapper);
    }

    @Bean(destroyMethod = "close")
    McpServerHolder mcpServerHolder(
            @Qualifier("mcpTools") List<GsTool> mcpTools,
            McpSettingsStore mcpSettingsStore,
            @Qualifier("mcpObjectMapper") ObjectMapper mcpObjectMapper) {
        return new McpServerHolder(mcpTools, mcpSettingsStore, mcpObjectMapper);
    }

    @Bean
    McpController mcpController(McpSettingsStore mcpSettingsStore, McpGate mcpGate, McpServerHolder mcpServerHolder) {
        return new McpController(mcpSettingsStore, mcpGate, mcpServerHolder);
    }

    @Bean
    McpSettingsController mcpSettingsController(
            McpSettingsStore mcpSettingsStore, McpGate mcpGate, @Qualifier("mcpObjectMapper") ObjectMapper mcpObjectMapper) {
        return new McpSettingsController(mcpSettingsStore, mcpGate, mcpObjectMapper);
    }
}
