package com.miniwms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.ByteArrayInputStream;
import java.util.List;
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
        assertThat(db.slot(adjust.slotId()).quantity()).isEqualTo(5);
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
        assertThat(db.slot(slot).quantity()).isEqualTo(1);
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
        assertThat(db.slot(target.id()).quantity()).isEqualTo(6);
        var stat = reports.products(null, null).stream().filter(p -> p.id() == f.product().id()).findFirst().orElseThrow();
        assertThat(stat.inbound()).isEqualTo(6);
        assertThat(stat.outbound()).isZero();
        assertThat(stat.quantity()).isEqualTo(6);
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
            assertThat(db.slot(source.id()).quantity()).isEqualTo(9);
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
        byte[] png = mvc.perform(get("/api/slots/" + slot + "/qrcode").session(session))
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
