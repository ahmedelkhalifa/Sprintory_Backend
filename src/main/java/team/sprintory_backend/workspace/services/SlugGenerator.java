package team.sprintory_backend.workspace.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Turns a workspace name into a unique URL slug, e.g. "My Team Ü" becomes "my-team-u".
 * A taken slug gets "-2", "-3" and so on. Two workspaces created at the same moment can
 * still pick the same slug; the unique constraint then rejects the second one with 409.
 */
@Component
@RequiredArgsConstructor
public class SlugGenerator {

    /** Same as the name column, so a slug is only ever cut to make room for a suffix. */
    static final int MAX_LENGTH = 100;
    private static final String FALLBACK = "workspace";

    private final WorkspaceRepository workspaceRepository;

    public String generate(String name) {
        String base = toSlug(name);
        String candidate = base;
        for (int suffix = 2; workspaceRepository.existsBySlug(candidate); suffix++) {
            String ending = "-" + suffix;
            candidate = trimHyphens(base.substring(0, Math.min(base.length(), MAX_LENGTH - ending.length())))
                    + ending;
        }
        return candidate;
    }

    /** Locale.ROOT: a Turkish default locale would lower-case "I" to a dotless "ı". */
    static String toSlug(String name) {
        // NFD splits "ü" into "u" plus a combining mark, which the next step removes.
        // The dotless "ı" has no decomposition, so it is mapped by hand.
        String ascii = Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('ı', 'i');
        String slug = trimHyphens(ascii.replaceAll("[^a-z0-9]+", "-"));
        if (slug.length() > MAX_LENGTH) {
            slug = trimHyphens(slug.substring(0, MAX_LENGTH));
        }
        return slug.isEmpty() ? FALLBACK : slug;
    }

    private static String trimHyphens(String value) {
        return value.replaceAll("^-+|-+$", "");
    }
}