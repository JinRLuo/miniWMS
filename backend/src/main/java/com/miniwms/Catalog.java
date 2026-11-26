package com.miniwms;

import jakarta.validation.constraints.*;
import java.net.URI;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class Catalog {
    record Product(long id, String sku, String name, String barcode, String category, String specification,
                   String unit, String imageUrl, BigDecimal warningLevel, boolean active, String note, BigDecimal quantity,
                   long warehouseId, String variety, Integer headCount, boolean specificationLocked) {
        public BigDecimal getEstimatedCount() {
            return headCount == null ? null : quantity.multiply(BigDecimal.valueOf(headCount)).setScale(0, RoundingMode.HALF_UP);
        }
    }

    record ProductForm(@NotBlank @Size(max=64) String sku, @NotBlank @Size(max=160) String name,
                       @Size(max=80) String barcode, @Size(max=80) String category,
                       @Size(max=100) String specification, @NotBlank @Size(max=16) String unit,
                       @Size(max=512) String imageUrl, @PositiveOrZero @Digits(integer=10, fraction=3) BigDecimal warningLevel,
                       @NotNull Boolean active, @Size(max=500) String note, @Positive Long warehouseId,
                       @Size(max=100) String variety, @Positive @Max(100000) Integer headCount) {
        ProductForm(String sku, String name, String barcode, String category, String specification, String unit,
                    String imageUrl, Integer warningLevel, Boolean active, String note) {
            this(sku, name, barcode, category, specification, unit, imageUrl,
                    warningLevel == null ? null : BigDecimal.valueOf(warningLevel), active, note, 1L, null, null);
        }
    }

    record Shelf(long id, String code, String name, int layerCount, long warehouseId) {}
    record ShelfForm(@NotBlank @Pattern(regexp="[A-Za-z0-9]{1,32}") String code,
                     @NotBlank @Size(max=100) String name, @NotNull @Min(1) @Max(100) Integer layerCount,
                     @Positive Long warehouseId, @Size(max=100) List<@NotNull @Min(1) @Max(100) Integer> cellCounts) {
        ShelfForm(String code, String name, Integer layerCount) { this(code, name, layerCount, 1L, null); }
    }
    record SlotForm(@Positive Long productId, @NotNull Boolean active) {}

    private static final String PRODUCT_VIEW = """
        SELECT p.*, COALESCE(t.quantity, 0) AS total,
               EXISTS(SELECT 1 FROM stock_movements m WHERE m.product_id=p.id) AS specification_locked FROM products p
        LEFT JOIN (SELECT s.product_id, SUM(i.quantity) AS quantity FROM shelf_slots s
                   JOIN inventory i ON s.id=i.slot_id GROUP BY s.product_id) t ON t.product_id=p.id
        """;
    private static final RowMapper<Product> PRODUCT = (r, n) -> new Product(
            r.getLong("id"), r.getString("sku"), r.getString("name"), r.getString("barcode"),
            r.getString("category"), r.getString("specification"), r.getString("unit"), r.getString("image_url"),
            r.getBigDecimal("warning_level"), r.getBoolean("active"), r.getString("note"), r.getBigDecimal("total"),
            r.getLong("warehouse_id"), r.getString("variety"), r.getObject("head_count", Integer.class), r.getBoolean("specification_locked"));
    private static final RowMapper<Shelf> SHELF = (r, n) -> new Shelf(r.getLong("id"), r.getString("code"),
            r.getString("name"), r.getInt("layer_count"), r.getLong("warehouse_id"));

    private final WarehouseDb db;
    private final Warehouses warehouses;

    Catalog(WarehouseDb db, Warehouses warehouses) {
        this.db = db;
        this.warehouses = warehouses;
    }

    List<Product> products() {
        return products(1);
    }

    List<Product> products(long warehouseId) {
        warehouses.get(warehouseId);
        return db.jdbc.query(PRODUCT_VIEW + " WHERE p.warehouse_id=? ORDER BY p.sku", PRODUCT, warehouseId);
    }

    List<Shelf> shelves() {
        return shelves(1);
    }

    List<Shelf> shelves(long warehouseId) {
        warehouses.get(warehouseId);
        return db.jdbc.query("SELECT * FROM shelves WHERE warehouse_id=? ORDER BY code", SHELF, warehouseId);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Product saveProduct(Long id, ProductForm form, long actor) {
        db.lockActor(actor);
        long warehouseId = Warehouses.id(form.warehouseId());
        boolean fish = warehouses.get(warehouseId).fish();
        String variety = fish ? optional(form.variety()) : null;
        Integer heads = fish ? form.headCount() : null;
        if (fish && (variety == null || heads == null || heads <= 0)) throw ApiError.bad("鱼胶需要填写品种和头数");
        String unit = fish ? "斤" : form.unit().trim();
        if (!fish && form.warningLevel() != null && form.warningLevel().stripTrailingZeros().scale() > 0)
            throw ApiError.bad("货架仓库的预警值必须为整数");
        String image = optional(form.imageUrl());
        if (image != null) validateImage(image);
        String sku = form.sku().trim().toUpperCase(Locale.ROOT);
        if (sku.length() > 64) throw ApiError.bad("SKU 编号不能超过 64 个字符");
        Object[] fields = {sku, fish ? variety + " · " + heads + "头" : form.name().trim(), optional(form.barcode()),
                optional(form.category()), fish ? heads + "头" : optional(form.specification()), unit, image,
                form.warningLevel(), form.active(), optional(form.note()), warehouseId, variety, heads};
        long productId;
        if (id == null) {
            productId = db.insert("""
                INSERT INTO products(sku,name,barcode,category,specification,unit,image_url,warning_level,active,note,warehouse_id,variety,head_count)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, fields);
        } else {
            db.lockProduct(id);
            Product current = db.one(PRODUCT_VIEW + " WHERE p.id=?", PRODUCT, "商品不存在", id);
            if (current.warehouseId() != warehouseId) throw ApiError.conflict("商品不属于当前仓库");
            boolean coreChanged = !java.util.Objects.equals(current.variety(), variety)
                    || !java.util.Objects.equals(current.headCount(), heads) || !current.unit().equals(unit);
            if (coreChanged && current.specificationLocked())
                throw ApiError.conflict("已有库存流水的品种、头数和单位固定，请新建商品规格");
            Object[] values = java.util.Arrays.copyOf(fields, fields.length + 1);
            values[fields.length] = id;
            db.jdbc.update("""
                UPDATE products SET sku=?,name=?,barcode=?,category=?,specification=?,unit=?,image_url=?,
                       warning_level=?,active=?,note=?,warehouse_id=?,variety=?,head_count=? WHERE id=?
                """, values);
            productId = id;
        }
        db.audit(actor, id == null ? "PRODUCT_CREATE" : "PRODUCT_EDIT", productId);
        return db.one(PRODUCT_VIEW + " WHERE p.id=?", PRODUCT, "商品不存在", productId);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Shelf saveShelf(Long id, ShelfForm form, long actor) {
        db.lockActor(actor);
        String code = form.code().toUpperCase(Locale.ROOT);
        String name = form.name().trim();
        int layers = form.layerCount();
        long warehouseId = Warehouses.id(form.warehouseId());
        boolean fish = warehouses.get(warehouseId).fish();
        List<Integer> cells = form.cellCounts();
        if (fish && (cells == null || cells.size() != layers)) throw ApiError.bad("请设置每一层的格子数量");
        long shelfId;
        if (id == null) {
            shelfId = db.insert("INSERT INTO shelves(code,name,layer_count,warehouse_id) VALUES(?,?,?,?)", code, name, layers, warehouseId);
        } else {
            Shelf current = db.one("SELECT * FROM shelves WHERE id=? FOR UPDATE", SHELF, "货架不存在", id);
            if (current.warehouseId() != warehouseId) throw ApiError.conflict("货架不属于当前仓库");
            if (!current.code().equals(code)) throw ApiError.conflict("已建货架的编号固定");
            if (layers < current.layerCount()) throw ApiError.conflict("可停用不再使用的库位");
            db.jdbc.update("UPDATE shelves SET name=?,layer_count=? WHERE id=?", name, layers, id);
            shelfId = id;
        }
        for (int layer = 1; layer <= layers; layer++) {
            int desired = fish ? cells.get(layer - 1) : 1;
            int existingCells = db.jdbc.queryForObject("SELECT COUNT(*) FROM shelf_slots WHERE shelf_id=? AND layer_no=?",
                    Integer.class, shelfId, layer);
            if (desired < existingCells) throw ApiError.conflict("已有格子不能删除，可停用不再使用的格子");
            for (int cell = existingCells + 1; cell <= desired; cell++) {
                String slotCode = fish ? code + "-" + String.format(Locale.ROOT, "%02d-%02d", layer, cell) : code + "-" + layer;
                long slot = db.insert("INSERT INTO shelf_slots(shelf_id,code,layer_no,cell_no,qr_token) VALUES(?,?,?,?,?)",
                        shelfId, slotCode, layer, cell, UUID.randomUUID().toString());
                db.jdbc.update("INSERT INTO inventory(slot_id, quantity) VALUES(?,0)", slot);
            }
        }
        db.audit(actor, id == null ? "SHELF_CREATE" : "SHELF_EDIT", shelfId);
        return new Shelf(shelfId, code, name, layers, warehouseId);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public WarehouseDb.Slot saveSlot(long id, SlotForm form, long actor) {
        return saveSlot(id, form, actor, 1);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public WarehouseDb.Slot saveSlot(long id, SlotForm form, long actor, long warehouseId) {
        db.lockActor(actor);
        db.lockSlot(id);
        var current = db.slot(id);
        db.requireWarehouse(current, warehouseId);
        boolean bindingChanged = !java.util.Objects.equals(current.productId(), form.productId());
        if (current.quantity().signum() > 0 && (bindingChanged || !form.active())) {
            throw ApiError.conflict("请先清空该库位的库存，再换绑或停用");
        }
        if (bindingChanged && form.productId() != null && !db.lockProduct(form.productId())) {
            throw ApiError.conflict("停用商品不能绑定");
        }
        if (form.productId() != null) {
            Long productWarehouse = db.jdbc.queryForObject("SELECT warehouse_id FROM products WHERE id=?", Long.class, form.productId());
            if (productWarehouse != warehouseId) throw ApiError.conflict("只能绑定当前仓库的商品");
        }
        db.jdbc.update("UPDATE shelf_slots SET product_id=?, active=? WHERE id=?", form.productId(), form.active(), id);
        db.audit(actor, "SLOT_EDIT", id);
        return db.slot(id);
    }

    static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateImage(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw ApiError.bad("商品图片需要有效的 HTTPS 地址");
        }
    }
}
