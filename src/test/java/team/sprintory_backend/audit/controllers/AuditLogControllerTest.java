package team.sprintory_backend.audit.controllers;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import team.sprintory_backend.audit.dto.AuditActorResponse;
import team.sprintory_backend.audit.dto.AuditLogEntryResponse;
import team.sprintory_backend.audit.dto.AuditLogFilters;
import team.sprintory_backend.audit.models.AuditAction;
import team.sprintory_backend.audit.services.AuditLogService;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.user.models.User;

/** The controller only parses the query and hands it to the service; access is tested there. */
class AuditLogControllerTest {

    private AuditLogService service;
    private AuditLogController controller;
    private User user;
    private AppUserDetails principal;

    @BeforeEach
    void setUp() {
        service = mock(AuditLogService.class);
        controller = new AuditLogController(service);
        user = User.builder().id(17L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        principal = new AppUserDetails(user);
    }

    @Test
    void listPassesTheParsedFiltersAndDropsTheClientSort() {
        PageResponse<AuditLogEntryResponse> result = new PageResponse<>(List.of(), 1, 10, 0, 0);
        AuditLogFilters expected = new AuditLogFilters(Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-02T00:00:00Z"), 23L, 15L, Set.of(AuditAction.TASK_DELETED));
        when(service.list(user, 4L, expected, PageRequest.of(1, 10))).thenReturn(result);

        assertSame(result, controller.list(principal, 4L, "2026-11-01T00:00:00Z", "2026-11-02T00:00:00Z",
                23L, 15L, "task_deleted", null, PageRequest.of(1, 10, Sort.by("actorName"))));
    }

    @Test
    void anInvalidFilterIsRefusedBeforeTheServiceIsCalled() {
        assertThrows(BadRequestException.class, () -> controller.list(principal, 4L, "not-a-date", null,
                null, null, null, null, PageRequest.of(0, 20)));
        verifyNoInteractions(service);
    }

    @Test
    void actorsComeFromTheService() {
        List<AuditActorResponse> actors = List.of(new AuditActorResponse(17L, "Ada Lovelace", "ada@example.com"));
        when(service.actors(any(), any())).thenReturn(actors);

        assertSame(actors, controller.actors(principal, 4L));
    }
}