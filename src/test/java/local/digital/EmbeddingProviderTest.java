package local.digital;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingProviderTest {
    @TempDir Path dir;
    ObjectMapper mapper = new ObjectMapper();

    @Test
    void autoModeFallsBackToLexicalOnlyWhenModelAndHttpAreAbsent() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "auto")
                .withProperty("app.model-dir", dir.resolve("empty-models").toString())
                .withProperty("app.embedding-key", "")
                .withProperty("app.http-timeout-seconds", "1");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            assertThat(knowledge.embeddingMode()).isEqualTo("lexical-only");
            var result = knowledge.search("笔记");
            assertThat(result.embeddingMode()).isEqualTo("lexical-only");
            assertThat(result.vectorCandidates()).isZero();
            assertThat(result.lexicalCandidates()).isGreaterThan(0);
        }
    }

    @Test
    void explicitModesFailLoudlyWhenUnavailable() {
        var envOnnx = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "false")
                .withProperty("app.embedding-mode", "onnx")
                .withProperty("app.model-dir", dir.resolve("non-existent-models").toString())
                .withProperty("app.http-timeout-seconds", "1");
        var settingsOnnx = new Settings(envOnnx);
        var httpOnnx = new ModelHttp(settingsOnnx, mapper);

        assertThatThrownBy(() -> new Knowledge(settingsOnnx, mapper, httpOnnx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ONNX");

        var envHttp = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "false")
                .withProperty("app.embedding-mode", "http")
                .withProperty("app.embedding-key", "")
                .withProperty("app.http-timeout-seconds", "1");
        var settingsHttp = new Settings(envHttp);
        var httpHttp = new ModelHttp(settingsHttp, mapper);

        assertThatThrownBy(() -> new Knowledge(settingsHttp, mapper, httpHttp))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置 embedding-key");
    }

    @Test
    void explicitHashAndLexicalModesWork() throws Exception {
        var envHash = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "hash")
                .withProperty("app.http-timeout-seconds", "1");
        var settingsHash = new Settings(envHash);
        var httpHash = new ModelHttp(settingsHash, mapper);

        try (var knowledge = new Knowledge(settingsHash, mapper, httpHash)) {
            assertThat(knowledge.embeddingMode()).isEqualTo("hash");
            var result = knowledge.search("笔记");
            assertThat(result.embeddingMode()).isEqualTo("hash");
            assertThat(result.vectorCandidates()).isGreaterThan(0);
        }

        var envLexical = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical")
                .withProperty("app.http-timeout-seconds", "1");
        var settingsLexical = new Settings(envLexical);
        var httpLexical = new ModelHttp(settingsLexical, mapper);

        try (var knowledge = new Knowledge(settingsLexical, mapper, httpLexical)) {
            assertThat(knowledge.embeddingMode()).isEqualTo("lexical-only");
            var result = knowledge.search("笔记");
            assertThat(result.embeddingMode()).isEqualTo("lexical-only");
            assertThat(result.vectorCandidates()).isZero();
        }
    }

    @Test
    void lexicalHitContainsAllExpectedTraceFields() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical")
                .withProperty("app.http-timeout-seconds", "1");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var result = knowledge.searchWithCandidates("奶茶点单小程序后端");
            assertThat(result.hits()).isNotEmpty();

            Knowledge.Hit firstHit = result.hits().getFirst();
            assertThat(firstHit.entryId()).isNotBlank();
            assertThat(firstHit.entryId()).isEqualTo(firstHit.entry().id());
            assertThat(firstHit.chunkIndex()).isGreaterThanOrEqualTo(0);
            assertThat(firstHit.charStart()).isGreaterThanOrEqualTo(0);
            assertThat(firstHit.charEnd()).isGreaterThan(firstHit.charStart());
            assertThat(firstHit.lexicalRank()).isNotNull();
            assertThat(firstHit.lexicalRank()).isEqualTo(1);
            assertThat(firstHit.lexicalScore()).isNotNull();
            assertThat(firstHit.lexicalScore()).isPositive();
            assertThat(firstHit.vectorRank()).isNull();
            assertThat(firstHit.vectorScoreRaw()).isNull();
            assertThat(firstHit.vectorScoreTransformed()).isNull();
            assertThat(firstHit.rrfScore()).isPositive();
            assertThat(firstHit.admittedBy()).isEqualTo("lexical");

            // Also verify Chat.Source contains entryId
            var chat = new Chat(knowledge, settings, http);
            var response = (Chat.Answer) chat.chat(new Chat.Question("奶茶点单小程序后端")).getBody();
            assertThat(response).isNotNull();
            assertThat(response.sources()).isNotEmpty();
            assertThat(response.sources().getFirst().entryId()).isEqualTo(firstHit.entryId());
        }
    }

    @Test
    void onnxEmbeddingInferenceIfSpikeModelPresent() throws Exception {
        Path modelDir = Path.of("models");
        if (!Files.isRegularFile(modelDir.resolve("model.onnx")) || !Files.isRegularFile(modelDir.resolve("vocab.txt"))) {
            Path spikeDir = Path.of("/home/hatch/workspace/projects/spike/model");
            if (Files.isRegularFile(spikeDir.resolve("model.onnx")) && Files.isRegularFile(spikeDir.resolve("vocab.txt"))) {
                modelDir = spikeDir;
            }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isRegularFile(modelDir.resolve("model.onnx")) && Files.isRegularFile(modelDir.resolve("vocab.txt")),
                "ONNX model and vocab files not present; skipping ONNX native-inference test"
        );

        try (var onnx = new OnnxEmbedding(modelDir)) {
            assertThat(onnx.dimensions()).isEqualTo(512);
            assertThat(onnx.mode()).isEqualTo("onnx");

            float[] docVec = onnx.embedDocument("测试文档向量");
            assertThat(docVec).hasSize(512);
            double sumSq = 0;
            for (float v : docVec) sumSq += (double) v * v;
            assertThat(Math.sqrt(sumSq)).isBetween(0.999, 1.001);

            float[] queryVec = onnx.embedQuery("测试查询向量");
            assertThat(queryVec).hasSize(512);
            sumSq = 0;
            for (float v : queryVec) sumSq += (double) v * v;
            assertThat(Math.sqrt(sumSq)).isBetween(0.999, 1.001);
        }
    }
}
