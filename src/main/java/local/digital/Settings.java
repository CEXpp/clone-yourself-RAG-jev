package local.digital;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class Settings {
    private final Environment env;
    public Settings(Environment env) { this.env = env; }
    public String get(String key) { return env.getProperty("app." + key, ""); }
    public int timeout() { return Math.clamp(Integer.parseInt(get("http-timeout-seconds")), 1, 60); }
    public boolean hasGeneration() { return !get("generation-key").isBlank() && !get("generation-model").isBlank(); }
    public boolean hasEmbedding() { return !get("embedding-key").isBlank() && !get("embedding-model").isBlank(); }
}
