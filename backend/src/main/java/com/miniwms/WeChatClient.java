package com.miniwms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/** Only server-verified OpenID and phone numbers may reach the account service. */
@Component
class WeChatClient {
    record Identity(String appId, String openId, String phone) {}
    private final String appId;
    private final String secret;
    private final ObjectMapper json;
    private final HttpClient http;
    private final URI origin;
    private String accessToken;
    private Instant tokenExpires = Instant.EPOCH;
    private Instant lastForcedRefresh = Instant.EPOCH;

    @Autowired
    WeChatClient(@Value("${wms.wechat.app-id:}") String appId,
                 @Value("${wms.wechat.app-secret:}") String secret, ObjectMapper json) {
        this(appId, secret, json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build(), URI.create("https://api.weixin.qq.com"));
    }

    // Test seam; production has no configurable upstream URL.
    WeChatClient(String appId, String secret, ObjectMapper json, HttpClient http, URI origin) {
        this.appId = appId; this.secret = secret; this.json = json; this.http = http; this.origin = origin;
    }

    Identity verify(String loginCode, String phoneCode) {
        if (appId.isBlank() || secret.isBlank()) throw unavailable("尚未配置微信登录，请联系管理员");
        var sessionUri = UriComponentsBuilder.fromUri(origin).path("/sns/jscode2session")
                .queryParam("appid", appId).queryParam("secret", secret).queryParam("js_code", loginCode)
                .queryParam("grant_type", "authorization_code").build().encode().toUri();
        var session = exchange(HttpRequest.newBuilder(sessionUri).GET());
        int error = session.path("errcode").asInt();
        if (error == 40029 || error == 40163) throw ApiError.bad("微信登录凭证已失效，请重新授权登录");
        if (error != 0) throw unavailable("微信登录暂不可用，请联系管理员检查配置或稍后重试");
        String openId = session.path("openid").asText();
        if (!openId.matches("[A-Za-z0-9_-]{1,128}")) throw unavailable("微信身份校验失败，请重新登录");

        JsonNode response = phone(phoneCode, openId, false);
        int phoneError = response.path("errcode").asInt();
        if (phoneError == 40001 || phoneError == 40014 || phoneError == 42001) response = phone(phoneCode, openId, true);
        if (response.path("errcode").asInt() != 0) throw ApiError.bad("手机号授权校验失败，请重新授权或联系管理员检查微信手机号能力");
        var info = response.path("phone_info");
        if (!appId.equals(info.path("watermark").path("appid").asText()))
            throw ApiError.bad("手机号授权不属于当前小程序，请重新授权");
        String number = info.path("purePhoneNumber").asText();
        String country = info.path("countryCode").asText();
        if (!number.matches("[0-9]{5,20}") || !country.matches("[0-9]{1,4}"))
            throw ApiError.bad("未获得有效手机号，请重新授权");
        return new Identity(appId, openId, "86".equals(country) ? number : "+" + country + " " + number);
    }

    private JsonNode phone(String code, String openId, boolean forceRefresh) {
        var uri = UriComponentsBuilder.fromUri(origin).path("/wxa/business/getuserphonenumber")
                .queryParam("access_token", token(forceRefresh)).build().encode().toUri();
        return post(uri, Map.of("code", code, "openid", openId));
    }

    private synchronized String token(boolean forceRefresh) {
        if (!forceRefresh && accessToken != null && Instant.now().isBefore(tokenExpires)) return accessToken;
        // WeChat permits forced refresh at most once per 30 seconds. Concurrent stale
        // callers should reuse the replacement token, not invalidate each other's tokens.
        if (forceRefresh && accessToken != null && Instant.now().isBefore(lastForcedRefresh.plusSeconds(30))) return accessToken;
        if (forceRefresh) lastForcedRefresh = Instant.now();
        var response = post(origin.resolve("/cgi-bin/stable_token"), Map.of("grant_type", "client_credential",
                "appid", appId, "secret", secret, "force_refresh", forceRefresh));
        String token = response.path("access_token").asText();
        int lifetime = response.path("expires_in").asInt();
        if (response.path("errcode").asInt() != 0 || token.isBlank() || lifetime <= 0)
            throw unavailable("微信手机号服务暂不可用，请联系管理员检查配置");
        accessToken = token;
        tokenExpires = Instant.now().plusSeconds(Math.max(1, lifetime - 60L));
        return token;
    }

    private JsonNode post(URI uri, Map<String, ?> data) {
        try {
            return exchange(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data))));
        } catch (IOException e) { throw unavailable("微信服务暂不可用，请稍后重试"); }
    }

    private JsonNode exchange(HttpRequest.Builder builder) {
        try {
            var response = http.send(builder.timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw unavailable("微信服务暂不可用，请稍后重试");
            var value = json.readTree(response.body());
            if (value == null || !value.isObject()) throw unavailable("微信服务返回格式无效，请稍后重试");
            return value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("微信服务请求中断，请重试");
        } catch (IOException | IllegalArgumentException e) {
            // Upstream URLs/errors can include AppSecret, access_token or a phone; never echo/log them.
            throw unavailable("微信服务暂不可用，请稍后重试");
        }
    }

    private static ResponseStatusException unavailable(String message) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
