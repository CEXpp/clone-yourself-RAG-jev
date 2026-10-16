package local.digital;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class MvpTest {
    @TempDir static Path dir;
    static ConfigurableApplicationContext app;
    static final ObjectMapper mapper = new ObjectMapper();
    static final HttpClient client = HttpClient.newHttpClient();
    static String base;

    static Entry entry(String id, String type, String content, String visibility, boolean confirmed) {
        return new Entry(id, type, id, content, "自述", "仅此范围", List.of("hidden-evidence"),
                visibility, confirmed, "测试资料 / 第1节", "2026-10-16");
    }
    @BeforeAll static void start() throws Exception {
        Files.createDirectories(dir.resolve("public")); Files.createDirectories(dir.resolve("private"));
        mapper.writeValue(dir.resolve("public/cards.json").toFile(), List.of(
                entry("rejected-unconfirmed", "skill", "UNCONFIRMED_CANARY", "public", false),
                entry("rejected-private", "skill", "PRIVATE_IN_PUBLIC_CANARY", "private", true)));
        mapper.writeValue(dir.resolve("private/cards.json").toFile(), List.of(
                entry("hidden-evidence", "evidence", "PRIVATE_FOLDER_CANARY", "public", true)));
        app = new SpringApplicationBuilder(App.class).run("--server.port=0", "--app.data-dir=" + dir,
                "--app.demo-data=true", "--app.jev-key=", "--app.embedding-key=", "--app.generation-key=");
        base = "http://127.0.0.1:" + ((ServletWebServerApplicationContext)app).getWebServer().getPort();
    }
    @AfterAll static void stop() { if (app != null) app.close(); }
    static HttpResponse<String> ask(String q) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("question", q))))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void runsOnJdk25AndServesPage() throws Exception {
        assertThat(Runtime.version().feature()).isEqualTo(25);
        var response = client.send(HttpRequest.newBuilder(URI.create(base)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("个人数字分身", "app.js");
        assertThat(response.headers().firstValue("Content-Security-Policy")).isPresent();
    }
    @Test void fixedQuestionsReturnStructuredPublicSkillsAndEvidence() throws Exception {
        var response = ask("你会什么？");
        var json = mapper.readTree(response.body());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.path("mode").asText()).isEqualTo("structured");
        assertThat(json.path("skills").size()).isEqualTo(2);
        assertThat(json.path("sources").size()).isEqualTo(3);
        assertThat(json.path("skills").get(0).path("evidence").toString()).contains("虚构证据");
        assertThat(response.body()).doesNotContain("CANARY", "hidden-evidence", "rejected-");
        assertThat(ask("介绍一下自己").body()).contains("虚构的示例分身");
        assertThat(ask("有哪些项目").body()).contains("阅读笔记盒");
    }
    @Test void hybridRetrievalReturnsLocatableSources() throws Exception {
        var json = mapper.readTree(ask("怎样记录笔记来源").body());
        assertThat(json.path("mode").asText()).isEqualTo("local-extract");
        assertThat(json.path("embeddingMode").asText()).isEqualTo("local-hash-demo");
        assertThat(json.path("sources").size()).isGreaterThan(0);
        assertThat(json.path("sources").get(0).path("locator").asText()).contains("字符");
        assertThat(json.path("sources").get(0).path("origin").asText()).contains("虚构示例");
        var result = app.getBean(Knowledge.class).search("怎样记录笔记来源");
        assertThat(result.lexicalCandidates()).isGreaterThan(0);
        assertThat(result.vectorCandidates()).isGreaterThan(0);
        assertThat(result.hits()).allSatisfy(hit -> assertThat(hit.rankScore()).isPositive());
    }
    @Test void noPrivateAccessOrUnknownFacts() throws Exception {
        assertThat(ask("PRIVATE_FOLDER_CANARY").body()).contains("insufficient-evidence").doesNotContain("PRIVATE_FOLDER_CANARY");
        assertThat(ask("星际飞船燃料价格").body()).contains("insufficient-evidence");
        for (String path : List.of("/data/private/cards.json", "/api/private", "/api/import")) {
            var response = client.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isIn(404, 405);
            assertThat(response.body()).doesNotContain("CANARY");
        }
    }
    @Test void validatesQuestion() throws Exception {
        assertThat(ask("").statusCode()).isEqualTo(400);
        assertThat(ask("x".repeat(2001)).statusCode()).isEqualTo(400);
        var invalid = client.send(HttpRequest.newBuilder(URI.create(base + "/api/chat")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("not json")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(ask("你好").body()).contains("已批准的公开资料");
    }
    @Test void doesNotExposeUnresolvedEvidenceIds() throws Exception {
        Path isolated = Files.createTempDirectory(dir, "evidence-check-");
        Files.createDirectories(isolated.resolve("public"));
        mapper.writeValue(isolated.resolve("public/card.json").toFile(), List.of(entry("public-skill", "skill", "PUBLIC_TASK", "public", true)));
        var settings = settings(isolated);
        try (var knowledge = new Knowledge(settings, mapper, new ModelHttp(settings, mapper))) {
            var response = new Chat(knowledge, settings, new ModelHttp(settings, mapper)).chat(new Chat.Question("你会什么"));
            assertThat(mapper.writeValueAsString(response.getBody())).contains("暂无公开证据").doesNotContain("hidden-evidence");
        }
    }
    @Test void emptyPublicCorpusHasNoAnswerAndDuplicateIdsFailClosed() throws Exception {
        Path isolated = Files.createTempDirectory(dir, "empty-check-");
        var settings = settings(isolated);
        var http = new ModelHttp(settings, mapper);
        try (var knowledge = new Knowledge(settings, mapper, http)) {
            assertThat(knowledge.count()).isZero();
            assertThat(knowledge.search("任何问题").hits()).isEmpty();
            var chat = new Chat(knowledge, settings, http);
            assertThat(((Chat.Answer)chat.chat(new Chat.Question("你会什么")).getBody()).sources()).isEmpty();
        }
        Files.createDirectories(isolated.resolve("public"));
        var duplicate = entry("duplicate", "skill", "公开任务", "public", true);
        mapper.writeValue(isolated.resolve("public/cards.json").toFile(), List.of(duplicate, duplicate));
        assertThatThrownBy(() -> new Knowledge(settings, mapper, http)).hasMessageContaining("重复");
    }
    static Settings settings(Path data) {
        return new Settings(new MockEnvironment().withProperty("app.data-dir", data.toString())
                .withProperty("app.demo-data", "false").withProperty("app.http-timeout-seconds", "1")
                .withProperty("app.jev-threshold", "0.65"));
    }
}
