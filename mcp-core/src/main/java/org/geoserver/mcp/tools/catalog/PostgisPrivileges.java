package org.geoserver.mcp.tools.catalog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.DataStoreInfo;
import org.geotools.jdbc.JDBCDataStore;

final class PostgisPrivileges {
    // LEFT JOIN plus COALESCE: a schema with no tables yields one row and no table warning.
    static final String SQL = """
            select has_schema_privilege(current_user, n.oid, 'USAGE'),
                   has_schema_privilege(current_user, n.oid, 'CREATE'),
                   coalesce(bool_and(has_table_privilege(current_user, c.oid, 'INSERT')), true),
                   coalesce(bool_and(has_table_privilege(current_user, c.oid, 'UPDATE')), true),
                   coalesce(bool_and(has_table_privilege(current_user, c.oid, 'DELETE')), true)
            from pg_namespace n
            left join pg_class c on c.relnamespace = n.oid and c.relkind in ('r', 'p')
            where n.nspname = ?
            group by n.oid""";

    private PostgisPrivileges() {}

    static List<String> check(Catalog catalog, DataStoreInfo store) {
        Object schema = store.getConnectionParameters().get("schema");
        try {
            var access = catalog.getResourcePool().getDataStore(store);
            if (access == null) return List.of("Privilege check skipped: store did not connect");
            if (!(access instanceof JDBCDataStore jdbc)) return List.of();
            try (Connection cx = jdbc.getDataSource().getConnection()) {
                return check(cx, schema == null ? "public" : schema.toString());
            }
        } catch (Exception e) {
            return List.of("Privilege check skipped: " + e.getMessage());
        }
    }

    static List<String> check(Connection cx, String schema) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement(SQL)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return List.of("Schema '" + schema + "' does not exist");
                return warnings(schema, rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5));
            }
        }
    }

    static List<String> warnings(String schema, boolean usage, boolean create, boolean insert, boolean update, boolean delete) {
        List<String> w = new ArrayList<>();
        if (!usage) w.add("No USAGE on schema '" + schema + "': the store cannot read any table");
        if (!create) w.add("No CREATE on schema '" + schema + "': new tables cannot be created through GeoServer");
        if (!insert || !update || !delete) w.add("Missing INSERT, UPDATE or DELETE on some tables in '" + schema + "': WFS-T edits fail on them");
        return w;
    }
}
