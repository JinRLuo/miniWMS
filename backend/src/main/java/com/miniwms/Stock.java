package com.miniwms;

import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class Stock {
    enum Type { IN, OUT, TRANSFER_OUT, TRANSFER_IN }

    record Adjust(@NotNull UUID requestId, @NotNull @Positive Long slotId, @NotNull @Positive Long productId,
                  @NotNull @Positive @Digits(integer=10, fraction=3) BigDecimal quantity, @Size(max=80) String documentNo,
                  @Size(max=500) String note) {
        Adjust(UUID requestId, Long slotId, Long productId, int quantity, String documentNo, String note) {
            this(requestId, slotId, productId, BigDecimal.valueOf(quantity), documentNo, note);
        }
        public Adjust { if (quantity != null) quantity = quantity.stripTrailingZeros(); }
    }

    record Transfer(@NotNull UUID requestId, @NotNull @Positive Long sourceSlotId,
                    @NotNull @Positive Long targetSlotId, @NotNull @Positive Long productId,
                    @NotNull @Positive @Digits(integer=10, fraction=3) BigDecimal quantity, @Size(max=500) String note) {
        Transfer(UUID requestId, Long sourceSlotId, Long targetSlotId, Long productId, int quantity, String note) {
            this(requestId, sourceSlotId, targetSlotId, productId, BigDecimal.valueOf(quantity), note);
        }
        public Transfer { if (quantity != null) quantity = quantity.stripTrailingZeros(); }
    }

    record Change(long slotId, Type type, BigDecimal beforeQty, BigDecimal afterQty) {}
    record Result(String documentNo, List<Change> changes) {}
    private record Request(long id, String fingerprint) {}

    private static final RowMapper<Change> CHANGE = (r, n) -> new Change(
            r.getLong("slot_id"), Type.valueOf(r.getString("type")), r.getBigDecimal("before_qty"), r.getBigDecimal("after_qty"));

    private final WarehouseDb db;

    private final com.fasterxml.jackson.databind.ObjectMapper json;

    Stock(WarehouseDb db, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Result adjust(long actor, Type type, Adjust form) { return adjust(actor, type, form, 1); }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Result adjust(long actor, Type type, Adjust form, long warehouseId) {
        if (type != Type.IN && type != Type.OUT) throw ApiError.bad("出入库类型无效");
        var request = begin(actor, form.requestId(), fingerprint(type, form));
        Result previous = previous(request.id());
        if (previous != null) {
            db.requireWarehouse(db.slot(form.slotId()), warehouseId);
            return previous;
        }

        db.lockSlot(form.slotId());
        var slot = db.slot(form.slotId());
        db.requireWarehouse(slot, warehouseId);
        validateQuantity(form.quantity(), slot);
        checkSource(slot, form.productId());
        boolean productActive = db.lockProduct(form.productId());
        if (type == Type.IN && !productActive) throw ApiError.conflict("商品已停用，无法入库");
        BigDecimal after = type == Type.IN ? add(slot.quantity(), form.quantity()) : deduct(slot.quantity(), form.quantity());
        String documentNo = Catalog.optional(form.documentNo());
        if (documentNo == null) documentNo = type + "-" + UUID.randomUUID();
        var change = new Change(slot.id(), type, slot.quantity(), after);
        write(request.id(), null, form.productId(), actor, documentNo, form.note(), change, db.now().getTime());
        return new Result(documentNo, List.of(change));
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Result transfer(long actor, Transfer form) { return transfer(actor, form, 1); }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Result transfer(long actor, Transfer form, long warehouseId) {
        var request = begin(actor, form.requestId(), fingerprint("TRANSFER", form));
        Result previous = previous(request.id());
        if (previous != null) {
            db.requireWarehouse(db.slot(form.sourceSlotId()), warehouseId);
            db.requireWarehouse(db.slot(form.targetSlotId()), warehouseId);
            return previous;
        }
        if (form.sourceSlotId().equals(form.targetSlotId())) throw ApiError.bad("请选择不同的来源和目标库位");

        db.lockSlot(Math.min(form.sourceSlotId(), form.targetSlotId()));
        db.lockSlot(Math.max(form.sourceSlotId(), form.targetSlotId()));
        var source = db.slot(form.sourceSlotId());
        var target = db.slot(form.targetSlotId());
        db.requireWarehouse(source, warehouseId);
        db.requireWarehouse(target, warehouseId);
        validateQuantity(form.quantity(), source);
        checkSource(source, form.productId());
        if (!target.active()) throw ApiError.conflict("目标库位已停用");
        if (target.productId() != null && !target.productId().equals(form.productId())) {
            throw ApiError.conflict("目标库位已绑定其他商品");
        }
        boolean productActive = db.lockProduct(form.productId());
        if (!productActive && target.productId() == null) throw ApiError.conflict("停用商品不能绑定到空库位");

        BigDecimal sourceAfter = deduct(source.quantity(), form.quantity());
        BigDecimal targetAfter = add(target.quantity(), form.quantity());
        if (target.productId() == null) {
            db.jdbc.update("UPDATE shelf_slots SET product_id=? WHERE id=?", form.productId(), target.id());
        }
        var now = db.now().getTime();
        String documentNo = "MV-" + UUID.randomUUID();
        long transferId = db.insert("""
            INSERT INTO stock_transfers(request_id,transfer_no,product_id,source_slot_id,target_slot_id,quantity,
                                        operator_id,created_at)
            VALUES(?,?,?,?,?,?,?,?)
            """, request.id(), documentNo, form.productId(), source.id(), target.id(), form.quantity(), actor, now);
        var out = new Change(source.id(), Type.TRANSFER_OUT, source.quantity(), sourceAfter);
        var in = new Change(target.id(), Type.TRANSFER_IN, target.quantity(), targetAfter);
        write(request.id(), transferId, form.productId(), actor, documentNo, form.note(), out, now);
        write(request.id(), transferId, form.productId(), actor, documentNo, form.note(), in, now);
        return new Result(documentNo, List.of(out, in));
    }

    private Request begin(long actor, UUID requestId, String fingerprint) {
        db.lockActor(actor);
        var existing = db.jdbc.query("SELECT id,fingerprint FROM stock_requests WHERE operator_id=? AND request_id=?",
                (r, n) -> new Request(r.getLong(1), r.getString(2)), actor, requestId.toString());
        if (!existing.isEmpty()) {
            Request original = existing.get(0);
            if (!original.fingerprint().equals(fingerprint)) throw ApiError.conflict("请求编号已用于其他库存操作");
            return original;
        }
        long id = db.insert("INSERT INTO stock_requests(operator_id,request_id,fingerprint) VALUES(?,?,?)",
                actor, requestId.toString(), fingerprint);
        return new Request(id, fingerprint);
    }

    private Result previous(long request) {
        var rows = db.jdbc.query("SELECT * FROM stock_movements WHERE request_id=? ORDER BY id", (r, n) ->
                new Result(r.getString("document_no"), List.of(CHANGE.mapRow(r, n))), request);
        if (rows.isEmpty()) return null;
        return new Result(rows.get(0).documentNo(), rows.stream().flatMap(row -> row.changes().stream()).toList());
    }

    private void write(long request, Long transfer, long product, long actor, String documentNo,
                       String note, Change change, long at) {
        db.jdbc.update("UPDATE inventory SET quantity=?,updated_at=CURRENT_TIMESTAMP(6) WHERE slot_id=?",
                change.afterQty(), change.slotId());
        db.jdbc.update("""
            INSERT INTO stock_movements(request_id,transfer_id,product_id,slot_id,type,quantity,before_qty,after_qty,
                                        operator_id,document_no,note,created_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """, request, transfer, product, change.slotId(), change.type().name(),
                change.afterQty().subtract(change.beforeQty()).abs(), change.beforeQty(), change.afterQty(),
                actor, documentNo, Catalog.optional(note), at);
    }

    private void checkSource(WarehouseDb.Slot slot, long expectedProduct) {
        if (!slot.active()) throw ApiError.conflict("库位已停用");
        if (slot.productId() == null) throw ApiError.conflict("库位尚未绑定商品");
        if (!Objects.equals(slot.productId(), expectedProduct)) throw ApiError.conflict("库位商品已变更，请重新确认");
    }

    private void validateQuantity(BigDecimal quantity, WarehouseDb.Slot slot) {
        if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 3)
            throw ApiError.bad("数量必须大于零，最多支持三位小数");
        if (slot.headCount() == null && quantity.stripTrailingZeros().scale() > 0)
            throw ApiError.bad("货架仓库的操作数量必须为整数");
    }

    private BigDecimal deduct(BigDecimal before, BigDecimal quantity) {
        if (quantity.compareTo(before) > 0) throw ApiError.conflict("库存不足，当前可用库存为 " + before.stripTrailingZeros().toPlainString());
        return before.subtract(quantity);
    }

    private BigDecimal add(BigDecimal before, BigDecimal quantity) {
        BigDecimal after = before.add(quantity);
        if (after.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) throw ApiError.conflict("超出该库位的库存数量上限");
        return after;
    }

    private String fingerprint(Object type, Record form) {
        try {
            byte[] payload = (type + "\n" + json.writer().with(com.fasterxml.jackson.core.JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN).writeValueAsString(form))
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
