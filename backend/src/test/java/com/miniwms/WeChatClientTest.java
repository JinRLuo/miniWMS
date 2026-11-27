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
    private final List<String> paths = new ArrayList<>();
    private final List<String> methods = new ArrayList<>();
    private final List<String> queries = new ArrayList<>();
    private String sessionBody = "{\"openid\":\"verified-openid\",\"session_key\":\"private-session-key\"}";
    private int responseStatus = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", request -> {
            paths.add(request.getRequestURI().getPath());
            methods.add(request.getRequestMethod());
            queries.add(request.getRequestURI().getRawQuery());
            byte[] bytes = sessionBody.getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(responseStatus, bytes.length);
            request.getResponseBody().write(bytes); request.close();
        });
        server.start();
        client = new WeChatClient("test-app", "private-secret", json, HttpClient.newHttpClient(), origin());
    }
    private URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    @AfterEach void stop() { server.stop(0); }

    @Test
    void loginUsesOnlyCode2SessionAndNeverRequestsPhoneOrAccessToken() throws Exception {
        var identity = client.verify("login-code");
        assertThat(identity).isEqualTo(new WeChatClient.Identity("test-app", "verified-openid"));
        client.verify("another-code");
        assertThat(paths).containsExactly("/sns/jscode2session", "/sns/jscode2session");
        assertThat(methods).containsOnly("GET");
        assertThat(queries.get(0)).contains("appid=test-app", "secret=private-secret", "js_code=login-code", "grant_type=authorization_code");
        assertThat(identity.toString()).doesNotContain("private-session-key", "private-secret");
        assertThat(json.writeValueAsString(identity)).doesNotContain("session_key", "phone", "secret");
    }
    @Test
    void invalidLoginCodesAndUpstreamMessagesNeverLeakCredentials() {
        for (int code : new int[]{40029, 40163}) {
            sessionBody = "{\"errcode\":" + code + ",\"errmsg\":\"private-secret private-session-key\"}";
            assertThatThrownBy(() -> client.verify("login")).isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("凭证已失效").hasMessageNotContaining("private-secret").hasMessageNotContaining("private-session-key");
        }
        assertThat(paths).containsOnly("/sns/jscode2session");
    }
    @Test
    void malformedIdentityOrConfigurationErrorCannotAuthenticate() {
        for (String body : List.of("{}", "{\"openid\":\"bad openid\"}", "{\"errcode\":40013,\"errmsg\":\"private-secret\"}", "<invalid>private-secret</invalid>")) {
            sessionBody = body;
            assertThatThrownBy(() -> client.verify("login")).isInstanceOf(ResponseStatusException.class)
                    .hasMessageNotContaining("private-secret");
        }
        responseStatus = 503;
        assertThatThrownBy(() -> client.verify("login")).hasMessageContaining("微信服务暂不可用");
    }
    @Test
    void missingConfigurationFailsClosedWithoutContactingWechat() {
        client = new WeChatClient("", "", json, HttpClient.newHttpClient(), origin());
        assertThatThrownBy(() -> client.verify("login")).hasMessageContaining("尚未配置微信登录");
        assertThat(paths).isEmpty();
    }
}
