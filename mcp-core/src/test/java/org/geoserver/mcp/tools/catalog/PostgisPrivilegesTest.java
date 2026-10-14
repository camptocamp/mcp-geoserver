package org.geoserver.mcp.tools.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.junit.Test;

public class PostgisPrivilegesTest {
    @Test
    public void fullPrivilegesGiveNoWarning() {
        assertEquals(List.of(), PostgisPrivileges.warnings("public", true, true, true, true, true));
    }

    @Test
    public void eachMissingPrivilegeIsNamed() {
        List<String> w = PostgisPrivileges.warnings("data", false, false, true, false, true);
        assertEquals(3, w.size());
        assertTrue(w.get(0), w.get(0).contains("USAGE"));
        assertTrue(w.get(1), w.get(1).contains("CREATE"));
        assertTrue(w.get(2), w.get(2).contains("WFS-T"));
    }

    @Test
    public void missingSchemaIsReported() throws Exception {
        Connection cx = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(cx.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        List<String> w = PostgisPrivileges.check(cx, "nope");
        assertEquals(1, w.size());
        assertTrue(w.get(0), w.get(0).contains("'nope'"));
    }
}
