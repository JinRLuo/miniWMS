package com.miniwms;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = { "spring.datasource.url=jdbc:h2:mem:wechat-auth-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "wms.admin-password=test-admin-password" })
@AutoConfigureMockMvc
class WeChatAuthTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Accounts accounts;
    @Autowired WeChatAccounts wechat;
    @Autowired WarehouseDb db;
    @MockitoBean WeChatClient client;

    @Test
    void registrationCreatesOnlyPendingAccessDespiteSpoofedIdentityOrRoleAndRotatesSession() throws Exception {
        var identity = identity();
        when(client.verify("login-code", "phone-code")).thenReturn(identity);
        var old = new MockHttpSession();
        String oldId = old.getId();
        var result = mvc.perform(post("/api/auth/wechat/login").session(old).with(csrf()).contentType(APPLICATION_JSON)
                .content("{\"loginCode\":\"login-code\",\"phoneCode\":\"phone-code\",\"approved\":true,\"role\":\"ADMIN\",\"phone\":\"fake\",\"openId\":\"admin\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.role").value("OPERATOR")).andExpect(jsonPath("$.phone").value(identity.phone()))
                .andExpect(jsonPath("$.wechatBound").value(true)).andExpect(jsonPath("$.openId").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.session_key").doesNotExist()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull(); assertThat(session.getId()).isNotEqualTo(oldId);
        for (String path : List.of("/api/warehouses", "/api/stock/context", "/api/stock/movements", "/api/stock/movements.csv",
                "/api/stock/transfers", "/api/slots/by-code/YJ01-01-01", "/api/slots/resolve/" + UUID.randomUUID(),
                "/api/products", "/api/reports/summary", "/api/admin/users", "/api/auth/preferences")) {
            mvc.perform(get(path).servletPath(path).session(session).param("warehouseId", "2"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_PENDING"));
        }
        for (String path : List.of("/api/stock/in", "/api/stock/out", "/api/stock/transfer", "/api/admin/users"))
            mvc.perform(post(path).servletPath(path).session(session).with(csrf()).contentType(APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        mvc.perform(put("/api/auth/preferences").session(session).with(csrf()).contentType(APPLICATION_JSON)
                .content("{\"weightUnit\":\"GRAM\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").servletPath("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void adminGrantAndRevokeTakeEffectOnTheSameWechatSession() throws Exception {
        var user = wechat.login(identity());
        var session = login(user);
        var admin = administrator();
        save(user, true, true, Accounts.Role.OPERATOR, admin);
        mvc.perform(get("/api/stock/context").servletPath("/api/stock/context").session(session).param("warehouseId", "2"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").servletPath("/api/admin/users").session(session)).andExpect(status().isForbidden());
        save(user, true, true, Accounts.Role.ADMIN, admin);
        mvc.perform(get("/api/admin/users").servletPath("/api/admin/users").session(session)).andExpect(status().isOk());
        save(user, false, true, Accounts.Role.ADMIN, admin);
        mvc.perform(get("/api/auth/me").servletPath("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false));
        mvc.perform(get("/api/stock/context").servletPath("/api/stock/context").session(session))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_PENDING"));
        assertThatThrownBy(() -> db.lockActor(user.id())).hasMessageContaining("尚未授权");
        mvc.perform(put("/api/admin/users/" + user.id()).servletPath("/api/admin/users/" + user.id()).session(session)
                .with(csrf()).contentType(APPLICATION_JSON).content("{}")) .andExpect(status().isForbidden());
        save(user, true, false, Accounts.Role.OPERATOR, admin);
        mvc.perform(get("/api/auth/me").servletPath("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        when(client.verify(anyString(), anyString())).thenReturn(new WeChatClient.Identity("test-app", openId(user.id()), user.phone()));
        mvc.perform(post("/api/auth/wechat/login").with(csrf()).contentType(APPLICATION_JSON)
                .content("{\"loginCode\":\"login\",\"phoneCode\":\"phone\"}")) .andExpect(status().isForbidden());
    }

    @Test
    void returningUserRetainsApprovalAndIdentitiesAreNeverMergedByPhone() throws Exception {
        var identity = identity();
        var first = wechat.login(identity);
        long admin = accounts.loadUserByUsername("admin").id();
        accounts.save(first.id(), new Accounts.Form(first.username(), "已授权微信用户", Accounts.Role.OPERATOR, true, null, true), admin);
        var again = wechat.login(new WeChatClient.Identity(identity.appId(), identity.openId(), "13900001111"));
        assertThat(again.id()).isEqualTo(first.id()); assertThat(again.approved()).isTrue();
        assertThat(again.phone()).isEqualTo("13900001111");
        var other = wechat.login(new WeChatClient.Identity(identity.appId(), "other-" + UUID.randomUUID(), again.phone()));
        assertThat(other.id()).isNotEqualTo(first.id()); assertThat(other.approved()).isFalse();
        var caseDifferent = wechat.login(new WeChatClient.Identity(identity.appId(), identity.openId().toUpperCase(java.util.Locale.ROOT), identity.phone()));
        assertThat(caseDifferent.id()).isNotEqualTo(first.id());
        assertThatThrownBy(() -> accounts.loadUserByUsername(first.username()))
                .isInstanceOf(org.springframework.security.core.userdetails.UsernameNotFoundException.class);
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", first.username()).param("password", "anything"))
                .andExpect(status().isUnauthorized());
        assertThatThrownBy(() -> accounts.save(first.id(), new Accounts.Form(first.username(), "用户", Accounts.Role.OPERATOR, true,
                "reset-password-2026", true), admin)).hasMessageContaining("不支持设置或重置");
    }

    @Test
    void concurrentFirstLoginsHaveOneAccountAndNoOrphanUsers() throws Exception {
        var identity = new WeChatClient.Identity("test-app", "concurrent-" + UUID.randomUUID(), "13800002222");
        int before = db.jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { barrier.await(); return wechat.login(identity); });
            var second = pool.submit(() -> { barrier.await(); return wechat.login(identity); });
            assertThat(first.get().id()).isEqualTo(second.get().id());
        } finally { pool.shutdownNow(); }
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(before + 1);
    }

    @Test
    void bothCodesAndCsrfAreRequiredAndInvalidWechatCredentialsCreateNoUser() throws Exception {
        int before = db.jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        mvc.perform(post("/api/auth/wechat/login").with(csrf()).contentType(APPLICATION_JSON).content("{\"loginCode\":\"only-login\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/wechat/login").contentType(APPLICATION_JSON)
                .content("{\"loginCode\":\"login\",\"phoneCode\":\"phone\"}")) .andExpect(status().isForbidden());
        verifyNoInteractions(client);
        when(client.verify(anyString(), anyString())).thenThrow(ApiError.bad("手机号授权校验失败"));
        mvc.perform(post("/api/auth/wechat/login").with(csrf()).contentType(APPLICATION_JSON)
                .content("{\"loginCode\":\"login\",\"phoneCode\":\"phone\"}")) .andExpect(status().isBadRequest());
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(before);
    }

    private WeChatClient.Identity identity() {
        return new WeChatClient.Identity("test-app", "openid-" + UUID.randomUUID(), "13800001111");
    }
    private String openId(long id) {
        return new String(db.jdbc.queryForObject("SELECT open_id FROM wechat_accounts WHERE user_id=?", byte[].class, id), StandardCharsets.UTF_8);
    }
    private MockHttpSession login(Accounts.Principal user) throws Exception {
        when(client.verify(anyString(), anyString())).thenReturn(new WeChatClient.Identity("test-app", openId(user.id()), user.phone()));
        return (MockHttpSession) mvc.perform(post("/api/auth/wechat/login").with(csrf()).contentType(APPLICATION_JSON)
                .content("{\"loginCode\":\"login\",\"phoneCode\":\"phone\"}")) .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    private MockHttpSession administrator() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).param("username", "admin")
                .param("password", "test-admin-password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
    private void save(Accounts.Principal user, boolean approved, boolean active, Accounts.Role role, MockHttpSession admin) throws Exception {
        mvc.perform(put("/api/admin/users/" + user.id()).servletPath("/api/admin/users/" + user.id()).session(admin).with(csrf())
                .contentType(APPLICATION_JSON).content(json.writeValueAsBytes(new Accounts.Form(user.username(), user.displayName(), role, active, null, approved))))
                .andExpect(status().isOk());
    }
}
