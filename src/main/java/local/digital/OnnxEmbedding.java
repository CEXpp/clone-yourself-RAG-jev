package local.digital;

import ai.onnxruntime.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class OnnxEmbedding implements EmbeddingProvider, AutoCloseable {
    public static final String QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章：";
    private final Tokenizer tokenizer;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final int dimensions = 512;

    public record TokenSpan(String token, int start, int end) {}

    public static class Tokenizer {
        public static final int MAX_LEN = 512;
        private final Map<String, Integer> vocab = new HashMap<>();

        public Tokenizer() {}

        public Tokenizer(Path vocabFile) throws IOException {
            List<String> lines = Files.readAllLines(vocabFile, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) vocab.put(lines.get(i), i);
        }

        public Tokenizer(List<String> vocabList) {
            for (int i = 0; i < vocabList.size(); i++) vocab.put(vocabList.get(i), i);
        }

        public static Tokenizer create(Path modelDir) {
            if (modelDir != null) {
                Path vocabFile = modelDir.resolve("vocab.txt");
                if (Files.isRegularFile(vocabFile)) {
                    try { return new Tokenizer(vocabFile); } catch (Exception ignored) {}
                }
            }
            Path defaultVocab = Path.of("models", "vocab.txt");
            if (Files.isRegularFile(defaultVocab)) {
                try { return new Tokenizer(defaultVocab); } catch (Exception ignored) {}
            }
            Path spikeVocab = Path.of("/home/hatch/workspace/projects/spike/model/vocab.txt");
            if (Files.isRegularFile(spikeVocab)) {
                try { return new Tokenizer(spikeVocab); } catch (Exception ignored) {}
            }
            return new Tokenizer();
        }

        public List<String> tokenize(String text) {
            List<String> tokens = new ArrayList<>();
            tokens.add("[CLS]");
            for (String word : basicTokenize(text)) tokens.addAll(wordPiece(word));
            tokens.add("[SEP]");
            if (tokens.size() > MAX_LEN) {
                tokens = new ArrayList<>(tokens.subList(0, MAX_LEN - 1));
                tokens.add("[SEP]");
            }
            return tokens;
        }

        public List<TokenSpan> tokenizeWithSpans(String text) {
            List<TokenSpan> spans = new ArrayList<>();
            int curStart = -1;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isWhitespace(c)) {
                    if (curStart != -1) {
                        flushWordSpans(text, curStart, i, spans);
                        curStart = -1;
                    }
                } else if (isCjk(c) || isPunct(c)) {
                    if (curStart != -1) {
                        flushWordSpans(text, curStart, i, spans);
                        curStart = -1;
                    }
                    flushWordSpans(text, i, i + 1, spans);
                } else {
                    if (curStart == -1) {
                        curStart = i;
                    }
                }
            }
            if (curStart != -1) {
                flushWordSpans(text, curStart, text.length(), spans);
            }
            return spans;
        }

        private void flushWordSpans(String text, int start, int end, List<TokenSpan> spans) {
            String word = text.substring(start, end);
            List<String> pieces = wordPiece(word.toLowerCase(Locale.ROOT));
            for (String piece : pieces) {
                spans.add(new TokenSpan(piece, start, end));
            }
        }

        public List<Integer> encode(String text) {
            int unkId = vocab.getOrDefault("[UNK]", 100);
            List<Integer> ids = new ArrayList<>();
            for (String t : tokenize(text)) ids.add(vocab.getOrDefault(t, unkId));
            return ids;
        }

        public List<String> basicTokenize(String text) {
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
                if (Character.isWhitespace(c)) { flush(out, cur); }
                else if (isCjk(c) || isPunct(c)) { flush(out, cur); out.add(String.valueOf(c)); }
                else cur.append(c);
            }
            flush(out, cur);
            return out;
        }

        private static void flush(List<String> out, StringBuilder cur) {
            if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
        }

        public static boolean isCjk(char c) {
            return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
        }

        public static boolean isPunct(char c) {
            return !Character.isLetterOrDigit(c) && !Character.isWhitespace(c);
        }

        public List<String> wordPiece(String word) {
            if (vocab.isEmpty() || word.length() == 1 || vocab.containsKey(word)) return List.of(word);
            List<String> pieces = new ArrayList<>();
            int start = 0;
            while (start < word.length()) {
                int end = word.length();
                String hit = null;
                while (start < end) {
                    String sub = word.substring(start, end);
                    if (start > 0) sub = "##" + sub;
                    if (vocab.containsKey(sub)) { hit = sub; break; }
                    end--;
                }
                if (hit == null) return List.of("[UNK]");
                pieces.add(hit);
                start = end;
            }
            return pieces;
        }

        public Map<String, Integer> vocab() {
            return Collections.unmodifiableMap(vocab);
        }
    }

    public OnnxEmbedding(Path modelDir) {
        Path modelPath = modelDir.resolve("model.onnx");
        Path vocabPath = modelDir.resolve("vocab.txt");
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalStateException("ONNX 模型文件不存在: " + modelPath);
        }
        if (!Files.isRegularFile(vocabPath)) {
            throw new IllegalStateException("ONNX 词表文件不存在: " + vocabPath);
        }
        try {
            this.tokenizer = new Tokenizer(vocabPath);
        } catch (Exception e) {
            String msg = "ONNX 词表文件读取失败: " + e.getMessage();
            System.err.println(msg);
            throw new IllegalStateException(msg, e);
        }
        try {
            this.env = OrtEnvironment.getEnvironment();
            try (var opts = new OrtSession.SessionOptions()) {
                this.session = env.createSession(modelPath.toString(), opts);
            }
        } catch (LinkageError le) {
            String msg = "ONNX Runtime 原生库加载失败 (LinkageError): " + le.getMessage();
            System.err.println(msg);
            throw new IllegalStateException(msg, le);
        } catch (OrtException oe) {
            String msg = "ONNX 模型会话创建失败: " + oe.getMessage();
            System.err.println(msg);
            throw new IllegalStateException(msg, oe);
        } catch (Exception e) {
            String msg = "ONNX 模型初始化失败: " + e.getMessage();
            System.err.println(msg);
            throw new IllegalStateException(msg, e);
        }
    }

    @Override
    public float[] embedDocument(String text) throws Exception {
        return embed(text);
    }

    @Override
    public float[] embedQuery(String text) throws Exception {
        return embed(QUERY_PREFIX + text);
    }

    public synchronized float[] embed(String text) throws Exception {
        List<Integer> ids = tokenizer.encode(text);
        long[] idsArr = ids.stream().mapToLong(Integer::longValue).toArray();
        long[] maskArr = new long[ids.size()];
        Arrays.fill(maskArr, 1L);
        long[] typeArr = new long[ids.size()];
        long[] shape = {1, ids.size()};
        try (OnnxTensor tIds = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(idsArr), shape);
             OnnxTensor tMask = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(maskArr), shape);
             OnnxTensor tType = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(typeArr), shape);
             OrtSession.Result r = session.run(Map.of("input_ids", tIds, "attention_mask", tMask, "token_type_ids", tType))) {
            float[][][] out = (float[][][]) r.get(0).getValue();
            float[] cls = out[0][0];
            double sum = 0;
            for (float f : cls) {
                if (!Float.isFinite(f)) throw new IllegalStateException("向量存在非有限值");
                sum += (double) f * f;
            }
            if (!(sum > 0)) throw new IllegalStateException("零向量");
            double norm = Math.sqrt(sum);
            float[] v = new float[cls.length];
            for (int i = 0; i < cls.length; i++) v[i] = (float) (cls[i] / norm);
            return v;
        }
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String mode() {
        return "onnx";
    }

    public Tokenizer tokenizer() {
        return tokenizer;
    }

    @Override
    public void close() throws Exception {
        if (session != null) {
            session.close();
        }
    }
}
