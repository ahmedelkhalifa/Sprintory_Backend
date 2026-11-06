package team.sprintory_backend.common.richtext;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The rich-text format of docs/mentions-docs.md §2: what is allowed, refused, dropped and extracted. */
class RichTextParserTest {

    private final RichTextParser parser = new RichTextParser(JsonMapper.builder().build());

    // --- accepted ---

    @Test
    void aDocumentUsingEveryAllowedNodeAndMarkIsAccepted() {
        String json = doc("""
                {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Title"}]},
                {"type":"paragraph","content":[
                  {"type":"text","text":"bold","marks":[{"type":"bold"},{"type":"italic"},{"type":"underline"},
                                                        {"type":"strike"},{"type":"code"}]},
                  {"type":"hardBreak"},
                  {"type":"text","text":"link","marks":[{"type":"link","attrs":{"href":"https://example.com"}}]},
                  {"type":"mention","attrs":{"kind":"user","id":"31","label":"Damir"}}]},
                {"type":"bulletList","content":[{"type":"listItem","content":[
                  {"type":"paragraph","content":[{"type":"text","text":"one"}]}]}]},
                {"type":"orderedList","attrs":{"start":3},"content":[{"type":"listItem","content":[
                  {"type":"paragraph","content":[{"type":"text","text":"two"}]}]}]},
                {"type":"blockquote","content":[{"type":"paragraph","content":[{"type":"text","text":"quote"}]}]},
                {"type":"codeBlock","attrs":{"language":"Java"},"content":[{"type":"text","text":"int x;"}]},
                {"type":"horizontalRule"}
                """);

        RichTextDocument document = parser.parse(json);

        assertFalse(document.isEmpty());
        assertEquals(Set.of(RichTextMention.user(31)), document.mentions());
        assertTrue(document.json().contains("\"language\":\"java\""), "language is lower-cased");
        assertTrue(document.json().contains("\"start\":3"));
    }

    @Test
    void anEmptyDocumentIsValidButEmpty() {
        RichTextDocument document = parser.parse("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}");

        assertTrue(document.isEmpty());
        assertEquals("", document.plainText());
        assertEquals("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}", document.json());
    }

    @Test
    void onlySpacesCountAsEmpty() {
        assertTrue(parser.parse(doc(paragraph(text("   ")))).isEmpty());
    }

    @Test
    void aMentionAloneIsNotEmpty() {
        assertFalse(parser.parse(doc(paragraph(mention("user", "\"7\"", "Ada")))).isEmpty());
    }

    @Test
    void aDocWithoutContentGetsAnEmptyContentArray() {
        assertEquals("{\"type\":\"doc\",\"content\":[]}", parser.parse("{\"type\":\"doc\"}").json());
    }

    // --- normalizing ---

    @Test
    void unknownAttributesAndFieldsAreDropped() {
        String json = """
                {"type":"doc","class":"x","content":[
                  {"type":"paragraph","attrs":{"style":"color:red","textAlign":"left"},"content":[
                    {"type":"text","text":"hi","onclick":"alert(1)",
                     "marks":[{"type":"link","attrs":{"href":"https://a.com","target":"_blank","class":"evil"}}]}]}]}
                """;

        assertEquals("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":["
                        + "{\"type\":\"text\",\"text\":\"hi\",\"marks\":[{\"type\":\"link\",\"attrs\":{\"href\":\"https://a.com\"}}]}]}]}",
                parser.parse(json).json());
    }

    @Test
    void aRepeatedMarkIsKeptOnce() {
        String json = doc(paragraph("""
                {"type":"text","text":"x","marks":[{"type":"bold"},{"type":"bold"}]}"""));

        assertTrue(parser.parse(json).json().contains("\"marks\":[{\"type\":\"bold\"}]"));
    }

    @Test
    void mentionsAreStoredWithKindAndIdAsAString() {
        String json = doc(paragraph(mention(null, "42", "Ada"), mention("team", "\"5\"", "Backend")));

        RichTextDocument document = parser.parse(json);

        assertTrue(document.json().contains("{\"kind\":\"user\",\"id\":\"42\",\"label\":\"Ada\"}"));
        assertTrue(document.json().contains("{\"kind\":\"team\",\"id\":\"5\",\"label\":\"Backend\"}"));
    }

    @Test
    void aLongLabelIsCutTo100Characters() {
        String json = doc(paragraph(mention("user", "1", "x".repeat(150))));

        assertTrue(parser.parse(json).json().contains("\"label\":\"" + "x".repeat(100) + "\""));
    }

    @Test
    void headingWithoutLevelBecomesLevel1AndListWithoutStartStartsAt1() {
        String json = doc("""
                {"type":"heading","content":[{"type":"text","text":"T"}]},
                {"type":"orderedList","content":[{"type":"listItem","content":[{"type":"paragraph"}]}]}
                """);

        String normalized = parser.parse(json).json();
        assertTrue(normalized.contains("{\"type\":\"heading\",\"attrs\":{\"level\":1}"));
        assertTrue(normalized.contains("{\"type\":\"orderedList\",\"attrs\":{\"start\":1}"));
    }

    // --- mentions and plain text ---

    @Test
    void mentionsAreCollectedOnceInOrderAndSplitByKind() {
        String json = doc(paragraph(mention("user", "3", "C"), mention("team", "9", "T"),
                mention("user", "1", "A"), mention("user", "\"3\"", "C again")));

        RichTextDocument document = parser.parse(json);

        assertEquals(List.of(RichTextMention.user(3), RichTextMention.team(9), RichTextMention.user(1)),
                List.copyOf(document.mentions()));
        assertEquals(List.of(3L, 1L), List.copyOf(document.userIds()));
        assertEquals(Set.of(9L), document.teamIds());
    }

    @Test
    void plainTextPutsBlocksOnTheirOwnLinesAndShowsMentionsByLabel() {
        String json = doc("""
                {"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Bug"}]},
                {"type":"paragraph","content":[{"type":"text","text":"Ask "},
                  {"type":"mention","attrs":{"kind":"user","id":"31","label":"Damir"}},
                  {"type":"hardBreak"},{"type":"text","text":"today"}]},
                {"type":"bulletList","content":[
                  {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"a"}]}]},
                  {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"b"}]}]}]}
                """);

        assertEquals("Bug\nAsk @Damir\ntoday\na\nb", parser.parse(json).plainText());
    }

    @Test
    void plainTextUsesTheCurrentNameWhenOneIsGiven() {
        String json = doc(paragraph(text("Hi "), mention("user", "31", "Old name"), text(" and "),
                mention("team", "5", null)));
        Map<RichTextMention, String> names = Map.of(RichTextMention.user(31), "Damir Gabaidullin");

        RichTextDocument document = parser.parse(json);

        assertEquals("Hi @Damir Gabaidullin and @team", document.plainText(names::get));
        assertEquals("Hi @Old name and @team", document.plainText());
    }

    // --- refused ---

    @Test
    void textThatIsNotADocumentIsRefused() {
        assertRefused(null, "The text is not a valid document.");
        assertRefused("  ", "The text is not a valid document.");
        assertRefused("Steps to reproduce: ...", "The text is not a valid document.");
        assertRefused("\"just a string\"", "The text is not a valid document.");
        assertRefused("[]", "The text is not a valid document.");
        assertRefused("{\"type\":\"paragraph\"}", "The text is not a valid document.");
        assertRefused("{\"content\":[]}", "The text is not a valid document.");
        assertRefused("{\"type\":\"doc\",\"content\":{}}", "The text is not a valid document.");
        assertRefused(doc("\"not a node\""), "The text is not a valid document.");
    }

    @Test
    void unknownNodesAndMarksAreRefused() {
        assertRefused(doc("{\"type\":\"image\",\"attrs\":{\"src\":\"x.png\"}}"), "Node type 'image' is not allowed.");
        assertRefused(doc("{\"type\":\"table\"}"), "Node type 'table' is not allowed.");
        assertRefused(doc(paragraph("{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"textStyle\"}]}")),
                "Mark type 'textStyle' is not allowed.");
        assertRefused(doc(paragraph("{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"highlight\"}]}")),
                "Mark type 'highlight' is not allowed.");
    }

    @Test
    void nodesInTheWrongPlaceAreRefused() {
        assertRefused(doc(text("loose text")), "Node type 'text' is not allowed inside 'doc'.");
        assertRefused(doc("{\"type\":\"bulletList\",\"content\":[" + paragraph(text("x")) + "]}"),
                "Node type 'paragraph' is not allowed inside 'bulletList'.");
        assertRefused(doc(paragraph(paragraph(text("x")))), "Node type 'paragraph' is not allowed inside 'paragraph'.");
        assertRefused(doc(paragraph("{\"type\":\"doc\"}")), "Node type 'doc' is not allowed inside 'paragraph'.");
        assertRefused(doc("{\"type\":\"codeBlock\",\"content\":[{\"type\":\"hardBreak\"}]}"),
                "Node type 'hardBreak' is not allowed inside 'codeBlock'.");
        assertRefused(doc("{\"type\":\"horizontalRule\",\"content\":[" + text("x") + "]}"),
                "Node type 'horizontalRule' cannot contain other nodes.");
    }

    @Test
    void unsafeLinksAreRefused() {
        for (String href : List.of("javascript:alert(1)", " JavaScript:alert(1)", "data:text/html,x",
                "vbscript:x", "/relative", "ftp://host", "https://", "mailto:")) {
            assertRefused(doc(paragraph(link(href))), "Links must use http, https or mailto.");
        }
        assertRefused(doc(paragraph("{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"link\"}]}")),
                "Links must use http, https or mailto.");
        assertRefused(doc(paragraph(link("https://a.com/" + "x".repeat(2000)))),
                "A link can have at most 2000 characters.");
    }

    @Test
    void safeLinksAreKept() {
        for (String href : List.of("http://a.com", "HTTPS://A.com/x?y=1", "mailto:ada@example.com")) {
            assertTrue(parser.parse(doc(paragraph(link(href)))).json().contains(href));
        }
    }

    @Test
    void badMentionsAreRefused() {
        assertRefused(doc(paragraph("{\"type\":\"mention\"}")), "A mention needs a valid id.");
        assertRefused(doc(paragraph(mention("user", "0", "x"))), "A mention needs a valid id.");
        assertRefused(doc(paragraph(mention("user", "-4", "x"))), "A mention needs a valid id.");
        assertRefused(doc(paragraph(mention("user", "\"abc\"", "x"))), "A mention needs a valid id.");
        assertRefused(doc(paragraph(mention("user", "1.5", "x"))), "A mention needs a valid id.");
        assertRefused(doc(paragraph(mention("user", "null", "x"))), "A mention needs a valid id.");
        assertRefused(doc(paragraph(mention("everyone", "1", "x"))), "A mention must be of kind 'user' or 'team'.");
    }

    @Test
    void tooManyMentionsAreRefused() {
        StringBuilder nodes = new StringBuilder();
        for (int id = 1; id <= RichTextParser.MAX_MENTIONS + 1; id++) {
            nodes.append(id > 1 ? "," : "").append(mention("user", String.valueOf(id), null));
        }
        assertRefused(doc(paragraph(nodes.toString())), "A text can mention at most 50 people and teams.");
    }

    @Test
    void fiftyMentionsAreAllowed() {
        StringBuilder nodes = new StringBuilder();
        for (int id = 1; id <= RichTextParser.MAX_MENTIONS; id++) {
            nodes.append(id > 1 ? "," : "").append(mention("user", String.valueOf(id), null));
        }
        assertEquals(50, parser.parse(doc(paragraph(nodes.toString()))).mentions().size());
    }

    @Test
    void nestingDeeperThan20IsRefused() {
        assertRefused(nestedQuotes(18), "The text is nested too deeply.");   // text at depth 21
        assertDoesNotThrow(() -> parser.parse(nestedQuotes(17)));            // text at depth 20
    }

    @Test
    void invalidAttributesAndTextAreRefused() {
        assertRefused(doc("{\"type\":\"heading\",\"attrs\":{\"level\":4}}"), "Headings can only have level 1, 2 or 3.");
        assertRefused(doc("{\"type\":\"heading\",\"attrs\":{\"level\":\"1\"}}"), "Headings can only have level 1, 2 or 3.");
        assertRefused(doc("{\"type\":\"orderedList\",\"attrs\":{\"start\":0}}"), "A numbered list must start at 1 or more.");
        assertRefused(doc("{\"type\":\"codeBlock\",\"attrs\":{\"language\":\"<script>\"}}"),
                "The code block language is not valid.");
        assertRefused(doc(paragraph("{\"type\":\"text\",\"text\":\"\"}")), "The text is not a valid document.");
        assertRefused(doc(paragraph("{\"type\":\"text\"}")), "The text is not a valid document.");
        assertRefused(doc(paragraph("{\"type\":\"text\",\"text\":\"x\",\"marks\":\"bold\"}")),
                "The text is not a valid document.");
        assertRefused(doc("{\"type\":\"codeBlock\",\"content\":["
                        + "{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"bold\"}]}]}"),
                "Text in a code block cannot have formatting.");
    }

    // --- helpers ---

    private void assertRefused(String json, String message) {
        InvalidRichTextException e = assertThrows(InvalidRichTextException.class, () -> parser.parse(json),
                () -> "expected a refusal for " + json);
        assertEquals(message, e.getMessage());
    }

    private static String doc(String blocks) {
        return "{\"type\":\"doc\",\"content\":[" + blocks + "]}";
    }

    private static String paragraph(String... inline) {
        return "{\"type\":\"paragraph\",\"content\":[" + String.join(",", inline) + "]}";
    }

    private static String text(String text) {
        return "{\"type\":\"text\",\"text\":\"" + text + "\"}";
    }

    private static String link(String href) {
        return "{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"link\",\"attrs\":{\"href\":\"" + href + "\"}}]}";
    }

    /** {@code kind} null leaves the attribute out; {@code id} is raw JSON (a number, a quoted string, null). */
    private static String mention(String kind, String id, String label) {
        StringBuilder attrs = new StringBuilder("\"id\":").append(id);
        if (kind != null) attrs.append(",\"kind\":\"").append(kind).append('"');
        if (label != null) attrs.append(",\"label\":\"").append(label).append('"');
        return "{\"type\":\"mention\",\"attrs\":{" + attrs + "}}";
    }

    /**
     * {@code levels} blockquotes inside each other, holding a paragraph with text. The doc is
     * depth 1, so the text sits at depth {@code levels + 3}.
     */
    private static String nestedQuotes(int levels) {
        String inner = paragraph(text("deep"));
        for (int i = 0; i < levels; i++) {
            inner = "{\"type\":\"blockquote\",\"content\":[" + inner + "]}";
        }
        return doc(inner);
    }
}