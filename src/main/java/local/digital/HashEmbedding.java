package local.digital;

import java.util.Locale;

public class HashEmbedding implements EmbeddingProvider {
    @Override
    public float[] embedDocument(String text) {
        return hash(text);
    }

    @Override
    public float[] embedQuery(String text) {
        return hash(text);
    }

    @Override
    public int dimensions() {
        return 256;
    }

    @Override
    public String mode() {
        return "hash";
    }

    public static float[] hash(String text) {
        float[] v = new float[256];
        String cleaned = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        if (cleaned.isEmpty()) { v[0] = 1; return v; }
        for (int i = 0; i < cleaned.length(); i++) {
            String token = cleaned.substring(i, Math.min(i + 2, cleaned.length()));
            v[Math.floorMod(token.hashCode(), v.length)]++;
        }
        return ModelHttp.normalize(v);
    }
}
