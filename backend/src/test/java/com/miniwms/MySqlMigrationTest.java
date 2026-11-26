package com.miniwms;

import java.sql.Connection;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Run against a disposable MySQL schema; CI provisions a dedicated MySQL service. */
@EnabledIfEnvironmentVariable(named="WMS_TEST_MYSQL_URL", matches=".+")
class MySqlMigrationTest {
    private String url;
    private String user;
    private String password;

    private Flyway flyway() {
        return Flyway.configure().dataSource(url, user, password).cleanDisabled(false).load();
    }

    @BeforeEach
    void initializeLegacyDatabase() throws Exception {
        url = System.getenv("WMS_TEST_MYSQL_URL");
        user = System.getenv("WMS_TEST_MYSQL_USER");
        password = System.getenv("WMS_TEST_MYSQL_PASSWORD");
        try (var c = DriverManager.getConnection(url, user, password)) {
            // Never allow the cleanup below to run against the application database.
            assertThat(c.getCatalog()).matches("miniwms_migration_test(?:_[a-z0-9]+)?");
        }
        flyway().clean();
        Flyway.configure().dataSource(url, user, password).target("1").load().migrate();
        try (var c = DriverManager.getConnection(url, user, password); var s = c.createStatement()) {
            s.execute("INSERT INTO users(id,username,display_name,password_hash,role) VALUES(1,'legacy','员工','hash','ADMIN')");
            s.execute("INSERT INTO products(id,sku,name,unit) VALUES(1,'OLD-SKU','原商品','件')");
            s.execute("INSERT INTO shelves(id,code,name,layer_count) VALUES(1,'OLD','原货架',1)");
            s.execute("INSERT INTO shelf_slots(id,shelf_id,code,layer_no,product_id,qr_token) VALUES(1,1,'OLD-1',1,1,'old-qr-token')");
            s.execute("INSERT INTO inventory(slot_id,quantity) VALUES(1,7)");
            s.execute("INSERT INTO stock_requests(id,operator_id,request_id,fingerprint) VALUES(1,1,'old-request','old-fingerprint')");
            s.execute("INSERT INTO stock_movements(request_id,product_id,slot_id,type,quantity,before_qty,after_qty,operator_id,document_no,created_at) VALUES(1,1,1,'IN',7,0,7,1,'OLD-IN',0)");
        }
    }

    @Test
    void newCellKeyKeepsTheShelfForeignKeySupported() throws Exception {
        flyway().migrate();
        verifyLegacyDataAndCellKey();
    }

    @Test
    void repairAndResumeAfterThePreviousIndexFailurePreservesData() throws Exception {
        try (var c = DriverManager.getConnection(url, user, password); var s = c.createStatement()) {
            // Reproduce the non-transactional prefix already committed on production.
            s.execute("CREATE TABLE warehouses (id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL, type VARCHAR(16) NOT NULL UNIQUE)");
            s.execute("INSERT INTO warehouses VALUES(1,'货架仓库','GENERAL'),(2,'鱼胶仓库','FISH_MAW')");
            s.execute("ALTER TABLE products ADD COLUMN warehouse_id BIGINT NOT NULL DEFAULT 1");
            s.execute("ALTER TABLE products ADD COLUMN variety VARCHAR(100)");
            s.execute("ALTER TABLE products ADD COLUMN head_count INT");
            s.execute("ALTER TABLE shelves ADD COLUMN warehouse_id BIGINT NOT NULL DEFAULT 1");
            s.execute("ALTER TABLE shelf_slots ADD COLUMN cell_no INT NOT NULL DEFAULT 1");
            s.execute("ALTER TABLE shelf_slots MODIFY COLUMN code VARCHAR(48) NOT NULL");
            for (String column : new String[]{"inventory.quantity","stock_movements.quantity","stock_movements.before_qty",
                    "stock_movements.after_qty","stock_transfers.quantity","products.warning_level"}) {
                String[] parts=column.split("\\.");
                s.execute("ALTER TABLE "+parts[0]+" MODIFY COLUMN "+parts[1]+" DECIMAL(18,3)"+
                        (column.equals("products.warning_level") ? "" : " NOT NULL"));
            }
            assertThatThrownBy(() -> s.execute("ALTER TABLE shelf_slots DROP INDEX shelf_id"))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("needed in a foreign key constraint");
            s.execute("""
                    INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success)
                    VALUES(2,'2','separate warehouses','JDBC','db.migration.V2__separate_warehouses',NULL,CURRENT_USER(),1,FALSE)
                    """);
        }
        assertThatThrownBy(() -> flyway().migrate()).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        flyway().repair();
        flyway().migrate();
        verifyLegacyDataAndCellKey();
    }

    private void verifyLegacyDataAndCellKey() throws Exception {
        try (var c = DriverManager.getConnection(url, user, password); var s = c.createStatement()) {
            try (var r=s.executeQuery("SELECT s.id,s.code,s.qr_token,s.cell_no,h.warehouse_id,p.warehouse_id,i.quantity FROM shelf_slots s JOIN shelves h ON h.id=s.shelf_id JOIN products p ON p.id=s.product_id JOIN inventory i ON i.slot_id=s.id")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getLong(1)).isEqualTo(1);
                assertThat(r.getString(2)).isEqualTo("OLD-1");
                assertThat(r.getString(3)).isEqualTo("old-qr-token");
                assertThat(r.getInt(4)).isEqualTo(1);
                assertThat(r.getLong(5)).isEqualTo(1);
                assertThat(r.getLong(6)).isEqualTo(1);
                assertThat(r.getBigDecimal(7)).isEqualByComparingTo("7");
            }
            try (var r=s.executeQuery("SELECT quantity,before_qty,after_qty FROM stock_movements WHERE document_no='OLD-IN'")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getBigDecimal(1)).isEqualByComparingTo("7");
                assertThat(r.getBigDecimal(2)).isZero();
                assertThat(r.getBigDecimal(3)).isEqualByComparingTo("7");
            }
            s.execute("INSERT INTO shelf_slots(shelf_id,code,layer_no,cell_no,qr_token) VALUES(1,'OLD-1-2',1,2,'second-cell-token')");
            assertThatThrownBy(() -> s.execute("INSERT INTO shelf_slots(shelf_id,code,layer_no,cell_no,qr_token) VALUES(999,'INVALID-1',1,1,'invalid-token')"))
                    .isInstanceOf(java.sql.SQLException.class);
            try (var r=s.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version='2' AND success=TRUE")) {
                assertThat(r.next()).isTrue(); assertThat(r.getInt(1)).isEqualTo(1);
            }
            s.execute("INSERT INTO user_preferences(user_id,weight_unit) VALUES(1,'GRAM')");
            try (var r=s.executeQuery("SELECT weight_unit FROM user_preferences WHERE user_id=1")) {
                assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("GRAM");
            }
            assertThatThrownBy(() -> s.execute("UPDATE user_preferences SET weight_unit='KG' WHERE user_id=1"))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }
}
