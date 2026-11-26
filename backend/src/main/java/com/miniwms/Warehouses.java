package com.miniwms;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
class Warehouses {
    record Warehouse(long id, String name, String type) {
        boolean fish() { return type.equals("FISH_MAW"); }
    }
    private final WarehouseDb db;
    Warehouses(WarehouseDb db) { this.db = db; }
    List<Warehouse> list() {
        return db.jdbc.query("SELECT * FROM warehouses ORDER BY id", (r, n) ->
                new Warehouse(r.getLong("id"), r.getString("name"), r.getString("type")));
    }
    Warehouse get(long id) {
        return db.one("SELECT * FROM warehouses WHERE id=?", (r, n) ->
                new Warehouse(r.getLong("id"), r.getString("name"), r.getString("type")), "仓库不存在", id);
    }
    static long id(Long id) { return id == null ? 1 : id; }
}
