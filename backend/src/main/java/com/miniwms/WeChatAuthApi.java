package com.miniwms;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/wechat")
class WeChatAuthApi {
    record Login(@NotBlank @Size(max=512) String loginCode, @NotBlank @Size(max=512) String phoneCode) {}
    private final WeChatClient client;
    private final WeChatAccounts accounts;
    private final SecurityContextRepository contexts;
    private final CompositeSessionAuthenticationStrategy sessions;

    WeChatAuthApi(WeChatClient client, WeChatAccounts accounts, SecurityContextRepository contexts,
                  CookieCsrfTokenRepository csrf) {
        this.client = client; this.accounts = accounts; this.contexts = contexts;
        this.sessions = new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrf)));
    }

    @PostMapping("/login")
    Accounts.View login(@Valid @RequestBody Login form, HttpServletRequest request, HttpServletResponse response) {
        var user = accounts.login(client.verify(form.loginCode(), form.phoneCode()));
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
        sessions.onAuthentication(auth, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return user.view();
    }
}
