package local.digital;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class HttpAdaptersTest {
    @TempDir Path dir;
    HttpServer server;
    ExecutorService executor;
    ObjectMapper mapper = new ObjectMapper();
    MockEnvironment env;
    ModelHttp http;
    AtomicReference<String> routeBody = new AtomicReference<>("{\"answers\":{\"route\":{\"type\":\"choice\",\"choice\":\"retrieve\",\"confidence\":0.9}}}");
    AtomicReference<String> generation = new AtomicReference<>("{\"choices\":[{\"message\":{\"content\":\"公开测试资料说明整理步骤。[1]\"}}]}");
    AtomicReference<String> embedding = new AtomicReference<>("{\"data\":[{\"embedding\":[1,0,0]}]}");
    AtomicInteger routeStatus = new AtomicInteger(200);
    AtomicInteger routeDelay = new AtomicInteger(0);
    List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestURI().getPath() + " " + request);
            String response; int status = 200;
            switch (exchange.getRequestURI().getPath()) {
                case "/jev" -> {
                    try { Thread.sleep(routeDelay.get()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    response = routeBody.get(); status = routeStatus.get();
                }
                case "/embeddings" -> response = embedding.get();
                case "/chat/completions" -> response = generation.get();
                default -> { response = "{}"; status = 404; }
            }
            try {
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        env = new MockEnvironment().withProperty("app.data-dir", dir.toString()).withProperty("app.demo-data", "false")
                .withProperty("app.http-timeout-seconds", "1").withProperty("app.jev-threshold", "0.65")
                .withProperty("app.jev-url", base + "/jev").withProperty("app.jev-key", "synthetic-test-key")
                .withProperty("app.jev-model", "jev-latest")
                .withProperty("app.embedding-url", base + "/embeddings").withProperty("app.embedding-key", "synthetic-test-key")
                .withProperty("app.embedding-model", "synthetic-embedding")
                .withProperty("app.generation-url", base + "/chat/completions").withProperty("app.generation-key", "synthetic-test-key")
                .withProperty("app.generation-model", "synthetic-generator");
        http = new ModelHttp(new Settings(env), mapper);
        Files.createDirectories(dir.resolve("public"));
        mapper.writeValue(dir.resolve("public/cards.json").toFile(), List.of(
                MvpTest.entry("visible", "skill", "公开测试资料说明整理步骤", "public", true),
                MvpTest.entry("hidden", "skill", "PRIVATE_MUST_NOT_LEAVE", "public", false)));
        Files.createDirectories(dir.resolve("private"));
        mapper.writeValue(dir.resolve("private/cards.json").toFile(), List.of(
                MvpTest.entry("private", "skill", "PRIVATE_FOLDER_MUST_NOT_LEAVE", "public", true)));
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    @Test void jevUsesOfficialChoiceContract() throws Exception {
        var decision = http.route("具体步骤");
        assertThat(decision.intent()).isEqualTo("retrieve"); assertThat(decision.confidence()).isEqualTo(0.9);
        var body = mapper.readTree(requests.getFirst().substring("/jev ".length()));
        assertThat(body.path("model").asText()).isEqualTo("jev-latest");
        assertThat(body.path("state").asText()).isEqualTo("具体步骤");
        assertThat(body.path("questions").path("route").path("type").asText()).isEqualTo("choice");
        assertThat(body.path("questions").path("route").path("criteria").size()).isEqualTo(5);
    }
    @Test void remotePipelineUsesSeparateModelsAndOnlyApprovedText() throws Exception {
        Settings settings = new Settings(env);
        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var response = (Chat.Answer)new Chat(knowledge, settings, http).chat(new Chat.Question("整理步骤")).getBody();
            assertThat(response.mode()).isEqualTo("http-generated");
            assertThat(response.embeddingMode()).isEqualTo("http");
            assertThat(response.sources()).hasSize(1);
            assertThat(response.answer()).contains("[1]");
        }
        assertThat(String.join("\n", requests)).contains("synthetic-embedding", "synthetic-generator", "jev-latest")
                .doesNotContain("PRIVATE_MUST_NOT_LEAVE", "PRIVATE_FOLDER_MUST_NOT_LEAVE", "hidden-evidence");
    }
    @Test void lowConfidenceAndInvalidDecisionFallBackToLocal() throws Exception {
        env.setProperty("app.embedding-key", "");
        Settings settings = new Settings(env);
        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var chat = new Chat(knowledge, settings, http);
            routeBody.set("{\"answers\":{\"route\":{\"type\":\"choice\",\"choice\":\"unknown\",\"confidence\":0.1}}}");
            var low = (Chat.Answer)chat.chat(new Chat.Question("你会什么")).getBody();
            assertThat(low.mode()).isEqualTo("structured");
            assertThat(low.routing().provider()).isEqualTo("local-low-confidence");
            routeBody.set("{\"answers\":{\"route\":{\"type\":\"choice\",\"choice\":\"invented-route\",\"confidence\":0.99}}}");
            var invalid = (Chat.Answer)chat.chat(new Chat.Question("你会什么")).getBody();
            assertThat(invalid.routing().provider()).isEqualTo("local-jev-unavailable");
        }
    }
    @Test void jevRateLimitAndTimeoutFallBackWithoutExposingProviderBody() throws Exception {
        env.setProperty("app.embedding-key", ""); Settings settings = new Settings(env);
        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var chat = new Chat(knowledge, settings, http);
            routeStatus.set(429); routeBody.set("SENSITIVE_PROVIDER_ERROR");
            var limited = (Chat.Answer)chat.chat(new Chat.Question("你会什么")).getBody();
            assertThat(limited.routing().provider()).isEqualTo("local-jev-unavailable");
            assertThat(mapper.writeValueAsString(limited)).doesNotContain("SENSITIVE_PROVIDER_ERROR");
            routeStatus.set(200); routeDelay.set(1500);
            var timeout = (Chat.Answer)chat.chat(new Chat.Question("你会什么")).getBody();
            assertThat(timeout.mode()).isEqualTo("structured");
            assertThat(timeout.routing().provider()).isEqualTo("local-jev-unavailable");
        }
    }
    @Test void generationMissingOrInvalidCitationFallsBackToExtract() throws Exception {
        Settings settings = new Settings(env);
        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var chat = new Chat(knowledge, settings, http);
            generation.set("{\"choices\":[{\"message\":{\"content\":\"unsupported [99]\"}}]}");
            var result = (Chat.Answer)chat.chat(new Chat.Question("整理步骤")).getBody();
            assertThat(result.mode()).isEqualTo("local-generation-unavailable");
            assertThat(result.answer()).contains("公开测试资料").doesNotContain("unsupported");
            generation.set("invalid-json");
            assertThat(((Chat.Answer)chat.chat(new Chat.Question("整理步骤")).getBody()).mode()).isEqualTo("local-generation-unavailable");
        }
    }
    @Test void embeddingBuildFailureFallsBackForEntireIndex() throws Exception {
        embedding.set("{\"data\":[{\"embedding\":[0,0,0]}]}");
        Settings settings = new Settings(env);
        try (var knowledge = new Knowledge(settings, mapper, http)) {
            assertThat(knowledge.embeddingMode()).isEqualTo("lexical-only");
            assertThat(knowledge.search("整理步骤").hits()).isNotEmpty();
        }
    }
    @Test void embeddingQueryFailureOrDimensionChangeUsesLexicalOnly() throws Exception {
        Settings settings = new Settings(env);
        try (var knowledge = new Knowledge(settings, mapper, http)) {
            embedding.set("{\"data\":[{\"embedding\":[1,0]}]}");
            var result = knowledge.search("整理步骤");
            assertThat(result.embeddingMode()).isEqualTo("lexical-only");
            assertThat(result.hits()).isNotEmpty();
            embedding.set("{}");
            assertThat(knowledge.search("整理步骤").embeddingMode()).isEqualTo("lexical-only");
        }
    }
    @Test void malformedJevAndVectorsAreRejected() {
        routeBody.set("{\"answers\":{\"route\":{\"type\":\"choice\",\"choice\":\"retrieve\"}}}");
        assertThatThrownBy(() -> http.route("test")).hasMessageContaining("契约");
        embedding.set("{\"data\":[{\"embedding\":[\"not-a-number\"]}]}");
        assertThatThrownBy(() -> http.embed("test")).hasMessageContaining("数值");
    }
}
