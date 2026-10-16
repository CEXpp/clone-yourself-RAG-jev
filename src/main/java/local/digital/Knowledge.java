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
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.util.*;

@Service
public class Knowledge implements AutoCloseable {
    private final List<Entry> entries;
    private final List<Chunk> chunks = new ArrayList<>();
    private final SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer();
    private final Directory directory;
    private final DirectoryReader reader;
    private final IndexSearcher searcher;
    private final ModelHttp http;
    private final boolean remote;
    private final int dimensions;

    public Knowledge(Settings settings, ObjectMapper mapper, ModelHttp http) throws Exception {
        this.http = http;
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
        for (Entry e : entries) {
            for (int start = 0; start < e.content().length(); start += 600) {
                int end = Math.min(start + 700, e.content().length());
                chunks.add(new Chunk(e, e.content().substring(start, end), start, end));
                if (end == e.content().length()) break;
            }
        }
        List<float[]> vectors = new ArrayList<>(); boolean useRemote = settings.hasEmbedding();
        if (useRemote) {
            try {
                for (Chunk c : chunks) vectors.add(http.embed(c.entry.title() + " " + c.text));
                if (vectors.stream().map(v -> v.length).distinct().count() > 1) throw new IllegalStateException("向量维度不一致");
            } catch (Exception failure) { vectors.clear(); useRemote = false; }
        }
        if (!useRemote) for (Chunk c : chunks) vectors.add(hash(c.entry.title() + " " + c.text));
        remote = useRemote;
        dimensions = vectors.isEmpty() ? 256 : vectors.getFirst().length;
        Files.createDirectories(root.resolve("index-public"));
        directory = FSDirectory.open(root.resolve("index-public"));
        var config = new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        config.setSimilarity(new BM25Similarity());
        try (var writer = new IndexWriter(directory, config)) {
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i); var doc = new Document();
                doc.add(new StoredField("ordinal", i));
                doc.add(new TextField("text", c.entry.title() + " " + c.text, Field.Store.NO));
                doc.add(new KnnFloatVectorField("vector", vectors.get(i), VectorSimilarityFunction.COSINE));
                writer.addDocument(doc);
            }
        }
        reader = DirectoryReader.open(directory); searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new BM25Similarity());
    }
    public List<Entry> ofType(String type) { return entries.stream().filter(e -> e.type().equals(type)).toList(); }
    public int count() { return entries.size(); }
    public String embeddingMode() { return remote ? "http" : "local-hash-demo"; }
    public SearchResult search(String question) throws Exception {
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
        String mode = embeddingMode();
        try {
            float[] query = remote ? http.embed(question) : hash(question);
            if (query.length != dimensions) throw new IllegalStateException("查询向量维度已改变");
            vector = searcher.search(new KnnFloatVectorQuery("vector", query, 12), 12);
        } catch (Exception unavailable) { mode = "lexical-only"; }
        Set<Integer> relevant = new HashSet<>();
        for (ScoreDoc hit : lexical.scoreDocs) relevant.add(hit.doc);
        // Lucene cosine score=(1+cosine)/2; threshold is a heuristic, not calibrated confidence.
        for (ScoreDoc hit : vector.scoreDocs) if (hit.score >= (remote ? 0.86f : 0.82f)) relevant.add(hit.doc);
        TopDocs fused = TopDocs.rrf(24, 60, new TopDocs[]{lexical, vector});
        var hits = new ArrayList<Hit>();
        for (ScoreDoc hit : fused.scoreDocs) if (relevant.contains(hit.doc)) {
            int ordinal = searcher.storedFields().document(hit.doc).getField("ordinal").numericValue().intValue();
            Chunk chunk = chunks.get(ordinal);
            hits.add(new Hit(chunk.entry, chunk.text, "字符 " + (chunk.start + 1) + "-" + chunk.end, hit.score));
            if (hits.size() == 4) break;
        }
        return new SearchResult(List.copyOf(hits), mode, lexical.scoreDocs.length, vector.scoreDocs.length);
    }
    static float[] hash(String text) {
        float[] v = new float[256];
        String cleaned = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        if (cleaned.isEmpty()) { v[0] = 1; return v; }
        for (int i = 0; i < cleaned.length(); i++) {
            String token = cleaned.substring(i, Math.min(i + 2, cleaned.length()));
            v[Math.floorMod(token.hashCode(), v.length)]++;
        }
        return ModelHttp.normalize(v);
    }
    @PreDestroy public void close() throws Exception { reader.close(); directory.close(); analyzer.close(); }
    private record Chunk(Entry entry, String text, int start, int end) {}
    public record Hit(Entry entry, String text, String locator, float rankScore) {}
    public record SearchResult(List<Hit> hits, String embeddingMode, int lexicalCandidates, int vectorCandidates) {}
}
