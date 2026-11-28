package com.miniwms;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:miniwms-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "wms.admin-password=test-admin-password", "wms.public-url=https://warehouse.example"
})
@AutoConfigureMockMvc
class OperatorAccessTest {
    @Autowired Accounts accounts;
    @Autowired Catalog catalog;
    @Autowired WarehouseDb db;
    @Autowired Stock stock;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private static final String PASSWORD = "operator-access-password";

    @Test
    void managementAndReportApisRequireAnAdministratorWhileOperationsRemainAvailable() throws Exception {
        long admin=accounts.loadUserByUsername("admin").id();
        var operator=operator(admin); var own=login(operator.username(),PASSWORD);var administrator=login("admin","test-admin-password");
        var f=fixture(admin);
        for(String path:List.of("/api/products","/api/shelves","/api/slots","/api/slots/"+f.slot()+"/qrcode",
                "/api/reports/summary","/api/reports/trend","/api/reports/products","/api/admin/users")) {
            mvc.perform(get(path).session(own).param("warehouseId","2")).andExpect(status().isForbidden());
            mvc.perform(get(path).session(administrator).param("warehouseId","2")).andExpect(status().isOk());
        }
        for(String path:List.of("/api/products","/api/shelves"))
            mvc.perform(post(path).session(own).with(csrf()).contentType(APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        for(String path:List.of("/api/products/"+f.product(),"/api/shelves/"+f.shelf(),"/api/slots/"+f.slot()))
            mvc.perform(put(path).session(own).with(csrf()).contentType(APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        mvc.perform(get("/api/stock/context").session(own).param("warehouseId","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.historyProducts").isEmpty()).andExpect(jsonPath("$.historySlots").isEmpty());
        for(String type:List.of("in","out")) {
            var form=new Stock.Adjust(UUID.randomUUID(),f.slot(),f.product(),new BigDecimal("0.125"),"OP-"+type,null);
            mvc.perform(post("/api/stock/"+type).session(own).with(csrf()).param("warehouseId","2")
                    .contentType(APPLICATION_JSON).content(json.writeValueAsBytes(form))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/slots/by-code/"+db.slot(f.slot()).code()).session(own).param("warehouseId","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(f.slot()));
        String token=db.jdbc.queryForObject("SELECT qr_token FROM shelf_slots WHERE id=?",String.class,f.slot());
        mvc.perform(get("/api/slots/resolve/"+token).session(own)).andExpect(status().isOk());
        var context=json.readTree(mvc.perform(get("/api/stock/context").session(own).param("warehouseId","2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(context.get("historyProducts").size()).isEqualTo(1);
        assertThat(context.get("historyProducts").get(0).has("quantity")).isFalse();
        assertThat(context.get("historySlots").get(0).has("quantity")).isFalse();
        for(var slot:context.get("locations")) {
            assertThat(slot.get("active").asBoolean()).isTrue();assertThat(slot.get("productId").isNull()).isFalse();
            assertThat(slot.get("warehouseId").asLong()).isEqualTo(2);
        }
    }

    @Test
    void recordsPaginationCsvAndFilterChoicesNeverExposeOtherOperatorsRecords() throws Exception {
        long admin=accounts.loadUserByUsername("admin").id();
        var first=operator(admin);var second=operator(admin);var own=login(first.username(),PASSWORD);
        var f=fixture(admin);var other=fixture(admin);
        stock.adjust(first.id(),Stock.Type.IN,new Stock.Adjust(UUID.randomUUID(),f.slot(),f.product(),1,"OWN-ACCESS",null),2);
        stock.adjust(second.id(),Stock.Type.IN,new Stock.Adjust(UUID.randomUUID(),f.slot(),f.product(),1,"OTHER-SAME-PRODUCT",null),2);
        stock.adjust(second.id(),Stock.Type.IN,new Stock.Adjust(UUID.randomUUID(),other.slot(),other.product(),1,"OTHER-ACCESS",null),2);
        mvc.perform(get("/api/stock/movements").session(own).param("warehouseId","2").param("mine","false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].documentNo").value("OWN-ACCESS"));
        mvc.perform(get("/api/stock/movements").session(own).param("warehouseId","2").param("mine","false").param("operator",second.username()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows").isEmpty());
        mvc.perform(get("/api/stock/movements").session(login("admin","test-admin-password")).param("warehouseId","2").param("operator",second.username()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(2));
        var administrator=login("admin","test-admin-password");
        mvc.perform(get("/api/stock/movements").session(administrator).param("warehouseId","2").param("slotId",String.valueOf(f.slot())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(2));
        var allChoices=json.readTree(mvc.perform(get("/api/stock/context").session(administrator).param("warehouseId","2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        var products=new java.util.ArrayList<Long>();
        allChoices.get("historyProducts").forEach(value->products.add(value.get("id").asLong()));
        assertThat(products).contains(f.product(),other.product());
        var slots=new java.util.ArrayList<Long>();
        allChoices.get("historySlots").forEach(value->slots.add(value.get("id").asLong()));
        assertThat(slots).contains(f.slot(),other.slot());
        var context=json.readTree(mvc.perform(get("/api/stock/context").session(own).param("warehouseId","2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(context.get("historyProducts").size()).isEqualTo(1);
        assertThat(context.get("historyProducts").get(0).get("id").asLong()).isEqualTo(f.product());
        assertThat(context.get("historySlots").size()).isEqualTo(1);
        for(int i=0;i<31;i++) stock.adjust(first.id(),Stock.Type.IN,
                new Stock.Adjust(UUID.randomUUID(),f.slot(),f.product(),new BigDecimal("0.001"),"OWN-PAGE-"+i,null),2);
        mvc.perform(get("/api/stock/movements").session(own).param("warehouseId","2").param("mine","false").param("page","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.more").value(false));
        var result=mvc.perform(get("/api/stock/movements.csv").session(own).param("warehouseId","2").param("mine","false"))
                .andExpect(request().asyncStarted()).andReturn();
        String csv=mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("OWN-ACCESS").doesNotContain("OTHER-SAME-PRODUCT","OTHER-ACCESS");
    }

    @Test
    void operatorChoicesAndExactFilterKeepSameNameAndInactiveAccountsDistinct() throws Exception {
        long admin=accounts.loadUserByUsername("admin").id();
        var first=operator(admin);var second=operator(admin);var unused=operator(admin);
        var administrator=login("admin","test-admin-password");var own=login(first.username(),PASSWORD);
        var f=fixture(admin);
        stock.adjust(first.id(),Stock.Type.IN,new Stock.Adjust(UUID.randomUUID(),f.slot(),f.product(),1,"ACTOR-FIRST",null),2);
        stock.adjust(second.id(),Stock.Type.IN,new Stock.Adjust(UUID.randomUUID(),f.slot(),f.product(),1,"ACTOR-SECOND",null),2);
        accounts.save(second.id(),new Accounts.Form(second.username(),second.displayName(),second.role(),false,null),admin);
        var context=json.readTree(mvc.perform(get("/api/stock/context").session(administrator).param("warehouseId","2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        var ids=new java.util.ArrayList<Long>();
        context.get("historyOperators").forEach(value->{
            ids.add(value.get("id").asLong());
            assertThat(value.has("phone")).isFalse();
            if(value.get("id").asLong()==second.id()) assertThat(value.get("active").asBoolean()).isFalse();
        });
        assertThat(ids).contains(first.id(),second.id()).doesNotContain(unused.id());
        mvc.perform(get("/api/stock/context").session(own).param("warehouseId","2").param("mine","false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.historyOperators.length()").value(1))
                .andExpect(jsonPath("$.historyOperators[0].id").value(first.id()));
        mvc.perform(get("/api/stock/movements").session(administrator).param("warehouseId","2").param("operatorId",String.valueOf(second.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].documentNo").value("ACTOR-SECOND"));
        mvc.perform(get("/api/stock/movements").session(own).param("warehouseId","2").param("mine","false").param("operatorId",String.valueOf(second.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows").isEmpty());
        var result=mvc.perform(get("/api/stock/movements.csv").session(administrator).param("warehouseId","2").param("operatorId",String.valueOf(second.id())))
                .andExpect(request().asyncStarted()).andReturn();
        String csv=mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("ACTOR-SECOND").doesNotContain("ACTOR-FIRST");
    }

    private Accounts.View operator(long admin) {
        return accounts.save(null,new Accounts.Form("access-"+UUID.randomUUID(),"权限测试",Accounts.Role.OPERATOR,true,PASSWORD),admin);
    }
    private Fixture fixture(long admin) {
        var p=catalog.saveProduct(null,new Catalog.ProductForm("ACL-"+UUID.randomUUID(),"鱼胶",null,null,null,"斤",null,null,true,null,2L,"权限鱼胶",33),admin);
        var s=catalog.saveShelf(null,new Catalog.ShelfForm("A"+UUID.randomUUID().toString().substring(0,8),"权限货架",1,2L,List.of(2)),admin);
        long slot=db.slots(2).stream().filter(value->value.shelfId()==s.id()).findFirst().orElseThrow().id();
        catalog.saveSlot(slot,new Catalog.SlotForm(p.id(),true),admin,2);
        return new Fixture(p.id(),s.id(),slot);
    }
    private MockHttpSession login(String username,String password) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",username).param("password",password))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
    private record Fixture(long product,long shelf,long slot) {}
}
