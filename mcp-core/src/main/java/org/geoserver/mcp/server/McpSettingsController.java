package org.geoserver.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.geoserver.mcp.security.McpGate;
import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.mcp.settings.McpSettingsStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class McpSettingsController {
    public static final String PATH = "/rest/mcp/settings";

    private final McpSettingsStore settings;
    private final McpGate gate;
    private final ObjectMapper json;

    public McpSettingsController(McpSettingsStore settings, McpGate gate, ObjectMapper json) {
        this.settings = settings;
        this.gate = gate;
        this.json = json;
    }

    @GetMapping(path = PATH)
    public void get(HttpServletResponse resp) throws IOException {
        if (!gate.isAdmin()) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        write(resp, settings.load());
    }

    @PutMapping(path = PATH)
    public void put(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (!gate.isAdmin()) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        JsonNode body = json.readTree(req.getInputStream());
        McpSettings current = settings.load();
        boolean enabled = body.path("enabled").asBoolean(current.enabled());
        boolean verbose = body.path("verbose").asBoolean(current.verbose());
        String base = body.path("internalBaseUrl").asText(current.internalBaseUrl());
        List<String> roles = current.allowedRoles();
        if (body.path("allowedRoles").isArray()) {
            roles = new ArrayList<>();
            for (JsonNode n : body.path("allowedRoles")) roles.add(n.asText());
        }
        McpSettings updated = new McpSettings(enabled, roles, verbose, base);
        settings.save(updated);
        write(resp, updated);
    }

    private void write(HttpServletResponse resp, McpSettings s) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(json.writeValueAsString(s));
        resp.getWriter().flush();
    }
}
