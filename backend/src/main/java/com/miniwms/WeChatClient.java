package com.miniwms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/** Only a server-verified WeChat identity may reach the account service. */
@Component
class WeChatClient {
    record Identity(String appId, String openId) {}
    private final String appId;
    private final String secret;
    private final ObjectMapper json;
    private final HttpClient http;
    private final URI origin;

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

    Identity verify(String loginCode) {
        if (appId.isBlank() || secret.isBlank()) throw unavailable("尚未配置微信登录，请联系管理员");
        var sessionUri = UriComponentsBuilder.fromUri(origin).path("/sns/jscode2session")
                .queryParam("appid", appId).queryParam("secret", secret).queryParam("js_code", loginCode)
                .queryParam("grant_type", "authorization_code").build().encode().toUri();
        var session = exchange(HttpRequest.newBuilder(sessionUri).GET());
        int error = session.path("errcode").asInt();
        if (error == 40029 || error == 40163) throw ApiError.bad("微信登录凭证已失效，请重新登录");
        if (error != 0) throw unavailable("微信登录暂不可用，请联系管理员检查配置或稍后重试");
        String openId = session.path("openid").asText();
        if (!openId.matches("[A-Za-z0-9_-]{1,128}")) throw unavailable("微信身份校验失败，请重新登录");

        return new Identity(appId, openId);
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
            // Upstream URLs/errors can contain credentials; never echo or log them.
            throw unavailable("微信服务暂不可用，请稍后重试");
        }
    }

    private static ResponseStatusException unavailable(String message) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
