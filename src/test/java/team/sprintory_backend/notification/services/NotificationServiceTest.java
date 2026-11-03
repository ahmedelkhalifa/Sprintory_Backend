package team.sprintory_backend.notification.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.notification.dto.NotificationResponse;
import team.sprintory_backend.notification.dto.NotificationTargetResponse;
import team.sprintory_backend.notification.models.Notification;
import team.sprintory_backend.notification.models.NotificationCategory;
import team.sprintory_backend.notification.models.NotificationType;
import team.sprintory_backend.notification.repositories.NotificationRepository;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;

class NotificationServiceTest {

    private NotificationRepository repository;
    private UserRepository userRepository;
    private ProjectAccess projectAccess;
    private TaskRepository taskRepository;
    private NotificationService service;
    private final User recipient = User.builder().id(17L).firstName("Ada").lastName("Lovelace").build();
    private final User actor = User.builder().id(31L).firstName("Damir").lastName("Gabaidullin").build();
    private Workspace workspace;
    private Project project;
    private Task task;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        userRepository = mock(UserRepository.class);
        projectAccess = mock(ProjectAccess.class);
        taskRepository = mock(TaskRepository.class);
        service = new NotificationService(repository, userRepository, projectAccess, taskRepository);
        when(userRepository.getReferenceById(17L)).thenReturn(recipient);
        when(repository.save(any(Notification.class))).thenAnswer(call -> call.getArgument(0));

        workspace = Workspace.builder().id(4L).name("Acme").slug("acme").build();
        project = Project.builder().id(15L).workspace(workspace).name("Mobile App").key("MOB").build();
        task = Task.builder().id(230L).project(project).key("MOB-42").title("Fix login").build();
    }

    // --- create ---

    @Test
    void aTaskNotificationStoresEverySnapshot() {
        Notification n = service.create(17L, NotificationType.TASK_COMMENTED, NotificationSubject.task(task, actor),
                "Damir Gabaidullin commented on MOB-42 \"Fix login\"", "Looks good");

        assertSame(recipient, n.getRecipient());
        assertEquals(NotificationType.TASK_COMMENTED, n.getType());
        assertSame(workspace, n.getWorkspace());
        assertEquals("Acme", n.getWorkspaceName());
        assertEquals(15L, n.getProjectId());
        assertEquals("MOB", n.getProjectKey());
        assertEquals("Mobile App", n.getProjectName());
        assertEquals(230L, n.getTaskId());
        assertEquals("MOB-42", n.getTaskKey());
        assertEquals("Fix login", n.getTaskTitle());
        assertEquals(31L, n.getActorId());
        assertEquals("Damir Gabaidullin", n.getActorName());
        assertEquals("Looks good", n.getExcerpt());
        assertNotNull(n.getCreatedAt());
        assertNull(n.getReadAt());
    }

    @Test
    void aProjectNotificationHasNoTask() {
        Notification n = service.create(17L, NotificationType.PROJECT_MEMBER_ADDED,
                NotificationSubject.project(project, actor), "added", null);

        assertEquals(15L, n.getProjectId());
        assertNull(n.getTaskId());
        assertNull(n.getTaskKey());
    }

    @Test
    void aSecurityNotificationHasNoWorkspaceProjectOrActor() {
        Notification n = service.create(17L, NotificationType.PASSWORD_RESET, NotificationSubject.system(),
                NotificationMessages.passwordReset(), null);

        assertNull(n.getWorkspace());
        assertNull(n.getWorkspaceName());
        assertNull(n.getProjectId());
        assertNull(n.getActorId());
        assertNull(n.getActorName());
    }

    @Test
    void aTooLongMessageIsCutToTheColumnSize() {
        Notification n = service.create(17L, NotificationType.TASK_ASSIGNED, NotificationSubject.task(task, actor),
                "x".repeat(1500), null);

        assertEquals(NotificationMessages.MESSAGE_LIMIT, n.getMessage().length());
        assertTrue(n.getMessage().endsWith("…"));
    }

    // --- list and count ---

    @Test
    @SuppressWarnings("unchecked")
    void listIsNewestFirstKeepsThePageAndMapsTheSnapshots() {
        Notification n = taskNotification(901L, null);
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(n), PageRequest.of(2, 10), 21));

        PageResponse<NotificationResponse> page = service.list(recipient, true, 4L, PageRequest.of(2, 10));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), pageable.capture());
        assertEquals(PageRequest.of(2, 10, NotificationService.NEWEST_FIRST), pageable.getValue());

        assertEquals(21, page.totalElements());
        NotificationResponse r = page.content().get(0);
        assertEquals(901L, r.id());
        assertEquals(NotificationType.TASK_ASSIGNED, r.type());
        assertEquals(NotificationCategory.ASSIGNMENT, r.category());
        assertFalse(r.read());
        assertEquals(new NotificationResponse.Actor(31L, "Damir Gabaidullin"), r.actor());
        assertEquals(new NotificationResponse.WorkspaceRef(4L, "Acme"), r.workspace());
        assertEquals(new NotificationResponse.ProjectRef(15L, "MOB", "Mobile App"), r.project());
        assertEquals(new NotificationResponse.TaskRef(230L, "MOB-42", "Fix login"), r.task());
    }

    @Test
    void aSecurityNotificationHasNoNestedParts() {
        NotificationResponse r = NotificationResponse.from(Notification.builder().id(5L)
                .type(NotificationType.PASSWORD_RESET).message("m").createdAt(Instant.now()).build());

        assertEquals(NotificationCategory.SECURITY, r.category());
        assertNull(r.actor());
        assertNull(r.workspace());
        assertNull(r.project());
        assertNull(r.task());
    }

    @Test
    void unreadCountIsTheCallersUnreadRows() {
        when(repository.countByRecipientIdAndReadAtIsNull(17L)).thenReturn(3L);

        assertEquals(3L, service.unreadCount(recipient).count());
    }

    // --- open ---

    @Test
    void openingATaskNotificationMarksItReadAndPointsAtTheTask() {
        Notification n = givenNotification(taskNotification(901L, null));
        givenViewWork();
        when(taskRepository.findByIdAndProjectId(230L, 15L)).thenReturn(Optional.of(task));

        NotificationTargetResponse target = service.open(recipient, 901L);

        assertEquals(NotificationTargetResponse.task(4L, 15L, 230L, "MOB-42"), target);
        assertNotNull(n.getReadAt());
    }

    @Test
    void openingAgainKeepsTheFirstReadTime() {
        Instant first = Instant.parse("2026-11-01T10:00:00Z");
        Notification n = givenNotification(taskNotification(901L, first));
        givenViewWork();
        when(taskRepository.findByIdAndProjectId(230L, 15L)).thenReturn(Optional.of(task));

        service.open(recipient, 901L);

        assertEquals(first, n.getReadAt());
    }

    @Test
    void someoneElsesNotificationIsNotFoundAndNothingIsChecked() {
        when(repository.findByIdAndRecipientId(901L, 17L)).thenReturn(Optional.empty());

        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                () -> service.open(recipient, 901L));
        assertEquals("Notification not found.", e.getMessage());
        verifyNoInteractions(projectAccess, taskRepository);
    }

    @Test
    void afterLeavingTheProjectATaskNotificationOpensTo404ButIsStillRead() {
        Notification n = givenNotification(taskNotification(901L, null));
        when(projectAccess.require(15L, recipient, ProjectPermission.VIEW_WORK))
                .thenThrow(new ResourceNotFoundException("Project not found."));

        assertThrows(ResourceNotFoundException.class, () -> service.open(recipient, 901L));
        assertNotNull(n.getReadAt());
    }

    @Test
    void withoutViewWorkATaskNotificationIs404Not403() {
        // A workspace admin who left the project still sees the project, but not its tasks.
        Notification n = givenNotification(taskNotification(901L, null));
        when(projectAccess.require(15L, recipient, ProjectPermission.VIEW_WORK))
                .thenThrow(new AccessDeniedException("Project permission required: VIEW_WORK"));

        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                () -> service.open(recipient, 901L));
        assertEquals("Task not found.", e.getMessage());
        assertNotNull(n.getReadAt());
    }

    @Test
    void aDeletedTaskOpensTo404() {
        givenNotification(taskNotification(901L, null));
        givenViewWork();
        when(taskRepository.findByIdAndProjectId(230L, 15L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.open(recipient, 901L));
    }

    @Test
    void aProjectNotificationPointsAtTheProjectWhileItIsVisible() {
        givenNotification(projectNotification(902L));
        when(projectAccess.requireVisible(15L, recipient))
                .thenReturn(new ProjectAccessResult(project, null, null, Set.of()));

        assertEquals(NotificationTargetResponse.project(4L, 15L), service.open(recipient, 902L));
        verifyNoInteractions(taskRepository);
    }

    @Test
    void aProjectNotificationOfAnInvisibleProjectIs404ButRead() {
        Notification n = givenNotification(projectNotification(902L));
        when(projectAccess.requireVisible(15L, recipient)).thenThrow(new ResourceNotFoundException("Project not found."));

        assertThrows(ResourceNotFoundException.class, () -> service.open(recipient, 902L));
        assertNotNull(n.getReadAt());
    }

    @Test
    void invitationAndSecurityNotificationsHaveNothingToOpen() {
        Notification invitation = givenNotification(Notification.builder().id(903L).recipient(recipient)
                .type(NotificationType.WORKSPACE_INVITATION).workspace(workspace).workspaceName("Acme")
                .actorId(31L).actorName("Damir Gabaidullin").message("invited").createdAt(Instant.now()).build());

        assertEquals(NotificationTargetResponse.none(), service.open(recipient, 903L));
        assertNotNull(invitation.getReadAt());
        verifyNoInteractions(projectAccess, taskRepository);
    }

    // --- read all ---

    @Test
    void readAllMarksTheCallersUnreadRowsAndAnswersZero() {
        assertEquals(0L, service.readAll(recipient).count());
        verify(repository).markAllRead(eq(17L), any(Instant.class));
    }

    // --- fixtures ---

    private Notification givenNotification(Notification n) {
        when(repository.findByIdAndRecipientId(n.getId(), 17L)).thenReturn(Optional.of(n));
        return n;
    }

    private void givenViewWork() {
        when(projectAccess.require(15L, recipient, ProjectPermission.VIEW_WORK))
                .thenReturn(new ProjectAccessResult(project, null, null, Set.of(ProjectPermission.VIEW_WORK)));
    }

    private Notification taskNotification(Long id, Instant readAt) {
        return Notification.builder().id(id).recipient(recipient).type(NotificationType.TASK_ASSIGNED)
                .workspace(workspace).workspaceName("Acme")
                .projectId(15L).projectKey("MOB").projectName("Mobile App")
                .taskId(230L).taskKey("MOB-42").taskTitle("Fix login")
                .actorId(31L).actorName("Damir Gabaidullin")
                .message("Damir Gabaidullin assigned you MOB-42 \"Fix login\"")
                .createdAt(Instant.parse("2026-11-03T14:05:31Z")).readAt(readAt).build();
    }

    private Notification projectNotification(Long id) {
        return Notification.builder().id(id).recipient(recipient).type(NotificationType.PROJECT_MEMBER_ADDED)
                .workspace(workspace).workspaceName("Acme")
                .projectId(15L).projectKey("MOB").projectName("Mobile App")
                .actorId(31L).actorName("Damir Gabaidullin").message("added")
                .createdAt(Instant.now()).build();
    }
}