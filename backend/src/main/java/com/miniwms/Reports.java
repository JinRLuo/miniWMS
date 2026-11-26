package com.miniwms;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.sql.ResultSet;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
class Reports {
    enum Period { DAY, WEEK, MONTH }

    record Filters(LocalDate from, LocalDate to, String product, String shelf, Stock.Type type, String operator,
                   String document, Boolean mine, String source, String target,
                   Long productId, Long slotId, Long sourceSlotId, Long targetSlotId, Long warehouseId, String variety, Integer headCount) {}
    record Movement(long id, long createdAt, Stock.Type type, String documentNo, String sku, String product,
                    String slot, BigDecimal quantity, BigDecimal beforeQty, BigDecimal afterQty, String operator, String note, String unit, Integer headCount) {}
    record Transfer(long id, long createdAt, String transferNo, String sku, String product, String source,
                    String target, BigDecimal quantity, String operator, String note, String unit) {}
    record Page<T>(List<T> rows, boolean more) {}
    record Trend(LocalDate date, BigDecimal inbound, BigDecimal outbound) {}
    record ProductStats(long id, String sku, String name, String unit, BigDecimal inbound, BigDecimal outbound, BigDecimal quantity) {}
    record UnitTotal(String unit, BigDecimal quantity, BigDecimal inbound, BigDecimal outbound) {}
    record Summary(long products, long occupiedSlots, long slots, long lowStock, List<UnitTotal> units,
                   String zone, LocalDate today) {}

    record Query(String sql, List<Object> args) {}
    private record Dates(LocalDate from, LocalDate to) {}

    private static final String MOVEMENTS = """
        FROM stock_movements m JOIN products p ON p.id=m.product_id JOIN shelf_slots s ON s.id=m.slot_id
        JOIN users u ON u.id=m.operator_id
        """;
    private static final String TRANSFERS = """
        FROM stock_transfers t JOIN products p ON p.id=t.product_id
        JOIN shelf_slots src ON src.id=t.source_slot_id JOIN shelf_slots dst ON dst.id=t.target_slot_id
        JOIN users u ON u.id=t.operator_id JOIN stock_movements m ON m.transfer_id=t.id AND m.type='TRANSFER_OUT'
        """;
    private static final String MOVEMENT_COLUMNS = """
        SELECT m.*, p.sku, p.name, p.unit, p.head_count, s.code, u.display_name
        """;
    private static final RowMapper<Movement> MOVEMENT = (r, n) -> new Movement(r.getLong("id"), r.getLong("created_at"),
            Stock.Type.valueOf(r.getString("type")), r.getString("document_no"), r.getString("sku"), r.getString("name"),
            r.getString("code"), r.getBigDecimal("quantity"), r.getBigDecimal("before_qty"), r.getBigDecimal("after_qty"),
            r.getString("display_name"), r.getString("note"), r.getString("unit"), r.getObject("head_count", Integer.class));

    private final WarehouseDb db;
    private final Warehouses warehouses;
    private final ZoneId zone;

    Reports(WarehouseDb db, Warehouses warehouses, @Value("${wms.zone}") String zone) {
        this.db = db;
        this.warehouses = warehouses;
        this.zone = ZoneId.of(zone);
    }

    Summary summary() { return summary(1); }

    Summary summary(long warehouseId) {
        warehouses.get(warehouseId);
        var day = LocalDate.now(zone);
        var totals = db.jdbc.queryForMap("""
            SELECT (SELECT COUNT(*) FROM products WHERE active=TRUE AND warehouse_id=?) AS product_count,
                   (SELECT COUNT(*) FROM shelf_slots s JOIN shelves h ON h.id=s.shelf_id
                    WHERE s.active=TRUE AND h.warehouse_id=?) AS slot_count,
                   (SELECT COUNT(*) FROM shelf_slots s JOIN shelves h ON h.id=s.shelf_id JOIN inventory i ON i.slot_id=s.id
                    WHERE s.active=TRUE AND i.quantity>0 AND h.warehouse_id=?) AS occupied,
                   (SELECT COUNT(*) FROM products p WHERE p.active=TRUE AND p.warehouse_id=? AND p.warning_level >
                        (SELECT COALESCE(SUM(i.quantity),0) FROM shelf_slots s JOIN inventory i ON i.slot_id=s.id
                         WHERE s.product_id=p.id)) AS warning
            """, warehouseId, warehouseId, warehouseId, warehouseId);
        var units = db.jdbc.query("""
            SELECT p.unit, COALESCE(SUM(i.quantity),0) AS quantity,
                   COALESCE(SUM(m.inbound),0) AS inbound, COALESCE(SUM(m.outbound),0) AS outbound
            FROM products p
            LEFT JOIN (SELECT s.product_id, SUM(i.quantity) AS quantity FROM shelf_slots s
                JOIN inventory i ON i.slot_id=s.id GROUP BY s.product_id) i ON i.product_id=p.id
            LEFT JOIN (SELECT product_id,
                SUM(CASE WHEN type='IN' THEN quantity ELSE 0 END) AS inbound,
                SUM(CASE WHEN type='OUT' THEN quantity ELSE 0 END) AS outbound
                FROM stock_movements WHERE created_at>=? AND created_at<? GROUP BY product_id) m ON m.product_id=p.id
            WHERE p.warehouse_id=? GROUP BY p.unit ORDER BY p.unit
            """, (r,n) -> new UnitTotal(r.getString("unit"), r.getBigDecimal("quantity"),
                r.getBigDecimal("inbound"), r.getBigDecimal("outbound")), start(day), start(day.plusDays(1)), warehouseId);
        return new Summary(value(totals, "product_count"), value(totals, "occupied"), value(totals, "slot_count"),
                value(totals, "warning"), units, zone.getId(), day);
    }

    Page<Movement> movements(Filters filters, Accounts.Principal actor, int page) {
        var query = conditions(filters, actor, false);
        return page(MOVEMENT_COLUMNS + MOVEMENTS + query.sql() + " ORDER BY m.created_at DESC,m.id DESC",
                MOVEMENT, query.args(), page);
    }

    Page<Transfer> transfers(Filters filters, Accounts.Principal actor, int page) {
        var query = conditions(filters, actor, true);
        return page("""
            SELECT t.*, p.sku, p.name, p.unit, src.code AS source, dst.code AS target, u.display_name, m.note
            """ + TRANSFERS + query.sql() + " ORDER BY t.created_at DESC,t.id DESC",
                (r, n) -> new Transfer(r.getLong("id"), r.getLong("created_at"), r.getString("transfer_no"),
                        r.getString("sku"), r.getString("name"), r.getString("source"), r.getString("target"),
                        r.getBigDecimal("quantity"), r.getString("display_name"), r.getString("note"), r.getString("unit")),
                query.args(), page);
    }

    void export(Query query, Writer writer) throws IOException {
        export(query, writer, UserPreferences.WeightUnit.JIN);
    }

    void export(Query query, Writer writer, UserPreferences.WeightUnit weightUnit) throws IOException {
        writer.write("\uFEFF");
        csv(writer, "时间", "操作", "单据", "SKU", "商品", "库位", "数量", "变动前", "变动后", "操作人", "备注", "单位", "头数");
        db.jdbc.query(MOVEMENT_COLUMNS + MOVEMENTS + query.sql() + " ORDER BY m.created_at DESC,m.id DESC",
                (ResultSet row) -> {
                    var m = MOVEMENT.mapRow(row, row.getRow());
                    var displayUnit = m.headCount() == null ? UserPreferences.WeightUnit.JIN : weightUnit;
                    try {
                        csv(writer, Instant.ofEpochMilli(m.createdAt()).atZone(zone).toOffsetDateTime().toString(),
                                switch (m.type()) {
                                    case IN -> "入库";
                                    case OUT -> "出库";
                                    case TRANSFER_OUT -> "移库转出";
                                    case TRANSFER_IN -> "移库转入";
                                },
                                m.documentNo(), m.sku(), m.product(), m.slot(), displayUnit.display(m.quantity(), m.unit()).stripTrailingZeros().toPlainString(),
                                displayUnit.display(m.beforeQty(), m.unit()).stripTrailingZeros().toPlainString(),
                                displayUnit.display(m.afterQty(), m.unit()).stripTrailingZeros().toPlainString(), m.operator(), m.note(), displayUnit.label(m.unit()), m.headCount() == null ? "" : m.headCount().toString());
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }, query.args().toArray());
    }

    Query exportQuery(Filters filters, Accounts.Principal actor) {
        return conditions(filters, actor, false);
    }

    List<Trend> trend(LocalDate from, LocalDate to, Period period) { return trend(from, to, period, 1, null); }

    List<Trend> trend(LocalDate from, LocalDate to, Period period, long warehouseId, String unit) {
        return trend(from, to, period, warehouseId, unit, null, null);
    }

    List<Trend> trend(LocalDate from, LocalDate to, Period period, long warehouseId, String unit,
                      String variety, Integer headCount) {
        warehouses.get(warehouseId);
        var dates = dates(from, to);
        Map<LocalDate, BigDecimal[]> buckets = new LinkedHashMap<>();
        for (var day = dates.from(); !day.isAfter(dates.to()); day = day.plusDays(1)) {
            buckets.computeIfAbsent(bucket(day, period), ignored -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        }
        StringBuilder conditions = new StringBuilder(" WHERE m.created_at>=? AND m.created_at<? AND m.type IN ('IN','OUT') AND p.warehouse_id=? AND p.unit=?");
        List<Object> args = new ArrayList<>(List.of(start(dates.from()), start(dates.to().plusDays(1)), warehouseId, trendUnit(warehouseId, unit)));
        fishFilters(conditions, args, variety, headCount);
        db.jdbc.query("SELECT m.created_at,m.type,m.quantity FROM stock_movements m JOIN products p ON p.id=m.product_id" + conditions,
            (ResultSet row) -> {
                var day = Instant.ofEpochMilli(row.getLong(1)).atZone(zone).toLocalDate();
                var values = buckets.get(bucket(day, period));
                int index = "IN".equals(row.getString(2)) ? 0 : 1;
                values[index] = values[index].add(row.getBigDecimal(3));
            }, args.toArray());
        return buckets.entrySet().stream().map(e -> new Trend(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
    }

    private String trendUnit(long warehouseId, String unit) {
        warehouses.get(warehouseId);
        if (unit != null && !unit.isBlank()) return unit;
        var units = db.jdbc.queryForList("SELECT DISTINCT unit FROM products WHERE warehouse_id=? ORDER BY unit", String.class, warehouseId);
        return units.isEmpty() ? "" : units.get(0);
    }

    List<ProductStats> products(LocalDate from, LocalDate to) { return products(from, to, 1); }

    List<ProductStats> products(LocalDate from, LocalDate to, long warehouseId) {
        return products(from, to, warehouseId, null, null);
    }

    List<ProductStats> products(LocalDate from, LocalDate to, long warehouseId, String variety, Integer headCount) {
        warehouses.get(warehouseId);
        var dates = dates(from, to);
        StringBuilder conditions = new StringBuilder(" WHERE p.warehouse_id=?");
        List<Object> args = new ArrayList<>(List.of(start(dates.from()), start(dates.to().plusDays(1)), warehouseId));
        fishFilters(conditions, args, variety, headCount);
        return db.jdbc.query("""
            SELECT p.id, p.sku, p.name, p.unit, COALESCE(m.inbound,0) AS inbound, COALESCE(m.outbound,0) AS outbound,
                   COALESCE(i.quantity,0) AS quantity
            FROM products p
            LEFT JOIN (SELECT product_id,
                   SUM(CASE WHEN type='IN' THEN quantity ELSE 0 END) AS inbound,
                   SUM(CASE WHEN type='OUT' THEN quantity ELSE 0 END) AS outbound
                   FROM stock_movements WHERE created_at>=? AND created_at<? AND type IN ('IN','OUT')
                   GROUP BY product_id) m ON m.product_id=p.id
            LEFT JOIN (SELECT s.product_id,SUM(i.quantity) AS quantity FROM inventory i JOIN shelf_slots s ON s.id=i.slot_id
                   GROUP BY s.product_id) i ON i.product_id=p.id
            """ + conditions + " ORDER BY outbound DESC,p.sku", (r, n) -> new ProductStats(r.getLong("id"), r.getString("sku"), r.getString("name"), r.getString("unit"),
                r.getBigDecimal("inbound"), r.getBigDecimal("outbound"), r.getBigDecimal("quantity")), args.toArray());
    }

    private Query conditions(Filters f, Accounts.Principal actor, boolean transfer) {
        warehouses.get(Warehouses.id(f.warehouseId()));
        if (f.from() != null && f.to() != null && f.from().isAfter(f.to())) throw ApiError.bad("开始日期不能晚于结束日期");
        String alias = transfer ? "t" : "m";
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        add(sql, args, "p.warehouse_id=?", Warehouses.id(f.warehouseId()));
        fishFilters(sql, args, f.variety(), f.headCount());
        if (f.from() != null) add(sql, args, alias + ".created_at>=?", start(f.from()));
        if (f.to() != null) add(sql, args, alias + ".created_at<?", start(f.to().plusDays(1)));
        if (f.productId() != null) add(sql, args, alias + ".product_id=?", f.productId());
        text(sql, args, f.product(), "p.sku", "p.name", "p.barcode");
        text(sql, args, f.operator(), "u.username", "u.display_name");
        text(sql, args, f.document(), "m.document_no");
        if (transfer) {
            if (f.sourceSlotId() != null) add(sql, args, "t.source_slot_id=?", f.sourceSlotId());
            if (f.targetSlotId() != null) add(sql, args, "t.target_slot_id=?", f.targetSlotId());
            text(sql, args, f.source(), "src.code");
            text(sql, args, f.target(), "dst.code");
        } else {
            if (f.slotId() != null) add(sql, args, "m.slot_id=?", f.slotId());
            if (f.type() != null) add(sql, args, "m.type=?", f.type().name());
            text(sql, args, f.shelf(), "s.code");
        }
        if (Boolean.TRUE.equals(f.mine()) || actor.role() == Accounts.Role.OPERATOR) {
            add(sql, args, alias + ".operator_id=?", actor.id());
        }
        return new Query(sql.toString(), args);
    }

    private <T> Page<T> page(String sql, RowMapper<T> mapper, List<Object> args, int page) {
        if (page < 1) throw ApiError.bad("页码必须大于零");
        args.add(31);
        args.add((page - 1L) * 30);
        var rows = db.jdbc.query(sql + " LIMIT ? OFFSET ?", mapper, args.toArray());
        return new Page<>(rows.subList(0, Math.min(rows.size(), 30)), rows.size() > 30);
    }

    private void fishFilters(StringBuilder sql, List<Object> args, String variety, Integer headCount) {
        String value = Catalog.optional(variety);
        if (value != null) {
            if (value.length() > 100) throw ApiError.bad("品种筛选不能超过100个字符");
            add(sql, args, "p.variety=?", value);
        }
        if (headCount != null) {
            if (headCount < 1 || headCount > 100000) throw ApiError.bad("头数须为1至100000的整数");
            add(sql, args, "p.head_count=?", headCount);
        }
    }

    private void text(StringBuilder sql, List<Object> args, String input, String... columns) {
        String value = Catalog.optional(input);
        if (value == null) return;
        if (value.length() > 160) throw ApiError.bad("筛选条件过长");
        String pattern = "%" + value.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var predicates = Arrays.stream(columns).map(col -> "LOWER(" + col + ") LIKE ? ESCAPE '!'").toList();
        sql.append(" AND (").append(String.join(" OR ", predicates)).append(")");
        for (String col : columns) args.add(pattern);
    }

    private void add(StringBuilder sql, List<Object> args, String condition, Object value) {
        sql.append(" AND ").append(condition);
        args.add(value);
    }

    private Dates dates(LocalDate from, LocalDate to) {
        LocalDate last = to == null ? LocalDate.now(zone) : to;
        LocalDate first = from == null ? last.minusDays(6) : from;
        if (first.isAfter(last) || first.plusDays(365).isBefore(last)) throw ApiError.bad("统计日期范围须在 366 天以内");
        return new Dates(first, last);
    }

    private long start(LocalDate date) { return date.atStartOfDay(zone).toInstant().toEpochMilli(); }

    private LocalDate bucket(LocalDate date, Period period) {
        return switch (period) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    private long value(Map<String, Object> values, String key) { return ((Number) values.get(key)).longValue(); }

    private void csv(Writer writer, String... values) throws IOException {
        StringJoiner row = new StringJoiner(",");
        for (String field : values) {
            String value = field == null ? "" : field;
            String trimmed = value.stripLeading();
            if (!value.isEmpty() && ("\t\r\n".indexOf(value.charAt(0)) >= 0
                    || (!trimmed.isEmpty() && "=+-@＝＋－＠".indexOf(trimmed.charAt(0)) >= 0))) {
                value = "'" + value;
            }
            row.add("\"" + value.replace("\"", "\"\"") + "\"");
        }
        writer.write(row + "\r\n");
    }
}
