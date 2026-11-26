package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Preserve existing locations and resume safely after MySQL's auto-committing DDL. */
public class V2__separate_warehouses extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        boolean h2 = c.getMetaData().getDatabaseProductName().equals("H2");
        execute(c, "CREATE TABLE IF NOT EXISTS warehouses (id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL, type VARCHAR(16) NOT NULL UNIQUE)");
        seedWarehouse(c, 1, "货架仓库", "GENERAL");
        seedWarehouse(c, 2, "鱼胶仓库", "FISH_MAW");
        addColumn(c, "products", "warehouse_id", "BIGINT NOT NULL DEFAULT 1");
        addColumn(c, "products", "variety", "VARCHAR(100)");
        addColumn(c, "products", "head_count", "INT");
        addColumn(c, "shelves", "warehouse_id", "BIGINT NOT NULL DEFAULT 1");
        addColumn(c, "shelf_slots", "cell_no", "INT NOT NULL DEFAULT 1");
        try (var column = c.getMetaData().getColumns(c.getCatalog(), schema(c, h2), "shelf_slots", "code")) {
            if (column.next() && column.getInt("COLUMN_SIZE") < 48)
                execute(c, "ALTER TABLE shelf_slots MODIFY COLUMN code VARCHAR(48) NOT NULL");
        }
        for (String column : List.of("inventory.quantity", "stock_movements.quantity", "stock_movements.before_qty",
                "stock_movements.after_qty", "stock_transfers.quantity", "products.warning_level")) {
            String[] parts = column.split("\\.");
            boolean decimal = false;
            try (var existing = c.getMetaData().getColumns(c.getCatalog(), schema(c, h2), parts[0], parts[1])) {
                if (existing.next()) {
                    int type = existing.getInt("DATA_TYPE");
                    decimal = (type == Types.DECIMAL || type == Types.NUMERIC)
                            && existing.getInt("COLUMN_SIZE") == 18 && existing.getInt("DECIMAL_DIGITS") == 3;
                }
            }
            if (!decimal) execute(c, "ALTER TABLE " + parts[0] + " MODIFY COLUMN " + parts[1] + " DECIMAL(18,3)"
                    + (column.equals("products.warning_level") ? "" : " NOT NULL"));
        }

        // MySQL uses the leftmost shelf_id column to enforce the foreign key. Keep that
        // index covered by the new key before dropping V1's unique-per-layer key.
        addConstraint(c, h2, "shelf_slots", "slot_cell_unique", "UNIQUE (shelf_id, layer_no, cell_no)");
        for (var key : uniqueKeys(c, h2).entrySet()) {
            if (key.getValue().equals(Set.of("shelf_id", "layer_no"))) {
                String quoted = h2 ? "\"" + key.getKey().replace("\"", "\"\"") + "\"" : "`" + key.getKey().replace("`", "``") + "`";
                execute(c, "ALTER TABLE shelf_slots DROP " + (h2 ? "CONSTRAINT " : "INDEX ") + quoted);
            }
        }
        addConstraint(c, h2, "products", "product_warehouse_fk", "FOREIGN KEY (warehouse_id) REFERENCES warehouses(id)");
        addConstraint(c, h2, "shelves", "shelf_warehouse_fk", "FOREIGN KEY (warehouse_id) REFERENCES warehouses(id)");
        addConstraint(c, h2, "shelf_slots", "cell_positive", "CHECK (cell_no > 0)");
        addConstraint(c, h2, "products", "head_count_positive", "CHECK (head_count IS NULL OR head_count > 0)");
    }

    private static String schema(Connection c, boolean h2) throws SQLException {
        return h2 ? c.getSchema() : null;
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (var statement = c.createStatement()) { statement.execute(sql); }
    }

    private static void seedWarehouse(Connection c, long id, String name, String type) throws SQLException {
        try (var query = c.prepareStatement("SELECT type FROM warehouses WHERE id=?")) {
            query.setLong(1, id);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    if (!type.equals(rows.getString(1))) throw new SQLException("Existing warehouse type differs for ID " + id);
                    return;
                }
            }
        }
        try (var insert = c.prepareStatement("INSERT INTO warehouses(id,name,type) VALUES(?,?,?)")) {
            insert.setLong(1, id); insert.setString(2, name); insert.setString(3, type); insert.executeUpdate();
        }
    }

    private static void addColumn(Connection c, String table, String column, String definition) throws SQLException {
        boolean h2 = c.getMetaData().getDatabaseProductName().equals("H2");
        try (var existing = c.getMetaData().getColumns(c.getCatalog(), schema(c, h2), table, column)) {
            if (existing.next()) return;
        }
        execute(c, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private static void addConstraint(Connection c, boolean h2, String table, String name, String definition) throws SQLException {
        try (var query = c.prepareStatement("""
                SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA=? AND LOWER(TABLE_NAME)=? AND LOWER(CONSTRAINT_NAME)=?
                """)) {
            query.setString(1, h2 ? c.getSchema() : c.getCatalog());
            query.setString(2, table); query.setString(3, name);
            try (var existing = query.executeQuery()) { if (existing.next()) return; }
        }
        execute(c, "ALTER TABLE " + table + " ADD CONSTRAINT " + name + " " + definition);
    }

    private static Map<String, Set<String>> uniqueKeys(Connection c, boolean h2) throws SQLException {
        Map<String, Set<String>> keys = new HashMap<>();
        if (h2) {
            try (var query = c.prepareStatement("""
                    SELECT k.CONSTRAINT_NAME,k.COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k
                    JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS t ON t.CONSTRAINT_NAME=k.CONSTRAINT_NAME
                        AND t.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND t.TABLE_NAME=k.TABLE_NAME
                    WHERE k.CONSTRAINT_SCHEMA=? AND LOWER(k.TABLE_NAME)='shelf_slots' AND t.CONSTRAINT_TYPE='UNIQUE'
                    """)) {
                query.setString(1, c.getSchema());
                try (var rows = query.executeQuery()) {
                    while (rows.next()) keys.computeIfAbsent(rows.getString(1), ignored -> new HashSet<>())
                            .add(rows.getString(2).toLowerCase(Locale.ROOT));
                }
            }
        } else {
            try (var rows = c.getMetaData().getIndexInfo(c.getCatalog(), null, "shelf_slots", true, false)) {
                while (rows.next()) if (rows.getString("INDEX_NAME") != null && rows.getString("COLUMN_NAME") != null)
                    keys.computeIfAbsent(rows.getString("INDEX_NAME"), ignored -> new HashSet<>())
                            .add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return keys;
    }
}
