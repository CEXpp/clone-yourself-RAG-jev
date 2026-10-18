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

        // Minimal multi-turn resolution (Scope B / design D5)
        String resolvedQuestion = null;
        String effectiveQuestion = q;
        if (request.history() != null && !request.history().isEmpty()) {
            String rewritten = resolveMultiTurn(q, request.history(), knowledge);
            if (rewritten != null && !rewritten.equals(q)) {
                resolvedQuestion = rewritten;
                effectiveQuestion = rewritten;
            }
        }

        ModelHttp.Decision decision = local(effectiveQuestion, "local");
        if (!settings.get("jev-key").isBlank()) {
            try {
                var remote = http.route(effectiveQuestion);
                double threshold = Double.parseDouble(settings.get("jev-threshold"));
                if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) threshold = 0.65;
                decision = remote.confidence() >= threshold ? remote : local(effectiveQuestion, "local-low-confidence");
            } catch (Exception unavailable) { decision = local(effectiveQuestion, "local-jev-unavailable"); }
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
                    "structured", decision, knowledge.embeddingMode(), sources, skills, resolvedQuestion));
        }
        if (decision.intent().equals("unknown")) return ResponseEntity.ok(new Answer(
                "你好，这里仅回答已批准的公开资料。可以问：你会什么、介绍一下自己、有哪些项目。", "local", decision,
                knowledge.embeddingMode(), List.of(), List.of(), resolvedQuestion));
        var result = knowledge.search(effectiveQuestion);
        if (result.hits().isEmpty()) return ResponseEntity.ok(new Answer("公开资料中没有足够依据回答这个问题。请补充问题或等待资料更新。",
                "insufficient-evidence", decision, result.embeddingMode(), List.of(), List.of(), resolvedQuestion));
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
                String generated = http.generate(effectiveQuestion, context.toString());
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
        return ResponseEntity.ok(new Answer(answer, mode, decision, result.embeddingMode(), List.copyOf(sources), List.of(), resolvedQuestion));
    }

    public static final int MAX_HISTORY_TURNS = 10;
    public static final int MAX_TURN_CHARS = 1000;
    public static final int MAX_TOTAL_HISTORY_CHARS = 4000;

    static List<Turn> sanitizeHistory(List<Turn> rawHistory) {
        if (rawHistory == null || rawHistory.isEmpty()) return List.of();
        List<Turn> valid = new ArrayList<>();
        Set<String> allowedRoles = Set.of("user", "assistant", "system");
        for (Turn t : rawHistory) {
            if (t == null || t.role() == null || t.text() == null) continue;
            String role = t.role().strip().toLowerCase(Locale.ROOT);
            if (!allowedRoles.contains(role)) continue;
            String text = t.text().strip();
            if (text.isEmpty()) continue;
            if (text.length() > MAX_TURN_CHARS) {
                text = text.substring(0, MAX_TURN_CHARS);
            }
            valid.add(new Turn(role, text));
        }
        if (valid.isEmpty()) return List.of();
        if (valid.size() > MAX_HISTORY_TURNS) {
            valid = valid.subList(valid.size() - MAX_HISTORY_TURNS, valid.size());
        }
        int totalChars = valid.stream().mapToInt(t -> t.text().length()).sum();
        while (totalChars > MAX_TOTAL_HISTORY_CHARS && valid.size() > 1) {
            totalChars -= valid.getFirst().text().length();
            valid = valid.subList(1, valid.size());
        }
        return valid;
    }

    private static final List<String> PROJECT_REFS = List.of(
            "这个项目", "该项目", "那个项目", "这项目", "那项目", "本项目"
    );
    private static final List<String> SYSTEM_REFS = List.of(
            "这个系统", "该系统", "那个系统", "这系统", "那系统", "本系统"
    );
    private static final List<String> SKILL_REFS = List.of(
            "这项技能", "这个技能", "该技能", "那个技能",
            "这项技术", "这个技术", "该技术", "这门技术", "这门技能"
    );
    private static final List<String> DOC_REFS = List.of(
            "这篇文档", "该文档", "这篇笔记", "该笔记", "这篇文章"
    );
    private static final List<String> PRONOUN_REFS = List.of(
            "它们", "它"
    );
    private static final List<String> DEICTIC_REFS = List.of(
            "这个", "那个"
    );

    record RefMatch(String matchedRef, String targetType) {}

    static RefMatch findReference(String question) {
        for (String r : PROJECT_REFS) if (question.contains(r)) return new RefMatch(r, "project");
        for (String r : SYSTEM_REFS) if (question.contains(r)) return new RefMatch(r, "system");
        for (String r : SKILL_REFS) if (question.contains(r)) return new RefMatch(r, "skill");
        for (String r : DOC_REFS) if (question.contains(r)) return new RefMatch(r, "document");
        for (String r : PRONOUN_REFS) if (question.contains(r)) return new RefMatch(r, "general");
        for (String r : DEICTIC_REFS) if (question.contains(r)) return new RefMatch(r, "general");
        return null;
    }

    static String cleanTitle(String title) {
        if (title == null) return "";
        return title.replaceAll("（[^）]*）", "").replaceAll("\\([^)]*\\)", "").strip();
    }

    static boolean isTypeCompatible(Entry e, String targetType) {
        return switch (targetType) {
            case "project" -> "project".equals(e.type());
            case "system" -> "project".equals(e.type()) || "分身系统".contains(e.title());
            case "skill" -> "skill".equals(e.type());
            case "document" -> "document".equals(e.type());
            case "general" -> List.of("project", "skill", "document", "learning").contains(e.type());
            default -> true;
        };
    }

    static boolean matchesEntry(Entry e, String text) {
        if (text == null || text.isBlank()) return false;
        if (text.contains(e.id())) return true;
        String clean = cleanTitle(e.title());
        if (!clean.isEmpty() && text.contains(clean)) return true;
        return switch (e.id()) {
            case "demo-project-milktea" -> text.contains("奶茶点单") || text.contains("奶茶项目") || text.contains("奶茶小程序") || text.contains("奶茶");
            case "demo-project-twin" -> text.contains("个人数字分身") || text.contains("数字分身") || text.contains("分身系统");
            case "demo-project-dianping" -> text.contains("点评类应用") || text.contains("点评项目") || text.contains("点评应用") || text.contains("点评") || text.contains("秒杀");
            case "demo-skill-java" -> text.contains("Java基础") || text.contains("Java并发") || (text.contains("Java") && !text.contains("JavaScript"));
            case "demo-skill-spring" -> text.contains("Spring Boot") || text.contains("SpringBoot");
            case "demo-skill-rag" -> text.contains("检索与RAG") || text.contains("向量检索") || text.contains("RAG");
            case "demo-skill-llm" -> text.contains("LLM应用") || text.contains("大模型") || text.contains("LLM");
            case "demo-skill-eng" -> text.contains("工程化与测试") || text.contains("工程化");
            case "demo-doc-vector-notes" -> text.contains("向量检索学习笔记") || text.contains("向量检索笔记");
            case "demo-learning-agent" -> text.contains("LangChain4j") || text.contains("Agent框架");
            default -> false;
        };
    }

    static String resolveMultiTurn(String question, List<Turn> rawHistory, Knowledge knowledge) {
        if (rawHistory == null || rawHistory.isEmpty()) return null;
        List<Turn> history = sanitizeHistory(rawHistory);
        if (history.isEmpty()) return null;

        RefMatch refMatch = findReference(question);
        if (refMatch == null) return null;

        // Preceding exchange: last turn and previous turn if user/assistant pair
        String exchangeText;
        if (history.size() >= 2 && "assistant".equals(history.getLast().role())) {
            exchangeText = history.get(history.size() - 2).text() + " " + history.getLast().text();
        } else {
            exchangeText = history.getLast().text();
        }

        List<Entry> pool = knowledge != null ? knowledge.allEntries() : List.of();
        List<Entry> candidates = new ArrayList<>();
        for (Entry e : pool) {
            if (isTypeCompatible(e, refMatch.targetType()) && matchesEntry(e, exchangeText)) {
                candidates.add(e);
            }
        }

        // Must contain exactly ONE uniquely identifiable topic entry
        if (candidates.size() != 1) return null;

        Entry topic = candidates.getFirst();
        String topicName = cleanTitle(topic.title());
        if (topicName.isEmpty()) return null;

        // If the question already explicitly mentions this topic, no rewrite is needed
        if (question.contains(topicName) || matchesEntry(topic, question)) {
            return null;
        }

        String ref = refMatch.matchedRef();
        int idx = question.indexOf(ref);
        if (idx < 0) return null;

        String prefix = question.substring(0, idx);
        String suffix = question.substring(idx + ref.length());

        // Deduplicate adjacent redundant words between topicName and suffix
        for (String dup : List.of("后端", "系统", "项目", "小程序")) {
            if (topicName.endsWith(dup) && suffix.startsWith(dup)) {
                suffix = suffix.substring(dup.length());
                break;
            }
        }

        String resolved = prefix + topicName + suffix;
        if (resolved.contains("它")) {
            resolved = resolved.replace("它", topicName);
        }
        return resolved.equals(question) ? null : resolved;
    }

    static ModelHttp.Decision local(String question, String provider) {
        String q = question.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}]", "");

        // Layer 1: Existing exact-match overview rules (kept untouched)
        String l1Intent = switch (q) {
            case "你会什么", "技能", "你有哪些技能", "whatcanyoudo", "skills" -> "skills";
            case "介绍一下自己", "你是谁", "自我介绍", "介绍自己", "profile" -> "profile";
            case "有哪些项目", "你做过哪些项目", "项目经历", "projects" -> "projects";
            case "你好", "hello", "hi" -> "unknown";
            default -> null;
        };
        if (l1Intent != null) {
            return new ModelHttp.Decision(l1Intent, -1, provider, "layer1:exact-match");
        }

        // Layer 2: Conservative overview vs retrieval (design D4)
        String lower = question.toLowerCase(Locale.ROOT);

        // 1. Disqualification checks: Negation, Exclusion, Comparison, Constraints, Entities
        // Negation / Exclusion
        for (String neg : List.of("除了", "除去", "除外", "排除", "不包括", "不包含", "不含", "不用", "不要", "不是", "不会", "不能", "不擅长", "没有", "未曾", "从未", "未做过", "非")) {
            if (lower.contains(neg)) {
                return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(negation:" + neg + ")");
            }
        }

        // Demonstratives / Specific deictic pointers
        for (String dem : List.of("这个项目", "该项目", "那个项目", "这项目", "那项目", "本项目", "这个系统", "该系统", "那个系统", "这系统", "那系统", "本系统", "这个", "那个", "这些", "那些", "某项", "某个", "单一", "单个", "这门", "这项", "这篇", "这里")) {
            if (lower.contains(dem)) {
                return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(demonstrative:" + dem + ")");
            }
        }

        // Specific technical / domain entities
        // ASCII letter presence (e.g. java, spring, lucene, redis, python, etc.)
        if (Pattern.compile("[a-zA-Z]").matcher(lower).find()) {
            return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(specific-entity:ascii)");
        }
        // Chinese domain / technical keywords
        for (String ent : List.of("奶茶", "点评", "秒杀", "一人一单", "关单", "超时", "延迟队列", "购物车", "订单", "缓存", "分身", "数字分身", "并发", "线程池", "分布式", "锁", "数据库", "自测", "爬虫", "向量", "架构", "设计", "原理", "源码", "微服务", "网关", "熔断", "链路追踪", "流式", "未做过", "自述", "虚构")) {
            if (lower.contains(ent)) {
                return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(specific-entity:" + ent + ")");
            }
        }

        // Comparison / Superlatives / Specific Selection
        for (String cmp : List.of("最", "更", "比较", "哪一个", "哪个", "哪项", "哪部", "哪家", "首个", "第一", "核心", "优先")) {
            if (lower.contains(cmp)) {
                return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(comparison:" + cmp + ")");
            }
        }

        // Interrogative constraints / Deep-dive questions
        for (String cst : List.of("为什么", "为何", "怎么", "如何", "怎样", "多少", "多大", "多久", "多深", "哪里", "何处", "什么水平", "到什么水平", "程度", "水平")) {
            if (lower.contains(cst)) {
                return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(constraint:" + cst + ")");
            }
        }

        // Polar confirmation particles
        for (String pol : List.of("吗", "么", "是不是", "会不会", "能不能", "懂不懂", "有没有", "行不行", "可否")) {
            if (lower.contains(pol)) {
                return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(confirmation:" + pol + ")");
            }
        }

        // 2. Category Detection
        boolean isSkill = lower.contains("技能") || lower.contains("能力") || lower.contains("本领") || lower.contains("技术栈") || lower.contains("擅长");
        boolean isProject = lower.contains("项目") || lower.contains("经历") || lower.contains("工程") || lower.contains("作品");
        boolean isProfile = lower.contains("个人信息") || lower.contains("个人资料") || lower.contains("个人背景") || lower.contains("基本情况") || lower.contains("简历") || lower.contains("自我介绍");

        int categoryCount = (isSkill ? 1 : 0) + (isProject ? 1 : 0) + (isProfile ? 1 : 0);
        if (categoryCount > 1) {
            return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(mixed-categories)");
        }
        if (categoryCount == 0) {
            return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(no-category)");
        }

        // 3. Clear Overview Intent Check
        String targetCategory = isSkill ? "skills" : (isProject ? "projects" : "profile");
        boolean hasOverviewIntent = false;
        if (isSkill || isProject) {
            for (String ov : List.of("有哪些", "有什么", "有啥", "做过哪些", "参与过哪些", "有哪些经历", "列出", "列举", "清单", "列表", "全貌", "全部", "所有", "整体", "总览", "概览", "汇总", "盘点", "一览", "都有哪些", "包含哪些", "包括哪些", "梳理")) {
                if (lower.contains(ov)) {
                    hasOverviewIntent = true;
                    break;
                }
            }
        } else {
            for (String ov : List.of("介绍", "说说", "概述", "概览", "简介")) {
                if (lower.contains(ov)) {
                    hasOverviewIntent = true;
                    break;
                }
            }
        }

        if (hasOverviewIntent) {
            return new ModelHttp.Decision(targetCategory, -1, provider, "layer2:overview:" + targetCategory);
        }

        return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(no-overview-intent)");
    }

    private static Source source(int n, Entry e, String locator, String excerpt) {
        return new Source(n, e.title(), e.source(), locator, e.updatedAt(), excerpt, e.id());
    }

    public record Turn(String role, String text) {}
    public record Question(String question, List<Turn> history) {
        public Question(String question) {
            this(question, null);
        }
    }
    public record Source(int number, String title, String origin, String locator, String updatedAt, String excerpt, String entryId) {}
    public record Skill(String name, String tasks, String level, String boundaries, List<String> evidence, String updatedAt) {}

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Answer(
            String answer,
            String mode,
            ModelHttp.Decision routing,
            String embeddingMode,
            List<Source> sources,
            List<Skill> skills,
            String resolvedQuestion
    ) {
        public Answer(String answer, String mode, ModelHttp.Decision routing, String embeddingMode, List<Source> sources, List<Skill> skills) {
            this(answer, mode, routing, embeddingMode, sources, skills, null);
        }
    }

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
