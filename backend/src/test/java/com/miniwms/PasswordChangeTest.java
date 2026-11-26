package com.miniwms;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
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
class PasswordChangeTest {
    @Autowired Accounts accounts;
    @Autowired UserPreferences preferences;
    @Autowired WarehouseDb db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    private static final String OLD = "password-before-change";
    private static final String NEW = "password-after-change";

    @Test
    void operatorChangesOnlyTheirOwnPasswordAndOldSessionsAreRejected() throws Exception {
        var user = create(Accounts.Role.OPERATOR);
        var other = create(Accounts.Role.OPERATOR);
        var current = login(user.username(), OLD);
        var otherDevice = login(user.username(), OLD);
        String otherHash = accounts.find(other.id()).passwordHash();
        preferences.save(user.id(), new UserPreferences.Settings(UserPreferences.WeightUnit.GRAM));
        String body = json.writeValueAsString(new Accounts.PasswordChange(OLD, NEW, NEW));
        body = body.substring(0, body.length()-1) + ",\"userId\":" + other.id() + ",\"role\":\"ADMIN\"}";
        mvc.perform(put("/api/auth/password").session(current).with(csrf()).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(current.isInvalid()).isTrue();
        assertThat(accounts.find(user.id()).role()).isEqualTo(Accounts.Role.OPERATOR);
        assertThat(accounts.find(other.id()).passwordHash()).isEqualTo(otherHash);
        assertThat(encoder.matches(NEW, accounts.find(user.id()).passwordHash())).isTrue();
        assertThat(encoder.matches(OLD, accounts.find(user.id()).passwordHash())).isFalse();
        assertThat(preferences.get(user.id()).weightUnit()).isEqualTo(UserPreferences.WeightUnit.GRAM);
        mvc.perform(get("/api/auth/me").servletPath("/api/auth/me").session(otherDevice)).andExpect(status().isUnauthorized());
        assertThat(otherDevice.isInvalid()).isTrue();
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", user.username()).param("password", OLD))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(login(user.username(), NEW))).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(user.username()));
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE operator_id=? AND target_id=? AND action='PASSWORD_CHANGE'",
                Integer.class, user.id(), user.id())).isEqualTo(1);
    }

    @Test
    void invalidPasswordChangesDoNotChangeCredentialsOrEndTheSession() throws Exception {
        var user = create(Accounts.Role.OPERATOR);
        var session = login(user.username(), OLD);
        String before = accounts.find(user.id()).passwordHash();
        for (var form : List.of(
                new Accounts.PasswordChange("incorrect-password", NEW, NEW),
                new Accounts.PasswordChange(OLD, NEW, "different-confirmation"),
                new Accounts.PasswordChange(OLD, OLD, OLD),
                new Accounts.PasswordChange(OLD, "short", "short"),
                new Accounts.PasswordChange(OLD, "密".repeat(25), "密".repeat(25)),
                new Accounts.PasswordChange("密".repeat(25), NEW, NEW),
                new Accounts.PasswordChange(OLD, "x".repeat(73), "x".repeat(73)))) {
            mvc.perform(put("/api/auth/password").session(session).with(csrf()).contentType(APPLICATION_JSON)
                    .content(json.writeValueAsBytes(form))).andExpect(status().isBadRequest());
            assertThat(accounts.find(user.id()).passwordHash()).isEqualTo(before);
            mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        }
        var form = new Accounts.PasswordChange(OLD, NEW, NEW);
        mvc.perform(put("/api/auth/password").session(session).contentType(APPLICATION_JSON)
                .content(json.writeValueAsBytes(form))).andExpect(status().isForbidden());
        mvc.perform(put("/api/auth/password").with(csrf()).contentType(APPLICATION_JSON)
                .content(json.writeValueAsBytes(form))).andExpect(status().isUnauthorized());
        assertThat(accounts.find(user.id()).passwordHash()).isEqualTo(before);
    }

    @Test
    void administratorsCanUseTheSamePersonalPasswordFlow() throws Exception {
        var user = create(Accounts.Role.ADMIN);
        mvc.perform(put("/api/auth/password").session(login(user.username(), OLD)).with(csrf()).contentType(APPLICATION_JSON)
                .content(json.writeValueAsBytes(new Accounts.PasswordChange(OLD, NEW, NEW))))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(login(user.username(), NEW))).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    private Accounts.View create(Accounts.Role role) {
        long admin = accounts.loadUserByUsername("admin").id();
        return accounts.save(null, new Accounts.Form("pwd-"+UUID.randomUUID(), "密码测试", role, true, OLD), admin);
    }
    private MockHttpSession login(String username, String password) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",username).param("password",password))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }
}
