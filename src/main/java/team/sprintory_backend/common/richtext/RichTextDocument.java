package team.sprintory_backend.common.richtext;

import tools.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * A checked and normalized TipTap document, made by {@link RichTextParser}. It holds the JSON to
 * store, the mentions in it, and builds the plain text used for limits and notification excerpts.
 */
public final class RichTextDocument {

    private final JsonNode tree;
    private final String json;
    private final Set<RichTextMention> mentions;

    RichTextDocument(JsonNode tree, String json, Set<RichTextMention> mentions) {
        this.tree = tree;
        this.json = json;
        this.mentions = Collections.unmodifiableSet(new LinkedHashSet<>(mentions));
    }

    /** The normalized document as compact JSON: what is stored and returned to clients. */
    public String json() {
        return json;
    }

    /** Every distinct person and team mentioned, in the order they first appear. */
    public Set<RichTextMention> mentions() {
        return mentions;
    }

    /** The ids of the mentioned people, teams not expanded. */
    public Set<Long> userIds() {
        return idsOf(RichTextMention.Kind.USER);
    }

    /** The ids of the mentioned teams. */
    public Set<Long> teamIds() {
        return idsOf(RichTextMention.Kind.TEAM);
    }

    /** No characters other than spaces and no mention: an empty comment, or "no description". */
    public boolean isEmpty() {
        return mentions.isEmpty() && plainText().isBlank();
    }

    /** The plain text with each mention as {@code @} + the label stored in the node. */
    public String plainText() {
        return plainText(mention -> null);
    }

    /**
     * The plain text: block nodes on their own lines, each mention as {@code @} + its name.
     * {@code nameOf} gives the current name; when it returns null the stored label is used.
     */
    public String plainText(Function<RichTextMention, String> nameOf) {
        StringBuilder out = new StringBuilder();
        appendText(tree, nameOf, out);
        return out.toString().strip();
    }

    private Set<Long> idsOf(RichTextMention.Kind kind) {
        Set<Long> ids = new LinkedHashSet<>();
        for (RichTextMention mention : mentions) {
            if (mention.kind() == kind) {
                ids.add(mention.id());
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    /** The tree is already normalized, so every node has a known type and valid fields. */
    private static void appendText(JsonNode node, Function<RichTextMention, String> nameOf, StringBuilder out) {
        String type = node.get("type").stringValue();
        switch (type) {
            case "text" -> out.append(node.get("text").stringValue());
            case "hardBreak" -> out.append('\n');
            case "mention" -> out.append('@').append(mentionName(node.get("attrs"), nameOf));
            default -> {
                JsonNode content = node.get("content");
                if (content != null) {
                    for (JsonNode child : content) {
                        appendText(child, nameOf, out);
                    }
                }
                if (RichTextParser.TEXT_BLOCKS.contains(type) && !out.isEmpty()
                        && out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
            }
        }
    }

    private static String mentionName(JsonNode attrs, Function<RichTextMention, String> nameOf) {
        RichTextMention.Kind kind = RichTextMention.Kind.fromJson(attrs.get("kind").stringValue());
        String name = nameOf.apply(new RichTextMention(kind, Long.parseLong(attrs.get("id").stringValue())));
        if (name != null && !name.isBlank()) {
            return name;
        }
        JsonNode label = attrs.get("label");
        return (label != null) ? label.stringValue() : kind.json();
    }
}