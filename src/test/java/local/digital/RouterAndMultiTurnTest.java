package local.digital;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouterAndMultiTurnTest {
    @TempDir Path dir;
    ObjectMapper mapper = new ObjectMapper();

    // -------------------------------------------------------------
    // Router v2: Layer 1 Exact Match Tests
    // -------------------------------------------------------------
    @Test
    void routerLayer1ExactMatchRoutesCorrectly() {
        // Skills
        for (String q : List.of("你会什么", "技能", "你有哪些技能", "whatcanyoudo", "skills")) {
            ModelHttp.Decision d = Chat.local(q, "local");
            assertThat(d.intent()).as("Failed for: " + q).isEqualTo("skills");
            assertThat(d.confidence()).isEqualTo(-1);
            assertThat(d.explanation()).isEqualTo("layer1:exact-match");
        }

        // Profile
        for (String q : List.of("介绍一下自己", "你是谁", "自我介绍", "介绍自己", "profile")) {
            ModelHttp.Decision d = Chat.local(q, "local");
            assertThat(d.intent()).as("Failed for: " + q).isEqualTo("profile");
            assertThat(d.confidence()).isEqualTo(-1);
            assertThat(d.explanation()).isEqualTo("layer1:exact-match");
        }

        // Projects
        for (String q : List.of("有哪些项目", "你做过哪些项目", "项目经历", "projects")) {
            ModelHttp.Decision d = Chat.local(q, "local");
            assertThat(d.intent()).as("Failed for: " + q).isEqualTo("projects");
            assertThat(d.confidence()).isEqualTo(-1);
            assertThat(d.explanation()).isEqualTo("layer1:exact-match");
        }

        // Unknown
        for (String q : List.of("你好", "hello", "hi")) {
            ModelHttp.Decision d = Chat.local(q, "local");
            assertThat(d.intent()).as("Failed for: " + q).isEqualTo("unknown");
            assertThat(d.confidence()).isEqualTo(-1);
            assertThat(d.explanation()).isEqualTo("layer1:exact-match");
        }
    }

    // -------------------------------------------------------------
    // Router v2: Layer 2 Conservative Overview Intent Tests
    // -------------------------------------------------------------
    @Test
    void routerLayer2ConservativeOverviewIntentRoutesToStructured() {
        // Clear skills overview phrasing
        List<String> skillOverviews = List.of(
                "列出你的全部技能",
                "有哪些技能清单",
                "全部技能列表",
                "你的所有能力清单",
                "汇总一下技能"
        );
        for (String q : skillOverviews) {
            ModelHttp.Decision d = Chat.local(q, "local");
            assertThat(d.intent()).as("Failed for: " + q).isEqualTo("skills");
            assertThat(d.confidence()).isEqualTo(-1);
            assertThat(d.explanation()).isEqualTo("layer2:overview:skills");
        }

        // Clear projects overview phrasing
        List<String> projectOverviews = List.of(
                "列出所有项目",
                "你的全部项目清单",
                "全部项目列表",
                "参与过哪些项目",
                "项目清单汇总"
        );
        for (String q : projectOverviews) {
            ModelHttp.Decision d = Chat.local(q, "local");
            assertThat(d.intent()).as("Failed for: " + q).isEqualTo("projects");
            assertThat(d.confidence()).isEqualTo(-1);
            assertThat(d.explanation()).isEqualTo("layer2:overview:projects");
        }

        // Clear profile overview phrasing
        List<String> profileOverviews = List.of(
                "详细介绍一下你的个人背景",
                "说说你的个人信息简介",
                "个人基本情况概述"
        );
        for (String q : profileOverviews) {
            ModelHttp.Decision d = Chat.local(q, "local");
            assertThat(d.intent()).as("Failed for: " + q).isEqualTo("profile");
            assertThat(d.confidence()).isEqualTo(-1);
            assertThat(d.explanation()).isEqualTo("layer2:overview:profile");
        }
    }

    // -------------------------------------------------------------
    // Router v2: Counterexamples that MUST fall through to retrieval
    // -------------------------------------------------------------
    @Test
    void routerCounterexamplesMustGoToRetrieval() {
        // 5 binding counterexamples from Scope A
        List<String> counterexamples = List.of(
                "你会 Java 吗？",
                "你的项目为什么选 Lucene？",
                "除了 Java，你还会什么？",
                "介绍一下这个项目。",
                "他的 Spring Boot 到什么水平？"
        );

        for (String q : counterexamples) {
            ModelHttp.Decision d = Chat.local(q, "local");
            assertThat(d.intent()).as("Counterexample MUST go to retrieval: " + q).isEqualTo("retrieve");
            assertThat(d.confidence()).isEqualTo(-1);
            assertThat(d.explanation()).startsWith("layer2:retrieve(");
        }
    }

    // -------------------------------------------------------------
    // Router v2: Safety gates, mixed categories, negation, comparison
    // -------------------------------------------------------------
    @Test
    void routerSafetyGatesDisqualifyToRetrieval() {
        // Mixed categories
        ModelHttp.Decision mixed = Chat.local("你的技能和项目有哪些", "local");
        assertThat(mixed.intent()).isEqualTo("retrieve");
        assertThat(mixed.explanation()).contains("mixed-categories");

        // Negation / exclusion
        ModelHttp.Decision neg = Chat.local("不包含Java的技能有哪些", "local");
        assertThat(neg.intent()).isEqualTo("retrieve");
        assertThat(neg.explanation()).contains("negation");

        // Comparison / superlative
        ModelHttp.Decision cmp = Chat.local("哪个项目最难？", "local");
        assertThat(cmp.intent()).isEqualTo("retrieve");
        assertThat(cmp.explanation()).contains("comparison");

        // Specific entity
        ModelHttp.Decision ent = Chat.local("你会 Python 吗？", "local");
        assertThat(ent.intent()).isEqualTo("retrieve");
        assertThat(ent.explanation()).contains("specific-entity");

        // Off topic
        ModelHttp.Decision off = Chat.local("今天天气怎么样？", "local");
        assertThat(off.intent()).isEqualTo("retrieve");
    }

    // -------------------------------------------------------------
    // Fix 3: Constrained overview regression tests & narrowing guard
    // -------------------------------------------------------------
    @Test
    void routerConstrainedOverviewPhrasingsMustRouteToRetrieval() {
        List<String> constrainedOverviews = List.of(
                "列出支付项目",
                "有哪些金融项目",
                "做过哪些电商项目",
                "做过哪些小程序项目",
                "列出你的前端技能",
                "你有哪些大数据技能",
                "介绍一下你的教育背景",
                "参与过哪些推荐系统项目"
        );

        for (String q : constrainedOverviews) {
            ModelHttp.Decision d = Chat.local(q, "local");
            assertThat(d.intent())
                    .as("Constrained overview phrasing MUST route to retrieval: " + q)
                    .isEqualTo("retrieve");
            assertThat(d.explanation()).contains("retrieve");
        }
    }

    @Test
    void deterministicNarrowingGuardEnforcesRetrievalEvenIfRemoteDecidesOverview() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical")
                .withProperty("app.http-timeout-seconds", "1");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var answerService = new AnswerService(knowledge, settings, http);

            // "列出支付项目" routes to retrieval, NOT the unrestricted projects overview
            Chat.Answer ans = answerService.answer(new Chat.Question("列出支付项目"));
            assertThat(ans.routing().intent()).isEqualTo("retrieve");
            assertThat(ans.mode()).isNotEqualTo("structured");

            // "列出区块链项目" carries constraint "区块链" (not in corpus), routes to retrieval and yields insufficient-evidence
            Chat.Answer ansBlockchain = answerService.answer(new Chat.Question("列出区块链项目"));
            assertThat(ansBlockchain.routing().intent()).isEqualTo("retrieve");
            assertThat(ansBlockchain.mode()).isEqualTo("insufficient-evidence");
            assertThat(ansBlockchain.sources()).isEmpty();
        }
    }

    // -------------------------------------------------------------
    // Scope B: Multi-turn Resolution End-to-End Tests
    // -------------------------------------------------------------
    @Test
    void multiTurnUniqueTopicRewriteEndToEndAtChatLevel() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical")
                .withProperty("app.lexical-coverage", "0.5")
                .withProperty("app.http-timeout-seconds", "1");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var chat = new Chat(knowledge, settings, http);

            // History with exactly ONE project topic: demo-project-milktea
            List<Chat.Turn> history = List.of(
                    new Chat.Turn("user", "奶茶点单小程序后端的主要功能是什么？"),
                    new Chat.Turn("assistant", "奶茶点单小程序后端（虚构示例项目）：为虚构奶茶店做的点单系统后端：Spring Boot 提供商品、购物车与订单接口，微信小程序为前端，管理端可维护菜单。")
            );

            // Current question containing reference "这个项目"
            var req = new Chat.Question("介绍一下这个项目。", history);
            var resp = (Chat.Answer) chat.chat(req).getBody();

            assertThat(resp).isNotNull();
            assertThat(resp.resolvedQuestion()).isEqualTo("介绍一下奶茶点单小程序后端。");
            assertThat(resp.routing().intent()).isEqualTo("retrieve");
            assertThat(resp.sources()).isNotEmpty();
            assertThat(resp.sources().getFirst().title()).contains("奶茶");
        }
    }

    @Test
    void multiTurnPronounRewriteEndToEndAtChatLevel() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical")
                .withProperty("app.lexical-coverage", "0.5")
                .withProperty("app.http-timeout-seconds", "1");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var chat = new Chat(knowledge, settings, http);

            List<Chat.Turn> history = List.of(
                    new Chat.Turn("user", "奶茶点单小程序后端怎么样？"),
                    new Chat.Turn("assistant", "奶茶点单小程序后端为虚构奶茶店做的点单系统后端...")
            );

            var req = new Chat.Question("它的技术栈是什么？", history);
            var resp = (Chat.Answer) chat.chat(req).getBody();

            assertThat(resp).isNotNull();
            assertThat(resp.resolvedQuestion()).isEqualTo("奶茶点单小程序后端的技术栈是什么？");
        }
    }

    @Test
    void multiTurnAmbiguousHistoryDoesNotRewrite() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical")
                .withProperty("app.lexical-coverage", "0.5")
                .withProperty("app.http-timeout-seconds", "1");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var chat = new Chat(knowledge, settings, http);

            // History discussing TWO project entries: demo-project-milktea and demo-project-dianping
            List<Chat.Turn> history = List.of(
                    new Chat.Turn("user", "你做过哪些项目？"),
                    new Chat.Turn("assistant", "我做过奶茶点单小程序后端和点评类应用。")
            );

            var req = new Chat.Question("介绍一下这个项目。", history);
            var resp = (Chat.Answer) chat.chat(req).getBody();

            assertThat(resp).isNotNull();
            // Must NOT rewrite when ambiguous
            assertThat(resp.resolvedQuestion()).isNull();
        }
    }

    @Test
    void multiTurnHistoryWithoutReferenceDoesNotRewrite() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical")
                .withProperty("app.lexical-coverage", "0.5")
                .withProperty("app.http-timeout-seconds", "1");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var chat = new Chat(knowledge, settings, http);

            List<Chat.Turn> history = List.of(
                    new Chat.Turn("user", "奶茶点单小程序后端怎么样？"),
                    new Chat.Turn("assistant", "奶茶点单小程序后端为虚构奶茶店做的点单系统后端...")
            );

            // Standalone question without any reference
            var req = new Chat.Question("订单超时关单是怎么实现的？", history);
            var resp = (Chat.Answer) chat.chat(req).getBody();

            assertThat(resp).isNotNull();
            assertThat(resp.resolvedQuestion()).isNull();
        }
    }

    @Test
    void multiTurnOversizedAndGarbageHistorySafelyIgnoredAndClamped() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical")
                .withProperty("app.lexical-coverage", "0.5")
                .withProperty("app.http-timeout-seconds", "1");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var chat = new Chat(knowledge, settings, http);

            List<Chat.Turn> dirtyHistory = new ArrayList<>();
            // Invalid roles
            dirtyHistory.add(new Chat.Turn("attacker", "<script>alert(1)</script>"));
            dirtyHistory.add(new Chat.Turn(null, "null role text"));
            dirtyHistory.add(new Chat.Turn("invalid-role", "some content"));
            // Oversized turn > 1000 chars
            dirtyHistory.add(new Chat.Turn("user", "X".repeat(5000)));
            // 20 turns
            for (int i = 0; i < 20; i++) {
                dirtyHistory.add(new Chat.Turn("assistant", "filler turn " + i));
            }

            var req = new Chat.Question("介绍一下这个项目。", dirtyHistory);
            var resp = (Chat.Answer) chat.chat(req).getBody();

            assertThat(resp).isNotNull();
            // Garbage history does not identify a unique topic -> no rewrite, no crash
            assertThat(resp.resolvedQuestion()).isNull();
        }
    }

    @Test
    void chatWithoutHistoryBehavesAsBefore() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical")
                .withProperty("app.lexical-coverage", "0.5")
                .withProperty("app.http-timeout-seconds", "1");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var chat = new Chat(knowledge, settings, http);

            var req1 = new Chat.Question("你会什么");
            var resp1 = (Chat.Answer) chat.chat(req1).getBody();
            assertThat(resp1.mode()).isEqualTo("structured");
            assertThat(resp1.routing().intent()).isEqualTo("skills");
            assertThat(resp1.resolvedQuestion()).isNull();

            var req2 = new Chat.Question("介绍一下这个项目。");
            var resp2 = (Chat.Answer) chat.chat(req2).getBody();
            assertThat(resp2.resolvedQuestion()).isNull();
        }
    }
}
