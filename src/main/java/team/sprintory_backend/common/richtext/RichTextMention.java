package team.sprintory_backend.common.richtext;

/**
 * One person or team mentioned in a rich text, by id (PMP-136). The name is never stored here:
 * it is looked up when the text is shown, so renames do not break old mentions.
 */
public record RichTextMention(Kind kind, long id) {

    public static RichTextMention user(long id) {
        return new RichTextMention(Kind.USER, id);
    }

    public static RichTextMention team(long id) {
        return new RichTextMention(Kind.TEAM, id);
    }

    /** The {@code kind} attribute of a TipTap mention node. */
    public enum Kind {
        USER("user"),
        TEAM("team");

        private final String json;

        Kind(String json) {
            this.json = json;
        }

        /** The value written in the {@code kind} attribute. */
        public String json() {
            return json;
        }

        /** {@code null} (attribute missing) means a person; an unknown value gives {@code null}. */
        static Kind fromJson(String value) {
            if (value == null) {
                return USER;
            }
            for (Kind kind : values()) {
                if (kind.json.equals(value)) {
                    return kind;
                }
            }
            return null;
        }
    }
}