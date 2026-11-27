package com.miniwms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class WeChatClientTest {
    private HttpServer server;
    private WeChatClient client;
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> bodies = new ArrayList<>();
    private String sessionBody = "{\"openid\":\"verified-openid\",\"session_key\":\"private-session-key\"}";
    private String watermark = "test-app";
    private int tokenCalls;
    private int phoneCalls;
    private boolean expiredToken;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", request -> {
            String path = request.getRequestURI().getPath();
            bodies.add(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = switch (path) {
                case "/sns/jscode2session" -> sessionBody;
                case "/cgi-bin/stable_token" -> { tokenCalls++; yield "{\"access_token\":\"token-" + tokenCalls + "\",\"expires_in\":7200}"; }
                case "/wxa/business/getuserphonenumber" -> {
                    phoneCalls++;
                    if (expiredToken && phoneCalls == 1) yield "{\"errcode\":42001,\"errmsg\":\"token expired\"}";
                    yield "{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"13800001111\",\"countryCode\":\"86\",\"watermark\":{\"appid\":\"" + watermark + "\"}}}";
                }
                default -> "{}";
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, bytes.length);
            request.getResponseBody().write(bytes); request.close();
        });
        server.start();
        client = new WeChatClient("test-app", "private-secret", json, HttpClient.newHttpClient(), origin());
    }
    private URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    @AfterEach void stop() { server.stop(0); }

    @Test
    void verifiesBothCredentialsAndCachesStableTokenWithoutReturningSessionKey() throws Exception {
        var identity = client.verify("login-code", "phone-code");
        assertThat(identity).isEqualTo(new WeChatClient.Identity("test-app", "verified-openid", "13800001111"));
        client.verify("another-code", "another-phone-code");
        assertThat(tokenCalls).isEqualTo(1); assertThat(phoneCalls).isEqualTo(2);
        assertThat(json.readTree(bodies.get(1)).get("secret").asText()).isEqualTo("private-secret");
        assertThat(json.readTree(bodies.get(2)).get("code").asText()).isEqualTo("phone-code");
        assertThat(json.readTree(bodies.get(2)).get("openid").asText()).isEqualTo("verified-openid");
        assertThat(identity.toString()).doesNotContain("private-session-key", "private-secret");
    }
    @Test
    void expiredTokenIsRefreshedOnceAndWrongAppPhoneIsRejected() throws Exception {
        expiredToken = true;
        client.verify("login", "phone");
        assertThat(tokenCalls).isEqualTo(2); assertThat(phoneCalls).isEqualTo(2);
        assertThat(bodies.stream().filter(body -> body.contains("force_refresh") && body.contains("true"))).hasSize(1);
        phoneCalls = 0;
        client.verify("second-login", "second-phone");
        assertThat(tokenCalls).isEqualTo(2); // Burst errors cannot repeatedly force-refresh.
        watermark = "different-app";
        assertThatThrownBy(() -> client.verify("login", "phone")).hasMessageContaining("不属于当前小程序");
    }
    @Test
    void invalidLoginCodeCannotObtainPhoneAndUpstreamMessagesNeverLeak() {
        sessionBody = "{\"errcode\":40029,\"errmsg\":\"private-secret private-session-key\"}";
        assertThatThrownBy(() -> client.verify("login", "phone")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("凭证已失效").hasMessageNotContaining("private-secret");
        assertThat(phoneCalls).isZero(); assertThat(tokenCalls).isZero();
        sessionBody = "<invalid>private-secret</invalid>";
        assertThatThrownBy(() -> client.verify("login", "phone")).isInstanceOf(ResponseStatusException.class)
                .hasMessageNotContaining("private-secret");
    }
    @Test
    void missingConfigurationFailsClosedWithoutContactingWechat() {
        client = new WeChatClient("", "", json, HttpClient.newHttpClient(), origin());
        assertThatThrownBy(() -> client.verify("login", "phone")).hasMessageContaining("尚未配置微信登录");
        assertThat(bodies).isEmpty();
    }
}
