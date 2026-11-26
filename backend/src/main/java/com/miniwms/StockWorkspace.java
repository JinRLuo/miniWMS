package com.miniwms;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

@Service
class StockWorkspace {
    record ProductChoice(long id, String sku, String name, boolean active, String variety, Integer headCount) {}
    record SlotChoice(long id, String code, boolean active) {}
    record Context(List<WarehouseDb.Slot> locations, List<ProductChoice> historyProducts,
                   List<SlotChoice> historySlots, String zone, LocalDate today) {}
    private final WarehouseDb db;
    private final Warehouses warehouses;
    private final ZoneId zone;

    StockWorkspace(WarehouseDb db, Warehouses warehouses, @Value("${wms.zone}") String zone) {
        this.db = db; this.warehouses = warehouses; this.zone = ZoneId.of(zone);
    }

    Context get(long warehouseId, long actor) {
        warehouses.get(warehouseId);
        var locations = db.slots(warehouseId).stream().filter(slot -> slot.active() && slot.productId() != null).toList();
        var products = db.jdbc.query("""
                SELECT DISTINCT p.id,p.sku,p.name,p.active,p.variety,p.head_count
                FROM stock_movements m JOIN products p ON p.id=m.product_id
                WHERE p.warehouse_id=? AND m.operator_id=? ORDER BY p.sku
                """, (r,n) -> new ProductChoice(r.getLong("id"),r.getString("sku"),r.getString("name"),
                r.getBoolean("active"),r.getString("variety"),r.getObject("head_count",Integer.class)),warehouseId,actor);
        var slots = db.jdbc.query("""
                SELECT DISTINCT s.id,s.code,s.active FROM stock_movements m
                JOIN shelf_slots s ON s.id=m.slot_id JOIN shelves h ON h.id=s.shelf_id
                WHERE h.warehouse_id=? AND m.operator_id=? ORDER BY s.code
                """, (r,n) -> new SlotChoice(r.getLong("id"),r.getString("code"),r.getBoolean("active")),warehouseId,actor);
        return new Context(locations,products,slots,zone.getId(),LocalDate.now(zone));
    }
}

@RestController
@RequestMapping("/api/stock/context")
class StockWorkspaceApi {
    private final StockWorkspace workspace;
    StockWorkspaceApi(StockWorkspace workspace) { this.workspace = workspace; }
    @GetMapping
    StockWorkspace.Context get(@RequestParam(defaultValue="1") long warehouseId,
                              @AuthenticationPrincipal Accounts.Principal actor) {
        return workspace.get(warehouseId,actor.id());
    }
}
