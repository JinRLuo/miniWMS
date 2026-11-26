package com.miniwms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:miniwms-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "wms.admin-password=test-admin-password",
        "wms.public-url=https://warehouse.example"
})
@AutoConfigureMockMvc
class WarehouseTest {
    @Autowired WarehouseDb db;
    @Autowired Catalog catalog;
    @Autowired Stock stock;
    @Autowired Accounts accounts;
    @Autowired Reports reports;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private static final String ADMIN_PASSWORD = "test-admin-password";
    private static final String OPERATOR_PASSWORD = "test-operator-password";
    private Accounts.Principal admin;

    @BeforeEach
    void findAdmin() {
        admin = accounts.loadUserByUsername("admin");
    }

    @Test
    void duplicateRequestsReturnOriginalResultAndConflictingReuseFails() {
        var f = fixture();
        var adjust = new Stock.Adjust(UUID.randomUUID(), f.slots().get(0).id(), f.product().id(), 5, "TEST-IN", null);
        var first = stock.adjust(admin.id(), Stock.Type.IN, adjust);
        assertThat(stock.adjust(admin.id(), Stock.Type.IN, adjust)).isEqualTo(first);
        assertThatThrownBy(() -> stock.adjust(admin.id(), Stock.Type.OUT, adjust))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("请求编号已用于其他库存操作");
        assertThat(db.slot(adjust.slotId()).quantity()).isEqualByComparingTo("5");
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE slot_id=?", Integer.class,
                adjust.slotId())).isEqualTo(1);
    }

    @Test
    void concurrentOutboundFromTwoOperatorsCannotOversell() throws Exception {
        var f = fixture();
        long slot = f.slots().get(0).id();
        long sku = f.product().id();
        long operator = operator().id();
        stock.adjust(admin.id(), Stock.Type.IN, new Stock.Adjust(UUID.randomUUID(), slot, sku, 5, null, null));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> outbound(start, admin.id(), slot, sku));
            var second = pool.submit(() -> outbound(start, operator, slot, sku));
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(db.slot(slot).quantity()).isEqualByComparingTo("1");
    }

    @Test
    void transferBindsTargetRetainsSourceAndExcludesMovementsFromSalesReports() {
        var f = fixture();
        var source = f.slots().get(0);
        var target = f.slots().get(1);
        stock.adjust(admin.id(), Stock.Type.IN, new Stock.Adjust(UUID.randomUUID(), source.id(), f.product().id(), 6, null, null));
        var move = new Stock.Transfer(UUID.randomUUID(), source.id(), target.id(), f.product().id(), 6, "重新分配库位");
        var result = stock.transfer(admin.id(), move);

        assertThat(stock.transfer(admin.id(), move)).isEqualTo(result);
        assertThat(result.changes()).extracting(Stock.Change::type).containsExactly(Stock.Type.TRANSFER_OUT, Stock.Type.TRANSFER_IN);
        assertThat(db.slot(source.id()).productId()).isEqualTo(f.product().id());
        assertThat(db.slot(target.id()).productId()).isEqualTo(f.product().id());
        assertThat(db.slot(source.id()).quantity()).isZero();
        assertThat(db.slot(target.id()).quantity()).isEqualByComparingTo("6");
        var stat = reports.products(null, null).stream().filter(p -> p.id() == f.product().id()).findFirst().orElseThrow();
        assertThat(stat.inbound()).isEqualByComparingTo("6");
        assertThat(stat.outbound()).isZero();
        assertThat(stat.quantity()).isEqualByComparingTo("6");
    }

    @Test
    void failureWritingSecondMovementRollsBackBothBalancesAndBinding() {
        var f = fixture();
        var source = f.slots().get(0);
        var target = f.slots().get(1);
        stock.adjust(admin.id(), Stock.Type.IN, new Stock.Adjust(UUID.randomUUID(), source.id(), f.product().id(), 9, null, null));
        var move = new Stock.Transfer(UUID.randomUUID(), source.id(), target.id(), f.product().id(), 4, null);
        db.jdbc.execute("ALTER TABLE stock_movements ADD CONSTRAINT test_reject_target CHECK (type <> 'TRANSFER_IN' OR slot_id <> "
                + target.id() + ")");
        try {
            assertThatThrownBy(() -> stock.transfer(admin.id(), move)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(db.slot(source.id()).quantity()).isEqualByComparingTo("9");
            assertThat(db.slot(target.id()).quantity()).isZero();
            assertThat(db.slot(target.id()).productId()).isNull();
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM stock_transfers WHERE target_slot_id=?",
                    Integer.class, target.id())).isZero();
        } finally {
            db.jdbc.execute("ALTER TABLE stock_movements DROP CONSTRAINT test_reject_target");
        }
        assertThat(stock.transfer(admin.id(), move).changes()).hasSize(2);
    }

    @Test
    void authenticatedQrResolvesTheSameSlotAndOperatorCannotEditCatalog() throws Exception {
        var f = fixture();
        var user = operator();
        var session = session(user.username(), OPERATOR_PASSWORD);
        long slot = f.slots().get(0).id();
        mvc.perform(get("/api/slots/" + slot + "/qrcode")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/slots/" + slot + "/qrcode").session(session)).andExpect(status().isForbidden());
        byte[] png = mvc.perform(get("/api/slots/" + slot + "/qrcode").session(session("admin", ADMIN_PASSWORD)))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andReturn().getResponse().getContentAsByteArray();
        var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(
                ImageIO.read(new ByteArrayInputStream(png)))));
        String link = new QRCodeReader().decode(bitmap).getText();
        assertThat(link).startsWith("https://warehouse.example/m/slot/");
        String token = link.substring(link.lastIndexOf('/') + 1);
        mvc.perform(get("/api/slots/resolve/" + token).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(slot));

        var form = new Catalog.SlotForm(null, true);
        mvc.perform(put("/api/slots/" + slot).session(session).with(csrf())
                .contentType(APPLICATION_JSON).content(json.writeValueAsString(form))).andExpect(status().isForbidden());
        var operation = new Stock.Adjust(UUID.randomUUID(), slot, f.product().id(), 2, "=CSV-CELL", null);
        mvc.perform(post("/api/stock/in").session(session).contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(operation))).andExpect(status().isForbidden());
        mvc.perform(post("/api/stock/in").session(session).with(csrf()).contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(operation))).andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].afterQty").value(2));
        mvc.perform(get("/api/stock/movements").session(session).param("product", f.product().sku()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].documentNo").value("=CSV-CELL"));
    }

    @Test
    void selectedProductAndSlotsFilterMovementsTransfersAndCsvExactly() throws Exception {
        var first = fixture();
        var second = fixture();
        for (var fixture : List.of(first, second)) {
            long source = fixture.slots().get(0).id();
            long target = fixture.slots().get(1).id();
            stock.adjust(admin.id(), Stock.Type.IN,
                    new Stock.Adjust(UUID.randomUUID(), source, fixture.product().id(), 5, null, null));
            stock.transfer(admin.id(), new Stock.Transfer(UUID.randomUUID(), source, target,
                    fixture.product().id(), 2, null));
        }
        var session = session("admin", ADMIN_PASSWORD);
        String product = Long.toString(first.product().id());
        String source = Long.toString(first.slots().get(0).id());
        String target = Long.toString(first.slots().get(1).id());
        String otherSource = Long.toString(second.slots().get(0).id());

        mvc.perform(get("/api/stock/movements").session(session).param("productId", product).param("slotId", source))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].sku").value(first.product().sku()));
        mvc.perform(get("/api/stock/movements").session(session).param("productId", product).param("slotId", otherSource))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(0));
        mvc.perform(get("/api/stock/transfers").session(session).param("productId", product)
                .param("sourceSlotId", source).param("targetSlotId", target))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].sku").value(first.product().sku()));
        mvc.perform(get("/api/stock/transfers").session(session).param("productId", product)
                .param("sourceSlotId", source).param("targetSlotId", otherSource))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(0));

        var export = mvc.perform(get("/api/stock/movements.csv").session(session)
                        .param("productId", product).param("slotId", source))
                .andExpect(request().asyncStarted()).andReturn();
        var csv = mvc.perform(asyncDispatch(export)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains(first.product().sku()).doesNotContain(second.product().sku());
    }

    @Test
    void fishCellsSupportExactWeightAndEstimatedCountsAndRetry() {
        var f = fishFixture();
        var source = f.slots().get(0);
        var target = f.slots().get(1);
        var inbound = new Stock.Adjust(UUID.randomUUID(), source.id(), f.product().id(), new BigDecimal("5.000"), null, null);
        var result = stock.adjust(admin.id(), Stock.Type.IN, inbound, 2);
        assertThat(stock.adjust(admin.id(), Stock.Type.IN,
                new Stock.Adjust(inbound.requestId(), source.id(), f.product().id(), new BigDecimal("5"), null, null), 2)).isEqualTo(result);
        assertThat(db.slot(source.id()).getEstimatedCount()).isEqualByComparingTo("165");
        stock.adjust(admin.id(), Stock.Type.OUT, new Stock.Adjust(UUID.randomUUID(), source.id(), f.product().id(), new BigDecimal("1.2"), null, null), 2);
        assertThat(db.slot(source.id()).quantity()).isEqualByComparingTo("3.8");
        assertThat(db.slot(source.id()).getEstimatedCount()).isEqualByComparingTo("125");
        stock.transfer(admin.id(), new Stock.Transfer(UUID.randomUUID(), source.id(), target.id(), f.product().id(), new BigDecimal("0.001"), null), 2);
        assertThat(db.slot(source.id()).quantity()).isEqualByComparingTo("3.799");
        assertThat(db.slot(target.id()).quantity()).isEqualByComparingTo("0.001");
        var stat = reports.products(null, null, 2).stream().filter(p -> p.id() == f.product().id()).findFirst().orElseThrow();
        assertThat(stat.quantity()).isEqualByComparingTo("3.8");
        assertThat(stat.outbound()).isEqualByComparingTo("1.2");
        assertThatThrownBy(() -> stock.adjust(admin.id(), Stock.Type.OUT,
                new Stock.Adjust(UUID.randomUUID(), source.id(), f.product().id(), new BigDecimal("3.8"), null, null), 2))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("库存不足");
        assertThatThrownBy(() -> stock.adjust(admin.id(), Stock.Type.IN,
                new Stock.Adjust(UUID.randomUUID(), source.id(), f.product().id(), new BigDecimal("0.0001"), null, null), 2))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("三位小数");
    }

    @Test
    void anIntegerRequestRecordedBeforeTheUpgradeCanStillBeRetried() throws Exception {
        var f = fixture();
        long slot = f.slots().get(0).id();
        UUID requestId = UUID.randomUUID();
        // Serialize the original integer DTO to reproduce the V1 idempotency contract.
        record LegacyAdjust(UUID requestId, Long slotId, Long productId, Integer quantity, String documentNo, String note) {}
        String original = json.writeValueAsString(new LegacyAdjust(requestId, slot, f.product().id(), 10, null, null));
        String fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(("IN\n" + original).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        long request = db.insert("INSERT INTO stock_requests(operator_id,request_id,fingerprint) VALUES(?,?,?)", admin.id(), requestId.toString(), fingerprint);
        db.jdbc.update("UPDATE inventory SET quantity=10 WHERE slot_id=?", slot);
        db.jdbc.update("""
                INSERT INTO stock_movements(request_id,product_id,slot_id,type,quantity,before_qty,after_qty,operator_id,document_no,created_at)
                VALUES(?,?,?,'IN',10,0,10,?,'LEGACY-IN',?)
                """, request, f.product().id(), slot, admin.id(), db.now().getTime());
        var result = stock.adjust(admin.id(), Stock.Type.IN,
                new Stock.Adjust(requestId, slot, f.product().id(), new BigDecimal("10.000"), null, null), 1);
        assertThat(result.documentNo()).isEqualTo("LEGACY-IN");
        assertThat(db.slot(slot).quantity()).isEqualByComparingTo("10");
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM stock_movements WHERE request_id=?", Long.class, request)).isEqualTo(1);
        assertThatThrownBy(() -> stock.adjust(admin.id(), Stock.Type.IN,
                new Stock.Adjust(requestId, slot, f.product().id(), new BigDecimal("10"), null, null), 2))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("不属于当前仓库");
    }

    @Test
    void warehouseBoundariesAndOrdinaryIntegerQuantitiesAreEnforced() {
        var general = fixture();
        var fish = fishFixture();
        long normalSlot = general.slots().get(0).id();
        long fishSlot = fish.slots().get(0).id();
        assertThatThrownBy(() -> catalog.saveSlot(normalSlot, new Catalog.SlotForm(fish.product().id(), true), admin.id(), 1))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("当前仓库的商品");
        assertThatThrownBy(() -> stock.adjust(admin.id(), Stock.Type.IN,
                new Stock.Adjust(UUID.randomUUID(), fishSlot, fish.product().id(), 5, null, null), 1))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("不属于当前仓库");
        assertThatThrownBy(() -> stock.transfer(admin.id(), new Stock.Transfer(UUID.randomUUID(), fishSlot, normalSlot, fish.product().id(), 1, null), 2))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("不属于当前仓库");
        assertThatThrownBy(() -> stock.adjust(admin.id(), Stock.Type.IN,
                new Stock.Adjust(UUID.randomUUID(), normalSlot, general.product().id(), new BigDecimal("0.5"), null, null), 1))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("整数");
        assertThat(db.slot(normalSlot).quantity()).isZero();
        assertThat(db.slot(fishSlot).quantity()).isZero();
    }

    @Test
    void warehouseReadsCodeLookupQrAndExportsStayScoped() throws Exception {
        var f = fishFixture();
        long slot = f.slots().get(0).id();
        stock.adjust(admin.id(), Stock.Type.IN, new Stock.Adjust(UUID.randomUUID(), slot, f.product().id(), new BigDecimal("0.125"), "FISH-IN", null), 2);
        var session = session("admin", ADMIN_PASSWORD);
        mvc.perform(get("/api/products").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.warehouseId == 2)]").isEmpty());
        mvc.perform(get("/api/slots/by-code/" + f.slots().get(0).code().toLowerCase()).param("warehouseId", "2").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(slot)).andExpect(jsonPath("$.quantity").value(0.125));
        mvc.perform(get("/api/slots/by-code/" + f.slots().get(0).code()).session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/stock/movements").param("warehouseId", "1").param("productId", "" + f.product().id()).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows").isEmpty());
        mvc.perform(get("/api/stock/movements").param("warehouseId", "2").param("productId", "" + f.product().id()).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].quantity").value(0.125)).andExpect(jsonPath("$.rows[0].unit").value("斤"));
        String token = db.jdbc.queryForObject("SELECT qr_token FROM shelf_slots WHERE id=?", String.class, slot);
        mvc.perform(get("/api/slots/resolve/" + token).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.warehouseId").value(2));
        var export = mvc.perform(get("/api/stock/movements.csv").session(session).param("warehouseId", "2").param("productId", "" + f.product().id()))
                .andExpect(request().asyncStarted()).andReturn();
        var csv = mvc.perform(asyncDispatch(export)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("0.125", "斤", "33", f.product().sku());
        assertThat(reports.products(null, null, 1)).noneMatch(p -> p.id() == f.product().id());
        var summary = reports.summary(2);
        assertThat(summary.units()).hasSize(1);
        assertThat(summary.units().get(0).unit()).isEqualTo("斤");
    }

    @Test
    void fishShelvesHaveVariableCellsAndStockedSpecificationsCannotBeRewritten() {
        var f = fishFixture();
        assertThat(f.slots()).hasSize(5);
        assertThat(f.slots().stream().filter(s -> s.layerNo() == 1)).hasSize(2);
        assertThat(f.slots().stream().filter(s -> s.layerNo() == 2)).hasSize(3);
        stock.adjust(admin.id(), Stock.Type.IN, new Stock.Adjust(UUID.randomUUID(), f.slots().get(0).id(), f.product().id(), 1, null, null), 2);
        assertThatThrownBy(() -> catalog.saveProduct(f.product().id(), new Catalog.ProductForm(f.product().sku(), "鱼胶", null,
                null, null, "斤", null, null, true, null, 2L, "白花", 50), admin.id()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("已有库存流水");
        assertThatThrownBy(() -> catalog.saveSlot(f.slots().get(0).id(), new Catalog.SlotForm(null, true), admin.id(), 2))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("先清空");
    }

    private Fixture fishFixture() {
        var product = catalog.saveProduct(null, new Catalog.ProductForm("FISH-" + UUID.randomUUID(), "鱼胶", null,
                null, null, "斤", null, new BigDecimal("0.5"), true, null, 2L, "白花", 33), admin.id());
        var shelf = catalog.saveShelf(null, new Catalog.ShelfForm("YJ" + UUID.randomUUID().toString().substring(0, 8),
                "鱼胶测试货架", 2, 2L, List.of(2, 3)), admin.id());
        var slots = db.slots(2).stream().filter(s -> s.shelfId() == shelf.id()).toList();
        catalog.saveSlot(slots.get(0).id(), new Catalog.SlotForm(product.id(), true), admin.id(), 2);
        return new Fixture(product, slots);
    }

    private boolean outbound(CountDownLatch start, long actor, long slot, long product) throws InterruptedException {
        start.await();
        try {
            stock.adjust(actor, Stock.Type.OUT, new Stock.Adjust(UUID.randomUUID(), slot, product, 4, null, null));
            return true;
        } catch (ResponseStatusException ex) {
            assertThat(ex.getReason()).contains("库存不足");
            return false;
        }
    }

    private MockHttpSession session(String username, String password) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                .param("username", username).param("password", password))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    private Accounts.View operator() {
        return accounts.save(null, new Accounts.Form("user-" + UUID.randomUUID(), "测试员工", Accounts.Role.OPERATOR,
                true, OPERATOR_PASSWORD), admin.id());
    }

    private Fixture fixture() {
        var product = catalog.saveProduct(null, new Catalog.ProductForm("SKU-" + UUID.randomUUID(), "测试商品", null,
                null, null, "件", null, null, true, null), admin.id());
        var shelf = catalog.saveShelf(null, new Catalog.ShelfForm("S" + UUID.randomUUID().toString().substring(0, 8),
                "测试货架", 2), admin.id());
        var slots = db.slots().stream().filter(s -> s.shelfId() == shelf.id()).toList();
        catalog.saveSlot(slots.get(0).id(), new Catalog.SlotForm(product.id(), true), admin.id());
        return new Fixture(product, slots);
    }

    private record Fixture(Catalog.Product product, List<WarehouseDb.Slot> slots) {}
}
