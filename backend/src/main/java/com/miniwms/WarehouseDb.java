package com.miniwms;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
                BigDecimal quantity, boolean active, boolean productActive, long warehouseId, int cellNo,
                String variety, Integer headCount) {
        public BigDecimal getEstimatedCount() {
            return headCount == null ? null : quantity.multiply(BigDecimal.valueOf(headCount)).setScale(0, RoundingMode.HALF_UP);
        }
    }

    static final String SLOT_VIEW = """
        SELECT s.id, s.shelf_id, h.code AS shelf_code, s.code, s.layer_no, s.product_id,
               p.sku, p.name AS product_name, p.unit, p.specification, i.quantity,
               s.active, p.active AS product_active, h.warehouse_id, s.cell_no, p.variety, p.head_count
        FROM shelf_slots s JOIN shelves h ON h.id=s.shelf_id JOIN inventory i ON i.slot_id=s.id
        LEFT JOIN products p ON p.id=s.product_id
        """;

    static final RowMapper<Slot> SLOT = (r, n) -> new Slot(
            r.getLong("id"), r.getLong("shelf_id"), r.getString("shelf_code"), r.getString("code"),
            r.getInt("layer_no"), r.getObject("product_id", Long.class), r.getString("sku"),
            r.getString("product_name"), r.getString("unit"), r.getString("specification"),
            r.getBigDecimal("quantity"), r.getBoolean("active"), r.getBoolean("product_active"),
            r.getLong("warehouse_id"), r.getInt("cell_no"), r.getString("variety"), r.getObject("head_count", Integer.class));

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
        return slots(1);
    }

    List<Slot> slots(long warehouseId) {
        return jdbc.query(SLOT_VIEW + " WHERE h.warehouse_id=? ORDER BY h.code, s.layer_no, s.cell_no", SLOT, warehouseId);
    }

    Slot slot(long id) {
        return one(SLOT_VIEW + " WHERE s.id=?", SLOT, "货架层不存在", id);
    }

    Slot resolve(UUID token) {
        return one(SLOT_VIEW + " WHERE s.qr_token=?", SLOT, "货架二维码无效", token.toString());
    }

    Slot byCode(String code, long warehouseId) {
        return one(SLOT_VIEW + " WHERE UPPER(s.code)=? AND h.warehouse_id=?", SLOT, "当前仓库没有此库位编号",
                code.trim().toUpperCase(java.util.Locale.ROOT), warehouseId);
    }

    void requireWarehouse(Slot slot, long warehouseId) {
        if (slot.warehouseId() != warehouseId) throw ApiError.conflict("库位不属于当前仓库，请切换仓库后操作");
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
