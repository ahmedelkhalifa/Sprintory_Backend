package team.sprintory_backend.audit.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import team.sprintory_backend.audit.models.AuditAction;
import team.sprintory_backend.exceptionHandler.BadRequestException;

class AuditLogFiltersTest {

    @Test
    void noParametersMeansNoFilter() {
        AuditLogFilters filters = AuditLogFilters.fromQuery(null, " ", null, null, "", null);

        assertNull(filters.from());
        assertNull(filters.to());
        assertNull(filters.actorId());
        assertNull(filters.projectId());
        assertNull(filters.actions(), "null means any action");
    }

    @Test
    void parsesTheDateRangeAndIds() {
        AuditLogFilters filters = AuditLogFilters.fromQuery("2026-10-31T21:00:00.000Z", "2026-11-02T21:00:00.000Z",
                17L, 15L, null, null);

        assertEquals(Instant.parse("2026-10-31T21:00:00Z"), filters.from());
        assertEquals(Instant.parse("2026-11-02T21:00:00Z"), filters.to());
        assertEquals(17L, filters.actorId());
        assertEquals(15L, filters.projectId());
    }

    @Test
    void actionsAreACaseInsensitiveCommaList() {
        AuditLogFilters filters = AuditLogFilters.fromQuery(null, null, null, null,
                " project_roles_changed , WORKSPACE_ROLE_CHANGED", null);

        assertEquals(Set.of(AuditAction.PROJECT_ROLES_CHANGED, AuditAction.WORKSPACE_ROLE_CHANGED), filters.actions());
    }

    @Test
    void anAreaStandsForAllOfItsActions() {
        AuditLogFilters filters = AuditLogFilters.fromQuery(null, null, null, null, null, "task,Security");

        assertEquals(EnumSet.of(AuditAction.TASK_DELETED, AuditAction.TASK_RESTORED,
                AuditAction.PASSWORD_RESET, AuditAction.LOGIN_FAILED_REPEATED), filters.actions());
    }

    @Test
    void actionsAndAreasTogetherKeepOnlyWhatBothAllow() {
        assertEquals(Set.of(AuditAction.TASK_DELETED),
                AuditLogFilters.fromQuery(null, null, null, null, "TASK_DELETED,TEAM_CREATED", "TASK").actions());
        assertEquals(Set.of(),
                AuditLogFilters.fromQuery(null, null, null, null, "TEAM_CREATED", "TASK").actions(),
                "no overlap: an empty set, which matches nothing");
    }

    @Test
    void rejectsInvalidValuesWithTheDocumentedMessages() {
        assertBadRequest("Invalid 'from': use an ISO-8601 instant such as 2026-11-01T00:00:00Z.",
                () -> AuditLogFilters.fromQuery("2026-11-01", null, null, null, null, null));
        assertBadRequest("Invalid 'to': use an ISO-8601 instant such as 2026-11-01T00:00:00Z.",
                () -> AuditLogFilters.fromQuery(null, "yesterday", null, null, null, null));
        assertBadRequest("'from' must be before 'to'.",
                () -> AuditLogFilters.fromQuery("2026-11-02T00:00:00Z", "2026-11-02T00:00:00Z", null, null, null, null));
        assertBadRequest("'actorId' must be a positive ID.",
                () -> AuditLogFilters.fromQuery(null, null, 0L, null, null, null));
        assertBadRequest("'projectId' must be a positive ID.",
                () -> AuditLogFilters.fromQuery(null, null, null, -3L, null, null));
        assertBadRequest("Invalid 'actions' filter: FOO.",
                () -> AuditLogFilters.fromQuery(null, null, null, null, "TASK_DELETED,foo", null));
        assertBadRequest("Invalid 'areas' filter: BOARD.",
                () -> AuditLogFilters.fromQuery(null, null, null, null, null, "board"));
        assertBadRequest("Invalid 'actions' filter.",
                () -> AuditLogFilters.fromQuery(null, null, null, null, "TASK_DELETED,,", null));
    }

    private static void assertBadRequest(String message, Executable call) {
        assertEquals(message, assertThrows(BadRequestException.class, call).getMessage());
    }
}