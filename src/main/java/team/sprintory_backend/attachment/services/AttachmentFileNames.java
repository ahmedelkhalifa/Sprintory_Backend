package team.sprintory_backend.attachment.services;

import java.util.regex.Pattern;

/**
 * The display name of an attachment (docs/attachments-docs.md §3.1). It is only ever shown as
 * text and sent in {@code Content-Disposition}; the file on disk is named by its key.
 */
final class AttachmentFileNames {

    static final int MAX_LENGTH = 255;
    /** Used when nothing is left of the sent name; it has no extension, so the type check refuses it. */
    static final String FALLBACK = "file";

    /** Control characters, and invisible format characters such as the right-to-left override. */
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cc}\\p{Cf}]");

    private AttachmentFileNames() {
    }

    /**
     * The last path segment ({@code ../../x.pdf} → {@code x.pdf}), without control or format
     * characters, trimmed, and cut to {@value #MAX_LENGTH} characters keeping the extension.
     */
    static String displayName(String sent) {
        if (sent == null) {
            return FALLBACK;
        }
        String name = sent.substring(Math.max(sent.lastIndexOf('/'), sent.lastIndexOf('\\')) + 1);
        name = UNSAFE.matcher(name).replaceAll("").strip();
        if (name.isEmpty()) {
            return FALLBACK;
        }
        return limit(name);
    }

    /** The same name with its extension replaced, e.g. {@code diagram.png} → {@code diagram.webp}. */
    static String withExtension(String name, String extension) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return limit(base + "." + extension);
    }

    private static String limit(String name) {
        if (name.length() <= MAX_LENGTH) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
        int end = MAX_LENGTH - extension.length();
        if (Character.isHighSurrogate(name.charAt(end - 1))) {
            end--; // never split a character such as an emoji in two
        }
        return name.substring(0, end) + extension;
    }
}