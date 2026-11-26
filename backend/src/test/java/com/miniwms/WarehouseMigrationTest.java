package com.miniwms;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WarehouseMigrationTest {
    @Test
    void v1BalancesHistoryAndQrTokensSurviveTheWarehouseMigration() throws Exception {
        String url = "jdbc:h2:mem:migration-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("1").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.execute("INSERT INTO users(id,username,display_name,password_hash,role) VALUES(1,'legacy','员工','hash','ADMIN')");
            s.execute("INSERT INTO products(id,sku,name,unit) VALUES(1,'OLD-SKU','原商品','件')");
            s.execute("INSERT INTO shelves(id,code,name,layer_count) VALUES(1,'OLD','原货架',1)");
            s.execute("INSERT INTO shelf_slots(id,shelf_id,code,layer_no,product_id,qr_token) VALUES(1,1,'OLD-1',1,1,'old-qr-token')");
            s.execute("INSERT INTO inventory(slot_id,quantity) VALUES(1,7)");
            s.execute("INSERT INTO stock_requests(id,operator_id,request_id,fingerprint) VALUES(1,1,'old-request','old-fingerprint')");
            s.execute("INSERT INTO stock_movements(request_id,product_id,slot_id,type,quantity,before_qty,after_qty,operator_id,document_no,created_at) VALUES(1,1,1,'IN',7,0,7,1,'OLD-IN',0)");
        }
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            try (var r = s.executeQuery("SELECT s.id,s.code,s.qr_token,s.cell_no,h.warehouse_id,p.warehouse_id,i.quantity FROM shelf_slots s JOIN shelves h ON h.id=s.shelf_id JOIN products p ON p.id=s.product_id JOIN inventory i ON i.slot_id=s.id")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getLong(1)).isEqualTo(1);
                assertThat(r.getString(2)).isEqualTo("OLD-1");
                assertThat(r.getString(3)).isEqualTo("old-qr-token");
                assertThat(r.getInt(4)).isEqualTo(1);
                assertThat(r.getLong(5)).isEqualTo(1);
                assertThat(r.getLong(6)).isEqualTo(1);
                assertThat(r.getBigDecimal(7)).isEqualByComparingTo("7");
            }
            try (var r = s.executeQuery("SELECT quantity,before_qty,after_qty FROM stock_movements WHERE document_no='OLD-IN'")) {
                assertThat(r.next()).isTrue();
                assertThat(r.getBigDecimal(1)).isEqualByComparingTo("7");
                assertThat(r.getBigDecimal(2)).isZero();
                assertThat(r.getBigDecimal(3)).isEqualByComparingTo("7");
            }
            // The previous unique-per-layer constraint has been replaced with unique-per-cell.
            s.execute("INSERT INTO shelf_slots(shelf_id,code,layer_no,cell_no,qr_token) VALUES(1,'OLD-1-2',1,2,'second-cell-token')");
            s.execute("INSERT INTO user_preferences(user_id,weight_unit) VALUES(1,'GRAM')");
            try (var r = s.executeQuery("SELECT weight_unit FROM user_preferences WHERE user_id=1")) {
                assertThat(r.next()).isTrue(); assertThat(r.getString(1)).isEqualTo("GRAM");
            }
        }
    }
}
