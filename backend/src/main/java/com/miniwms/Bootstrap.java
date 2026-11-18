package com.miniwms;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class Bootstrap implements ApplicationRunner {
    private final Accounts accounts;
    private final Catalog catalog;
    private final WarehouseDb db;
    private final Stock stock;
    private final String password;
    private final boolean demo;

    Bootstrap(Accounts accounts, Catalog catalog, WarehouseDb db, Stock stock,
              @Value("${wms.admin-password}") String password, @Value("${wms.demo}") boolean demo) {
        this.accounts = accounts;
        this.catalog = catalog;
        this.db = db;
        this.stock = stock;
        this.password = password;
        this.demo = demo;
    }

    @Override
    public void run(ApplicationArguments args) {
        String initialPassword = demo && password.isBlank() ? "miniwms-demo-2026" : password;
        long actor = accounts.initialize(initialPassword);
        if (!demo || !catalog.products().isEmpty() || !catalog.shelves().isEmpty()) return;

        var lamp = catalog.saveProduct(null, new Catalog.ProductForm("LAMP-001", "复古玻璃台灯", "6901234567001",
                "照明", "奶油白", "件", null, 20, true, null), actor);
        var storage = catalog.saveProduct(null, new Catalog.ProductForm("BOX-002", "桌面收纳盒", "6901234567002",
                "收纳", "烟灰色", "件", null, 30, true, null), actor);
        var light = catalog.saveProduct(null, new Catalog.ProductForm("LIGHT-003", "便携小夜灯", "6901234567003",
                "照明", "暖白光", "件", null, 15, true, null), actor);
        catalog.saveShelf(null, new Catalog.ShelfForm("S01", "A 区主货架", 4), actor);
        catalog.saveShelf(null, new Catalog.ShelfForm("S02", "B 区备货架", 3), actor);
        seed("S01-4", lamp.id(), 40, 3, actor);
        seed("S02-2", lamp.id(), 35, 0, actor);
        seed("S01-3", storage.id(), 66, 8, actor);
        seed("S01-2", light.id(), 12, 2, actor);
    }

    private void seed(String code, long product, int in, int out, long actor) {
        var slot = db.slots().stream().filter(s -> s.code().equals(code)).findFirst().orElseThrow();
        catalog.saveSlot(slot.id(), new Catalog.SlotForm(product, true), actor);
        stock.adjust(actor, Stock.Type.IN, new Stock.Adjust(UUID.randomUUID(), slot.id(), product, in, null, "演示入库"));
        if (out > 0) stock.adjust(actor, Stock.Type.OUT,
                new Stock.Adjust(UUID.randomUUID(), slot.id(), product, out, null, "演示出库"));
    }

}
