package org.geoserver.mcp.security;

import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.security.GeoServerSecurityManager;
import org.geoserver.security.impl.GeoServerRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class McpGate {
    private final GeoServerSecurityManager securityManager;

    public McpGate(GeoServerSecurityManager securityManager) {
        this.securityManager = securityManager;
    }

    public boolean allowed(McpSettings settings) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        for (String role : settings.allowedRoles()) {
            if (securityManager.checkAuthenticationForRole(auth, new GeoServerRole(role))) return true;
        }
        return false;
    }

    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return securityManager.checkAuthenticationForRole(auth, GeoServerRole.ADMIN_ROLE);
    }
}
