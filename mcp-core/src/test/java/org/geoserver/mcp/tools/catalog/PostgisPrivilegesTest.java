package org.geoserver.mcp.tools.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.ResourcePool;
import org.geotools.api.data.DataStore;
import org.geotools.jdbc.JDBCDataStore;
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

    @Test
    public void existingSchemaReturnsWarnings() throws Exception {
        Connection cx = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(cx.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(anyInt())).thenReturn(true);
        assertEquals(List.of(), PostgisPrivileges.check(cx, "public"));
    }

    @Test
    public void storeLevelCheckHandlesEveryOutcome() throws Exception {
        Catalog catalog = mock(Catalog.class);
        ResourcePool pool = mock(ResourcePool.class);
        DataStoreInfo store = mock(DataStoreInfo.class);
        when(catalog.getResourcePool()).thenReturn(pool);
        when(store.getConnectionParameters()).thenReturn(new HashMap<>(Map.of("schema", "data")));

        when(pool.getDataStore(store)).thenReturn(null);
        assertTrue(PostgisPrivileges.check(catalog, store).get(0).contains("did not connect"));

        doReturn(mock(DataStore.class)).when(pool).getDataStore(store);
        assertEquals(List.of(), PostgisPrivileges.check(catalog, store));

        JDBCDataStore jdbc = mock(JDBCDataStore.class);
        DataSource ds = mock(DataSource.class);
        when(jdbc.getDataSource()).thenReturn(ds);
        when(ds.getConnection()).thenThrow(new SQLException("refused"));
        doReturn(jdbc).when(pool).getDataStore(store);
        assertEquals(List.of("Privilege check skipped: refused"), PostgisPrivileges.check(catalog, store));
    }

    @Test
    public void anySingleMissingTablePrivilegeWarns() {
        assertEquals(1, PostgisPrivileges.warnings("s", true, true, true, false, true).size());
        assertEquals(1, PostgisPrivileges.warnings("s", true, true, true, true, false).size());
    }
}
