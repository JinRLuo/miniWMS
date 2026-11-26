package db.migration;

import java.sql.Connection;
import java.util.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Preserve existing slot IDs, codes and QR tokens while adding cell locations. */
public class V2__separate_warehouses extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE warehouses (id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL, type VARCHAR(16) NOT NULL UNIQUE)");
            s.execute("INSERT INTO warehouses VALUES (1, '货架仓库', 'GENERAL'), (2, '鱼胶仓库', 'FISH_MAW')");
            s.execute("ALTER TABLE products ADD COLUMN warehouse_id BIGINT NOT NULL DEFAULT 1");
            s.execute("ALTER TABLE products ADD COLUMN variety VARCHAR(100)");
            s.execute("ALTER TABLE products ADD COLUMN head_count INT");
            s.execute("ALTER TABLE shelves ADD COLUMN warehouse_id BIGINT NOT NULL DEFAULT 1");
            s.execute("ALTER TABLE shelf_slots ADD COLUMN cell_no INT NOT NULL DEFAULT 1");
            s.execute("ALTER TABLE shelf_slots MODIFY COLUMN code VARCHAR(48) NOT NULL");
            for (String column : List.of("inventory.quantity", "stock_movements.quantity", "stock_movements.before_qty",
                    "stock_movements.after_qty", "stock_transfers.quantity", "products.warning_level")) {
                String[] p = column.split("\\.");
                s.execute("ALTER TABLE " + p[0] + " MODIFY COLUMN " + p[1] + " DECIMAL(18,3)" +
                        (column.equals("products.warning_level") ? "" : " NOT NULL"));
            }
        }
        // V1 used an unnamed unique constraint: discover its generated name on each supported database.
        boolean h2 = c.getMetaData().getDatabaseProductName().equals("H2");
        Map<String, Set<String>> keys = new HashMap<>();
        if (h2) {
            try (var s = c.createStatement(); var r = s.executeQuery("""
                SELECT k.CONSTRAINT_NAME,k.COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k
                JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS t ON t.CONSTRAINT_NAME=k.CONSTRAINT_NAME
                    AND t.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND t.TABLE_NAME=k.TABLE_NAME
                WHERE LOWER(k.TABLE_NAME)='shelf_slots' AND t.CONSTRAINT_TYPE='UNIQUE'
                """)) {
                while (r.next()) keys.computeIfAbsent(r.getString(1), ignored -> new HashSet<>()).add(r.getString(2).toLowerCase(Locale.ROOT));
            }
        } else {
            try (var r = c.getMetaData().getIndexInfo(c.getCatalog(), null, "shelf_slots", true, false)) {
                while (r.next()) if (r.getString("INDEX_NAME") != null && r.getString("COLUMN_NAME") != null)
                    keys.computeIfAbsent(r.getString("INDEX_NAME"), ignored -> new HashSet<>()).add(r.getString("COLUMN_NAME"));
            }
        }
        try (var s = c.createStatement()) {
            String key = keys.entrySet().stream().filter(e -> e.getValue().equals(Set.of("shelf_id", "layer_no")))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
            s.execute("ALTER TABLE shelf_slots DROP " + (h2 ? "CONSTRAINT " : "INDEX ") + (h2 ? "\"" + key.replace("\"", "\"\"") + "\"" : "`" + key.replace("`", "``") + "`"));
            s.execute("ALTER TABLE shelf_slots ADD CONSTRAINT slot_cell_unique UNIQUE (shelf_id, layer_no, cell_no)");
            s.execute("ALTER TABLE products ADD CONSTRAINT product_warehouse_fk FOREIGN KEY (warehouse_id) REFERENCES warehouses(id)");
            s.execute("ALTER TABLE shelves ADD CONSTRAINT shelf_warehouse_fk FOREIGN KEY (warehouse_id) REFERENCES warehouses(id)");
            s.execute("ALTER TABLE shelf_slots ADD CONSTRAINT cell_positive CHECK (cell_no > 0)");
            s.execute("ALTER TABLE products ADD CONSTRAINT head_count_positive CHECK (head_count IS NULL OR head_count > 0)");
        }
    }
}
