package team.sprintory_backend.common.richtext;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks and normalizes the TipTap JSON of comments, task descriptions and chat messages
 * (docs/mentions-docs.md §2). Only the nodes, marks and attributes on the allow-list survive, so
 * a stored text can never carry HTML or a script. Anything not allowed is refused with
 * {@link InvalidRichTextException}; unknown attributes are dropped silently.
 *
 * <p>This class checks only the format. Whether the mentioned people may be mentioned is decided
 * by the mention rules (PMP-136), and length limits by the feature that stores the text.
 */
@Component
public class RichTextParser {

    public static final int MAX_DEPTH = 20;
    public static final int MAX_MENTIONS = 50;
    public static final int MAX_LINK_LENGTH = 2000;
    public static final int MAX_LABEL_LENGTH = 100;

    static final String NOT_A_DOCUMENT = "The text is not a valid document.";

    private static final Set<String> BLOCKS = Set.of(
            "paragraph", "heading", "bulletList", "orderedList", "blockquote", "codeBlock", "horizontalRule");
    private static final Set<String> INLINE = Set.of("text", "hardBreak", "mention");
    private static final Set<String> MARKS = Set.of("bold", "italic", "underline", "strike", "code", "link");

    /** Blocks whose text forms one line of the plain text. */
    static final Set<String> TEXT_BLOCKS = Set.of("paragraph", "heading", "codeBlock");

    /** Which nodes each node may contain. A node with an empty set is a leaf. */
    private static final Map<String, Set<String>> CHILDREN = Map.ofEntries(
            Map.entry("doc", BLOCKS),
            Map.entry("paragraph", INLINE),
            Map.entry("heading", INLINE),
            Map.entry("bulletList", Set.of("listItem")),
            Map.entry("orderedList", Set.of("listItem")),
            Map.entry("listItem", BLOCKS),
            Map.entry("blockquote", BLOCKS),
            Map.entry("codeBlock", Set.of("text")),
            Map.entry("horizontalRule", Set.of()),
            Map.entry("text", Set.of()),
            Map.entry("hardBreak", Set.of()),
            Map.entry("mention", Set.of()));

    private static final Pattern LANGUAGE = Pattern.compile("[a-z0-9+#-]{1,30}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,18}");

    private final ObjectMapper objectMapper;

    public RichTextParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses, checks and normalizes {@code json}, a TipTap document as a string.
     *
     * @throws InvalidRichTextException if the text is not an allowed document
     */
    public RichTextDocument parse(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidRichTextException(NOT_A_DOCUMENT);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new InvalidRichTextException(NOT_A_DOCUMENT);
        }
        if (root == null || !root.isObject() || !"doc".equals(typeOf(root))) {
            throw new InvalidRichTextException(NOT_A_DOCUMENT);
        }

        Set<RichTextMention> mentions = new LinkedHashSet<>();
        ObjectNode normalized = normalize(root, 1, false, mentions);
        if (mentions.size() > MAX_MENTIONS) {
            throw new InvalidRichTextException(
                    "A text can mention at most " + MAX_MENTIONS + " people and teams.");
        }
        return new RichTextDocument(normalized, objectMapper.writeValueAsString(normalized), mentions);
    }

    /** Rebuilds one node with only what is allowed; {@code inCode} is true inside a code block. */
    private ObjectNode normalize(JsonNode node, int depth, boolean inCode, Set<RichTextMention> mentions) {
        if (depth > MAX_DEPTH) {
            throw new InvalidRichTextException("The text is nested too deeply.");
        }
        String type = typeOf(node);
        ObjectNode out = objectMapper.createObjectNode();
        out.put("type", type);

        switch (type) {
            case "heading" -> out.putObject("attrs").put("level", headingLevel(node.get("attrs")));
            case "orderedList" -> out.putObject("attrs").put("start", listStart(node.get("attrs")));
            case "codeBlock" -> putLanguage(out, node.get("attrs"));
            case "mention" -> out.set("attrs", mention(node.get("attrs"), mentions));
            case "text" -> out.put("text", text(node));
            default -> { }
        }
        if (INLINE.contains(type)) {
            putMarks(out, node.get("marks"), inCode);
        }

        Set<String> allowed = CHILDREN.get(type);
        JsonNode content = node.get("content");
        if (content != null && !content.isNull()) {
            if (!content.isArray()) {
                throw new InvalidRichTextException(NOT_A_DOCUMENT);
            }
            if (allowed.isEmpty() && !content.isEmpty()) {
                throw new InvalidRichTextException("Node type '" + type + "' cannot contain other nodes.");
            }
            ArrayNode children = objectMapper.createArrayNode();
            for (JsonNode child : content) {
                String childType = typeOf(child);
                if (!allowed.contains(childType)) {
                    throw new InvalidRichTextException(
                            "Node type '" + childType + "' is not allowed inside '" + type + "'.");
                }
                children.add(normalize(child, depth + 1, inCode || "codeBlock".equals(type), mentions));
            }
            if (!children.isEmpty()) {
                out.set("content", children);
            }
        }
        if ("doc".equals(type) && !out.has("content")) {
            out.putArray("content");
        }
        return out;
    }

    /**
     * The node's type, which must be a known one. A node that is not an object, or has no type,
     * makes the whole text invalid.
     */
    private static String typeOf(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new InvalidRichTextException(NOT_A_DOCUMENT);
        }
        JsonNode type = node.get("type");
        if (type == null || !type.isString()) {
            throw new InvalidRichTextException(NOT_A_DOCUMENT);
        }
        String name = type.stringValue();
        if (!CHILDREN.containsKey(name)) {
            throw new InvalidRichTextException("Node type '" + name + "' is not allowed.");
        }
        return name;
    }

    /** A text node must have some text: ProseMirror itself forbids empty text nodes. */
    private static String text(JsonNode node) {
        JsonNode text = node.get("text");
        if (text == null || !text.isString() || text.stringValue().isEmpty()) {
            throw new InvalidRichTextException(NOT_A_DOCUMENT);
        }
        return text.stringValue();
    }

    private static int headingLevel(JsonNode attrs) {
        JsonNode level = (attrs == null) ? null : attrs.get("level");
        if (level == null || level.isNull()) {
            return 1;
        }
        if (!level.isIntegralNumber() || !level.canConvertToLong()
                || level.longValue() < 1 || level.longValue() > 3) {
            throw new InvalidRichTextException("Headings can only have level 1, 2 or 3.");
        }
        return (int) level.longValue();
    }

    private static int listStart(JsonNode attrs) {
        JsonNode start = (attrs == null) ? null : attrs.get("start");
        if (start == null || start.isNull()) {
            return 1;
        }
        if (!start.isIntegralNumber() || !start.canConvertToLong()
                || start.longValue() < 1 || start.longValue() > Integer.MAX_VALUE) {
            throw new InvalidRichTextException("A numbered list must start at 1 or more.");
        }
        return (int) start.longValue();
    }

    /** {@code language} is kept lower-case, or left out when the code block has none. */
    private void putLanguage(ObjectNode out, JsonNode attrs) {
        JsonNode language = (attrs == null) ? null : attrs.get("language");
        if (language == null || language.isNull()) {
            return;
        }
        String value = language.isString() ? language.stringValue().toLowerCase(Locale.ROOT) : null;
        if (value == null || !LANGUAGE.matcher(value).matches()) {
            throw new InvalidRichTextException("The code block language is not valid.");
        }
        out.putObject("attrs").put("language", value);
    }

    /**
     * A mention keeps {@code kind}, {@code id} (as a string, as TipTap stores it) and an optional
     * {@code label} of at most 100 characters, cut if longer. The label is only a fallback for display.
     */
    private ObjectNode mention(JsonNode attrs, Set<RichTextMention> mentions) {
        if (attrs == null || !attrs.isObject()) {
            throw new InvalidRichTextException("A mention needs a valid id.");
        }
        JsonNode kindNode = attrs.get("kind");
        RichTextMention.Kind kind = (kindNode == null || kindNode.isNull()) ? RichTextMention.Kind.USER
                : kindNode.isString() ? RichTextMention.Kind.fromJson(kindNode.stringValue()) : null;
        if (kind == null) {
            throw new InvalidRichTextException("A mention must be of kind 'user' or 'team'.");
        }
        long id = mentionId(attrs.get("id"));
        mentions.add(new RichTextMention(kind, id));

        ObjectNode out = objectMapper.createObjectNode();
        out.put("kind", kind.json());
        out.put("id", Long.toString(id));
        JsonNode label = attrs.get("label");
        if (label != null && label.isString() && !label.stringValue().isBlank()) {
            String value = label.stringValue().strip();
            out.put("label", value.length() > MAX_LABEL_LENGTH ? value.substring(0, MAX_LABEL_LENGTH) : value);
        }
        return out;
    }

    /** A positive whole number, sent as a number or as a string of digits. */
    private static long mentionId(JsonNode id) {
        long value = 0;
        if (id != null && id.isIntegralNumber() && id.canConvertToLong()) {
            value = id.longValue();
        } else if (id != null && id.isString() && DIGITS.matcher(id.stringValue()).matches()) {
            value = Long.parseLong(id.stringValue());
        }
        if (value <= 0) {
            throw new InvalidRichTextException("A mention needs a valid id.");
        }
        return value;
    }

    /** Allowed marks only, each type once; text inside a code block cannot have any. */
    private void putMarks(ObjectNode out, JsonNode marks, boolean inCode) {
        if (marks == null || marks.isNull()) {
            return;
        }
        if (!marks.isArray()) {
            throw new InvalidRichTextException(NOT_A_DOCUMENT);
        }
        if (marks.isEmpty()) {
            return;
        }
        if (inCode) {
            throw new InvalidRichTextException("Text in a code block cannot have formatting.");
        }
        Map<String, ObjectNode> kept = new LinkedHashMap<>();
        for (JsonNode mark : marks) {
            if (mark == null || !mark.isObject() || mark.get("type") == null || !mark.get("type").isString()) {
                throw new InvalidRichTextException(NOT_A_DOCUMENT);
            }
            String type = mark.get("type").stringValue();
            if (!MARKS.contains(type)) {
                throw new InvalidRichTextException("Mark type '" + type + "' is not allowed.");
            }
            if (kept.containsKey(type)) {
                continue;
            }
            ObjectNode normalized = objectMapper.createObjectNode();
            normalized.put("type", type);
            if ("link".equals(type)) {
                normalized.putObject("attrs").put("href", href(mark.get("attrs")));
            }
            kept.put(type, normalized);
        }
        ArrayNode array = out.putArray("marks");
        kept.values().forEach(array::add);
    }

    /** {@code http}, {@code https} and {@code mailto} only: no {@code javascript:} or {@code data:}. */
    private static String href(JsonNode attrs) {
        JsonNode href = (attrs == null || !attrs.isObject()) ? null : attrs.get("href");
        if (href == null || !href.isString()) {
            throw new InvalidRichTextException("Links must use http, https or mailto.");
        }
        String value = href.stringValue().strip();
        if (value.length() > MAX_LINK_LENGTH) {
            throw new InvalidRichTextException("A link can have at most " + MAX_LINK_LENGTH + " characters.");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!hasTarget(lower, "http://") && !hasTarget(lower, "https://") && !hasTarget(lower, "mailto:")) {
            throw new InvalidRichTextException("Links must use http, https or mailto.");
        }
        return value;
    }

    /** Starts with {@code scheme} and has something after it. */
    private static boolean hasTarget(String href, String scheme) {
        return href.startsWith(scheme) && href.length() > scheme.length();
    }
}