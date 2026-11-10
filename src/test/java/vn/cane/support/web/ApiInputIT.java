package vn.cane.support.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.cane.CaneApplication;
import vn.cane.fixture.TestInfrastructure;
import vn.cane.support.validation.InputNormalizer;
import vn.cane.support.validation.NumericInputs;

/** Real HTTP/container wiring; the probe controller and permit rule exist only in test classes. */
@Testcontainers
class ApiInputIT {
    private static final String PREFIX = "/api/v1/__test";

    @Container
    static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    @Container
    static final GenericContainer<?> REDIS = TestInfrastructure.redis();

    private static ConfigurableApplicationContext context;
    private static HttpClient client;
    private static JsonMapper mapper;
    private static String origin;

    @BeforeAll
    static void start() throws Exception {
        TestInfrastructure.prepareCore(POSTGRES);
        var app = new SpringApplication(CaneApplication.class, ProbeConfiguration.class);
        app.setLogStartupInfo(false);
        app.setDefaultProperties(Map.of(
                "CANE_DB_URL",
                POSTGRES.getJdbcUrl(),
                "CANE_DB_USER",
                "cane_api",
                "CANE_DB_PASSWORD",
                TestInfrastructure.API_PASSWORD,
                "CANE_REDIS_HOST",
                REDIS.getHost(),
                "CANE_REDIS_PORT",
                REDIS.getMappedPort(6379).toString(),
                "CANE_REDIS_USER",
                "cane_runtime",
                "CANE_REDIS_PASSWORD",
                TestInfrastructure.REDIS_PASSWORD));
        context = app.run("--spring.profiles.active=test,api", "--spring.main.keep-alive=false");
        origin = "http://127.0.0.1:"
                + ((WebServerApplicationContext) context).getWebServer().getPort();
        mapper = context.getBean(JsonMapper.class);
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        if (client != null) client.close();
        if (context != null) context.close();
    }

    @Test
    void normalizesValidInputAndEmitsUtcInstantWithoutRounding() throws Exception {
        var response = post("/input", "{\"name\":\" Shop \",\"amountVnd\":9007199254740991,\"active\":true}");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()))
                .isEqualTo(mapper.readTree("{\"name\":\"Shop\",\"amountVnd\":9007199254740991,\"active\":true}"));
        assertCommonHeaders(response);
        var time = send("GET", "/time", HttpRequest.BodyPublishers.noBody(), Map.of());
        assertThat(mapper.readTree(time.body()).get("expiresAt").asString())
                .isEqualTo("2026-11-10T01:02:03.123456789Z");
    }

    @Test
    void rejectsDuplicateKeysTrailingJsonUnknownFieldsAndScalarCoercion() throws Exception {
        for (String input : List.of(
                "{\"name\":\"A\",\"name\":\"B\",\"amountVnd\":1,\"active\":true}",
                "{\"name\":\"A\",\"amountVnd\":1,\"active\":true,\"extra\":1}",
                "{\"name\":\"A\",\"amountVnd\":1.5,\"active\":true}",
                "{\"name\":123,\"amountVnd\":1,\"active\":true}",
                "{\"name\":\"A\",\"amountVnd\":\"1\",\"active\":true}",
                "{\"name\":\"A\",\"amountVnd\":1,\"active\":\"true\"}",
                "{\"name\":\"A\",\"amountVnd\":1,\"active\":null}",
                "{\"name\":\"A\",\"amountVnd\":1,\"active\":true} {}",
                "{")) {
            assertError(post("/input", input), 400, "INVALID_INPUT");
        }
        assertError(post("/tree", "{\"outer\":{\"secret\":1,\"secret\":2}}"), 400, "INVALID_INPUT");
        for (String value : List.of("0", "\"COD\"", "\"cash\"")) {
            assertError(post("/method", "{\"method\":" + value + "}"), 400, "INVALID_INPUT");
        }
        assertThat(post("/method", "{\"method\":\"cod\"}").statusCode()).isEqualTo(200);
        assertError(post("/uuid", "{\"id\":\"1-1-1-1-1\"}"), 400, "INVALID_INPUT");
        assertError(post("/uuid", "{\"id\":\"AAAAAAAAAAAAAAAAAAAAAA==\"}"), 400, "INVALID_INPUT");
        assertThat(post("/uuid", "{\"id\":\"550e8400-e29b-41d4-a716-446655440000\"}")
                        .statusCode())
                .isEqualTo(200);
    }

    @Test
    void distinguishesDomainAndBeanValidationFromMalformedPathAndBody() throws Exception {
        var domain = post("/input", "{\"name\":\"A\",\"amountVnd\":9007199254740992,\"active\":true}");
        assertError(domain, 422, "INVALID_INPUT");
        assertThat(mapper.readTree(domain.body()).at("/error/fieldErrors").propertyNames())
                .containsExactly("amountVnd");
        var bean = post("/bean", "{\"name\":\" \"}");
        assertError(bean, 422, "INVALID_INPUT");
        assertThat(mapper.readTree(bean.body()).at("/error/fieldErrors").propertyNames())
                .containsExactly("name");
        assertError(
                send("GET", "/item/not-a-uuid", HttpRequest.BodyPublishers.noBody(), Map.of()), 400, "INVALID_INPUT");
        assertError(
                send("GET", "/item/1-1-1-1-1", HttpRequest.BodyPublishers.noBody(), Map.of()), 400, "INVALID_INPUT");
        assertError(post("/input", ""), 400, "INVALID_INPUT");
    }

    @Test
    void enforcesByteLimitAtBoundaryAndOnChunkedUtf8Body() throws Exception {
        String prefix = "{\"value\":\"";
        String suffix = "\"}";
        int overhead = (prefix + suffix).getBytes(StandardCharsets.UTF_8).length;
        String exact = prefix + "a".repeat(JsonBodyFilter.MAX_BODY_BYTES - overhead) + suffix;
        assertThat(post("/tree", exact).statusCode()).isEqualTo(200);
        assertError(post("/tree", exact + " "), 413, "INVALID_INPUT");
        byte[] chunked =
                (prefix + "đ".repeat(JsonBodyFilter.MAX_BODY_BYTES / 2) + suffix).getBytes(StandardCharsets.UTF_8);
        assertError(
                send(
                        "POST",
                        "/tree",
                        HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(chunked)),
                        Map.of("Content-Type", "application/json")),
                413,
                "INVALID_INPUT");
    }

    @Test
    void rejectsWrongMediaAndKeepsFrameworkErrorsInJsonEvenForHtmlAccept() throws Exception {
        assertError(
                send("POST", "/input", HttpRequest.BodyPublishers.ofString("{}"), Map.of("Content-Type", "text/plain")),
                415,
                "INVALID_INPUT");
        assertError(send("POST", "/input", HttpRequest.BodyPublishers.ofString("{}"), Map.of()), 415, "INVALID_INPUT");
        assertError(
                send("GET", "/missing", HttpRequest.BodyPublishers.noBody(), Map.of("Accept", "text/html")),
                404,
                "NOT_FOUND");
        var method = send("DELETE", "/input", HttpRequest.BodyPublishers.noBody(), Map.of("Accept", "text/html"));
        assertError(method, 405, "INVALID_INPUT");
        assertThat(method.headers().firstValue("Allow").orElse("")).contains("POST");
    }

    @Test
    void securityDenialAndCsrfUseSameSafeErrorWithoutOpeningProductionRoutes() throws Exception {
        assertError(
                send("GET", "/item;rejected=PRIVATE-INPUT", HttpRequest.BodyPublishers.noBody(), Map.of()),
                400,
                "INVALID_INPUT");
        for (String method : List.of("GET", "POST")) {
            var request = HttpRequest.newBuilder(URI.create(origin + "/api/v1/shop"))
                    .timeout(Duration.ofSeconds(5))
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .build();
            assertError(client.send(request, HttpResponse.BodyHandlers.ofString()), 403, "FORBIDDEN");
        }
    }

    @Test
    void errorsNeverEchoRejectedDataAndIgnoreClientRequestId() throws Exception {
        String privateValue = "PRIVATE-PASSWORD-COOKIE-TOKEN";
        var response = send(
                "POST",
                "/bean",
                HttpRequest.BodyPublishers.ofString("{\"unknown\":\"" + privateValue + "\"}"),
                Map.of("Content-Type", "application/json", "X-Request-ID", privateValue));
        assertError(response, 400, "INVALID_INPUT");
        assertThat(response.body()).doesNotContain(privateValue, "exception", "stackTrace", "rejectedValue");
    }

    @Test
    void logsOnlySafeRouteTemplateAndCorrelatesGenericInfrastructureFailure() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var captured = new CapturedLogs();
        captured.start();
        root.addAppender(captured);
        String privateValue = "PRIVATE-URL-ADDRESS-COOKIE-TOKEN";
        try {
            assertError(post("/bean", "{\"unknown\":\"" + privateValue + "' OR '1'='1\"}"), 400, "INVALID_INPUT");
            var response = send(
                    "GET",
                    "/item/" + privateValue + "?password=" + privateValue,
                    HttpRequest.BodyPublishers.noBody(),
                    Map.of(
                            "Cookie",
                            "session=" + privateValue,
                            "Authorization",
                            "Bearer " + privateValue,
                            "X-CSRF-Token",
                            privateValue));
            assertError(response, 400, "INVALID_INPUT");
            var failure = send("GET", "/failure", HttpRequest.BodyPublishers.noBody(), Map.of());
            assertError(failure, 503, "SERVICE_UNAVAILABLE");
            assertThat(failure.body()).doesNotContain("PRIVATE-SQL", "DataAccess");
            assertThat(captured.requestCompletions.await(5, TimeUnit.SECONDS)).isTrue();
            var requestLogs = captured.events.stream()
                    .filter(event -> event.getLoggerName().equals(RequestTraceFilter.class.getName()))
                    .toList();
            assertThat(requestLogs).anySatisfy(event -> {
                assertThat(logFields(event))
                        .containsEntry("route", "/api/v1/__test/item/{id}")
                        .containsEntry("status", 400)
                        .containsEntry("component", "http")
                        .containsKey("durationMs");
                assertThat(event.getMDCPropertyMap().get("requestId"))
                        .isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
            });
            assertThat(requestLogs).anySatisfy(event -> {
                assertThat(logFields(event))
                        .containsEntry("errorCode", "SERVICE_UNAVAILABLE")
                        .containsEntry("status", 503);
                assertThat(event.getMDCPropertyMap().get("requestId"))
                        .isEqualTo(failure.headers().firstValue("X-Request-ID").orElseThrow());
            });
            for (ILoggingEvent event : captured.events) {
                assertThat(event.getFormattedMessage()).doesNotContain(privateValue, "PRIVATE-SQL");
                assertThat(logFields(event).toString()).doesNotContain(privateValue, "PRIVATE-SQL");
                if (event.getThrowableProxy() != null) {
                    assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy()))
                            .doesNotContain(privateValue, "PRIVATE-SQL");
                }
            }
        } finally {
            root.detachAppender(captured);
            captured.stop();
        }
    }

    private static HttpResponse<String> post(String path, String json) throws Exception {
        return send(
                "POST", path, HttpRequest.BodyPublishers.ofString(json), Map.of("Content-Type", "application/json"));
    }

    private static HttpResponse<String> send(
            String method, String path, HttpRequest.BodyPublisher body, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(origin + PREFIX + path))
                .timeout(Duration.ofSeconds(5))
                .method(method, body);
        headers.forEach(builder::header);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertCommonHeaders(response);
        JsonNode error = mapper.readTree(response.body()).get("error");
        assertThat(error.propertyNames())
                .containsExactlyInAnyOrder("code", "message", "fieldErrors", "itemErrors", "requestId");
        assertThat(error.get("code").asString()).isEqualTo(code);
        assertThat(error.get("requestId").asString())
                .isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
        assertThat(error.get("fieldErrors").isObject()).isTrue();
        assertThat(error.get("itemErrors").isArray()).isTrue();
    }

    private static void assertCommonHeaders(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(UUID.fromString(response.headers().firstValue("X-Request-ID").orElseThrow()))
                .isNotNull();
        assertThat(response.headers().allValues("Set-Cookie").toString()).doesNotContain("JSESSIONID");
    }

    private static final class CapturedLogs extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch requestCompletions = new CountDownLatch(3);

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            events.add(event);
            if (event.getLoggerName().equals(RequestTraceFilter.class.getName())
                    && event.getFormattedMessage().equals("Request completed")) {
                requestCompletions.countDown();
            }
        }
    }

    private static Map<String, Object> logFields(ILoggingEvent event) {
        var fields = new LinkedHashMap<String, Object>();
        if (event.getKeyValuePairs() != null)
            event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
        return fields;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }

        @Bean
        @Order(0)
        SecurityFilterChain probeSecurity(HttpSecurity http) throws Exception {
            return http.securityMatcher(PREFIX + "/**")
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .requestCache(cache -> cache.disable())
                    .build();
        }
    }

    @TestComponent
    @RestController
    @RequestMapping(PREFIX)
    static class ProbeController {
        @PostMapping("/method")
        MethodInput method(@RequestBody MethodInput input) {
            return input;
        }

        @PostMapping("/uuid")
        UuidInput uuid(@RequestBody UuidInput input) {
            return input;
        }

        @PostMapping("/input")
        Input input(@RequestBody Input input) {
            return new Input(
                    InputNormalizer.requiredText(input.name(), 100, "name"),
                    BigInteger.valueOf(NumericInputs.vnd(input.amountVnd(), false, "amountVnd")),
                    input.active());
        }

        @PostMapping("/tree")
        Map<String, Integer> tree(@RequestBody JsonNode input) {
            return Map.of("size", input.size());
        }

        @PostMapping("/bean")
        Map<String, Boolean> bean(@RequestBody @Valid BeanInput input) {
            return Map.of("accepted", true);
        }

        @GetMapping("/item/{id}")
        Map<String, UUID> item(@PathVariable UUID id) {
            return Map.of("id", id);
        }

        @GetMapping("/time")
        Map<String, Instant> time() {
            return Map.of("expiresAt", Instant.parse("2026-11-10T01:02:03.123456789Z"));
        }

        @GetMapping("/failure")
        void failure() {
            throw new DataAccessResourceFailureException("PRIVATE-SQL SELECT secret FROM private_address");
        }
    }

    record Input(String name, BigInteger amountVnd, boolean active) {}

    record BeanInput(@NotBlank String name) {}

    enum PaymentMethod {
        cod,
        bank_transfer
    }

    record MethodInput(PaymentMethod method) {}

    record UuidInput(UUID id) {}
}
