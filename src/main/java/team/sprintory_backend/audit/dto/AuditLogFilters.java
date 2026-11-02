package team.sprintory_backend.audit.dto;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import team.sprintory_backend.audit.models.AuditAction;
import team.sprintory_backend.audit.models.AuditArea;
import team.sprintory_backend.exceptionHandler.BadRequestException;

/**
 * The audit log's query parameters (docs/audit-log-docs.md §3.1), validated. Every given filter
 * narrows the result. {@code actions} is null for "any action"; the {@code actions} and
 * {@code areas} parameters are combined into it, so an empty set means nothing can match.
 */
public record AuditLogFilters(Instant from, Instant to, Long actorId, Long projectId, Set<AuditAction> actions) {

    public AuditLogFilters {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BadRequestException("'from' must be before 'to'.");
        }
        requirePositive(actorId, "actorId");
        requirePositive(projectId, "projectId");
        actions = (actions == null) ? null : Set.copyOf(actions);
    }

    public static AuditLogFilters none() {
        return new AuditLogFilters(null, null, null, null, null);
    }

    public static AuditLogFilters fromQuery(String from, String to, Long actorId, Long projectId,
                                            String actions, String areas) {
        Set<AuditAction> wanted = null;
        Set<AuditAction> listed = parseList(actions, "actions", AuditAction.class, AuditAction::valueOf);
        if (listed != null) {
            wanted = listed;
        }
        Set<AuditArea> listedAreas = parseList(areas, "areas", AuditArea.class, AuditArea::valueOf);
        if (listedAreas != null) {
            Set<AuditAction> inAreas = EnumSet.noneOf(AuditAction.class);
            for (AuditAction action : AuditAction.values()) {
                if (listedAreas.contains(action.area())) {
                    inAreas.add(action);
                }
            }
            if (wanted == null) {
                wanted = inAreas;
            } else {
                wanted.retainAll(inAreas);
            }
        }
        return new AuditLogFilters(parseInstant(from, "from"), parseInstant(to, "to"), actorId, projectId, wanted);
    }

    private static Instant parseInstant(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException(
                    "Invalid '" + name + "': use an ISO-8601 instant such as 2026-11-01T00:00:00Z.");
        }
    }

    /** A comma list, case-insensitive, spaces ignored; blank means "not given". */
    private static <E extends Enum<E>> Set<E> parseList(String value, String name, Class<E> type,
                                                       Function<String, E> parse) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Set<E> parsed = EnumSet.noneOf(type);
        for (String raw : value.split(",", -1)) {
            String item = raw.trim().toUpperCase(Locale.ROOT);
            if (item.isEmpty()) {
                throw new BadRequestException("Invalid '" + name + "' filter.");
            }
            try {
                parsed.add(parse.apply(item));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid '" + name + "' filter: " + item + ".");
            }
        }
        return parsed;
    }

    private static void requirePositive(Long id, String name) {
        if (id != null && id <= 0) {
            throw new BadRequestException("'" + name + "' must be a positive ID.");
        }
    }
}