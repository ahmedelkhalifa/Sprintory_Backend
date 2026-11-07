package team.sprintory_backend.task.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import team.sprintory_backend.common.richtext.InvalidRichTextException;
import team.sprintory_backend.common.richtext.RichTextDocument;
import team.sprintory_backend.common.richtext.RichTextMention;
import team.sprintory_backend.common.richtext.RichTextParser;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.mention.exceptions.InvalidMentionException;
import team.sprintory_backend.mention.services.MentionService;
import team.sprintory_backend.notification.events.Mentioned;
import team.sprintory_backend.notification.events.TaskAssigned;
import team.sprintory_backend.notification.events.TaskCreated;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectBoard;
import team.sprintory_backend.project.models.ProjectBoardColumn;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectBoardColumnRepository;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.task.dto.CreateSubtaskRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.dto.UpdateSubtaskRequest;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.models.TaskPriority;
import team.sprintory_backend.task.models.TaskType;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** Pure service tests for subtasks (PMP-132): access and every repository are mocked. */
class SubtaskServiceTest {
    private static final Long PROJECT_ID = 3L;
    private static final Long PARENT_ID = 5L;
    private static final Long SUBTASK_ID = 6L;

    private ProjectAccess access;
    private ProjectRepository projectRepository;
    private TaskRepository taskRepository;
    private ProjectBoardColumnRepository columnRepository;
    private ProjectMemberRepository projectMemberRepository;
    private UserRepository userRepository;
    private TaskStatusHistoryService history;
    private ApplicationEventPublisher events;
    private MentionService mentions;
    private SubtaskService service;

    private User user;
    private User bob;
    private Project project;
    private ProjectBoardColumn backlog;
    private ProjectBoardColumn inProgress;
    private Task parent;

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccess.class);
        projectRepository = mock(ProjectRepository.class);
        taskRepository = mock(TaskRepository.class);
        columnRepository = mock(ProjectBoardColumnRepository.class);
        projectMemberRepository = mock(ProjectMemberRepository.class);
        userRepository = mock(UserRepository.class);
        history = mock(TaskStatusHistoryService.class);
        events = mock(ApplicationEventPublisher.class);
        mentions = mock(MentionService.class);
        service = new SubtaskService(access, projectRepository, taskRepository, columnRepository,
                projectMemberRepository, userRepository, history, events,
                new TaskContent(new RichTextParser(JsonMapper.builder().build()), mentions, events));

        user = User.builder().id(42L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
        bob = User.builder().id(17L).firstName("Bob").lastName("Builder").email("bob@example.com").build();
        Workspace workspace = Workspace.builder().id(9L).name("Acme").slug("acme").build();
        project = Project.builder().id(PROJECT_ID).workspace(workspace).name("Mobile App").key("MOB")
                .template(ProjectTemplate.KANBAN).build();
        ProjectBoard board = new ProjectBoard(project);
        backlog = column(board, 11L, "Backlog", 0);
        inProgress = column(board, 12L, "In Progress", 1);
        parent = Task.builder().id(PARENT_ID).project(project).number(1).key("MOB-1").title("Login")
                .type(TaskType.STORY).priority(TaskPriority.MEDIUM).status(backlog).build();

        when(access.requireActive(PROJECT_ID, user, ProjectPermission.EDIT_TASKS))
                .thenReturn(new ProjectAccessResult(project, null, null, Set.of(ProjectPermission.EDIT_TASKS)));
        when(columnRepository.findByBoardProjectIdOrderByPositionAscIdAsc(PROJECT_ID))
                .thenReturn(List.of(backlog, inProgress));
        when(columnRepository.findByIdAndBoardProjectId(12L, PROJECT_ID)).thenReturn(Optional.of(inProgress));
        when(projectRepository.findLastTaskNumber(PROJECT_ID)).thenReturn(57);
        when(taskRepository.findByIdAndProjectId(PARENT_ID, PROJECT_ID)).thenReturn(Optional.of(parent));
        when(taskRepository.saveAndFlush(any(Task.class))).thenAnswer(call -> {
            Task saved = call.getArgument(0);
            if (saved.getId() == null) saved.setId(600L);
            return saved;
        });
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, 17L)).thenReturn(true);
        when(userRepository.getReferenceById(17L)).thenReturn(bob);
    }

    // --- create ---

    @Test
    void createSavesTheSubtaskUnderItsParentAndRecordsIt() {
        TaskResponse response = service.create(user, PROJECT_ID, PARENT_ID,
                new CreateSubtaskRequest("  Write tests  ", null, null, 90,
                        List.of(" backend ", "Backend", "R09"), doc("Hi")));

        Task saved = savedSubtask();
        assertEquals("MOB-57", response.key());
        assertEquals("Write tests", response.title());
        assertSame(parent, saved.getParent());
        assertSame(backlog, saved.getStatus(), "no statusId means the board's first column");
        assertEquals(90, response.estimateMinutes());
        assertEquals(List.of("R09", "backend"), response.labels());
        assertEquals(doc("Hi"), response.description());
        verify(history).recordCreated(saved, user);
        verify(history).recordSubtaskAdded(parent, saved, user);
        verify(events).publishEvent(new TaskCreated(saved, user));
        verify(events, never()).publishEvent(any(TaskAssigned.class));
        verify(events, never()).publishEvent(any(Mentioned.class));
    }

    @Test
    void createWithAnAssigneeTellsThem() {
        service.create(user, PROJECT_ID, PARENT_ID, new CreateSubtaskRequest("Write tests", 17L, 12L, null));

        Task saved = savedSubtask();
        assertSame(inProgress, saved.getStatus());
        verify(events).publishEvent(new TaskAssigned(saved, user, bob));
    }

    @Test
    void createWithMentionsTellsTheMentionedPeople() {
        when(mentions.newlyMentionedUserIds(eq(project), any(RichTextDocument.class), isNull()))
                .thenReturn(Set.of(31L));

        service.create(user, PROJECT_ID, PARENT_ID,
                new CreateSubtaskRequest("Write tests", null, null, null, null, docWith(text("Ask "), mention(31))));

        Task saved = savedSubtask();
        verify(events).publishEvent(new Mentioned(saved, user, Set.of(31L), "Ask @Damir"));
    }

    @Test
    void anEmptyDocumentMeansNoDescriptionAndChecksNoMentions() {
        TaskResponse response = service.create(user, PROJECT_ID, PARENT_ID, new CreateSubtaskRequest("Write tests",
                null, null, null, null, "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}"));

        assertNull(response.description());
        verifyNoInteractions(mentions);
    }

    @Test
    void aPlainTextDescriptionIsRefusedBeforeAnythingIsWritten() {
        assertThrows(InvalidRichTextException.class, () -> service.create(user, PROJECT_ID, PARENT_ID,
                new CreateSubtaskRequest("Write tests", null, null, null, null, "Steps to reproduce")));

        verify(projectRepository, never()).incrementTaskNumber(anyLong());
        verify(taskRepository, never()).saveAndFlush(any(Task.class));
        verifyNoInteractions(history, events);
    }

    @Test
    void anOutsiderMentionIsRefusedBeforeAnythingIsWritten() {
        when(mentions.newlyMentionedUserIds(eq(project), any(RichTextDocument.class), isNull()))
                .thenThrow(new InvalidMentionException("Only members of this project can be mentioned."));

        assertThrows(InvalidMentionException.class, () -> service.create(user, PROJECT_ID, PARENT_ID,
                new CreateSubtaskRequest("Write tests", null, null, null, null, docWith(mention(99)))));

        verify(projectRepository, never()).incrementTaskNumber(anyLong());
        verify(taskRepository, never()).saveAndFlush(any(Task.class));
        verifyNoInteractions(history, events);
    }

    @Test
    void aSubtaskCannotHaveSubtasks() {
        Task child = subtask();
        when(taskRepository.findByIdAndProjectId(SUBTASK_ID, PROJECT_ID)).thenReturn(Optional.of(child));

        assertThrows(BadRequestException.class, () -> service.create(user, PROJECT_ID, SUBTASK_ID,
                new CreateSubtaskRequest("Too deep", null, null, null)));

        verify(taskRepository, never()).saveAndFlush(any(Task.class));
        verifyNoInteractions(history, events);
    }

    @Test
    void anAssigneeOutsideTheProjectIsRefused() {
        assertThrows(BadRequestException.class, () -> service.create(user, PROJECT_ID, PARENT_ID,
                new CreateSubtaskRequest("Write tests", 99L, null, null)));

        verify(taskRepository, never()).saveAndFlush(any(Task.class));
        verifyNoInteractions(history, events);
    }

    @Test
    void aRefusedAccessCheckChangesNothing() {
        when(access.requireActive(PROJECT_ID, user, ProjectPermission.EDIT_TASKS))
                .thenThrow(new AccessDeniedException("Viewer"));

        assertThrows(AccessDeniedException.class, () -> service.create(user, PROJECT_ID, PARENT_ID,
                new CreateSubtaskRequest("Write tests", null, null, null)));
        assertThrows(AccessDeniedException.class, () -> service.update(user, PROJECT_ID, PARENT_ID, SUBTASK_ID,
                new UpdateSubtaskRequest("New", null, null, null, null, null)));

        verifyNoInteractions(projectRepository, taskRepository, history, events, mentions);
    }

    // --- update ---

    @Test
    void updateRecordsEachChangedFieldAndTheStatusMove() {
        Task child = givenSubtask();

        service.update(user, PROJECT_ID, PARENT_ID, SUBTASK_ID, new UpdateSubtaskRequest("Write more tests",
                null, 12L, null, null, null, List.of("qa"), null, null));

        verify(history).record(child, backlog, inProgress, user);
        ArgumentCaptor<TaskSnapshot> before = ArgumentCaptor.forClass(TaskSnapshot.class);
        ArgumentCaptor<TaskSnapshot> after = ArgumentCaptor.forClass(TaskSnapshot.class);
        verify(history).recordChanges(eq(child), before.capture(), after.capture(), eq(user));
        assertEquals("Write tests", before.getValue().title());
        assertEquals("Write more tests", after.getValue().title());
        assertNotEquals(before.getValue().labels(), after.getValue().labels());
        assertEquals(Set.of("qa"), child.getLabels());
    }

    @Test
    void emptyLabelsRemoveThemAll() {
        Task child = givenSubtask();

        service.update(user, PROJECT_ID, PARENT_ID, SUBTASK_ID, new UpdateSubtaskRequest(null, null, null, null,
                null, null, List.of(), null, null));

        assertTrue(child.getLabels().isEmpty());
    }

    @Test
    void assigningAnotherPersonTellsThemOnce() {
        Task child = givenSubtask();

        service.update(user, PROJECT_ID, PARENT_ID, SUBTASK_ID, new UpdateSubtaskRequest(null, 17L, null, null,
                null, null));

        assertSame(bob, child.getAssignee());
        verify(events).publishEvent(new TaskAssigned(child, user, bob));
    }

    @Test
    void reassigningTheSamePersonOrClearingTellsNoOne() {
        Task child = givenSubtask();
        child.setAssignee(bob);

        service.update(user, PROJECT_ID, PARENT_ID, SUBTASK_ID, new UpdateSubtaskRequest(null, 17L, null, null,
                null, null));
        service.update(user, PROJECT_ID, PARENT_ID, SUBTASK_ID, new UpdateSubtaskRequest(null, null, null, null,
                true, null));

        assertNull(child.getAssignee());
        verify(events, never()).publishEvent(any(TaskAssigned.class));
    }

    @Test
    void editingComparesTheNewMentionsWithTheStoredDescription() {
        Task child = givenSubtask();
        child.setDescription(docWith(mention(31)));
        when(mentions.newlyMentionedUserIds(eq(project), any(RichTextDocument.class), any(RichTextDocument.class)))
                .thenReturn(Set.of(17L));

        service.update(user, PROJECT_ID, PARENT_ID, SUBTASK_ID, new UpdateSubtaskRequest(null, null, null, null,
                null, null, null, docWith(mention(31), mention(17)), null));

        ArgumentCaptor<RichTextDocument> previous = ArgumentCaptor.forClass(RichTextDocument.class);
        verify(mentions).newlyMentionedUserIds(eq(project), any(RichTextDocument.class), previous.capture());
        assertEquals(Set.of(RichTextMention.user(31)), previous.getValue().mentions());
        assertEquals(docWith(mention(31), mention(17)), child.getDescription());
        verify(events).publishEvent(new Mentioned(child, user, Set.of(17L), "@Damir@Damir"));
    }

    @Test
    void clearingTheDescriptionChecksNoMentions() {
        Task child = givenSubtask();
        child.setDescription(doc("Old"));

        service.update(user, PROJECT_ID, PARENT_ID, SUBTASK_ID, new UpdateSubtaskRequest(null, null, null, null,
                null, null, null, null, true));

        assertNull(child.getDescription());
        verifyNoInteractions(mentions);
        verify(events, never()).publishEvent(any(Mentioned.class));
    }

    @Test
    void aPlainTextDescriptionOnEditIsRefusedBeforeAnythingChanges() {
        Task child = givenSubtask();

        assertThrows(InvalidRichTextException.class, () -> service.update(user, PROJECT_ID, PARENT_ID, SUBTASK_ID,
                new UpdateSubtaskRequest("New", null, null, null, null, null, null, "plain", null)));

        assertEquals("Write tests", child.getTitle());
        verifyNoInteractions(history, events);
    }

    @Test
    void aSubtaskOfAnotherParentIsNotFound() {
        Task other = Task.builder().id(7L).project(project).key("MOB-2").title("Other").status(backlog).build();
        Task child = subtask();
        child.setParent(other);
        when(taskRepository.findByIdAndProjectId(SUBTASK_ID, PROJECT_ID)).thenReturn(Optional.of(child));

        assertThrows(ResourceNotFoundException.class, () -> service.update(user, PROJECT_ID, PARENT_ID, SUBTASK_ID,
                new UpdateSubtaskRequest("New", null, null, null, null, null)));

        verifyNoInteractions(history, events);
    }

    // --- helpers ---

    private Task subtask() {
        return Task.builder().id(SUBTASK_ID).project(project).parent(parent).number(57).key("MOB-57")
                .title("Write tests").type(TaskType.TASK).priority(TaskPriority.MEDIUM).status(backlog)
                .labels(new HashSet<>(Set.of("backend"))).build();
    }

    private Task givenSubtask() {
        Task child = subtask();
        when(taskRepository.findByIdAndProjectId(SUBTASK_ID, PROJECT_ID)).thenReturn(Optional.of(child));
        return child;
    }

    private Task savedSubtask() {
        ArgumentCaptor<Task> saved = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    /** A one-paragraph TipTap document, already in the normalized form the backend stores. */
    private static String doc(String text) {
        return docWith(text(text));
    }

    private static String docWith(String... inline) {
        return "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[" + String.join(",", inline) + "]}]}";
    }

    private static String text(String text) {
        return "{\"type\":\"text\",\"text\":\"" + text + "\"}";
    }

    private static String mention(long userId) {
        return "{\"type\":\"mention\",\"attrs\":{\"kind\":\"user\",\"id\":\"" + userId + "\",\"label\":\"Damir\"}}";
    }

    private static ProjectBoardColumn column(ProjectBoard board, Long id, String name, int position) {
        ProjectBoardColumn column = new ProjectBoardColumn(board, name, position);
        column.setId(id);
        return column;
    }
}