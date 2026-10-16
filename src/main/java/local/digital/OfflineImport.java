package local.digital;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

public final class OfflineImport {
    private OfflineImport() {}
    static boolean requested(String[] args) {
        return Arrays.stream(args).anyMatch(a -> a.startsWith("--app.import-file="));
    }
    static void run(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                String[] pair = arg.substring(2).split("=", 2); options.put(pair[0], pair[1]);
            }
        }
        Path input = Path.of(options.getOrDefault("app.import-file", ""));
        Path data = Path.of(options.getOrDefault("app.data-dir", System.getenv().getOrDefault("DATA_DIR", "./data")));
        boolean approve = "true".equals(options.get("app.approve-public"));
        var counts = importFile(input, data, approve);
        System.out.println("离线导入完成：private=" + counts[0] + ", public=" + counts[1]
                + "。重启应用重建索引；本操作未调用远程服务。");
    }
    public static int[] importFile(Path input, Path data, boolean approve) throws Exception {
        if (!Files.isRegularFile(input) || Files.size(input) > 50L * 1024 * 1024)
            throw new IllegalArgumentException("输入须为不超过50MB的本地文件");
        var mapper = new ObjectMapper();
        String name = input.getFileName().toString();
        List<Entry> entries;
        String extension = name.toLowerCase(Locale.ROOT);
        if (extension.endsWith(".json")) {
            entries = mapper.readValue(Files.readString(input, StandardCharsets.UTF_8), new TypeReference<List<Entry>>() {});
        } else if (extension.endsWith(".pdf")) {
            entries = new ArrayList<>();
            try (var pdf = Loader.loadPDF(input.toFile())) {
                for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                    var stripper = new PDFTextStripper(); stripper.setStartPage(page); stripper.setEndPage(page);
                    String text = stripper.getText(pdf).strip();
                    if (!text.isEmpty()) entries.add(document(text, name, "第 " + page + " 页"));
                }
            }
            if (entries.isEmpty()) throw new IllegalArgumentException("PDF没有可提取文本；MVP不含OCR");
        } else if (extension.endsWith(".md") || extension.endsWith(".txt")) {
            String text = Files.readString(input, StandardCharsets.UTF_8);
            entries = new ArrayList<>();
            String[] lines = text.split("\n", -1);
            for (int from = 0; from < lines.length; from += 80) {
                int to = Math.min(lines.length, from + 80);
                String block = String.join("\n", Arrays.copyOfRange(lines, from, to)).strip();
                if (!block.isEmpty()) entries.add(document(block, name, "第 " + (from + 1) + "-" + to + " 行"));
            }
        } else throw new IllegalArgumentException("支持 JSON、UTF-8 TXT/Markdown 和文本型 PDF");
        if (entries.isEmpty()) throw new IllegalArgumentException("没有可导入条目");
        Set<String> ids = new HashSet<>();
        for (Entry e : entries) {
            e.validate();
            if (!ids.add(e.id())) throw new IllegalArgumentException("同一批次 id 重复");
        }
        var privateEntries = new ArrayList<Entry>(); var publicEntries = new ArrayList<Entry>();
        for (Entry e : entries) {
            if (approve && e.approved()) publicEntries.add(e); else privateEntries.add(e.asPrivate());
        }
        write(mapper, data.resolve("private"), privateEntries);
        write(mapper, data.resolve("public"), publicEntries);
        return new int[]{privateEntries.size(), publicEntries.size()};
    }
    private static Entry document(String text, String file, String locator) {
        return new Entry("doc-" + UUID.randomUUID(), "document", file, text, null, null,
                List.of(), "private", false, file + " / " + locator, LocalDate.now().toString());
    }
    private static void write(ObjectMapper mapper, Path dir, List<Entry> entries) throws Exception {
        if (entries.isEmpty()) return;
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, ".import-", ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), entries);
            Files.move(temp, dir.resolve("import-" + UUID.randomUUID() + ".json"), StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }
}
