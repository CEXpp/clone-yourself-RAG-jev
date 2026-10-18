package local.digital;

public interface EmbeddingProvider extends AutoCloseable {
    float[] embedDocument(String text) throws Exception;
    float[] embedQuery(String text) throws Exception;
    int dimensions();
    String mode();
    @Override default void close() throws Exception {}
}
