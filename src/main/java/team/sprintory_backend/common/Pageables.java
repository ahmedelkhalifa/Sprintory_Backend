package team.sprintory_backend.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Locale;

/**
 * Small helpers shared by every paginated, searchable list. Every per-area {@code ?q=} search
 * goes through {@link #containsPattern}, so search behaves the same everywhere and a later
 * app-wide search can combine the per-area ones.
 */
public final class Pageables {

    private Pageables() {
    }

    /**
     * Keeps the client's page and size but drops any {@code ?sort=}: each list has a fixed order
     * in its query, and an unknown sort property from the client would otherwise fail the request.
     */
    public static Pageable withoutSort(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }

    /**
     * Turns the optional {@code ?q=} into a case-insensitive "contains" pattern for {@code like}.
     * No search gives "%", which matches everything, so queries need no null check.
     */
    public static String containsPattern(String q) {
        return (q == null || q.isBlank()) ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
    }

    /**
     * Escapes {@code like} wildcards so {@code q=50%} looks for a literal "50%". The query must
     * declare the escape character: {@code like :pattern escape '\'}. Used by the task list and
     * universal search; the older lists above do not escape.
     */
    public static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}