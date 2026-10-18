package local.digital;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 统一问答管线 (AnswerService)。
 * 供 JSON 接口 (/api/chat)、SSE 流式接口 (/api/chat/stream) 与离线评测 (EvalRunner 端到端路径) 共享调用。
 * 引用编号有效性校验仅在此处统一实现。
 */
@Service
@Lazy
public class AnswerService {
    private final Knowledge knowledge;
    private final Settings settings;
    private final ModelHttp http;

    public AnswerService(Knowledge knowledge, Settings settings, ModelHttp http) {
        this.knowledge = knowledge;
        this.settings = settings;
        this.http = http;
    }

    /**
     * 流式事件监听器，用于 SSE 进度流通知。
     */
    public interface AnswerListener {
        AnswerListener NOOP = new AnswerListener() {};
        default void onRoute(ModelHttp.Decision decision) {}
        default void onTrace(TraceData trace) {}
        default void onProgress(String stage, String message) {}
        default void onAnswer(Chat.Answer answer) {}
        default void onError(String error) {}
        default boolean isCancelled() { return false; }
    }

    /**
     * 单条检索透视条目（与 Knowledge.Hit 对齐，供前端透视面板三列展示）。
     */
    public record TraceHit(
            String entryId,
            int chunkIndex,
            String title,
            String locator,
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
        public static TraceHit from(Knowledge.Hit hit) {
            return new TraceHit(
                    hit.entryId(),
                    hit.chunkIndex(),
                    hit.entry() != null ? hit.entry().title() : "",
                    hit.locator(),
                    hit.charStart(),
                    hit.charEnd(),
                    hit.lexicalRank(),
                    hit.lexicalScore(),
                    hit.vectorRank(),
                    hit.vectorScoreRaw(),
                    hit.vectorScoreTransformed(),
                    hit.rrfScore(),
                    hit.admittedBy(),
                    hit.gateNote()
            );
        }
    }

    /**
     * 检索透视汇总数据包。
     */
    public record TraceData(
            String embeddingMode,
            int lexicalCandidates,
            int vectorCandidates,
            List<TraceHit> hits
    ) {}

    /**
     * 同步问答入口（JSON 端点与评测路径使用）。
     */
    public Chat.Answer answer(Chat.Question request) throws Exception {
        return answer(request, AnswerListener.NOOP);
    }

    /**
     * 带进度监听的问答入口（SSE 流式端点使用）。
     * 执行顺序严格保证：route -> trace -> progress -> answer。
     */
    public Chat.Answer answer(Chat.Question request, AnswerListener listener) throws Exception {
        String q = request.question() == null ? "" : request.question().strip();
        if (q.isEmpty() || q.length() > 2000) {
            throw new IllegalArgumentException("请输入1至2000字的问题");
        }

        // 1. 多轮指代消解 (Minimal multi-turn resolution / Scope B & design D5)
        String resolvedQuestion = null;
        String effectiveQuestion = q;
        if (request.history() != null && !request.history().isEmpty()) {
            String rewritten = resolveMultiTurn(q, request.history(), knowledge);
            if (rewritten != null && !rewritten.equals(q)) {
                resolvedQuestion = rewritten;
                effectiveQuestion = rewritten;
            }
        }

        if (listener.isCancelled()) return null;

        // 2. 意图路由判定 (Local / Remote JEV)
        ModelHttp.Decision decision = local(effectiveQuestion, "local");
        if (!settings.get("jev-key").isBlank()) {
            try {
                var remote = http.route(effectiveQuestion);
                double threshold = Double.parseDouble(settings.get("jev-threshold"));
                if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) threshold = 0.65;
                decision = remote.confidence() >= threshold ? remote : local(effectiveQuestion, "local-low-confidence");
            } catch (Exception unavailable) {
                decision = local(effectiveQuestion, "local-jev-unavailable");
            }
        }

        // 确定性收窄守卫：任何带有约束（领域限定、属性、否定、比较）的提问一律走检索+门禁，不可被高置信度远程决策带偏
        if (List.of("skills", "projects", "profile").contains(decision.intent())) {
            if (!isGenuinelyUnrestrictedOverview(effectiveQuestion, decision.intent())) {
                decision = new ModelHttp.Decision("retrieve", -1, decision.provider(), "narrowing-guard(constrained-overview)");
            }
        }

        if (listener.isCancelled()) return null;
        listener.onRoute(decision);

        // 3. 根据意图分支处理
        String type = switch (decision.intent()) {
            case "skills" -> "skill";
            case "projects" -> "project";
            case "profile" -> "profile";
            default -> "";
        };

        // 3.1 结构化总览意图
        if (!type.isEmpty()) {
            TraceData emptyTrace = new TraceData(knowledge.embeddingMode(), 0, 0, List.of());
            if (listener.isCancelled()) return null;
            listener.onTrace(emptyTrace);
            listener.onProgress("ready", "正在整理公开资料结构化清单…");

            List<Entry> entries = knowledge.ofType(type);
            StringBuilder answerText = new StringBuilder();
            List<Chat.Source> sources = new ArrayList<>();
            List<Chat.Skill> skills = new ArrayList<>();

            for (Entry e : entries) {
                int n = sources.size() + 1;
                String citable = e.searchableContent();
                sources.add(new Chat.Source(n, e.title(), e.source(), "完整条目", e.updatedAt(), citable, e.id(), 0, citable.length()));
                answerText.append(e.title()).append("：").append(e.content());
                if (e.level() != null) answerText.append("\n熟练程度：").append(e.level());
                if (e.boundaries() != null) answerText.append("\n范围限制：").append(e.boundaries());
                answerText.append(" [").append(n).append("]\n\n");
                if (type.equals("skill")) {
                    List<String> evidence = new ArrayList<>();
                    if (e.evidenceIds() != null) {
                        for (Entry proof : knowledge.ofType("evidence")) {
                            if (e.evidenceIds().contains(proof.id())) {
                                int proofNumber = sources.size() + 1;
                                sources.add(new Chat.Source(proofNumber, proof.title(), proof.source(), "完整证据条目", proof.updatedAt(), proof.content(), proof.id(), 0, proof.content().length()));
                                evidence.add(proof.title() + " [" + proofNumber + "]");
                            }
                        }
                    }
                    skills.add(new Chat.Skill(e.title(), e.content(), e.level(), e.boundaries(),
                            evidence.isEmpty() ? List.of("暂无公开证据，仅条目自述") : List.copyOf(evidence), e.updatedAt()));
                }
            }

            Chat.Answer ans = new Chat.Answer(
                    answerText.isEmpty() ? "暂无已批准的公开条目。" : answerText.toString().strip(),
                    "structured", decision, knowledge.embeddingMode(), sources, skills, resolvedQuestion, emptyTrace.hits());
            if (listener.isCancelled()) return null;
            listener.onAnswer(ans);
            return ans;
        }

        // 3.2 寒暄/未知意图
        if (decision.intent().equals("unknown")) {
            TraceData emptyTrace = new TraceData(knowledge.embeddingMode(), 0, 0, List.of());
            if (listener.isCancelled()) return null;
            listener.onTrace(emptyTrace);
            listener.onProgress("ready", "准备回答…");

            Chat.Answer ans = new Chat.Answer(
                    "你好，这里仅回答已批准的公开资料。可以问：你会什么、介绍一下自己、有哪些项目。",
                    "local", decision, knowledge.embeddingMode(), List.of(), List.of(), resolvedQuestion, emptyTrace.hits());
            if (listener.isCancelled()) return null;
            listener.onAnswer(ans);
            return ans;
        }

        // 3.3 检索路径 (retrieve)
        listener.onProgress("searching", "正在执行混合检索（BM25 + 向量检索）…");
        var result = knowledge.search(effectiveQuestion);
        List<TraceHit> traceHits = result.hits().stream().map(TraceHit::from).toList();
        TraceData traceData = new TraceData(result.embeddingMode(), result.lexicalCandidates(), result.vectorCandidates(), traceHits);

        if (listener.isCancelled()) return null;
        listener.onTrace(traceData);

        // 无依据拒答 (insufficient-evidence)
        if (result.hits().isEmpty()) {
            Chat.Answer ans = new Chat.Answer(
                    "公开资料中没有足够依据回答这个问题。请补充问题或等待资料更新。",
                    "insufficient-evidence", decision, result.embeddingMode(), List.of(), List.of(), resolvedQuestion, traceHits);
            if (listener.isCancelled()) return null;
            listener.onAnswer(ans);
            return ans;
        }

        // 整理来源卡与上下文
        List<Chat.Source> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (var hit : result.hits()) {
            int n = sources.size() + 1;
            sources.add(new Chat.Source(n, hit.entry().title(), hit.entry().source(), hit.locator(),
                    hit.entry().updatedAt(), hit.text(), hit.entry().id(), hit.charStart(), hit.charEnd()));
            context.append("[").append(n).append("] ").append(hit.entry().title()).append("\n").append(hit.text()).append("\n");
        }

        String fallbackAnswer = "根据公开资料摘录：\n" + context.toString().strip();
        String finalAnswer = fallbackAnswer;
        String mode = "local-extract";

        // 远程大模型生成与引用校验
        if (settings.hasGeneration()) {
            listener.onProgress("generating", "正在请求大模型生成带引用的回答…");
            try {
                if (listener.isCancelled()) return null;
                String generated = http.generate(effectiveQuestion, context.toString());
                listener.onProgress("validating", "正在校验生成结果的引用编号有效性…");

                if (validateCitations(generated, sources.size())) {
                    finalAnswer = generated;
                    mode = "http-generated";
                } else {
                    listener.onProgress("fallback", "引用编号校验未通过，降级为资料摘录…");
                    mode = "local-generation-unavailable";
                }
            } catch (Exception unavailable) {
                listener.onProgress("fallback", "远程模型不可用，降级为资料摘录…");
                mode = "local-generation-unavailable";
            }
        } else {
            listener.onProgress("extracting", "本地模式：整理公开资料摘录完成");
        }

        if (listener.isCancelled()) return null;
        Chat.Answer ans = new Chat.Answer(
                finalAnswer, mode, decision, result.embeddingMode(),
                List.copyOf(sources), List.of(), resolvedQuestion, traceHits);
        listener.onAnswer(ans);
        return ans;
    }

    /**
     * 引用编号有效性校验（全项目唯一权威实现，D6）。
     * 规则：
     * 1. 至少包含一个 [n] 格式的引用；
     * 2. 所有 [n] 引用的数字必须在 [1, sourcesCount] 范围内；
     * 3. 校验的是「引用编号有效」，不夸大为「论断已被支持」。
     */
    public static boolean validateCitations(String text, int sourcesCount) {
        if (text == null || text.isBlank() || sourcesCount <= 0) return false;
        var matcher = Pattern.compile("\\[(\\d+)]").matcher(text);
        int valid = 0;
        while (matcher.find()) {
            try {
                int n = Integer.parseInt(matcher.group(1));
                if (n < 1 || n > sourcesCount) return false;
                valid++;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return valid > 0;
    }

    // -------------------------------------------------------------
    // 多轮指代消解逻辑 (Minimal multi-turn resolution)
    // -------------------------------------------------------------
    public static final int MAX_HISTORY_TURNS = 10;
    public static final int MAX_TURN_CHARS = 1000;
    public static final int MAX_TOTAL_HISTORY_CHARS = 4000;

    public static List<Chat.Turn> sanitizeHistory(List<Chat.Turn> rawHistory) {
        if (rawHistory == null || rawHistory.isEmpty()) return List.of();
        List<Chat.Turn> valid = new ArrayList<>();
        Set<String> allowedRoles = Set.of("user", "assistant", "system");
        for (Chat.Turn t : rawHistory) {
            if (t == null || t.role() == null || t.text() == null) continue;
            String role = t.role().strip().toLowerCase(Locale.ROOT);
            if (!allowedRoles.contains(role)) continue;
            String text = t.text().strip();
            if (text.isEmpty()) continue;
            if (text.length() > MAX_TURN_CHARS) {
                text = text.substring(0, MAX_TURN_CHARS);
            }
            valid.add(new Chat.Turn(role, text));
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

    public static String cleanTitle(String title) {
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

    public static String resolveMultiTurn(String question, List<Chat.Turn> rawHistory, Knowledge knowledge) {
        if (rawHistory == null || rawHistory.isEmpty()) return null;
        List<Chat.Turn> history = sanitizeHistory(rawHistory);
        if (history.isEmpty()) return null;

        RefMatch refMatch = findReference(question);
        if (refMatch == null) return null;

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

        if (candidates.size() != 1) return null;

        Entry topic = candidates.getFirst();
        String topicName = cleanTitle(topic.title());
        if (topicName.isEmpty()) return null;

        if (question.contains(topicName) || matchesEntry(topic, question)) {
            return null;
        }

        String ref = refMatch.matchedRef();
        int idx = question.indexOf(ref);
        if (idx < 0) return null;

        String prefix = question.substring(0, idx);
        String suffix = question.substring(idx + ref.length());

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

    // -------------------------------------------------------------
    // 本地保守意图路由 (Router v2 / design D4)
    // -------------------------------------------------------------
    public static ModelHttp.Decision local(String question, String provider) {
        String q = question.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}]", "");

        // 第一层：精确匹配总览规则 (Layer 1: Exact Match)
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

        // 第二层：保守总览判定 (Layer 2: Conservative Overview vs Retrieval)
        String lower = question.toLowerCase(Locale.ROOT);

        // 1. 否定/排除词检测
        for (String neg : List.of("除了", "除去", "除外", "排除", "不包括", "不包含", "不含", "不用", "不要", "不是", "不会", "不能", "不擅长", "没有", "未曾", "从未", "未做过", "非")) {
            if (lower.contains(neg)) {
                return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(negation:" + neg + ")");
            }
        }

        // 指代与具体限定词
        for (String dem : List.of("这个项目", "该项目", "那个项目", "这项目", "那项目", "本项目", "这个系统", "该系统", "那个系统", "这系统", "那系统", "本系统", "这个", "那个", "这些", "那些", "某项", "某个", "单一", "单个", "这门", "这项", "这篇", "这里")) {
            if (lower.contains(dem)) {
                return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(demonstrative:" + dem + ")");
            }
        }

        // 具体技术实体：包含英文单词
        if (Pattern.compile("[a-zA-Z]").matcher(lower).find()) {
            return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(specific-entity:ascii)");
        }
        // 具体中文领域词
        for (String ent : List.of("奶茶", "点评", "秒杀", "一人一单", "关单", "超时", "延迟队列", "购物车", "订单", "缓存", "分身", "数字分身", "并发", "线程池", "分布式", "锁", "数据库", "自测", "爬虫", "向量", "架构", "设计", "原理", "源码", "微服务", "网关", "熔断", "链路追踪", "流式", "未做过", "自述", "虚构")) {
            if (lower.contains(ent)) {
                return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(specific-entity:" + ent + ")");
            }
        }

        // 比较/最高级/指定选择
        for (String cmp : List.of("最", "更", "比较", "哪一个", "哪个", "哪项", "哪部", "哪家", "首个", "第一", "核心", "优先")) {
            if (lower.contains(cmp)) {
                return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(comparison:" + cmp + ")");
            }
        }

        // 追问/约束
        for (String cst : List.of("为什么", "为何", "怎么", "如何", "怎样", "多少", "多大", "多久", "多深", "哪里", "何处", "什么水平", "到什么水平", "程度", "水平")) {
            if (lower.contains(cst)) {
                return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(constraint:" + cst + ")");
            }
        }

        // 疑问助词
        for (String pol : List.of("吗", "么", "是不是", "会不会", "能不能", "懂不懂", "有没有", "行不行", "可否")) {
            if (lower.contains(pol)) {
                return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(confirmation:" + pol + ")");
            }
        }

        // 2. 类别识别
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

        // 3. 明确总览意图信号
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
            if (isGenuinelyUnrestrictedOverview(question, targetCategory)) {
                return new ModelHttp.Decision(targetCategory, -1, provider, "layer2:overview:" + targetCategory);
            }
            return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(constrained-overview)");
        }

        return new ModelHttp.Decision("retrieve", -1, provider, "layer2:retrieve(no-overview-intent)");
    }

    private static final List<String> COMMON_OVERVIEW_TOKENS = List.of(
            "做过哪些", "参与过哪些", "有哪些经历", "都有哪些", "包含哪些", "包括哪些",
            "拿得出手的", "项目经历", "项目清单", "项目列表", "你会什么", "你有哪些技能",
            "技能清单", "技能列表", "自我介绍", "介绍一下自己", "介绍自己", "个人信息",
            "个人资料", "个人背景", "基本情况", "有哪些", "有什么", "有啥", "做过", "参与过",
            "会什么", "懂什么", "列一下", "列出", "列举", "清单", "列表", "全貌",
            "全部", "所有", "整体", "总览", "概览", "汇总", "盘点", "一览",
            "梳理", "说说", "告诉", "聊聊", "看看", "详细", "简要", "简介",
            "简述", "概述", "介绍", "哪些", "什么", "自己", "个人", "情况",
            "背景", "简历", "你是谁", "你", "您", "你的", "您的", "请",
            "帮我", "给我", "麻烦", "能否", "可以", "一下", "下", "列",
            "的", "了", "过", "啊", "呀", "吧", "呢", "么"
    );

    private static final List<String> PROJECT_SPECIFIC_OVERVIEW_TOKENS = List.of(
            "项目", "工程", "作品", "经历"
    );

    private static final List<String> SKILL_SPECIFIC_OVERVIEW_TOKENS = List.of(
            "技能", "能力", "本领", "技术栈", "技术", "擅长"
    );

    private static final List<String> PROFILE_SPECIFIC_OVERVIEW_TOKENS = List.of(
            "自我介绍", "个人信息", "个人资料", "个人背景", "基本情况", "简历"
    );

    public static boolean isGenuinelyUnrestrictedOverview(String question, String targetCategory) {
        if (question == null || question.isBlank()) return false;
        String q = question.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}]", "");
        switch (q) {
            case "你会什么", "技能", "你有哪些技能", "whatcanyoudo", "skills" -> {
                if ("skills".equals(targetCategory)) return true;
            }
            case "介绍一下自己", "你是谁", "自我介绍", "介绍自己", "profile" -> {
                if ("profile".equals(targetCategory)) return true;
            }
            case "有哪些项目", "你做过哪些项目", "项目经历", "projects" -> {
                if ("projects".equals(targetCategory)) return true;
            }
        }

        String lower = question.toLowerCase(Locale.ROOT);

        // 1. 约束与排除检测
        for (String neg : List.of("除了", "除去", "除外", "排除", "不包括", "不包含", "不含", "不用", "不要", "不是", "不会", "不能", "不擅长", "没有", "未曾", "从未", "未做过", "非")) {
            if (lower.contains(neg)) return false;
        }
        for (String dem : List.of("这个项目", "该项目", "那个项目", "这项目", "那项目", "本项目", "这个系统", "该系统", "那个系统", "这系统", "那系统", "本系统", "这个", "那个", "这些", "那些", "某项", "某个", "单一", "单个", "这门", "这项", "这篇", "这里")) {
            if (lower.contains(dem)) return false;
        }
        if (Pattern.compile("[a-zA-Z0-9]").matcher(lower).find()) {
            return false;
        }
        for (String ent : List.of("奶茶", "点评", "秒杀", "一人一单", "关单", "超时", "延迟队列", "购物车", "订单", "缓存", "分身", "数字分身", "并发", "线程池", "分布式", "锁", "数据库", "自测", "爬虫", "向量", "架构", "设计", "原理", "源码", "微服务", "网关", "熔断", "链路追踪", "流式", "未做过", "自述", "虚构")) {
            if (lower.contains(ent)) return false;
        }
        for (String cmp : List.of("最", "更", "比较", "哪一个", "哪个", "哪项", "哪部", "哪家", "首个", "第一", "核心", "优先")) {
            if (lower.contains(cmp)) return false;
        }
        for (String cst : List.of("为什么", "为何", "怎么", "如何", "怎样", "多少", "多大", "多久", "多深", "哪里", "何处", "什么水平", "到什么水平", "程度", "水平")) {
            if (lower.contains(cst)) return false;
        }
        String polCheck = lower.replace("什么", "").replace("怎么", "").replace("多么", "");
        for (String pol : List.of("吗", "么", "是不是", "会不会", "能不能", "懂不懂", "有没有", "行不行", "可否")) {
            if (polCheck.contains(pol)) return false;
        }

        // 2. 剥离无约束总览词库，检查是否有任何修饰性残留（如“支付”、“金融”、“前端”等）
        List<String> allowed = new ArrayList<>(COMMON_OVERVIEW_TOKENS);
        switch (targetCategory) {
            case "skills" -> allowed.addAll(SKILL_SPECIFIC_OVERVIEW_TOKENS);
            case "projects" -> allowed.addAll(PROJECT_SPECIFIC_OVERVIEW_TOKENS);
            case "profile" -> allowed.addAll(PROFILE_SPECIFIC_OVERVIEW_TOKENS);
            default -> { return false; }
        }
        allowed.sort(Comparator.comparingInt(String::length).reversed());

        String remainder = lower.replaceAll("[\\s\\p{P}\\p{S}]", "");
        for (String token : allowed) {
            remainder = remainder.replace(token, "");
        }
        return remainder.isEmpty();
    }
}
