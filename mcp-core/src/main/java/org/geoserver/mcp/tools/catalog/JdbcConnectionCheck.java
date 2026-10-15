package org.geoserver.mcp.tools.catalog;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import org.geoserver.mcp.tool.ToolException;
import org.geotools.api.data.DataAccessFactory;
import org.geotools.api.data.DataStore;
import org.geotools.jdbc.JDBCDataStore;
import org.geotools.jdbc.JDBCDataStoreFactory;

final class JdbcConnectionCheck {
    private JdbcConnectionCheck() {}

    static void verify(DataAccessFactory factory, String storeName, Map<String, Serializable> params) {
        if (!(factory instanceof JDBCDataStoreFactory jdbcFactory)) return;
        DataStore store = null;
        try {
            store = jdbcFactory.createDataStore(params);
            try (Connection cx = ((JDBCDataStore) store).getDataSource().getConnection();
                    Statement st = cx.createStatement()) {
                st.execute("select 1");
            }
        } catch (Exception e) {
            throw new ToolException("Cannot connect store '" + storeName + "': " + rootMessage(e));
        } finally {
            if (store != null) store.dispose();
        }
    }

    static String rootMessage(Throwable e) {
        String message = e.getClass().getSimpleName();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null) message = t.getMessage();
        }
        return message;
    }
}
