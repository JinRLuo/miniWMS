package com.miniwms;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

@Component
class WarehouseDb {
    final JdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();

    WarehouseDb(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Slot(long id, long shelfId, String shelfCode, String code, int layerNo,
                Long productId, String sku, String productName, String unit, String specification,
                int quantity, boolean active, boolean productActive) {}

    static final String SLOT_VIEW = """
        SELECT s.id, s.shelf_id, h.code AS shelf_code, s.code, s.layer_no, s.product_id,
               p.sku, p.name AS product_name, p.unit, p.specification, i.quantity,
               s.active, p.active AS product_active
        FROM shelf_slots s JOIN shelves h ON h.id=s.shelf_id JOIN inventory i ON i.slot_id=s.id
        LEFT JOIN products p ON p.id=s.product_id
        """;

    static final RowMapper<Slot> SLOT = (r, n) -> new Slot(
            r.getLong("id"), r.getLong("shelf_id"), r.getString("shelf_code"), r.getString("code"),
            r.getInt("layer_no"), r.getObject("product_id", Long.class), r.getString("sku"),
            r.getString("product_name"), r.getString("unit"), r.getString("specification"),
            r.getInt("quantity"), r.getBoolean("active"), r.getBoolean("product_active"));

    Timestamp now() {
        return Timestamp.from(Instant.now(clock));
    }

    <T> T one(String sql, RowMapper<T> mapper, String message, Object... params) {
        return jdbc.query(sql, mapper, params).stream().findFirst()
                .orElseThrow(() -> ApiError.missing(message));
    }

    long insert(String sql, Object... params) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    List<Slot> slots() {
        return jdbc.query(SLOT_VIEW + " ORDER BY h.code, s.layer_no", SLOT);
    }

    Slot slot(long id) {
        return one(SLOT_VIEW + " WHERE s.id=?", SLOT, "货架层不存在", id);
    }

    Slot resolve(UUID token) {
        return one(SLOT_VIEW + " WHERE s.qr_token=?", SLOT, "货架二维码无效", token.toString());
    }

    void lockActor(long id) {
        boolean active = one("SELECT active FROM users WHERE id=? FOR UPDATE",
                (r, n) -> r.getBoolean(1), "账号不存在", id);
        if (!active) throw ApiError.conflict("账号已停用");
    }

    void lockSlot(long id) {
        one("SELECT id FROM shelf_slots WHERE id=? FOR UPDATE", (r, n) -> r.getLong(1), "货架层不存在", id);
    }

    boolean lockProduct(long id) {
        return one("SELECT active FROM products WHERE id=? FOR UPDATE",
                (r, n) -> r.getBoolean(1), "商品不存在", id);
    }

    void audit(long actor, String action, long target) {
        jdbc.update("INSERT INTO audit_logs(operator_id,action,target_id) VALUES(?,?,?)", actor, action, target);
    }
}
