package local.digital;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.util.*;

@Service
@Lazy
public class Knowledge implements AutoCloseable {
    private final Settings settings;
    private final List<Entry> entries;
    private final List<Chunk> chunks = new ArrayList<>();
    private final SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer();
    private final Directory directory;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final EmbeddingProvider provider;
    private final String effectiveMode;
    private final int dimensions;

    @org.springframework.beans.factory.annotation.Autowired
    public Knowledge(Settings settings, ObjectMapper mapper, ModelHttp http) throws Exception {
        this(settings, mapper, http, null, null);
    }

    public Knowledge(Settings settings, ObjectMapper mapper, ModelHttp http, Path indexDir, String modeOverride) throws Exception {
        this.settings = settings;
        List<Entry> candidates = new ArrayList<>();
        if (Boolean.parseBoolean(settings.get("demo-data"))) {
            try (var stream = new ClassPathResource("demo/entries.json").getInputStream()) {
                candidates.addAll(mapper.readValue(stream, new TypeReference<List<Entry>>() {}));
            }
        }
        Path root = Path.of(settings.get("data-dir"));
        Path publicDir = root.resolve("public");
        if (Files.isDirectory(publicDir)) {
            try (var files = Files.list(publicDir)) {
                for (Path p : files.filter(p -> p.toString().endsWith(".json")).sorted().toList())
                    candidates.addAll(mapper.readValue(p.toFile(), new TypeReference<List<Entry>>() {}));
            }
        }
        // Exclude before parsing content, chunking, embedding, fixed answers, or HTTP transmission.
        LinkedHashMap<String, Entry> approved = new LinkedHashMap<>();
        for (Entry e : candidates) if (e.approved()) {
            e.validate();
            if (approved.putIfAbsent(e.id(), e) != null)
                throw new IllegalArgumentException("公开条目id重复；请在本地公开存储中移除旧副本后重启");
        }
        entries = List.copyOf(approved.values());

        // Token-aware chunking
        OnnxEmbedding.Tokenizer tokenizer = OnnxEmbedding.Tokenizer.create(Path.of(settings.modelDir()));
        for (Entry e : entries) {
            chunks.addAll(chunkEntry(e, tokenizer));
        }

        ProviderResolution resolution = resolveProvider(settings, http, chunks, modeOverride);
        this.provider = resolution.provider();
        this.effectiveMode = resolution.effectiveMode();
        List<float[]> vectors = resolution.vectors();
        this.dimensions = vectors.isEmpty() ? (provider != null ? provider.dimensions() : 0) : vectors.getFirst().length;

        Path targetIndexDir;
        if (indexDir != null) {
            targetIndexDir = indexDir;
        } else {
            String custom = settings.indexDir();
            targetIndexDir = (custom != null && !custom.isBlank()) ? Path.of(custom) : root.resolve("index-public");
        }
        Files.createDirectories(targetIndexDir);
        directory = FSDirectory.open(targetIndexDir);
        var config = new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        config.setSimilarity(new BM25Similarity());
        try (var writer = new IndexWriter(directory, config)) {
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i); var doc = new Document();
                doc.add(new StoredField("ordinal", i));
                doc.add(new TextField("text", c.entry.title() + " " + c.text, Field.Store.NO));
                if (!vectors.isEmpty()) {
                    doc.add(new KnnFloatVectorField("vector", vectors.get(i), VectorSimilarityFunction.COSINE));
                }
                writer.addDocument(doc);
            }
        }
        reader = DirectoryReader.open(directory); searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new BM25Similarity());
    }

    private static ProviderResolution resolveProvider(Settings settings, ModelHttp http, List<Chunk> chunks, String modeOverride) throws Exception {
        String mode = (modeOverride != null && !modeOverride.isBlank())
                ? modeOverride.toLowerCase(Locale.ROOT)
                : settings.embeddingMode().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "lexical", "lexical-only" -> new ProviderResolution(null, "lexical-only", List.of());
            case "hash" -> {
                var p = new HashEmbedding();
                yield new ProviderResolution(p, "hash", embedChunks(p, chunks));
            }
            case "http" -> {
                if (!settings.hasEmbedding()) {
                    throw new IllegalStateException("显式指定 embedding-mode=http，但未配置 embedding-key 或 embedding-model");
                }
                var p = new HttpEmbedding(http);
                yield new ProviderResolution(p, "http", embedChunks(p, chunks));
            }
            case "onnx" -> {
                Path modelDir = Path.of(settings.modelDir());
                var p = new OnnxEmbedding(modelDir);
                yield new ProviderResolution(p, "onnx", embedChunks(p, chunks));
            }
            case "auto" -> resolveAuto(settings, http, chunks);
            default -> throw new IllegalArgumentException("未知 embedding-mode: " + mode);
        };
    }

    private static ProviderResolution resolveAuto(Settings settings, ModelHttp http, List<Chunk> chunks) {
        if (settings.hasEmbedding()) {
            try {
                var p = new HttpEmbedding(http);
                List<float[]> vecs = embedChunks(p, chunks);
                return new ProviderResolution(p, "http", vecs);
            } catch (Exception failure) {
                System.err.println("auto 模式下 HTTP embedding 失败，尝试回退到 ONNX: " + failure.getMessage());
            }
        }
        Path modelDir = Path.of(settings.modelDir());
        Path modelFile = modelDir.resolve("model.onnx");
        Path vocabFile = modelDir.resolve("vocab.txt");
        if (Files.isRegularFile(modelFile) && Files.isRegularFile(vocabFile)) {
            OnnxEmbedding p = null;
            try {
                p = new OnnxEmbedding(modelDir);
                List<float[]> vecs = embedChunks(p, chunks);
                return new ProviderResolution(p, "onnx", vecs);
            } catch (Exception failure) {
                if (p != null) {
                    try { p.close(); } catch (Exception ignored) {}
                }
                System.err.println("auto 模式下 ONNX 加载或推理失败，尝试回退到 lexical-only: " + failure.getMessage());
            }
        }
        return new ProviderResolution(null, "lexical-only", List.of());
    }

    private static List<float[]> embedChunks(EmbeddingProvider provider, List<Chunk> chunks) throws Exception {
        if (chunks.isEmpty()) return List.of();
        List<float[]> vectors = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            vectors.add(provider.embedDocument(c.entry.title() + " " + c.text));
        }
        if (vectors.stream().map(v -> v.length).distinct().count() > 1) {
            throw new IllegalStateException("向量维度不一致");
        }
        return vectors;
    }

    public List<Entry> ofType(String type) { return entries.stream().filter(e -> e.type().equals(type)).toList(); }
    public int count() { return entries.size(); }
    public String embeddingMode() { return effectiveMode; }

    public static Set<String> analyzeText(SmartChineseAnalyzer analyzer, String text) {
        Set<String> terms = new LinkedHashSet<>();
        try (var stream = analyzer.tokenStream("text", text)) {
            var term = stream.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                String t = term.toString().strip();
                if (!t.isEmpty()) terms.add(t);
            }
            stream.end();
        } catch (Exception ignored) {}
        return terms;
    }

    public static List<Chunk> chunkEntry(Entry e, OnnxEmbedding.Tokenizer tokenizer) {
        String content = e.content();
        if (content.isEmpty()) {
            return List.of(new Chunk(e, "", 0, 0, 0));
        }
        List<OnnxEmbedding.TokenSpan> spans = tokenizer.tokenizeWithSpans(content);
        if (spans.isEmpty()) {
            return List.of(new Chunk(e, content, 0, content.length(), 0));
        }

        int titleTokens = Math.max(0, tokenizer.tokenize(e.title()).size() - 2);
        // Budget <= 400 tokens including title and special tokens ([CLS] and [SEP])
        // Reserve 2 tokens safety margin
        int maxChunkTokens = Math.max(64, 400 - 2 - titleTokens - 2);
        int overlapTokens = 64;

        List<Chunk> result = new ArrayList<>();
        int tokenIdx = 0;
        int chunkIdx = 0;

        while (tokenIdx < spans.size()) {
            int endTokenIdx = Math.min(tokenIdx + maxChunkTokens, spans.size());
            while (endTokenIdx < spans.size() && spans.get(endTokenIdx).token().startsWith("##")) {
                endTokenIdx++;
            }

            int charStart = spans.get(tokenIdx).start();
            int charEnd = (endTokenIdx >= spans.size()) ? content.length() : spans.get(endTokenIdx - 1).end();

            String chunkText = content.substring(charStart, charEnd);
            result.add(new Chunk(e, chunkText, charStart, charEnd, chunkIdx++));

            if (endTokenIdx >= spans.size()) {
                break;
            }

            int nextTokenIdx = endTokenIdx - overlapTokens;
            if (nextTokenIdx <= tokenIdx) {
                nextTokenIdx = tokenIdx + 1;
            }
            while (nextTokenIdx < spans.size() && spans.get(nextTokenIdx).token().startsWith("##")) {
                nextTokenIdx++;
            }
            if (nextTokenIdx <= tokenIdx) {
                nextTokenIdx = tokenIdx + 1;
            }
            tokenIdx = nextTokenIdx;
        }

        return result;
    }

    public SearchResult search(String question) throws Exception {
        return searchWithCandidates(question);
    }

    public SearchResult searchWithCandidates(String question) throws Exception {
        var builder = new BooleanQuery.Builder();
        int terms = 0;
        try (var stream = analyzer.tokenStream("text", question)) {
            var term = stream.addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken() && terms++ < 64)
                builder.add(new TermQuery(new Term("text", term.toString())), BooleanClause.Occur.SHOULD);
            stream.end();
        }
        TopDocs lexical = searcher.search(builder.build(), 12);
        TopDocs vector = new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), new ScoreDoc[0]);
        String mode = effectiveMode;
        if (provider != null && dimensions > 0) {
            try {
                float[] query = provider.embedQuery(question);
                if (query.length != dimensions) throw new IllegalStateException("查询向量维度已改变");
                vector = searcher.search(new KnnFloatVectorQuery("vector", query, 12), 12);
                mode = provider.mode();
            } catch (Exception unavailable) {
                mode = "lexical-only";
                vector = new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), new ScoreDoc[0]);
            }
        } else {
            mode = "lexical-only";
        }

        Map<Integer, Integer> lexicalRanks = new HashMap<>();
        Map<Integer, Float> lexicalScores = new HashMap<>();
        Set<String> queryTerms = analyzeText(analyzer, question);
        Map<Integer, Double> lexicalCoverages = new HashMap<>();
        Set<Integer> lexicalAdmitted = new HashSet<>();
        double lexicalCoverageThreshold = (settings != null) ? settings.lexicalCoverage() : 0.5;

        for (int i = 0; i < lexical.scoreDocs.length; i++) {
            ScoreDoc sd = lexical.scoreDocs[i];
            lexicalRanks.put(sd.doc, i + 1);
            lexicalScores.put(sd.doc, sd.score);

            int ordinal = searcher.storedFields().document(sd.doc).getField("ordinal").numericValue().intValue();
            Chunk chunk = chunks.get(ordinal);
            String fullText = chunk.entry().title() + " " + chunk.text();
            Set<String> chunkTerms = analyzeText(analyzer, fullText);

            int matches = 0;
            for (String qt : queryTerms) {
                if (chunkTerms.contains(qt)) matches++;
            }
            double coverage = queryTerms.isEmpty() ? 0.0 : (double) matches / queryTerms.size();
            lexicalCoverages.put(sd.doc, coverage);
            if (coverage >= lexicalCoverageThreshold) {
                lexicalAdmitted.add(sd.doc);
            }
        }

        Map<Integer, Integer> vectorRanks = new HashMap<>();
        Map<Integer, Float> vectorScoresRaw = new HashMap<>();
        Map<Integer, Float> vectorScoresTransformed = new HashMap<>();
        for (int i = 0; i < vector.scoreDocs.length; i++) {
            ScoreDoc sd = vector.scoreDocs[i];
            vectorRanks.put(sd.doc, i + 1);
            vectorScoresTransformed.put(sd.doc, sd.score);
            vectorScoresRaw.put(sd.doc, 2.0f * sd.score - 1.0f);
        }

        Float configuredThreshold = (settings != null) ? settings.vectorThreshold() : null;
        float vectorThreshold = (configuredThreshold != null) ? configuredThreshold : switch (provider != null ? provider.mode() : "") {
            case "http" -> 0.86f;
            case "onnx" -> 0.80f;
            default -> 0.82f;
        };
        Set<Integer> vectorAdmitted = new HashSet<>();
        for (ScoreDoc hit : vector.scoreDocs) {
            int ordinal = searcher.storedFields().document(hit.doc).getField("ordinal").numericValue().intValue();
            Chunk c = chunks.get(ordinal);
            if (hit.score >= vectorThreshold) vectorAdmitted.add(hit.doc);
        }

        Set<Integer> relevant = new HashSet<>(lexicalAdmitted);
        relevant.addAll(vectorAdmitted);

        TopDocs fused = TopDocs.rrf(24, 60, new TopDocs[]{lexical, vector});
        var hits = new ArrayList<Hit>();
        for (ScoreDoc hit : fused.scoreDocs) {
            if (relevant.contains(hit.doc)) {
                int ordinal = searcher.storedFields().document(hit.doc).getField("ordinal").numericValue().intValue();
                Chunk chunk = chunks.get(ordinal);

                // Dedup: in the final hit list, if two chunks of the same entry overlap in character range,
                // keep the higher-ranked one and backfill from lower-ranked candidates.
                boolean overlapsExisting = false;
                for (Hit accepted : hits) {
                    if (accepted.entryId().equals(chunk.entry().id())) {
                        if (Math.max(chunk.start(), accepted.charStart()) < Math.min(chunk.end(), accepted.charEnd())) {
                            overlapsExisting = true;
                            break;
                        }
                    }
                }
                if (overlapsExisting) {
                    continue;
                }

                boolean inLex = lexicalAdmitted.contains(hit.doc);
                boolean inVec = vectorAdmitted.contains(hit.doc);
                String admittedBy = inLex && inVec ? "both" : inLex ? "lexical" : "vector";

                double cov = lexicalCoverages.getOrDefault(hit.doc, 0.0);
                Float vecScore = vectorScoresTransformed.get(hit.doc);
                String gateNote = switch (admittedBy) {
                    case "both" -> String.format(Locale.ROOT, "lexical-coverage=%.2f>=%.2f, vector-score=%.4f>=%.4f", cov, lexicalCoverageThreshold, vecScore, vectorThreshold);
                    case "lexical" -> String.format(Locale.ROOT, "lexical-coverage=%.2f>=%.2f", cov, lexicalCoverageThreshold);
                    case "vector" -> String.format(Locale.ROOT, "vector-score=%.4f>=%.4f", vecScore, vectorThreshold);
                    default -> "";
                };

                hits.add(new Hit(
                    chunk.entry(),
                    chunk.text(),
                    "字符 " + (chunk.start() + 1) + "-" + chunk.end(),
                    hit.score,
                    chunk.entry().id(),
                    chunk.chunkIndex(),
                    chunk.start(),
                    chunk.end(),
                    lexicalRanks.get(hit.doc),
                    lexicalScores.get(hit.doc),
                    vectorRanks.get(hit.doc),
                    vectorScoresRaw.get(hit.doc),
                    vectorScoresTransformed.get(hit.doc),
                    hit.score,
                    admittedBy,
                    gateNote
                ));
                if (hits.size() == 4) break;
            }
        }
        return new SearchResult(List.copyOf(hits), mode, lexical.scoreDocs.length, vector.scoreDocs.length);
    }

    static float[] hash(String text) {
        return HashEmbedding.hash(text);
    }

    @PreDestroy @Override public void close() throws Exception {
        if (provider != null) {
            provider.close();
        }
        reader.close();
        directory.close();
        analyzer.close();
    }

    public record Chunk(Entry entry, String text, int start, int end, int chunkIndex) {}
    public record Hit(
        Entry entry,
        String text,
        String locator,
        float rankScore,
        String entryId,
        int chunkIndex,
        int charStart,
        int charEnd,
        Integer lexicalRank,
        Float lexicalScore,
        Integer vectorRank,
        Float vectorScoreRaw,
        Float vectorScoreTransformed,
        float rrfScore,
        String admittedBy,
        String gateNote
    ) {
        public Hit(Entry entry, String text, String locator, float rankScore, String entryId,
                   int chunkIndex, int charStart, int charEnd, Integer lexicalRank,
                   Float lexicalScore, Integer vectorRank, Float vectorScoreRaw,
                   Float vectorScoreTransformed, float rrfScore, String admittedBy) {
            this(entry, text, locator, rankScore, entryId, chunkIndex, charStart, charEnd,
                 lexicalRank, lexicalScore, vectorRank, vectorScoreRaw, vectorScoreTransformed,
                 rrfScore, admittedBy, "");
        }
    }
    public record SearchResult(List<Hit> hits, String embeddingMode, int lexicalCandidates, int vectorCandidates) {}
    private record ProviderResolution(EmbeddingProvider provider, String effectiveMode, List<float[]> vectors) {}
}
