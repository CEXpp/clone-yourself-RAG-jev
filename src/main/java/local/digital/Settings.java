package local.digital;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class Settings {
    private final Environment env;
    public Settings(Environment env) { this.env = env; }
    public String get(String key) { return env.getProperty("app." + key, ""); }
    public int timeout() {
        String v = get("http-timeout-seconds");
        if (v.isBlank()) return 8;
        try { return Math.clamp(Integer.parseInt(v), 1, 60); } catch (Exception e) { return 8; }
    }
    public boolean hasGeneration() { return !get("generation-key").isBlank() && !get("generation-model").isBlank(); }
    public boolean hasEmbedding() { return !get("embedding-key").isBlank() && !get("embedding-model").isBlank(); }
    public String embeddingMode() { String m = get("embedding-mode"); return m.isBlank() ? "auto" : m; }
    public String modelDir() { String d = get("model-dir"); return d.isBlank() ? "models" : d; }
    public String indexDir() { return get("index-dir"); }
    public double lexicalCoverage() {
        String v = get("lexical-coverage");
        if (v.isBlank()) return 0.5;
        try { return Double.parseDouble(v); } catch (Exception e) { return 0.5; }
    }
    public Float vectorThreshold() {
        String v = get("vector-threshold");
        if (v.isBlank()) return null;
        try { return Float.parseFloat(v); } catch (Exception e) { return null; }
    }
    public Settings withDisabledGeneration() {
        return new Settings(this.env) {
            @Override public boolean hasGeneration() { return false; }
        };
    }
}
