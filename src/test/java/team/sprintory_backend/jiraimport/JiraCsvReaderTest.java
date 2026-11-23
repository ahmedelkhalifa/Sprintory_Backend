package team.sprintory_backend.jiraimport;

import org.junit.jupiter.api.Test;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.jiraimport.services.JiraCsvReader;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class JiraCsvReaderTest {
    private final JiraCsvReader reader = new JiraCsvReader();

    @Test void readsQuotedNewlinesEscapedQuotesAndDuplicateLabels() {
        String csv = "\uFEFFIssue id,Issue key,Project key,Issue Type,Summary,Status,Custom field (Rank),Labels,Labels,Description\r\n"
                + "100,PMP-1,PMP,Story,\"A \"\"quoted\"\" title\",Done,0|a,first,second,\"Line one\r\nLine two\"\r\n";
        var result = reader.parse(csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, result.issues().size());
        var row = result.issues().get(0);
        assertEquals("A \"quoted\" title", row.title());
        assertEquals("Line one\r\nLine two", row.description());
        assertEquals(java.util.List.of("first", "second"), row.labels());
    }

    @Test void malformedCsvIsRejectedInsteadOfSilentlyDroppingValues() {
        String csv = "Issue id,Issue key,Project key,Issue Type,Summary,Status,Custom field (Rank)\n"
                + "100,PMP-1,PMP,Story,\"unfinished,Done,0|a";
        assertThrows(BadRequestException.class, () -> reader.parse(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void mixedProjectsAreRejected() {
        String csv = "Issue id,Issue key,Project key,Issue Type,Summary,Status,Custom field (Rank)\n"
                + "100,PMP-1,PMP,Story,A,Done,0|a\n101,ABC-1,ABC,Story,B,Done,0|b\n";
        assertThrows(BadRequestException.class, () -> reader.parse(csv.getBytes(StandardCharsets.UTF_8)));
    }
}
