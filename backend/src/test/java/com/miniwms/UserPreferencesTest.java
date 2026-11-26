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
class UserPreferencesTest {
    @Autowired Accounts accounts;
    @Autowired UserPreferences preferences;
    @Autowired Catalog catalog;
    @Autowired WarehouseDb db;
    @Autowired Stock stock;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private static final String PASSWORD = "preference-test-password";

    @Test
    void eachAccountOwnsAPersistentPreferenceWithJinAsTheDefault() throws Exception {
        long admin = accounts.loadUserByUsername("admin").id();
        var first = operator(admin); var second = operator(admin);
        var session = login(first.username());
        mvc.perform(get("/api/auth/preferences").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.weightUnit").value("JIN"));
        mvc.perform(put("/api/auth/preferences").session(session).with(csrf()).contentType(APPLICATION_JSON)
                .content("{\"weightUnit\":\"GRAM\",\"userId\":" + second.id() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.weightUnit").value("GRAM"));
        assertThat(preferences.get(first.id()).weightUnit()).isEqualTo(UserPreferences.WeightUnit.GRAM);
        assertThat(preferences.get(second.id()).weightUnit()).isEqualTo(UserPreferences.WeightUnit.JIN);
        mvc.perform(get("/api/auth/preferences").session(login(first.username()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.weightUnit").value("GRAM"));
        mvc.perform(get("/api/auth/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/auth/preferences").session(session).contentType(APPLICATION_JSON).content("{\"weightUnit\":\"JIN\"}"))
                .andExpect(status().isForbidden());
        for (String value : List.of("null", "\"KG\"")) {
            mvc.perform(put("/api/auth/preferences").session(session).with(csrf()).contentType(APPLICATION_JSON)
                    .content("{\"weightUnit\":" + value + "}")).andExpect(status().isBadRequest());
        }
    }

    @Test
    void gramExportsConvertWeightsWhileSourceInventoryAndHeadsStayUnchanged() throws Exception {
        long admin = accounts.loadUserByUsername("admin").id();
        var operator = operator(admin);
        var product = catalog.saveProduct(null, new Catalog.ProductForm("WEIGHT-"+UUID.randomUUID(),"鱼胶",null,null,null,
                "斤",null,null,true,null,2L,"白花",33),admin);
        var shelf = catalog.saveShelf(null,new Catalog.ShelfForm("W"+UUID.randomUUID().toString().substring(0,8),"单位测试",1,2L,List.of(1)),admin);
        long slot = db.slots(2).stream().filter(value->value.shelfId()==shelf.id()).findFirst().orElseThrow().id();
        catalog.saveSlot(slot,new Catalog.SlotForm(product.id(),true),admin,2);
        stock.adjust(operator.id(),Stock.Type.IN,new Stock.Adjust(UUID.randomUUID(),slot,product.id(),new BigDecimal("0.125"),"WEIGHT-TEST",null),2);
        var session = login(operator.username());
        String jin = csv(session,product.id());
        assertThat(jin).contains("\"0.125\",\"0\",\"0.125\"").contains("\"斤\",\"33\"");
        preferences.save(operator.id(),new UserPreferences.Settings(UserPreferences.WeightUnit.GRAM));
        String grams = csv(session,product.id());
        assertThat(grams).contains("\"62.5\",\"0\",\"62.5\"").contains("\"克\",\"33\"");
        assertThat(db.slot(slot).quantity()).isEqualByComparingTo("0.125");
        assertThat(db.slot(slot).headCount()).isEqualTo(33);
        assertThat(db.slot(slot).getEstimatedCount()).isEqualByComparingTo("4");
        assertThat(UserPreferences.WeightUnit.GRAM.display(new BigDecimal("12"),"件")).isEqualByComparingTo("12");
        assertThat(UserPreferences.WeightUnit.GRAM.label("件")).isEqualTo("件");
        preferences.save(operator.id(),new UserPreferences.Settings(UserPreferences.WeightUnit.JIN));
        assertThat(csv(session,product.id())).isEqualTo(jin);
    }

    @Test
    void specificationLockSurvivesClearingStockAndRejectsCoreChangesThroughTheApi() throws Exception {
        long admin = accounts.loadUserByUsername("admin").id();
        var product = catalog.saveProduct(null,form("LOCK-"+UUID.randomUUID(),"白花",33,null),admin);
        assertThat(product.specificationLocked()).isFalse();
        product = catalog.saveProduct(product.id(),form(product.sku(),"黄花",40,null),admin);
        assertThat(product.specificationLocked()).isFalse();
        var shelf = catalog.saveShelf(null,new Catalog.ShelfForm("L"+UUID.randomUUID().toString().substring(0,8),"锁定测试",1,2L,List.of(1)),admin);
        long slot = db.slots(2).stream().filter(value->value.shelfId()==shelf.id()).findFirst().orElseThrow().id();
        catalog.saveSlot(slot,new Catalog.SlotForm(product.id(),true),admin,2);
        stock.adjust(admin,Stock.Type.IN,new Stock.Adjust(UUID.randomUUID(),slot,product.id(),1,null,null),2);
        stock.adjust(admin,Stock.Type.OUT,new Stock.Adjust(UUID.randomUUID(),slot,product.id(),1,null,null),2);
        assertThat(db.slot(slot).quantity()).isZero();
        var session = adminSession();
        mvc.perform(get("/api/products").param("warehouseId","2").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == " + product.id() + ")].specificationLocked").value(org.hamcrest.Matchers.contains(true)));
        for (var change : List.of(form(product.sku(),"白花",40,null),form(product.sku(),"黄花",50,null))) {
            mvc.perform(put("/api/products/"+product.id()).param("warehouseId","2").session(session).with(csrf())
                    .contentType(APPLICATION_JSON).content(json.writeValueAsBytes(change))).andExpect(status().isConflict());
        }
        mvc.perform(put("/api/products/"+product.id()).param("warehouseId","2").session(session).with(csrf())
                .contentType(APPLICATION_JSON).content(json.writeValueAsBytes(form(product.sku(),"黄花",40,"允许编辑备注"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.specificationLocked").value(true)).andExpect(jsonPath("$.note").value("允许编辑备注"));
        assertThat(db.slot(slot).variety()).isEqualTo("黄花");
        assertThat(db.slot(slot).headCount()).isEqualTo(40);
    }

    private Catalog.ProductForm form(String sku,String variety,int heads,String note) {
        return new Catalog.ProductForm(sku,"鱼胶",null,null,null,"斤",null,null,true,note,2L,variety,heads);
    }
    private Accounts.View operator(long admin) {
        return accounts.save(null,new Accounts.Form("pref-"+UUID.randomUUID(),"单位测试",Accounts.Role.OPERATOR,true,PASSWORD),admin);
    }
    private MockHttpSession login(String username) throws Exception {
        return session(username,PASSWORD);
    }
    private MockHttpSession adminSession() throws Exception { return session("admin","test-admin-password"); }
    private MockHttpSession session(String username,String password) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",username).param("password",password))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
    private String csv(MockHttpSession session,long product) throws Exception {
        var result=mvc.perform(get("/api/stock/movements.csv").session(session).param("warehouseId","2").param("productId",""+product))
                .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
