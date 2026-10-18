package local.digital;

public class HttpEmbedding implements EmbeddingProvider {
    private final ModelHttp http;
    private volatile int dimensions = -1;

    public HttpEmbedding(ModelHttp http) {
        this.http = http;
    }

    @Override
    public float[] embedDocument(String text) throws Exception {
        float[] v = http.embed(text);
        if (dimensions == -1) dimensions = v.length;
        return v;
    }

    @Override
    public float[] embedQuery(String text) throws Exception {
        float[] v = http.embed(text);
        if (dimensions == -1) dimensions = v.length;
        return v;
    }

    @Override
    public int dimensions() {
        if (dimensions == -1) {
            try {
                float[] v = http.embed("probe");
                dimensions = v.length;
            } catch (Exception e) {
                return -1;
            }
        }
        return dimensions;
    }

    @Override
    public String mode() {
        return "http";
    }
}
