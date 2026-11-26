package com.miniwms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "WMS_TEST_REDIS_PORT", matches = "[0-9]+")
class RedisSessionTest {
    private final String namespace = "miniwms:test:" + UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper();
    private static final String PASSWORD = "redis-test-admin-password";
    private String base;

    @Test
    void sessionsAndCachesSurviveRestartWhileInventoryAndAccountChecksRemainLive() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        long slotId;
        String token;
        String sessionId;
        String secondOrigin = "https://second.example";
        try (var first = start("https://first.example")) {
            var response = login(client, "admin", PASSWORD);
            assertThat(String.join(";", response.headers().allValues("set-cookie")).toLowerCase())
                    .contains("jsessionid=", "max-age=1296000", "httponly", "samesite=lax");
            sessionId = sessionId(cookies);
            var repository = repository(first);
            assertThat(repository.findById(sessionId).getMaxInactiveInterval()).isEqualTo(Duration.ofDays(15));
            var redis = first.getBean(StringRedisTemplate.class);
            assertThat(redis.getExpire(namespace + ":session:sessions:" + sessionId)).isBetween(1295990L, 1296000L);

            assertThat(get(client, "/warehouses").statusCode()).isEqualTo(200);
            assertThat(redis.getExpire(namespace + ":cache:warehouses::list")).isBetween(290L, 300L);
            var db = first.getBean(WarehouseDb.class);
            var slot = db.slots(2).stream().filter(value -> value.productId() != null).findFirst().orElseThrow();
            slotId = slot.id();
            token = db.jdbc.queryForObject("SELECT qr_token FROM shelf_slots WHERE id=?", String.class, slotId);
            byte[] png = get(client, "/slots/" + slotId + "/qrcode").body();
            assertThat(get(client, "/slots/" + slotId + "/qrcode").body()).isEqualTo(png);
            assertThat(decode(png)).isEqualTo("https://first.example/m/slot/" + token);
            assertThat(redis.getExpire(namespace + ":cache:qr-images::https://first.example/m/slot/" + token))
                    .isBetween(86390L, 86400L);
        }

        try (var second = start(secondOrigin)) {
            var redis = second.getBean(StringRedisTemplate.class);
            try {
                assertThat(get(client, "/auth/me").statusCode()).isEqualTo(200);
                assertThat(sessionId(cookies)).isEqualTo(sessionId);
                assertThat(second.getBean(RedisSessionRepository.class).findById(sessionId)).isNotNull();
                assertThat(redis.hasKey(namespace + ":cache:warehouses::list")).isTrue();
                byte[] png = get(client, "/slots/" + slotId + "/qrcode").body();
                assertThat(decode(png)).isEqualTo(secondOrigin + "/m/slot/" + token);
                assertThat(redis.hasKey(namespace + ":cache:qr-images::" + secondOrigin + "/m/slot/" + token)).isTrue();

                var db = second.getBean(WarehouseDb.class);
                var accounts = second.getBean(Accounts.class);
                long admin = accounts.loadUserByUsername("admin").id();
                var slot = db.slot(slotId);
                assertThat(get(client, "/slots?warehouseId=2").statusCode()).isEqualTo(200);
                second.getBean(Stock.class).adjust(admin, Stock.Type.IN, new Stock.Adjust(UUID.randomUUID(), slotId,
                        slot.productId(), new BigDecimal("0.125"), null, "Redis integration test"), 2);
                JsonNode fresh = json.readTree(get(client, "/slots?warehouseId=2").body());
                JsonNode updated = java.util.stream.StreamSupport.stream(fresh.spliterator(), false)
                        .filter(value -> value.get("id").asLong() == slotId).findFirst().orElseThrow();
                assertThat(updated.get("quantity").decimalValue()).isEqualByComparingTo(slot.quantity().add(new BigDecimal("0.125")));

                var operator = accounts.save(null, new Accounts.Form("redis-operator", "Redis 操作员", Accounts.Role.OPERATOR,
                        true, PASSWORD), admin);
                var operatorCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
                var operatorClient = HttpClient.newBuilder().cookieHandler(operatorCookies).build();
                login(operatorClient, operator.username(), PASSWORD);
                String operatorSession = sessionId(operatorCookies);
                accounts.save(operator.id(), new Accounts.Form(operator.username(), operator.displayName(), operator.role(), false, null), admin);
                assertThat(get(operatorClient, "/auth/me").statusCode()).isEqualTo(401);
                assertThat(second.getBean(RedisSessionRepository.class).findById(operatorSession)).isNull();

                var expiryCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
                var expiryClient = HttpClient.newBuilder().cookieHandler(expiryCookies).build();
                login(expiryClient, "admin", PASSWORD);
                var repository = repository(second);
                var expired = repository.findById(sessionId(expiryCookies));
                expired.setLastAccessedTime(Instant.now().minus(Duration.ofDays(16)));
                repository.save(expired);
                assertThat(get(expiryClient, "/auth/me").statusCode()).isEqualTo(401);

                String csrf = json.readTree(get(client, "/auth/csrf").body()).get("token").asText();
                assertThat(send(client, "/auth/logout", "", csrf).statusCode()).isEqualTo(204);
                assertThat(repository.findById(sessionId)).isNull();
                assertThat(get(client, "/auth/me").statusCode()).isEqualTo(401);
            } finally {
                // Every integration run owns an isolated prefix; never clear an entire Redis database.
                var keys = redis.keys(namespace + ":*");
                if (keys != null && !keys.isEmpty()) redis.delete(keys);
            }
        }
    }

    private ServletWebServerApplicationContext start(String origin) {
        List<String> args = new ArrayList<>(List.of(
                "--server.address=127.0.0.1", "--server.port=0", "--server.servlet.session.cookie.secure=false",
                "--spring.datasource.url=jdbc:h2:mem:" + namespace + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "--spring.data.redis.host=" + System.getenv().getOrDefault("WMS_TEST_REDIS_HOST", "127.0.0.1"),
                "--spring.data.redis.port=" + System.getenv("WMS_TEST_REDIS_PORT"),
                "--spring.data.redis.password=" + System.getenv().getOrDefault("WMS_TEST_REDIS_PASSWORD", ""),
                "--spring.session.redis.namespace=" + namespace + ":session", "--spring.cache.redis.key-prefix=" + namespace + ":cache:",
                "--wms.admin-password=" + PASSWORD, "--wms.demo=true", "--wms.public-url=" + origin,
                "--logging.level.root=WARN", "--spring.main.banner-mode=off"));
        var context = (ServletWebServerApplicationContext) new SpringApplicationBuilder(MiniWms.class)
                .profiles("redis").run(args.toArray(String[]::new));
        base = "http://127.0.0.1:" + context.getWebServer().getPort() + "/api";
        return context;
    }

    private HttpResponse<byte[]> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> send(HttpClient client, String path, String body, String csrf) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                .header("X-XSRF-TOKEN", csrf).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> login(HttpClient client, String username, String password) throws Exception {
        String csrf = json.readTree(get(client, "/auth/csrf").body()).get("token").asText();
        var response = send(client, "/auth/login", "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8), csrf);
        assertThat(response.statusCode()).isEqualTo(204);
        return response;
    }

    private String sessionId(CookieManager cookies) {
        String value = cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONID"))
                .findFirst().orElseThrow().getValue().replace("\"", "");
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private String decode(byte[] png) throws Exception {
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                new BufferedImageLuminanceSource(ImageIO.read(new ByteArrayInputStream(png)))))).getText();
    }

    @SuppressWarnings("unchecked")
    private SessionRepository<Session> repository(ServletWebServerApplicationContext context) {
        return (SessionRepository<Session>) (SessionRepository<?>) context.getBean(RedisSessionRepository.class);
    }
}
