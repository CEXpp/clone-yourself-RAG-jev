package local.digital;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.context.annotation.Configuration;

import java.util.*;
import java.util.regex.Pattern;

@org.springframework.context.annotation.Lazy
@RestController
@RequestMapping("/api")
public class Chat {
    private final Knowledge knowledge;
    private final Settings settings;
    private final ModelHttp http;
    public Chat(Knowledge knowledge, Settings settings, ModelHttp http) {
        this.knowledge = knowledge; this.settings = settings; this.http = http;
    }
    @GetMapping("/status") public Map<String, Object> status() {
        return Map.of("publicEntries", knowledge.count(), "embedding", knowledge.embeddingMode(),
                "generation", settings.hasGeneration() ? "http-enabled" : "local-extract",
                "routing", settings.get("jev-key").isBlank() ? "local" : "jev-enabled",
                "demoData", Boolean.parseBoolean(settings.get("demo-data")), "java", Runtime.version().feature());
    }
    @PostMapping("/chat") public ResponseEntity<?> chat(@RequestBody Question request) throws Exception {
        String q = request.question() == null ? "" : request.question().strip();
        if (q.isEmpty() || q.length() > 2000) return ResponseEntity.badRequest().body(Map.of("error", "请输入1至2000字的问题"));
        ModelHttp.Decision decision = local(q, "local");
        if (!settings.get("jev-key").isBlank()) {
            try {
                var remote = http.route(q);
                double threshold = Double.parseDouble(settings.get("jev-threshold"));
                if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) threshold = 0.65;
                decision = remote.confidence() >= threshold ? remote : local(q, "local-low-confidence");
            } catch (Exception unavailable) { decision = local(q, "local-jev-unavailable"); }
        }
        List<Source> sources = new ArrayList<>(); List<Skill> skills = new ArrayList<>();
        String type = switch (decision.intent()) { case "skills" -> "skill"; case "projects" -> "project"; case "profile" -> "profile"; default -> ""; };
        if (!type.isEmpty()) {
            List<Entry> entries = knowledge.ofType(type);
            StringBuilder answer = new StringBuilder();
            for (Entry e : entries) {
                int n = sources.size() + 1;
                sources.add(source(n, e, "完整条目", e.content()));
                answer.append(e.title()).append("：").append(e.content());
                if (e.level() != null) answer.append("\n熟练程度：").append(e.level());
                if (e.boundaries() != null) answer.append("\n范围限制：").append(e.boundaries());
                answer.append(" [").append(n).append("]\n\n");
                if (type.equals("skill")) {
                    List<String> evidence = new ArrayList<>();
                    if (e.evidenceIds() != null) {
                        // Resolve against the approved corpus only. Never expose unresolved private IDs.
                        for (Entry proof : knowledge.ofType("evidence")) if (e.evidenceIds().contains(proof.id())) {
                            int proofNumber = sources.size() + 1;
                            sources.add(source(proofNumber, proof, "完整证据条目", proof.content()));
                            evidence.add(proof.title() + " [" + proofNumber + "]");
                        }
                    }
                    skills.add(new Skill(e.title(), e.content(), e.level(), e.boundaries(),
                            evidence.isEmpty() ? List.of("暂无公开证据，仅条目自述") : List.copyOf(evidence), e.updatedAt()));
                }
            }
            return ResponseEntity.ok(new Answer(answer.isEmpty() ? "暂无已批准的公开条目。" : answer.toString().strip(),
                    "structured", decision, knowledge.embeddingMode(), sources, skills));
        }
        if (decision.intent().equals("unknown")) return ResponseEntity.ok(new Answer(
                "你好，这里仅回答已批准的公开资料。可以问：你会什么、介绍一下自己、有哪些项目。", "local", decision,
                knowledge.embeddingMode(), List.of(), List.of()));
        var result = knowledge.search(q);
        if (result.hits().isEmpty()) return ResponseEntity.ok(new Answer("公开资料中没有足够依据回答这个问题。请补充问题或等待资料更新。",
                "insufficient-evidence", decision, result.embeddingMode(), List.of(), List.of()));
        StringBuilder context = new StringBuilder();
        for (var hit : result.hits()) {
            int n = sources.size() + 1;
            sources.add(source(n, hit.entry(), hit.locator(), hit.text()));
            context.append("[").append(n).append("] ").append(hit.entry().title()).append("\n").append(hit.text()).append("\n");
        }
        String answer = "根据公开资料摘录：\n" + context.toString().strip();
        String mode = "local-extract";
        if (settings.hasGeneration()) {
            try {
                String generated = http.generate(q, context.toString());
                var citations = Pattern.compile("\\[(\\d+)]").matcher(generated);
                int valid = 0;
                while (citations.find()) {
                    int n = Integer.parseInt(citations.group(1));
                    if (n < 1 || n > sources.size()) throw new IllegalStateException("引用编号无效");
                    valid++;
                }
                if (valid == 0) throw new IllegalStateException("生成结果缺少引用");
                answer = generated; mode = "http-generated";
            } catch (Exception unavailable) { mode = "local-generation-unavailable"; }
        }
        return ResponseEntity.ok(new Answer(answer, mode, decision, result.embeddingMode(), List.copyOf(sources), List.of()));
    }
    static ModelHttp.Decision local(String question, String provider) {
        String q = question.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}]", "");
        String intent = switch (q) {
            case "你会什么", "技能", "你有哪些技能", "whatcanyoudo", "skills" -> "skills";
            case "介绍一下自己", "你是谁", "自我介绍", "介绍自己", "profile" -> "profile";
            case "有哪些项目", "你做过哪些项目", "项目经历", "projects" -> "projects";
            case "你好", "hello", "hi" -> "unknown";
            default -> "retrieve";
        };
        // Local heuristic is not a calibrated probability; use confidence=-1 explicitly.
        return new ModelHttp.Decision(intent, -1, provider);
    }
    private static Source source(int n, Entry e, String locator, String excerpt) {
        return new Source(n, e.title(), e.source(), locator, e.updatedAt(), excerpt, e.id());
    }
    public record Question(String question) {}
    public record Source(int number, String title, String origin, String locator, String updatedAt, String excerpt, String entryId) {}
    public record Skill(String name, String tasks, String level, String boundaries, List<String> evidence, String updatedAt) {}
    public record Answer(String answer, String mode, ModelHttp.Decision routing, String embeddingMode, List<Source> sources, List<Skill> skills) {}

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(Exception.class) ResponseEntity<?> failure(Exception error) {
            var status = error instanceof org.springframework.web.ErrorResponse web
                    ? web.getStatusCode() : HttpStatus.INTERNAL_SERVER_ERROR;
            return ResponseEntity.status(status).body(Map.of("error", status.value() == 404 ? "资源不存在" : "请求未完成，请检查输入或稍后重试"));
        }
        @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
        ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("error", "请提交有效JSON问题")); }
    }
    @Configuration
    static class Headers implements WebMvcConfigurer {
        @Override public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
                @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest req,
                        jakarta.servlet.http.HttpServletResponse res, Object handler) {
                    res.setHeader("X-Content-Type-Options", "nosniff");
                    res.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'");
                    res.setHeader("Cache-Control", "no-store");
                    return true;
                }
            });
        }
    }
}
