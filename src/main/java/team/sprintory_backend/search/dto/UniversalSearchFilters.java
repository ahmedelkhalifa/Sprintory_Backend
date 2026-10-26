package team.sprintory_backend.search.dto;

import team.sprintory_backend.exceptionHandler.BadRequestException;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Search across visible resources; optional filters restrict the scope, never broaden it. */
public record UniversalSearchFilters(String q, Set<SearchResultType> types,
                                     Long workspaceId, Long projectId) {
    public UniversalSearchFilters {
        if (q == null || q.isBlank()) {
            throw new BadRequestException("'q' is required.");
        }
        q = q.trim();
        if (q.length() > 255) {
            throw new BadRequestException("'q' must be at most 255 characters.");
        }
        types = (types == null || types.isEmpty())
                ? Set.copyOf(EnumSet.allOf(SearchResultType.class)) : Set.copyOf(types);
        if (workspaceId != null && workspaceId <= 0) {
            throw new BadRequestException("'workspaceId' must be positive.");
        }
        if (projectId != null && projectId <= 0) {
            throw new BadRequestException("'projectId' must be positive.");
        }
    }

    public static UniversalSearchFilters fromQuery(String q, String types,
                                                   Long workspaceId, Long projectId) {
        if (types == null || types.isBlank()) {
            return new UniversalSearchFilters(q, null, workspaceId, projectId);
        }
        EnumSet<SearchResultType> selected = EnumSet.noneOf(SearchResultType.class);
        for (String raw : types.split(",", -1)) {
            String value = raw.trim().toUpperCase(Locale.ROOT);
            if (value.isEmpty()) {
                throw new BadRequestException("Invalid 'types' filter.");
            }
            try {
                selected.add(SearchResultType.valueOf(value));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid 'types' filter: " + value + ".");
            }
        }
        return new UniversalSearchFilters(q, selected, workspaceId, projectId);
    }
}
