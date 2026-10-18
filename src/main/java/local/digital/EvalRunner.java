package local.digital;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class EvalRunner {
    private final Settings settings;
    private final ObjectMapper mapper;
    private final ModelHttp http;

    public EvalRunner(Settings settings, ObjectMapper mapper, ModelHttp http) {
        this.settings = settings;
        this.mapper = mapper;
        this.http = http;
    }

    public static boolean requested(String[] args) {
        return Arrays.stream(args).anyMatch(a -> a.equals("--app.eval") || a.startsWith("--app.eval=") || a.startsWith("--app.eval-") || a.startsWith("--app.eval."));
    }

    public record EvalCase(
            String id,
            String question,
            boolean answerable,
            List<String> expectedEntryIds,
            String keyFact,
            String expectedRoute,
            String group
    ) {}

    public record EvalInput(String split, int version, String note, List<EvalCase> cases) {}

    public record MetricFraction(int numerator, int denominator, double rate, String formatted) {
        public static MetricFraction of(int num, int den) {
            double r = den == 0 ? 0.0 : (double) num / den;
            return new MetricFraction(num, den, r, String.format(Locale.ROOT, "%d/%d (%.1f%%)", num, den, r * 100.0));
        }
    }

    public record CaseDetail(
            String id,
            String question,
            String group,
            boolean answerable,
            String expectedRoute,
            String actualRoute,
            boolean routeMatch,
            List<String> expectedEntryIds,
            String keyFact,
            boolean hitAt4,
            double mrrAt4,
            double recallAt4,
            String e2eStatus,
            int sourcesCount,
            double latencyMs,
            List<Map<String, Object>> hits
    ) {}

    public record ModeResult(
            String mode,
            String effectiveBackend,
            long indexBuildTimeMs,
            double warmP50Ms,
            double warmP95Ms,
            MetricFraction recallAt4,
            MetricFraction hitAt4,
            double mrrAt4,
            MetricFraction routeAccuracy,
            MetricFraction falseAnswerRate,
            MetricFraction falseRefusalRate,
            List<CaseDetail> details
    ) {}

    public void runEval(String[] args) throws Exception {
        Map<String, String> cliOptions = parseArgs(args);
        String split = cliOptions.getOrDefault("split", "dev");
        if (split.equals("all")) {
            evaluateSplit("dev", cliOptions);
            evaluateSplit("test", cliOptions);
        } else {
            evaluateSplit(split, cliOptions);
        }
    }

    private void evaluateSplit(String split, Map<String, String> cliOptions) throws Exception {
        Path inputPath = Path.of("eval", split + ".json");
        if (!Files.isRegularFile(inputPath)) {
            throw new IllegalArgumentException("评测集文件不存在: " + inputPath);
        }
        EvalInput input = mapper.readValue(Files.readString(inputPath), EvalInput.class);
        List<EvalCase> cases = input.cases();

        List<String> modesToRun = determineModes(cliOptions);

        List<ModeResult> modeResults = new ArrayList<>();
        for (String mode : modesToRun) {
            ModeResult mr = evaluateMode(mode, split, cases);
            modeResults.add(mr);
        }

        // Print compact markdown table to stdout
        printMarkdownSummary(split, cases.size(), modeResults);

        // Save machine-readable JSON under eval/results/
        saveResultsJson(split, modeResults);
    }

    private List<String> determineModes(Map<String, String> cliOptions) {
        String explicitMode = cliOptions.get("embedding-mode");
        if (explicitMode == null || explicitMode.isBlank() || explicitMode.equals("auto")) {
            explicitMode = settings.get("embedding-mode");
        }
        if (explicitMode != null && !explicitMode.isBlank() && !explicitMode.equals("auto") && !explicitMode.equals("all")) {
            String m = explicitMode.toLowerCase(Locale.ROOT);
            checkModeAvailability(m, true);
            return List.of(m);
        }

        // Run each mode that is available: lexical-only, hash, onnx (and http if configured)
        List<String> modes = new ArrayList<>();
        modes.add("lexical");
        modes.add("hash");

        if (checkModeAvailability("onnx", false)) {
            modes.add("onnx");
        }

        if (settings.hasEmbedding()) {
            modes.add("http");
        }

        return modes;
    }

    private boolean checkModeAvailability(String mode, boolean failLoudly) {
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "lexical", "lexical-only", "hash" -> true;
            case "onnx" -> {
                Path modelDir = Path.of(settings.modelDir());
                Path modelFile = modelDir.resolve("model.onnx");
                Path vocabFile = modelDir.resolve("vocab.txt");
                boolean present = Files.isRegularFile(modelFile) && Files.isRegularFile(vocabFile);
                if (!present) {
                    Path spikeDir = Path.of("/home/hatch/workspace/projects/spike/model");
                    if (Files.isRegularFile(spikeDir.resolve("model.onnx")) && Files.isRegularFile(spikeDir.resolve("vocab.txt"))) {
                        present = true;
                    }
                }
                if (!present && failLoudly) {
                    throw new IllegalStateException("显式请求 onnx 模式，但 ONNX 模型或词表文件不存在 (检查 " + modelDir + ")");
                }
                yield present;
            }
            case "http" -> {
                boolean ok = settings.hasEmbedding();
                if (!ok && failLoudly) {
                    throw new IllegalStateException("显式请求 http 模式，但未配置 embedding-key 或 embedding-model");
                }
                yield ok;
            }
            default -> {
                if (failLoudly) throw new IllegalArgumentException("未知 embedding-mode: " + mode);
                yield false;
            }
        };
    }

    private ModeResult evaluateMode(String mode, String split, List<EvalCase> cases) throws Exception {
        Path evalIndexDir = Path.of("eval", "index-" + mode);
        deleteRecursively(evalIndexDir);
        Files.createDirectories(evalIndexDir);

        long indexStart = System.currentTimeMillis();
        Knowledge knowledge;
        try {
            knowledge = new Knowledge(settings, mapper, http, evalIndexDir, mode);
        } catch (Exception e) {
            throw new IllegalStateException("构建评测索引失败 [mode=" + mode + "]: " + e.getMessage(), e);
        }
        long indexBuildTimeMs = System.currentTimeMillis() - indexStart;

        String effectiveBackend = switch (knowledge.embeddingMode()) {
            case "lexical-only" -> "BM25-only";
            case "hash" -> "BM25 + Hash (256d)";
            case "onnx" -> "BM25 + BGE-small-zh-v1.5 (512d)";
            case "http" -> "BM25 + HTTP (" + settings.get("embedding-model") + ")";
            default -> knowledge.embeddingMode();
        };

        AnswerService answerService = new AnswerService(knowledge, settings.withDisabledGeneration(), http);

        try {
            // Warm-up pass
            for (EvalCase c : cases) {
                knowledge.search(c.question());
            }

            // Latency measurement & evaluation pass
            List<CaseDetail> details = new ArrayList<>();
            List<Double> latencies = new ArrayList<>();

            int hitCount = 0;
            int totalRecallCovered = 0;
            int totalExpectedEntries = 0;
            double mrrSum = 0.0;
            int answerableCases = 0;

            int routeMatches = 0;
            int falseAnswerCount = 0;
            int unanswerableCases = 0;
            int falseRefusalCount = 0;

            for (EvalCase c : cases) {
                // Measure warm retrieval latency (3 sample runs)
                long t0 = System.nanoTime();
                Knowledge.SearchResult sr = knowledge.search(c.question());
                knowledge.search(c.question());
                knowledge.search(c.question());
                long t1 = System.nanoTime();
                double latencyMs = (t1 - t0) / 3_000_000.0;
                latencies.add(latencyMs);

                List<Knowledge.Hit> hits = sr.hits();

                // Chunk-level relevance evaluation
                boolean hitAt4 = false;
                Double firstRankReciprocal = 0.0;
                Set<String> coveredExpected = new HashSet<>();

                for (int rank = 0; rank < hits.size(); rank++) {
                    Knowledge.Hit h = hits.get(rank);
                    boolean relevant = isChunkRelevant(h, c);
                    if (relevant) {
                        if (!hitAt4) {
                            hitAt4 = true;
                            firstRankReciprocal = 1.0 / (rank + 1);
                        }
                        coveredExpected.add(h.entryId());
                    }
                }

                double recallAt4 = 0.0;
                if (c.answerable()) {
                    answerableCases++;
                    if (hitAt4) hitCount++;
                    mrrSum += firstRankReciprocal;

                    int expectedCount = (c.expectedEntryIds() == null) ? 0 : c.expectedEntryIds().size();
                    totalExpectedEntries += expectedCount;
                    totalRecallCovered += coveredExpected.size();
                    recallAt4 = expectedCount == 0 ? 0.0 : (double) coveredExpected.size() / expectedCount;
                } else {
                    unanswerableCases++;
                }

                // End-to-end evaluation with generation disabled
                Chat.Answer chatResp = answerService.answer(new Chat.Question(c.question()));
                String actualBucket = mapToBucket(chatResp);
                boolean routeMatch = actualBucket.equals(c.expectedRoute());
                if (routeMatch) routeMatches++;

                String e2eStatus;
                if (!c.answerable()) {
                    // Answered with sources = false answer
                    boolean answeredWithSources = !chatResp.sources().isEmpty();
                    if (answeredWithSources) {
                        falseAnswerCount++;
                        e2eStatus = "false_answer";
                    } else {
                        e2eStatus = "correct_refusal";
                    }
                } else {
                    // Answerable question refused = false refusal
                    boolean refused = actualBucket.equals("refuse") || (chatResp.sources().isEmpty() && !actualBucket.equals("structured"));
                    if (refused) {
                        falseRefusalCount++;
                        e2eStatus = "false_refusal";
                    } else {
                        e2eStatus = "correct_answer";
                    }
                }

                List<Map<String, Object>> hitDetails = new ArrayList<>();
                for (Knowledge.Hit h : hits) {
                    hitDetails.add(Map.of(
                            "entryId", h.entryId(),
                            "chunkIndex", h.chunkIndex(),
                            "charStart", h.charStart(),
                            "charEnd", h.charEnd(),
                            "admittedBy", h.admittedBy(),
                            "gateNote", h.gateNote(),
                            "rrfScore", h.rrfScore(),
                            "relevant", isChunkRelevant(h, c)
                    ));
                }

                details.add(new CaseDetail(
                        c.id(),
                        c.question(),
                        c.group(),
                        c.answerable(),
                        c.expectedRoute(),
                        actualBucket,
                        routeMatch,
                        c.expectedEntryIds(),
                        c.keyFact(),
                        hitAt4,
                        firstRankReciprocal,
                        recallAt4,
                        e2eStatus,
                        chatResp.sources().size(),
                        latencyMs,
                        hitDetails
                ));
            }

            Collections.sort(latencies);
            double warmP50Ms = latencies.get(latencies.size() / 2);
            int p95Idx = (int) Math.ceil(latencies.size() * 0.95) - 1;
            double warmP95Ms = latencies.get(Math.clamp(p95Idx, 0, latencies.size() - 1));

            MetricFraction recallFraction = MetricFraction.of(totalRecallCovered, totalExpectedEntries);
            MetricFraction hitFraction = MetricFraction.of(hitCount, answerableCases);
            double mrr = answerableCases == 0 ? 0.0 : mrrSum / answerableCases;

            MetricFraction routeAccuracy = MetricFraction.of(routeMatches, cases.size());
            MetricFraction falseAnswerRate = MetricFraction.of(falseAnswerCount, unanswerableCases);
            MetricFraction falseRefusalRate = MetricFraction.of(falseRefusalCount, answerableCases);

            return new ModeResult(
                    knowledge.embeddingMode(),
                    effectiveBackend,
                    indexBuildTimeMs,
                    warmP50Ms,
                    warmP95Ms,
                    recallFraction,
                    hitFraction,
                    mrr,
                    routeAccuracy,
                    falseAnswerRate,
                    falseRefusalRate,
                    details
            );
        } finally {
            knowledge.close();
            deleteRecursively(evalIndexDir);
        }
    }

    private static boolean isChunkRelevant(Knowledge.Hit hit, EvalCase c) {
        if (c.expectedEntryIds() == null || !c.expectedEntryIds().contains(hit.entryId())) {
            return false;
        }
        if (c.keyFact() != null && !c.keyFact().isBlank()) {
            return hit.text().contains(c.keyFact());
        }
        return true;
    }

    private static String mapToBucket(Chat.Answer answer) {
        if ("structured".equals(answer.mode())) return "structured";
        if ("local-extract".equals(answer.mode()) || "http-generated".equals(answer.mode())) return "retrieve";
        if ("insufficient-evidence".equals(answer.mode()) || "unknown".equals(answer.routing().intent())) return "refuse";
        return answer.sources().isEmpty() ? "refuse" : "retrieve";
    }

    private void printMarkdownSummary(String split, int totalCases, List<ModeResult> results) {
        System.out.println();
        System.out.println("### Evaluation Summary on Fixed Fixture Corpus (`" + split + ".json`, N=" + totalCases + ")");
        System.out.println();
        System.out.println("| Mode | Effective Backend | Index Build | Warm p50 | Warm p95 | Recall@4 | Hit@4 | MRR@4 | Route Accuracy | False-Answer Rate | False-Refusal Rate |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|");
        for (ModeResult r : results) {
            System.out.printf(Locale.ROOT,
                    "| %s | %s | %dms | %.2fms | %.2fms | %s | %s | %.3f | %s | %s | %s |\n",
                    r.mode(),
                    r.effectiveBackend(),
                    r.indexBuildTimeMs(),
                    r.warmP50Ms(),
                    r.warmP95Ms(),
                    r.recallAt4().formatted(),
                    r.hitAt4().formatted(),
                    r.mrrAt4(),
                    r.routeAccuracy().formatted(),
                    r.falseAnswerRate().formatted(),
                    r.falseRefusalRate().formatted()
            );
        }
        System.out.println();
    }

    private void saveResultsJson(String split, List<ModeResult> results) throws IOException {
        Path dir = Path.of("eval", "results");
        Files.createDirectories(dir);

        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
        Path runFile = dir.resolve("eval-" + split + "-" + ts + ".json");
        Path latestFile = dir.resolve("eval-" + split + "-latest.json");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("split", split);
        output.put("timestamp", Instant.now().toString());
        output.put("lexicalCoverageThreshold", settings.lexicalCoverage());
        output.put("vectorThreshold", settings.vectorThreshold());
        output.put("modes", results);

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        Files.writeString(runFile, json);
        Files.writeString(latestFile, json);

        System.out.println("Detailed per-case results written to " + runFile + " (and " + latestFile + ")");
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (String a : args) {
            if (a.startsWith("--app.eval=")) {
                opts.put("split", a.substring("--app.eval=".length()));
            } else if (a.startsWith("--app.eval-split=") || a.startsWith("--app.eval.split=")) {
                opts.put("split", a.substring(a.indexOf('=') + 1));
            } else if (a.startsWith("--app.embedding-mode=")) {
                opts.put("embedding-mode", a.substring("--app.embedding-mode=".length()));
            } else if (a.startsWith("--app.eval-mode=")) {
                opts.put("embedding-mode", a.substring("--app.eval-mode=".length()));
            }
        }
        return opts;
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
