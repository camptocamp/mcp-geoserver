package org.geoserver.mcp.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.geoserver.mcp.settings.McpSettings;
import org.geoserver.security.GeoServerSecurityManager;
import org.geoserver.security.impl.GeoServerRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class McpGateTest {
    GeoServerSecurityManager sm = mock(GeoServerSecurityManager.class);
    McpGate gate = new McpGate(sm);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousPassesNullAuthenticationToTheSecurityManager() {
        assertFalse(gate.allowed(McpSettings.DEFAULTS.withEnabled(true)));
        verify(sm).checkAuthenticationForRole(isNull(), eq(new GeoServerRole("ROLE_ADMINISTRATOR")));
    }

    @Test
    void anyAllowedRoleGrants() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("bob", "x", "ROLE_GIS");
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(sm.checkAuthenticationForRole(auth, new GeoServerRole("ROLE_ADMINISTRATOR"))).thenReturn(false);
        when(sm.checkAuthenticationForRole(auth, new GeoServerRole("ROLE_GIS"))).thenReturn(true);
        assertTrue(gate.allowed(new McpSettings(true, List.of("ROLE_ADMINISTRATOR", "ROLE_GIS"), false, "")));
    }
}
