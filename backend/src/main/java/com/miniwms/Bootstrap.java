package com.miniwms;

import java.util.UUID;
import java.util.List;
import java.math.BigDecimal;
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
        if (!demo) return;
        if (catalog.products(2).isEmpty() && catalog.shelves(2).isEmpty()) seedFish(actor);
        if (!catalog.products().isEmpty() || !catalog.shelves().isEmpty()) return;

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

    private void seedFish(long actor) {
        var white = catalog.saveProduct(null, new Catalog.ProductForm("YJ-BH33", "白花", null, "鱼胶", null,
                "斤", null, new BigDecimal("3"), true, null, 2L, "白花", 33), actor);
        var yellow = catalog.saveProduct(null, new Catalog.ProductForm("YJ-HH20", "黄花", null, "鱼胶", null,
                "斤", null, new BigDecimal("2"), true, null, 2L, "黄花", 20), actor);
        var white50 = catalog.saveProduct(null, new Catalog.ProductForm("YJ-BH50", "白花", null, "鱼胶", null,
                "斤", null, new BigDecimal("1"), true, null, 2L, "白花", 50), actor);
        var shelf = catalog.saveShelf(null, new Catalog.ShelfForm("YJ01", "鱼胶主货架", 3, 2L, List.of(2, 2, 2)), actor);
        var cells = db.slots(2).stream().filter(s -> s.shelfId() == shelf.id()).toList();
        long[] products = {white.id(), yellow.id(), white50.id()};
        String[] weights = {"5", "2.75", "0.8"};
        for (int i = 0; i < products.length; i++) {
            catalog.saveSlot(cells.get(i).id(), new Catalog.SlotForm(products[i], true), actor, 2);
            stock.adjust(actor, Stock.Type.IN, new Stock.Adjust(UUID.randomUUID(), cells.get(i).id(), products[i],
                    new BigDecimal(weights[i]), null, "本地演示期初入库"), 2);
        }
    }

    private void seed(String code, long product, int in, int out, long actor) {
        var slot = db.slots().stream().filter(s -> s.code().equals(code)).findFirst().orElseThrow();
        catalog.saveSlot(slot.id(), new Catalog.SlotForm(product, true), actor);
        stock.adjust(actor, Stock.Type.IN, new Stock.Adjust(UUID.randomUUID(), slot.id(), product, in, null, "演示入库"));
        if (out > 0) stock.adjust(actor, Stock.Type.OUT,
                new Stock.Adjust(UUID.randomUUID(), slot.id(), product, out, null, "演示出库"));
    }

}
