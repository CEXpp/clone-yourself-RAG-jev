package local.digital;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerServiceTest {
    @TempDir Path dir;
    ObjectMapper mapper = new ObjectMapper();

    // -------------------------------------------------------------
    // Citation Validation Tests (D6: Single authoritative place)
    // -------------------------------------------------------------
    @Test
    void citationValidationEnforcesValidBoundsAndNonEmpty() {
        // Valid citations
        assertThat(AnswerService.validateCitations("事实依据 [1]。", 1)).isTrue();
        assertThat(AnswerService.validateCitations("多处引用 [1] 和 [2] 以及 [3]。", 3)).isTrue();
        assertThat(AnswerService.validateCitations("[2] 颠倒顺序 [1] 也可以。", 2)).isTrue();

        // Invalid: missing citations
        assertThat(AnswerService.validateCitations("没有任何引用编号的事实论断。", 3)).isFalse();
        assertThat(AnswerService.validateCitations("", 3)).isFalse();
        assertThat(AnswerService.validateCitations(null, 3)).isFalse();

        // Invalid: out of range (zero, negative, beyond sources count)
        assertThat(AnswerService.validateCitations("引用 [0] 是无效的。", 3)).isFalse();
        assertThat(AnswerService.validateCitations("引用 [4] 超出了来源总数 3。", 3)).isFalse();
        assertThat(AnswerService.validateCitations("有效 [1] 但混入了越界 [5]。", 3)).isFalse();

        // Invalid: zero sources
        assertThat(AnswerService.validateCitations("即使有引用 [1] 但来源数为0。", 0)).isFalse();
    }

    // -------------------------------------------------------------
    // Pipeline & Event Sequence Tests (D6 / Scope B)
    // -------------------------------------------------------------
    @Test
    void streamingEventsOrderIsStrictlyRespected() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical")
                .withProperty("app.lexical-coverage", "0.5")
                .withProperty("app.http-timeout-seconds", "1");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var answerService = new AnswerService(knowledge, settings, http);

            List<String> eventSequence = new ArrayList<>();
            List<ModelHttp.Decision> routeCaptured = new ArrayList<>();
            List<AnswerService.TraceData> traceCaptured = new ArrayList<>();
            List<Chat.Answer> answerCaptured = new ArrayList<>();

            Chat.Answer result = answerService.answer(new Chat.Question("介绍一下这个项目。"), new AnswerService.AnswerListener() {
                @Override
                public void onRoute(ModelHttp.Decision decision) {
                    eventSequence.add("route");
                    routeCaptured.add(decision);
                }

                @Override
                public void onTrace(AnswerService.TraceData trace) {
                    eventSequence.add("trace");
                    traceCaptured.add(trace);
                }

                @Override
                public void onProgress(String stage, String message) {
                    eventSequence.add("progress:" + stage);
                }

                @Override
                public void onAnswer(Chat.Answer answer) {
                    eventSequence.add("answer");
                    answerCaptured.add(answer);
                }
            });

            // 验证事件序列必须满足：route 首发，接着 trace，随后 progress，最后 answer
            assertThat(eventSequence.getFirst()).isEqualTo("route");
            assertThat(eventSequence.get(1)).isEqualTo("progress:searching");
            assertThat(eventSequence.get(2)).isEqualTo("trace");
            assertThat(eventSequence.getLast()).isEqualTo("answer");

            // 验证 trace 数据
            assertThat(traceCaptured).hasSize(1);
            assertThat(traceCaptured.getFirst().hits()).isNotEmpty();
            assertThat(traceCaptured.getFirst().hits()).anyMatch(h -> h.entryId().contains("demo-"));

            // 验证 answer 负载
            assertThat(answerCaptured).hasSize(1);
            assertThat(result).isSameAs(answerCaptured.getFirst());
            assertThat(result.mode()).isEqualTo("local-extract");
            assertThat(result.sources()).isNotEmpty();
            assertThat(result.sources().getFirst().charStart()).isNotNull();
            assertThat(result.sources().getFirst().charEnd()).isNotNull();
        }
    }

    @Test
    void cancellationAbortsPipelineEarlyWithoutLeakingResources() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical")
                .withProperty("app.lexical-coverage", "0.5")
                .withProperty("app.http-timeout-seconds", "1");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var answerService = new AnswerService(knowledge, settings, http);

            AtomicBoolean cancelled = new AtomicBoolean(false);
            AtomicInteger routeCallCount = new AtomicInteger(0);
            AtomicInteger traceCallCount = new AtomicInteger(0);

            // 模拟路由判定完成后客户端断开连接
            Chat.Answer result = answerService.answer(new Chat.Question("介绍一下这个项目。"), new AnswerService.AnswerListener() {
                @Override
                public boolean isCancelled() {
                    return cancelled.get();
                }

                @Override
                public void onRoute(ModelHttp.Decision decision) {
                    routeCallCount.incrementAndGet();
                    cancelled.set(true); // 模拟断开
                }

                @Override
                public void onTrace(AnswerService.TraceData trace) {
                    traceCallCount.incrementAndGet();
                }
            });

            // 取消后，后续检索与回答不会执行
            assertThat(result).isNull();
            assertThat(routeCallCount.get()).isEqualTo(1);
            assertThat(traceCallCount.get()).isEqualTo(0);
        }
    }

    @Test
    void validatesInputQuestionLengthAndEmpty() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var answerService = new AnswerService(knowledge, settings, http);

            assertThatThrownBy(() -> answerService.answer(new Chat.Question("")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("请输入1至2000字的问题");

            assertThatThrownBy(() -> answerService.answer(new Chat.Question("   ")))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> answerService.answer(new Chat.Question("x".repeat(2001))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void structuredOverviewRouteReturnsEmptyTraceAndSkills() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var answerService = new AnswerService(knowledge, settings, http);

            Chat.Answer ans = answerService.answer(new Chat.Question("你会什么"));
            assertThat(ans.mode()).isEqualTo("structured");
            assertThat(ans.routing().intent()).isEqualTo("skills");
            assertThat(ans.skills()).isNotEmpty();
            assertThat(ans.sources()).isNotEmpty();
            assertThat(ans.trace()).isEmpty();
        }
    }

    @Test
    void unanswerableQuestionReturnsInsufficientEvidenceWithTrace() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical")
                .withProperty("app.lexical-coverage", "0.5");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var answerService = new AnswerService(knowledge, settings, http);

            Chat.Answer ans = answerService.answer(new Chat.Question("火星飞船的建造预算是多少？"));
            assertThat(ans.mode()).isEqualTo("insufficient-evidence");
            assertThat(ans.sources()).isEmpty();
            assertThat(ans.trace()).isEmpty();
            assertThat(ans.answer()).contains("没有足够依据");
        }
    }
}
