package local.digital;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ImportTest {
    @TempDir Path dir;
    ObjectMapper mapper = new ObjectMapper();
    @Test void jsonRequiresBothRecordApprovalAndExplicitCommandFlag() throws Exception {
        Path file = dir.resolve("cards.json");
        mapper.writeValue(file.toFile(), List.of(MvpTest.entry("approved", "skill", "sample", "public", true),
                MvpTest.entry("unconfirmed", "skill", "sample", "public", false),
                MvpTest.entry("private", "skill", "sample", "private", true)));
        Path data = dir.resolve("default");
        assertThat(OfflineImport.importFile(file, data, false)).containsExactly(3, 0);
        try (var files = Files.list(data.resolve("private"))) {
            List<Entry> entries = mapper.readValue(files.findFirst().orElseThrow().toFile(), new TypeReference<List<Entry>>(){});
            assertThat(entries).allSatisfy(e -> {assertThat(e.visibility()).isEqualTo("private"); assertThat(e.publicConfirmed()).isFalse();});
        }
        assertThat(Files.exists(data.resolve("public"))).isFalse();
        assertThat(OfflineImport.importFile(file, dir.resolve("explicit"), true)).containsExactly(2, 1);
    }
    @Test void rawTextRemainsPrivateEvenWithApprovalFlag() throws Exception {
        Path file = dir.resolve("notes.md"); Files.writeString(file, "# Synthetic source\nNot a real user record");
        Path data = dir.resolve("store");
        assertThat(OfflineImport.importFile(file, data, true)).containsExactly(1, 0);
        try (var files = Files.list(data.resolve("private"))) {
            String json = Files.readString(files.findFirst().orElseThrow());
            assertThat(json).contains("第 1-2 行", "private");
        }
    }
    @Test void pdfExtractsAllPagesWithPageLocators() throws Exception {
        Path file = dir.resolve("synthetic.pdf");
        try (var pdf = new PDDocument()) {
            for (int n=1; n<=2; n++) {
                var page = new PDPage(); pdf.addPage(page);
                try (var stream = new PDPageContentStream(pdf, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(40, 700); stream.showText("Synthetic page " + n); stream.endText();
                }
            }
            pdf.save(file.toFile());
        }
        Path data = dir.resolve("pdf-store");
        assertThat(OfflineImport.importFile(file, data, false)).containsExactly(2, 0);
        try (var files = Files.list(data.resolve("private"))) {
            String json = Files.readString(files.findFirst().orElseThrow());
            assertThat(json).contains("第 1 页", "第 2 页", "Synthetic page 1", "Synthetic page 2");
        }
    }
    @Test void rejectsMalformedOrDuplicateCardsBeforeWriting() throws Exception {
        Path file = dir.resolve("bad.json"); Path data = dir.resolve("bad-store");
        var e = MvpTest.entry("same", "skill", "sample", "public", true);
        mapper.writeValue(file.toFile(), List.of(e, e));
        assertThatThrownBy(() -> OfflineImport.importFile(file, data, true)).hasMessageContaining("重复");
        assertThat(Files.exists(data)).isFalse();
    }
}
