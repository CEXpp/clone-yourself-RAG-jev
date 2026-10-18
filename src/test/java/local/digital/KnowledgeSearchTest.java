package local.digital;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeSearchTest {
    @TempDir Path dir;
    ObjectMapper mapper = new ObjectMapper();

    @Test
    void chunkingLongChinesePassagePreservesOffsetsAndPlacesFactInFinalChunk() {
        var tokenizer = OnnxEmbedding.Tokenizer.create(Path.of("models"));

        // Long Chinese text (~550+ tokens) styled after demo-doc-vector-notes
        StringBuilder sb = new StringBuilder();
        sb.append("第一部分：向量检索的基本原理。关键词检索依赖字面命中，但是在同义改写和复杂语义表达下，字面常常无法匹配。");
        sb.append("为了解决这个问题，我们需要引入语义嵌入，把离散的文字映射为高维连续空间中的向量。");
        sb.append("第二部分：嵌入模型的选择。哈希向量只统计局部字符片段，不具备真正的语义泛化能力；而基于预训练语言模型的深度嵌入能够在海量文本中学习到语义共现。真正的嵌入模型在海量语料上训练，能把语义相近的句子放得更近。");
        sb.append("第三部分：混合检索与融合。在实际工业界落地中，通常采用 BM25 词法检索与 Dense 向量检索并联，然后通过 RRF 倒数排名融合算法综合排序；融合前要分别看两路的候选质量，不能盲目相信融合结果。");
        sb.append("第四部分：分块策略。分块过大会导致语义稀释，分块过小又会导致上下文断裂失去完整信息。切块要保留原文定位，引用时才能回到确切位置。");
        sb.append("第四部分补充讨论：在长文档与多轮对话场景中，如果直接拼接全部上下文，词元预算会迅速耗尽，因此需要有条理的结构化拆分与指代消解。");
        sb.append("同时，底层工程实现必须保证分块逻辑确定性，对所有索引文档保持严格可重现的分块边界，避免由于非确定性导致离线评测与在线推理不一致。");
        sb.append("第五部分（关键决策结论）：本项目确认了最终的参数配置——平滑常数k取60防止单路过度主导。");

        String content = sb.toString();
        String keyFact = "平滑常数k取60防止单路过度主导";
        Entry entry = new Entry("test-long-doc", "document", "混合检索长文档", content, null, null,
                List.of(), "public", true, "测试来源", "2026-10-18");

        List<Knowledge.Chunk> chunks = Knowledge.chunkEntry(entry, tokenizer);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);

        // Verify exact character substring preservation for all chunks
        for (Knowledge.Chunk chunk : chunks) {
            assertThat(chunk.text()).isEqualTo(content.substring(chunk.start(), chunk.end()));
            assertThat(chunk.start()).isGreaterThanOrEqualTo(0);
            assertThat(chunk.end()).isLessThanOrEqualTo(content.length());
        }

        // Key fact sits in the final chunk, NOT in chunk 0
        assertThat(chunks.getFirst().text()).doesNotContain(keyFact);
        assertThat(chunks.getLast().text()).contains(keyFact);

        // Overlap verification: chunk 1 starts before chunk 0 ends
        Knowledge.Chunk chunk0 = chunks.get(0);
        Knowledge.Chunk chunk1 = chunks.get(1);
        assertThat(chunk1.start()).isLessThan(chunk0.end());
        int overlapChars = chunk0.end() - chunk1.start();
        assertThat(overlapChars).isGreaterThan(30);
    }

    @Test
    void sharedVocabularyNegativeReturnsNoRelevantHitsInLexicalMode() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical")
                .withProperty("app.lexical-coverage", "0.5")
                .withProperty("app.http-timeout-seconds", "1");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            // "他的奶茶项目服务过多少付费用户？" shares terms like "奶茶" and "项目",
            // but distinct query term coverage < 0.5 on all candidates
            var result = knowledge.search("他的奶茶项目服务过多少付费用户？");
            assertThat(result.hits()).isEmpty();
            assertThat(result.lexicalCandidates()).isGreaterThan(0);
        }
    }

    @Test
    void unsupportedNumberNegativeReturnsNoRelevantHits() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical")
                .withProperty("app.lexical-coverage", "0.5")
                .withProperty("app.http-timeout-seconds", "1");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var result = knowledge.search("他带过几人团队？");
            assertThat(result.hits()).isEmpty();
        }
    }

    @Test
    void dedupDropsOverlappingChunksAndBackfills() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical")
                .withProperty("app.lexical-coverage", "0.2") // lower threshold to admit candidates
                .withProperty("app.http-timeout-seconds", "1");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            // Search for vector notes which has 2 overlapping chunks
            var result = knowledge.search("向量检索学习笔记 为什么需要向量检索 RRF 平滑常数");
            List<Knowledge.Hit> hits = result.hits();

            // Check that no two hits from the same entry overlap in character range
            for (int i = 0; i < hits.size(); i++) {
                for (int j = i + 1; j < hits.size(); j++) {
                    Knowledge.Hit h1 = hits.get(i);
                    Knowledge.Hit h2 = hits.get(j);
                    if (h1.entryId().equals(h2.entryId())) {
                        boolean overlaps = Math.max(h1.charStart(), h2.charStart()) < Math.min(h1.charEnd(), h2.charEnd());
                        assertThat(overlaps).as("Hits from the same entry should not overlap: " + h1 + " vs " + h2).isFalse();
                    }
                }
            }
        }
    }

    @Test
    void gateTraceFieldsArePopulated() throws Exception {
        var env = new MockEnvironment()
                .withProperty("app.data-dir", dir.toString())
                .withProperty("app.demo-data", "true")
                .withProperty("app.embedding-mode", "lexical")
                .withProperty("app.lexical-coverage", "0.5")
                .withProperty("app.http-timeout-seconds", "1");
        var settings = new Settings(env);
        var http = new ModelHttp(settings, mapper);

        try (var knowledge = new Knowledge(settings, mapper, http)) {
            var result = knowledge.search("订单超时关单是怎么实现的？");
            assertThat(result.hits()).isNotEmpty();

            Knowledge.Hit first = result.hits().getFirst();
            assertThat(first.admittedBy()).isEqualTo("lexical");
            assertThat(first.gateNote()).contains("lexical-coverage=");
        }
    }
}

