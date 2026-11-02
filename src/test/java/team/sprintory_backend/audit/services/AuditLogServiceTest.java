package team.sprintory_backend.audit.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.audit.models.AuditAction;
import team.sprintory_backend.audit.models.AuditArea;
import team.sprintory_backend.audit.models.AuditChange;
import team.sprintory_backend.audit.models.AuditLogEntry;
import team.sprintory_backend.audit.models.AuditTarget;
import team.sprintory_backend.audit.models.AuditTargetType;
import team.sprintory_backend.audit.repositories.AuditLogRepository;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.team.models.Team;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;

class AuditLogServiceTest {

    private AuditLogRepository auditLogRepository;
    private WorkspaceMemberRepository workspaceMemberRepository;
    private AuditLogService service;

    private Workspace workspace;
    private User ada;
    private User linus;
    private Project project;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        workspaceMemberRepository = mock(WorkspaceMemberRepository.class);
        service = new AuditLogService(auditLogRepository, workspaceMemberRepository);

        workspace = Workspace.builder().id(4L).name("Acme").slug("acme").build();
        ada = User.builder().id(17L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        linus = User.builder().id(23L).firstName("Linus").lastName("Torvalds").email("linus@example.com").build();
        project = Project.builder().id(15L).workspace(workspace).name("Mobile App").key("MOB").build();
    }

    // --- record ---

    @Test
    void recordStoresEverySnapshotField() {
        Instant before = Instant.now();

        service.record(workspace, ada, AuditAction.PROJECT_ROLES_CHANGED, AuditTarget.user(linus), project,
                AuditChange.of("roles", "MEMBER", "PROJECT_MANAGER, MEMBER"));

        AuditLogEntry entry = savedEntry();
        assertSame(workspace, entry.getWorkspace());
        assertEquals(AuditAction.PROJECT_ROLES_CHANGED, entry.getAction());
        assertEquals(17L, entry.getActorId());
        assertEquals("Ada Lovelace", entry.getActorName());
        assertEquals("ada@example.com", entry.getActorEmail());
        assertEquals(AuditTargetType.USER, entry.getTargetType());
        assertEquals(23L, entry.getTargetId());
        assertEquals("Linus Torvalds", entry.getTargetName());
        assertEquals(15L, entry.getProjectId());
        assertEquals("Mobile App", entry.getProjectName());
        assertEquals(List.of(AuditChange.of("roles", "MEMBER", "PROJECT_MANAGER, MEMBER")), entry.getChanges());
        assertNotNull(entry.getOccurredAt());
        assertTrue(!entry.getOccurredAt().isBefore(before)
                && Duration.between(before, entry.getOccurredAt()).toSeconds() < 5);
    }

    @Test
    void recordWithoutActorOrProjectLeavesThoseFieldsNull() {
        service.record(workspace, null, AuditAction.TEAM_CREATED,
                AuditTarget.team(Team.builder().id(8L).workspace(workspace).name("Backend").build()), null);

        AuditLogEntry entry = savedEntry();
        assertNull(entry.getActorId());
        assertNull(entry.getActorName());
        assertNull(entry.getActorEmail());
        assertNull(entry.getProjectId());
        assertNull(entry.getProjectName());
        assertEquals(AuditTargetType.TEAM, entry.getTargetType());
        assertEquals("Backend", entry.getTargetName());
        assertTrue(entry.getChanges().isEmpty());
    }

    @Test
    void recordKeepsChangesInTheGivenOrder() {
        service.record(workspace, ada, AuditAction.PROJECT_SETTINGS_CHANGED, AuditTarget.project(project), project,
                AuditChange.of("name", "Mobile", "Mobile App"),
                AuditChange.of("description", null, "Our app"));

        assertEquals(List.of("name", "description"),
                savedEntry().getChanges().stream().map(AuditChange::getField).toList());
    }

    // --- recordInAllWorkspacesOf ---

    @Test
    @SuppressWarnings("unchecked")
    void securityEventGoesIntoEveryWorkspaceOfThePerson() {
        Workspace other = Workspace.builder().id(9L).name("Side project").slug("side").build();
        when(workspaceMemberRepository.findAllByUserId(23L)).thenReturn(List.of(
                WorkspaceMember.builder().workspace(workspace).user(linus).build(),
                WorkspaceMember.builder().workspace(other).user(linus).build()));

        service.recordInAllWorkspacesOf(linus, null, AuditAction.LOGIN_FAILED_REPEATED,
                AuditChange.of("failedAttempts", null, "5"));

        ArgumentCaptor<Iterable<AuditLogEntry>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(auditLogRepository).saveAll(captor.capture());
        List<AuditLogEntry> entries = (List<AuditLogEntry>) captor.getValue();
        assertEquals(List.of(workspace, other), entries.stream().map(AuditLogEntry::getWorkspace).toList());
        for (AuditLogEntry entry : entries) {
            assertEquals(AuditAction.LOGIN_FAILED_REPEATED, entry.getAction());
            assertNull(entry.getActorId());
            assertEquals(AuditTargetType.USER, entry.getTargetType());
            assertEquals(23L, entry.getTargetId());
            assertEquals("Linus Torvalds", entry.getTargetName());
            assertNull(entry.getProjectId());
            assertEquals(List.of(AuditChange.of("failedAttempts", null, "5")), entry.getChanges());
        }
    }

    @Test
    void securityEventForSomeoneInNoWorkspaceWritesNothing() {
        when(workspaceMemberRepository.findAllByUserId(23L)).thenReturn(List.of());

        service.recordInAllWorkspacesOf(linus, linus, AuditAction.PASSWORD_RESET);

        verify(auditLogRepository, never()).saveAll(anyIterable());
        verify(auditLogRepository, never()).save(any());
    }

    // --- same-transaction rule ---

    @Test
    void bothWritersJoinTheCallersTransactionAndRefuseToRunWithoutOne() throws NoSuchMethodException {
        Method record = AuditLogService.class.getMethod("record", Workspace.class, User.class,
                AuditAction.class, AuditTarget.class, Project.class, AuditChange[].class);
        Method recordAll = AuditLogService.class.getMethod("recordInAllWorkspacesOf", User.class, User.class,
                AuditAction.class, AuditChange[].class);

        assertEquals(Propagation.MANDATORY, record.getAnnotation(Transactional.class).propagation());
        assertEquals(Propagation.MANDATORY, recordAll.getAnnotation(Transactional.class).propagation());
    }

    // --- targets and actions ---

    @Test
    void targetFactoriesBuildTheDocumentedNames() {
        Task task = Task.builder().id(42L).project(project).key("MOB-42").title("Fix login").build();

        assertEquals(new AuditTarget(AuditTargetType.TASK, 42L, "MOB-42 Fix login"), AuditTarget.task(task));
        assertEquals(new AuditTarget(AuditTargetType.INVITATION, null, "bob@example.com"),
                AuditTarget.invitation("bob@example.com"));
        assertEquals(new AuditTarget(AuditTargetType.WORKSPACE, 4L, "Acme"), AuditTarget.workspace(workspace));
        assertEquals(new AuditTarget(AuditTargetType.PROJECT, 15L, "Mobile App"), AuditTarget.project(project));
    }

    @Test
    void everyActionBelongsToTheDocumentedArea() {
        assertEquals(AuditArea.WORKSPACE, AuditAction.MEMBER_LEFT.area());
        assertEquals(AuditArea.TEAM, AuditAction.TEAM_MEMBER_ADDED.area());
        assertEquals(AuditArea.PROJECT, AuditAction.PROJECT_ROLES_CHANGED.area());
        assertEquals(AuditArea.TASK, AuditAction.TASK_RESTORED.area());
        assertEquals(AuditArea.SECURITY, AuditAction.LOGIN_FAILED_REPEATED.area());
        assertEquals(20, AuditAction.values().length, "a new action needs a migration and a row in the docs");
    }

    private AuditLogEntry savedEntry() {
        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogRepository).save(captor.capture());
        return captor.getValue();
    }
}