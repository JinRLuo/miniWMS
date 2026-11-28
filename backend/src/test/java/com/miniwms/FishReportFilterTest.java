package com.miniwms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:miniwms-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "wms.admin-password=test-admin-password",
        "wms.public-url=https://warehouse.example"
})
@AutoConfigureMockMvc
class FishReportFilterTest {
    @Autowired WarehouseDb db;
    @Autowired Catalog catalog;
    @Autowired Stock stock;
    @Autowired Accounts accounts;
    @Autowired Reports reports;
    @Autowired MockMvc mvc;
    private long admin;
    private String variety;
    private Fish first, duplicate, otherHeads, otherVariety, similarName;
    private MockHttpSession session;

    @BeforeEach
    void seedMultipleSpecifications() throws Exception {
        admin = accounts.loadUserByUsername("admin").id();
        variety = "筛选白花%_" + UUID.randomUUID().toString().substring(0,8);
        first = fish(variety, 33, "5", "1.2", "0.5");
        duplicate = fish(variety, 33, "2.5", "0.5", "0.25");
        otherHeads = fish(variety, 50, "4", "1", "1");
        otherVariety = fish("黄花" + UUID.randomUUID(), 33, "7", "2", "1");
        similarName = fish(variety + "尾", 33, "9", "3", "1");
        session = login("admin", "test-admin-password");
    }

    @Test
    void recordsTransfersAndCsvUseExactVarietyAndOptionalHeads() throws Exception {
        mvc.perform(get("/api/stock/movements").session(session).param("warehouseId","2").param("variety",variety))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(12));
        mvc.perform(get("/api/stock/movements").session(session).param("warehouseId","2").param("variety",variety).param("headCount","33"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(8));
        mvc.perform(get("/api/stock/movements").session(session).param("warehouseId","2").param("variety",variety)
                .param("headCount","33").param("productId",String.valueOf(otherHeads.product().id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows").isEmpty());
        mvc.perform(get("/api/stock/movements").session(session).param("warehouseId","1").param("variety",variety))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows").isEmpty());
        mvc.perform(get("/api/stock/transfers").session(session).param("warehouseId","2").param("variety",variety))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(3));
        mvc.perform(get("/api/stock/transfers").session(session).param("warehouseId","2").param("variety",variety).param("headCount","33"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(2));
        String csv = export(session, variety, "33");
        assertThat(csv).contains(first.product().sku(), duplicate.product().sku())
                .doesNotContain(otherHeads.product().sku(), otherVariety.product().sku(), similarName.product().sku());
        assertThat(csv.lines().count()).isEqualTo(9);
    }

    @Test
    void undatedHistoryTransfersAndCsvDefaultToWarehouseWeekWhileExplicitDatesStillWork() throws Exception {
        var zone = ZoneId.of("Asia/Shanghai");
        var monday = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long begin = monday.atStartOfDay(zone).toInstant().toEpochMilli();
        long end = monday.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli();
        for (String type : List.of("IN","OUT","TRANSFER_OUT","TRANSFER_IN")) {
            long time = switch (type) {
                case "IN" -> begin - 1;
                case "OUT" -> begin;
                case "TRANSFER_OUT" -> end - 1;
                default -> end;
            };
            db.jdbc.update("UPDATE stock_movements SET created_at=? WHERE product_id=? AND type=?",time,first.product().id(),type);
        }
        db.jdbc.update("UPDATE stock_transfers SET created_at=? WHERE product_id=?",end - 1,first.product().id());
        String product = String.valueOf(first.product().id());
        mvc.perform(get("/api/stock/movements").session(session).param("warehouseId","2").param("productId",product))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[*].type",containsInAnyOrder("OUT","TRANSFER_OUT")));
        mvc.perform(get("/api/stock/transfers").session(session).param("warehouseId","2").param("productId",product))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1));
        var result = mvc.perform(get("/api/stock/movements.csv").session(session).param("warehouseId","2").param("productId",product))
                .andExpect(request().asyncStarted()).andReturn();
        String csv = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv.lines().count()).isEqualTo(3);
        assertThat(csv).doesNotContain("ADMIN-FILTER-IN");
        mvc.perform(get("/api/stock/movements").session(session).param("warehouseId","2").param("productId",product)
                        .param("from",monday.minusDays(1).toString()).param("to",monday.minusDays(1).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].type").value("IN"));
        db.jdbc.update("UPDATE stock_transfers SET created_at=? WHERE product_id=?",end,first.product().id());
        mvc.perform(get("/api/stock/transfers").session(session).param("warehouseId","2").param("productId",product))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows").isEmpty());
    }

    @Test
    void chartsAndProductSummariesUseTheSameSpecificationFilter() throws Exception {
        LocalDate day = db.now().toInstant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        mvc.perform(get("/api/reports/products").session(session).param("warehouseId","2").param("variety"," " + variety + " "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[*].sku",containsInAnyOrder(first.product().sku(),duplicate.product().sku(),otherHeads.product().sku())));
        mvc.perform(get("/api/reports/products").session(session).param("warehouseId","2").param("variety",variety).param("headCount","33"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].sku",containsInAnyOrder(first.product().sku(),duplicate.product().sku())));
        var stats = reports.products(day,day,2,variety,33);
        assertThat(stats.stream().map(Reports.ProductStats::inbound).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("7.5");
        assertThat(stats.stream().map(Reports.ProductStats::outbound).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("1.7");
        assertThat(stats.stream().map(Reports.ProductStats::quantity).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("5.8");
        for (String period : List.of("DAY","WEEK","MONTH")) {
            mvc.perform(get("/api/reports/trend").session(session).param("warehouseId","2").param("variety",variety)
                    .param("from",day.toString()).param("to",day.toString()).param("unit","斤").param("period",period))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].inbound").value(11.5)).andExpect(jsonPath("$[0].outbound").value(2.7));
            mvc.perform(get("/api/reports/trend").session(session).param("warehouseId","2").param("variety",variety).param("headCount","33")
                    .param("from",day.toString()).param("to",day.toString()).param("unit","斤").param("period",period))
                    .andExpect(status().isOk()).andExpect(jsonPath("$[0].inbound").value(7.5)).andExpect(jsonPath("$[0].outbound").value(1.7));
        }
        mvc.perform(get("/api/reports/trend").session(session).param("warehouseId","2").param("variety",variety).param("headCount","99")
                .param("from",day.toString()).param("to",day.toString()).param("unit","斤"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].inbound").value(0)).andExpect(jsonPath("$[0].outbound").value(0));
        mvc.perform(get("/api/reports/products").session(session).param("warehouseId","1").param("variety",variety))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void filtersPreserveOperatorPermissionsPaginationAndInputValidation() throws Exception {
        String password="test-operator-password";
        var operator = accounts.save(null,new Accounts.Form("filter-"+UUID.randomUUID(),"筛选操作员",Accounts.Role.OPERATOR,true,password),admin);
        stock.adjust(operator.id(),Stock.Type.IN,new Stock.Adjust(UUID.randomUUID(),first.source(),first.product().id(),new BigDecimal("0.1"),"OP-FILTER-IN",null),2);
        stock.transfer(operator.id(),new Stock.Transfer(UUID.randomUUID(),first.source(),first.target(),first.product().id(),new BigDecimal("0.1"),null),2);
        MockHttpSession own = login(operator.username(),password);
        mvc.perform(get("/api/stock/movements").session(own).param("warehouseId","2").param("variety",variety).param("headCount","33").param("mine","false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(3));
        mvc.perform(get("/api/stock/transfers").session(own).param("warehouseId","2").param("variety",variety).param("headCount","33"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1));
        String csv=export(own,variety,"33");
        assertThat(csv).contains("OP-FILTER-IN").doesNotContain("ADMIN-FILTER-IN");
        assertThat(csv.lines().count()).isEqualTo(4);
        for (int i=0;i<35;i++) stock.adjust(operator.id(),Stock.Type.IN,
                new Stock.Adjust(UUID.randomUUID(),first.source(),first.product().id(),new BigDecimal("0.001"),null,null),2);
        mvc.perform(get("/api/stock/movements").session(own).param("warehouseId","2").param("variety",variety).param("headCount","33"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(30)).andExpect(jsonPath("$.more").value(true));
        mvc.perform(get("/api/stock/movements").session(own).param("warehouseId","2").param("variety",variety).param("headCount","33").param("page","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(8)).andExpect(jsonPath("$.more").value(false));
        for (String endpoint : List.of("/stock/movements","/stock/transfers","/stock/movements.csv","/reports/trend","/reports/products")) {
            for (String invalid : List.of("0","-1","100001","33.5"))
                mvc.perform(get("/api"+endpoint).session(session).param("warehouseId","2").param("variety",variety).param("headCount",invalid))
                        .andExpect(status().isBadRequest());
            mvc.perform(get("/api"+endpoint).session(session).param("warehouseId","2").param("variety","x".repeat(101)))
                    .andExpect(status().isBadRequest());
        }
    }

    private Fish fish(String variety,int heads,String inbound,String outbound,String transfer) {
        var product=catalog.saveProduct(null,new Catalog.ProductForm("FILTER-"+UUID.randomUUID(),"鱼胶",null,null,null,"斤",null,null,true,null,2L,variety,heads),admin);
        var shelf=catalog.saveShelf(null,new Catalog.ShelfForm("F"+UUID.randomUUID().toString().substring(0,8),"筛选测试",1,2L,List.of(2)),admin);
        var slots=db.slots(2).stream().filter(slot->slot.shelfId()==shelf.id()).toList();
        long source=slots.get(0).id(),target=slots.get(1).id();
        catalog.saveSlot(source,new Catalog.SlotForm(product.id(),true),admin,2);
        stock.adjust(admin,Stock.Type.IN,new Stock.Adjust(UUID.randomUUID(),source,product.id(),new BigDecimal(inbound),"ADMIN-FILTER-IN",null),2);
        stock.adjust(admin,Stock.Type.OUT,new Stock.Adjust(UUID.randomUUID(),source,product.id(),new BigDecimal(outbound),null,null),2);
        stock.transfer(admin,new Stock.Transfer(UUID.randomUUID(),source,target,product.id(),new BigDecimal(transfer),null),2);
        return new Fish(product,source,target);
    }
    private MockHttpSession login(String username,String password) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",username).param("password",password))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
    private String export(MockHttpSession user,String variety,String heads) throws Exception {
        var result=mvc.perform(get("/api/stock/movements.csv").session(user).param("warehouseId","2").param("variety",variety).param("headCount",heads))
                .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
    private record Fish(Catalog.Product product,long source,long target) {}
}
