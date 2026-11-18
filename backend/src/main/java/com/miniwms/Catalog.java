package com.miniwms;

import jakarta.validation.constraints.*;
import java.net.URI;
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
                   String unit, String imageUrl, Integer warningLevel, boolean active, String note, long quantity) {}

    record ProductForm(@NotBlank @Size(max=64) String sku, @NotBlank @Size(max=160) String name,
                       @Size(max=80) String barcode, @Size(max=80) String category,
                       @Size(max=100) String specification, @NotBlank @Size(max=16) String unit,
                       @Size(max=512) String imageUrl, @PositiveOrZero Integer warningLevel,
                       @NotNull Boolean active, @Size(max=500) String note) {}

    record Shelf(long id, String code, String name, int layerCount) {}
    record ShelfForm(@NotBlank @Pattern(regexp="[A-Za-z0-9]{1,32}") String code,
                     @NotBlank @Size(max=100) String name, @NotNull @Min(1) @Max(100) Integer layerCount) {}
    record SlotForm(@Positive Long productId, @NotNull Boolean active) {}

    private static final String PRODUCT_VIEW = """
        SELECT p.*, COALESCE(t.quantity, 0) AS total FROM products p
        LEFT JOIN (SELECT s.product_id, SUM(i.quantity) AS quantity FROM shelf_slots s
                   JOIN inventory i ON s.id=i.slot_id GROUP BY s.product_id) t ON t.product_id=p.id
        """;
    private static final RowMapper<Product> PRODUCT = (r, n) -> new Product(
            r.getLong("id"), r.getString("sku"), r.getString("name"), r.getString("barcode"),
            r.getString("category"), r.getString("specification"), r.getString("unit"), r.getString("image_url"),
            r.getObject("warning_level", Integer.class), r.getBoolean("active"), r.getString("note"), r.getLong("total"));
    private static final RowMapper<Shelf> SHELF = (r, n) -> new Shelf(r.getLong("id"), r.getString("code"),
            r.getString("name"), r.getInt("layer_count"));

    private final WarehouseDb db;

    Catalog(WarehouseDb db) {
        this.db = db;
    }

    List<Product> products() {
        return db.jdbc.query(PRODUCT_VIEW + " ORDER BY p.sku", PRODUCT);
    }

    List<Shelf> shelves() {
        return db.jdbc.query("SELECT * FROM shelves ORDER BY code", SHELF);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Product saveProduct(Long id, ProductForm form, long actor) {
        db.lockActor(actor);
        String image = optional(form.imageUrl());
        if (image != null) validateImage(image);
        String sku = form.sku().trim().toUpperCase(Locale.ROOT);
        if (sku.length() > 64) throw ApiError.bad("SKU 编号不能超过 64 个字符");
        Object[] fields = {sku, form.name().trim(), optional(form.barcode()),
                optional(form.category()), optional(form.specification()), form.unit().trim(), image,
                form.warningLevel(), form.active(), optional(form.note())};
        long productId;
        if (id == null) {
            productId = db.insert("""
                INSERT INTO products(sku,name,barcode,category,specification,unit,image_url,warning_level,active,note)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, fields);
        } else {
            db.lockProduct(id);
            Object[] values = java.util.Arrays.copyOf(fields, fields.length + 1);
            values[fields.length] = id;
            db.jdbc.update("""
                UPDATE products SET sku=?,name=?,barcode=?,category=?,specification=?,unit=?,image_url=?,
                       warning_level=?,active=?,note=? WHERE id=?
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
        int existingLayers = 0;
        long shelfId;
        if (id == null) {
            shelfId = db.insert("INSERT INTO shelves(code,name,layer_count) VALUES(?,?,?)", code, name, layers);
        } else {
            Shelf current = db.one("SELECT * FROM shelves WHERE id=? FOR UPDATE", SHELF, "货架不存在", id);
            if (!current.code().equals(code)) throw ApiError.conflict("已建货架的编号固定");
            if (layers < current.layerCount()) throw ApiError.conflict("可停用不再使用的货架层");
            existingLayers = current.layerCount();
            db.jdbc.update("UPDATE shelves SET name=?,layer_count=? WHERE id=?", name, layers, id);
            shelfId = id;
        }
        for (int layer = existingLayers + 1; layer <= layers; layer++) {
            long slot = db.insert("INSERT INTO shelf_slots(shelf_id,code,layer_no,qr_token) VALUES(?,?,?,?)",
                    shelfId, code + "-" + layer, layer, UUID.randomUUID().toString());
            db.jdbc.update("INSERT INTO inventory(slot_id, quantity) VALUES(?,0)", slot);
        }
        db.audit(actor, id == null ? "SHELF_CREATE" : "SHELF_EDIT", shelfId);
        return new Shelf(shelfId, code, name, layers);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public WarehouseDb.Slot saveSlot(long id, SlotForm form, long actor) {
        db.lockActor(actor);
        db.lockSlot(id);
        var current = db.slot(id);
        boolean bindingChanged = !java.util.Objects.equals(current.productId(), form.productId());
        if (current.quantity() > 0 && (bindingChanged || !form.active())) {
            throw ApiError.conflict("请先清空该货架层的库存，再换绑或停用");
        }
        if (bindingChanged && form.productId() != null && !db.lockProduct(form.productId())) {
            throw ApiError.conflict("停用商品不能绑定");
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
