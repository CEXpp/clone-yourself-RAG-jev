package local.digital;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WordPieceTokenizerTest {

    @Test
    void cjkCharactersAreTokenizedIndividually() {
        var vocab = List.of("[PAD]", "[UNK]", "[CLS]", "[SEP]", "中", "文", "测", "试", "你", "好");
        var tokenizer = new OnnxEmbedding.Tokenizer(vocab);

        List<String> tokens = tokenizer.tokenize("中文测试");
        assertThat(tokens).containsExactly("[CLS]", "中", "文", "测", "试", "[SEP]");

        List<Integer> ids = tokenizer.encode("你好");
        assertThat(ids).containsExactly(vocab.indexOf("[CLS]"), vocab.indexOf("你"), vocab.indexOf("好"), vocab.indexOf("[SEP]"));
    }

    @Test
    void unknownWordsProduceUnkToken() {
        var vocab = List.of("[PAD]", "[UNK]", "[CLS]", "[SEP]", "hello");
        var tokenizer = new OnnxEmbedding.Tokenizer(vocab);

        List<String> tokens = tokenizer.tokenize("unknown");
        assertThat(tokens).containsExactly("[CLS]", "[UNK]", "[SEP]");

        List<Integer> ids = tokenizer.encode("unknown");
        assertThat(ids).containsExactly(vocab.indexOf("[CLS]"), vocab.indexOf("[UNK]"), vocab.indexOf("[SEP]"));
    }

    @Test
    void subwordsUseDoubleHashContinuation() {
        var vocab = List.of("[PAD]", "[UNK]", "[CLS]", "[SEP]", "play", "##ing", "un", "##break", "##able");
        var tokenizer = new OnnxEmbedding.Tokenizer(vocab);

        List<String> pieces = tokenizer.wordPiece("playing");
        assertThat(pieces).containsExactly("play", "##ing");

        List<String> tokens = tokenizer.tokenize("playing unbreakable");
        assertThat(tokens).containsExactly("[CLS]", "play", "##ing", "un", "##break", "##able", "[SEP]");
    }

    @Test
    void truncationCapsTokensAtMaxLenWithSep() {
        var vocab = List.of("[PAD]", "[UNK]", "[CLS]", "[SEP]", "a");
        var tokenizer = new OnnxEmbedding.Tokenizer(vocab);

        // 600 'a's would normally produce 602 tokens ([CLS] + 600 'a' + [SEP])
        String text = "a ".repeat(600);
        List<String> tokens = tokenizer.tokenize(text);

        assertThat(tokens).hasSize(OnnxEmbedding.Tokenizer.MAX_LEN);
        assertThat(tokens.getFirst()).isEqualTo("[CLS]");
        assertThat(tokens.getLast()).isEqualTo("[SEP]");
        assertThat(tokens.subList(1, OnnxEmbedding.Tokenizer.MAX_LEN - 1)).allMatch(t -> t.equals("a"));
    }

    @Test
    void realVocabFileTestsIfPresent() throws Exception {
        Path realVocab = Path.of("models", "vocab.txt");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isRegularFile(realVocab),
                "Vocabulary file not found at models/vocab.txt"
        );

        var tokenizer = new OnnxEmbedding.Tokenizer(realVocab);
        List<String> tokens = tokenizer.tokenize("检索系统");
        assertThat(tokens.getFirst()).isEqualTo("[CLS]");
        assertThat(tokens.getLast()).isEqualTo("[SEP]");
        assertThat(tokens).contains("检", "索", "系", "统");

        // Truncation on real vocab
        String longText = "测试 ".repeat(400);
        List<String> longTokens = tokenizer.tokenize(longText);
        assertThat(longTokens).hasSize(OnnxEmbedding.Tokenizer.MAX_LEN);
        assertThat(longTokens.getFirst()).isEqualTo("[CLS]");
        assertThat(longTokens.getLast()).isEqualTo("[SEP]");
    }

    @Test
    void mixedCaseTechnicalStringsFollowUpstreamDoLowerCaseFalse() throws Exception {
        Path realVocab = Path.of("models", "vocab.txt");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isRegularFile(realVocab),
                "Vocabulary file not found at models/vocab.txt"
        );
        var tokenizer = new OnnxEmbedding.Tokenizer(realVocab);

        // Mixed-case strings: because vocab.txt has do_lower_case=false and contains no uppercase letters,
        // upstream reference tokenizer produces [UNK] (id 100) for mixed-case words.
        List<Integer> springBootIds = tokenizer.encode("Spring Boot");
        assertThat(springBootIds).containsExactly(101, 100, 100, 102);

        List<Integer> langChainIds = tokenizer.encode("LangChain4j");
        assertThat(langChainIds).containsExactly(101, 100, 102);

        List<Integer> bm25Ids = tokenizer.encode("BM25");
        assertThat(bm25Ids).containsExactly(101, 100, 102);

        // Lowercase counterparts: "spring", "boot", and "25" are in vocab, matching exact token IDs
        List<Integer> lowerSpringBootIds = tokenizer.encode("spring boot");
        assertThat(lowerSpringBootIds).containsExactly(101, 11043, 13119, 102);

        List<Integer> num25Ids = tokenizer.encode("25");
        assertThat(num25Ids).containsExactly(101, 8132, 102);

        // Verify span tokenization also does not force lowercase
        var spans = tokenizer.tokenizeWithSpans("Spring Boot 框架");
        assertThat(spans.get(0).token()).isEqualTo("[UNK]");
        assertThat(spans.get(0).start()).isEqualTo(0);
        assertThat(spans.get(0).end()).isEqualTo(6);
    }

    @Test
    void tokenizeWithSpansPreservesCharacterOffsetsExactly() {
        var vocab = List.of("[PAD]", "[UNK]", "[CLS]", "[SEP]", "play", "##ing", "中", "文");
        var tokenizer = new OnnxEmbedding.Tokenizer(vocab);

        String text = "playing 中文";
        var spans = tokenizer.tokenizeWithSpans(text);

        assertThat(spans).hasSize(4);
        // "playing" splits into "play" and "##ing", both covering chars 0..7
        assertThat(spans.get(0).token()).isEqualTo("play");
        assertThat(spans.get(0).start()).isEqualTo(0);
        assertThat(spans.get(0).end()).isEqualTo(7);
        assertThat(text.substring(spans.get(0).start(), spans.get(0).end())).isEqualTo("playing");

        assertThat(spans.get(1).token()).isEqualTo("##ing");
        assertThat(spans.get(1).start()).isEqualTo(0);
        assertThat(spans.get(1).end()).isEqualTo(7);

        // "中" covers char 8..9
        assertThat(spans.get(2).token()).isEqualTo("中");
        assertThat(spans.get(2).start()).isEqualTo(8);
        assertThat(spans.get(2).end()).isEqualTo(9);
        assertThat(text.substring(spans.get(2).start(), spans.get(2).end())).isEqualTo("中");

        // "文" covers char 9..10
        assertThat(spans.get(3).token()).isEqualTo("文");
        assertThat(spans.get(3).start()).isEqualTo(9);
        assertThat(spans.get(3).end()).isEqualTo(10);
        assertThat(text.substring(spans.get(3).start(), spans.get(3).end())).isEqualTo("文");
    }

    @Test
    void fallbackTokenizerWorksWithoutVocabFile() {
        var tokenizer = new OnnxEmbedding.Tokenizer();
        var spans = tokenizer.tokenizeWithSpans("Java 开发者");
        assertThat(spans).hasSize(4); // "Java", "开", "发", "者"
        assertThat(spans.getFirst().token()).isEqualTo("Java");
        assertThat(spans.getFirst().start()).isEqualTo(0);
        assertThat(spans.getFirst().end()).isEqualTo(4);
    }
}
