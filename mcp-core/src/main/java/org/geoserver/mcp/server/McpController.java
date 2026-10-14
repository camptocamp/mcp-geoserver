package org.geoserver.mcp.server;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.geoserver.mcp.security.McpGate;
import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class McpController {
    private final McpSettingsStore settings;
    private final McpGate gate;
    private final McpServerHolder holder;

    public McpController(McpSettingsStore settings, McpGate gate, McpServerHolder holder) {
        this.settings = settings;
        this.gate = gate;
        this.holder = holder;
    }

    @RequestMapping(path = "/mcp", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE})
    public void handle(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        McpSettings s = settings.load();
        if (!s.enabled()) {
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "MCP server is disabled");
            return;
        }
        if (!gate.allowed(s)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "MCP server requires one of roles " + s.allowedRoles());
            return;
        }
        holder.transport().service(req, resp);
    }
}
