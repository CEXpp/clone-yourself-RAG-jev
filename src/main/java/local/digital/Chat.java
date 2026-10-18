package local.digital;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Lazy
@RestController
@RequestMapping("/api")
public class Chat {
    private final AnswerService answerService;
    private final Knowledge knowledge;
    private final Settings settings;
    private final ModelHttp http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public Chat(AnswerService answerService, Knowledge knowledge, Settings settings, ModelHttp http) {
        this.answerService = answerService;
        this.knowledge = knowledge;
        this.settings = settings;
        this.http = http;
    }

    public Chat(Knowledge knowledge, Settings settings, ModelHttp http) {
        this(new AnswerService(knowledge, settings, http), knowledge, settings, http);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "publicEntries", knowledge.count(),
                "embedding", knowledge.embeddingMode(),
                "generation", settings.hasGeneration() ? "http-enabled" : "local-extract",
                "routing", settings.get("jev-key").isBlank() ? "local" : "jev-enabled",
                "demoData", Boolean.parseBoolean(settings.get("demo-data")),
                "java", Runtime.version().feature());
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody Question request) throws Exception {
        String q = request.question() == null ? "" : request.question().strip();
        if (q.isEmpty() || q.length() > 2000) {
            return ResponseEntity.badRequest().body(Map.of("error", "请输入1至2000字的问题"));
        }
        return ResponseEntity.ok(answerService.answer(request));
    }

    /**
     * SSE 进度流接口 (POST /api/chat/stream)。
     * 事件顺序严格遵循规范 (D6)：
     * 1. route (路由决策及 explanation)
     * 2. trace (Knowledge 检索透视命中数据，包含 ranks/scores/admittedBy/gateNote)
     * 3. progress (生成或阶段进展提示)
     * 4. answer (校验通过后的完整回答 payload，或引用失效时的摘录回退)
     * 5. error (仅在出错时发出的脱敏错误)
     * 6. done (流终局标志)
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody Question request) {
        String q = request.question() == null ? "" : request.question().strip();
        SseEmitter emitter = new SseEmitter(60_000L);
        AtomicBoolean active = new AtomicBoolean(true);

        emitter.onCompletion(() -> active.set(false));
        emitter.onTimeout(() -> active.set(false));
        emitter.onError(e -> active.set(false));

        if (q.isEmpty() || q.length() > 2000) {
            sendEvent(emitter, active, "error", Map.of("error", "请输入1至2000字的问题"));
            sendEvent(emitter, active, "done", Map.of("status", "error"));
            emitter.complete();
            return emitter;
        }

        executor.submit(() -> {
            try {
                answerService.answer(request, new AnswerService.AnswerListener() {
                    @Override
                    public boolean isCancelled() {
                        return !active.get();
                    }

                    @Override
                    public void onRoute(ModelHttp.Decision decision) {
                        sendEvent(emitter, active, "route", decision);
                    }

                    @Override
                    public void onTrace(AnswerService.TraceData trace) {
                        sendEvent(emitter, active, "trace", trace);
                    }

                    @Override
                    public void onProgress(String stage, String message) {
                        sendEvent(emitter, active, "progress", Map.of("stage", stage, "message", message));
                    }

                    @Override
                    public void onAnswer(Answer answer) {
                        sendEvent(emitter, active, "answer", answer);
                    }
                });

                if (active.get()) {
                    sendEvent(emitter, active, "done", Map.of("status", "completed"));
                    emitter.complete();
                }
            } catch (Exception e) {
                if (active.get()) {
                    sendEvent(emitter, active, "error", Map.of("error", "请求未完成，请检查输入或稍后重试"));
                    sendEvent(emitter, active, "done", Map.of("status", "error"));
                    emitter.complete();
                }
            }
        });

        return emitter;
    }

    private static void sendEvent(SseEmitter emitter, AtomicBoolean active, String name, Object data) {
        if (!active.get()) return;
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            active.set(false);
        }
    }

    @PreDestroy
    public void destroy() {
        executor.shutdown();
    }

    // -------------------------------------------------------------
    // 向后兼容的代理方法与常量 (保证已有单元测试无需签名修改即可运行)
    // -------------------------------------------------------------
    public static final int MAX_HISTORY_TURNS = AnswerService.MAX_HISTORY_TURNS;
    public static final int MAX_TURN_CHARS = AnswerService.MAX_TURN_CHARS;
    public static final int MAX_TOTAL_HISTORY_CHARS = AnswerService.MAX_TOTAL_HISTORY_CHARS;

    public static List<Turn> sanitizeHistory(List<Turn> rawHistory) {
        return AnswerService.sanitizeHistory(rawHistory);
    }

    public static String resolveMultiTurn(String question, List<Turn> rawHistory, Knowledge knowledge) {
        return AnswerService.resolveMultiTurn(question, rawHistory, knowledge);
    }

    public static ModelHttp.Decision local(String question, String provider) {
        return AnswerService.local(question, provider);
    }

    // -------------------------------------------------------------
    // 数据模型 (Records)
    // -------------------------------------------------------------
    public record Turn(String role, String text) {}

    public record Question(String question, List<Turn> history) {
        public Question(String question) {
            this(question, null);
        }
    }

    public record Source(
            int number,
            String title,
            String origin,
            String locator,
            String updatedAt,
            String excerpt,
            String entryId,
            Integer charStart,
            Integer charEnd
    ) {
        public Source(int number, String title, String origin, String locator, String updatedAt, String excerpt, String entryId) {
            this(number, title, origin, locator, updatedAt, excerpt, entryId, null, null);
        }
    }

    public record Skill(String name, String tasks, String level, String boundaries, List<String> evidence, String updatedAt) {}

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Answer(
            String answer,
            String mode,
            ModelHttp.Decision routing,
            String embeddingMode,
            List<Source> sources,
            List<Skill> skills,
            String resolvedQuestion,
            List<AnswerService.TraceHit> trace
    ) {
        public Answer(String answer, String mode, ModelHttp.Decision routing, String embeddingMode, List<Source> sources, List<Skill> skills) {
            this(answer, mode, routing, embeddingMode, sources, skills, null, null);
        }

        public Answer(String answer, String mode, ModelHttp.Decision routing, String embeddingMode, List<Source> sources, List<Skill> skills, String resolvedQuestion) {
            this(answer, mode, routing, embeddingMode, sources, skills, resolvedQuestion, null);
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
