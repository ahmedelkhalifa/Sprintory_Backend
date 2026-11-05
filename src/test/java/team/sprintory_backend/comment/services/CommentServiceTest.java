package team.sprintory_backend.comment.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import team.sprintory_backend.comment.dto.CommentRequest;
import team.sprintory_backend.comment.dto.CommentResponse;
import team.sprintory_backend.comment.models.TaskComment;
import team.sprintory_backend.comment.repositories.TaskCommentRepository;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.common.richtext.InvalidRichTextException;
import team.sprintory_backend.common.richtext.RichTextDocument;
import team.sprintory_backend.common.richtext.RichTextMention;
import team.sprintory_backend.common.richtext.RichTextParser;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.mention.exceptions.InvalidMentionException;
import team.sprintory_backend.mention.services.MentionService;
import team.sprintory_backend.notification.events.Mentioned;
import team.sprintory_backend.notification.events.TaskCommented;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.services.TaskStatusHistoryService;
import team.sprintory_backend.team.models.Team;
import team.sprintory_backend.team.repositories.TeamRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.exceptions.WorkspaceArchivedException;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static team.sprintory_backend.project.models.ProjectPermission.COMMENT;
import static team.sprintory_backend.project.models.ProjectPermission.MODERATE_COMMENTS;
import static team.sprintory_backend.project.models.ProjectPermission.VIEW_WORK;

/** The comment rules of docs/comments-docs.md, with access and repositories mocked. */
class CommentServiceTest {

    private static final Long PROJECT_ID = 3L;
    private static final Long TASK_ID = 5L;
    private static final Long COMMENT_ID = 77L;

    private ProjectAccess access;
    private TaskRepository taskRepository;
    private TaskCommentRepository commentRepository;
    private MentionService mentions;
    private TaskStatusHistoryService history;
    private UserRepository userRepository;
    private TeamRepository teamRepository;
    private ApplicationEventPublisher events;
    private CommentService service;

    private final User ada = User.builder().id(42L).firstName("Ada").lastName("Lovelace").build();
    private final User grace = User.builder().id(9L).firstName("Grace").lastName("Hopper").build();
    private final User damir = User.builder().id(31L).firstName("Damir").lastName("Gabaidullin").build();
    private Workspace workspace;
    private Project project;
    private Task task;

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccess.class);
        taskRepository = mock(TaskRepository.class);
        commentRepository = mock(TaskCommentRepository.class);
        mentions = mock(MentionService.class);
        history = mock(TaskStatusHistoryService.class);
        userRepository = mock(UserRepository.class);
        teamRepository = mock(TeamRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new CommentService(access, taskRepository, commentRepository,
                new RichTextParser(JsonMapper.builder().build()), mentions, history, userRepository,
                teamRepository, events);

        workspace = Workspace.builder().id(8L).name("Acme").slug("acme").build();
        project = Project.builder().id(PROJECT_ID).workspace(workspace).name("Mobile App").key("MOB").build();
        task = Task.builder().id(TASK_ID).project(project).key("MOB-5").title("Fix login").build();
        when(taskRepository.findByIdAndProjectId(TASK_ID, PROJECT_ID)).thenReturn(Optional.of(task));
        when(commentRepository.save(any(TaskComment.class))).thenAnswer(call -> {
            TaskComment saved = call.getArgument(0);
            if (saved.getId() == null) saved.setId(COMMENT_ID);
            return saved;
        });
        when(userRepository.findAllById(any())).thenReturn(List.of(damir));
    }

    // --- post ---

    @Test
    void aMemberPostsANormalizedCommentAndItsWatchersAndMentionsAreTold() {
        givenActive(ada, COMMENT);
        when(mentions.newlyMentionedUserIds(eq(project), any(RichTextDocument.class), isNull()))
                .thenReturn(Set.of(31L));
        String sent = "{\"type\":\"doc\",\"style\":\"x\",\"content\":[{\"type\":\"paragraph\",\"content\":["
                + text("Can you check, ") + "," + mention("user", 31, "Damir") + "]}]}";

        CommentResponse response = service.create(ada, PROJECT_ID, TASK_ID, new CommentRequest(sent));

        TaskComment saved = savedComment();
        assertEquals(doc(text("Can you check, "), mention("user", 31, "Damir")), saved.getBody(), "stored normalized");
        assertSame(ada, saved.getAuthor());
        assertSame(task, saved.getTask());
        verify(events).publishEvent(new TaskCommented(task, ada, COMMENT_ID, "Can you check, @Damir", Set.of(31L)));
        assertEquals(new CommentResponse.Author(42L, "Ada Lovelace"), response.author());
        assertEquals(List.of(new CommentResponse.MentionName("user", 31L, "Damir Gabaidullin")), response.mentions());
        assertNull(response.editedAt());
        assertFalse(response.deleted());
        assertTrue(response.canEdit());
        assertTrue(response.canDelete());
    }

    @Test
    void aViewerCannotPost() {
        when(access.requireActive(PROJECT_ID, ada, COMMENT))
                .thenThrow(new AccessDeniedException("Project permission required: COMMENT"));

        assertThrows(AccessDeniedException.class,
                () -> service.create(ada, PROJECT_ID, TASK_ID, new CommentRequest(doc(text("Hi")))));
        verifyNoInteractions(commentRepository, events);
    }

    @Test
    void anEmptyOrTooLongCommentIsRefused() {
        givenActive(ada, COMMENT);

        InvalidRichTextException empty = assertThrows(InvalidRichTextException.class, () -> service.create(ada,
                PROJECT_ID, TASK_ID, new CommentRequest("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}")));
        InvalidRichTextException tooLong = assertThrows(InvalidRichTextException.class, () -> service.create(ada,
                PROJECT_ID, TASK_ID, new CommentRequest(doc(text("x".repeat(CommentService.MAX_LENGTH + 1))))));

        assertEquals("A comment cannot be empty.", empty.getMessage());
        assertEquals("A comment can have at most 10000 characters.", tooLong.getMessage());
        verifyNoInteractions(commentRepository, events);
    }

    @Test
    void exactlyTheMaximumLengthIsAllowed() {
        givenActive(ada, COMMENT);

        service.create(ada, PROJECT_ID, TASK_ID, new CommentRequest(doc(text("x".repeat(CommentService.MAX_LENGTH)))));

        verify(commentRepository).save(any(TaskComment.class));
    }

    @Test
    void plainTextIsRefused() {
        givenActive(ada, COMMENT);

        assertThrows(InvalidRichTextException.class,
                () -> service.create(ada, PROJECT_ID, TASK_ID, new CommentRequest("just text")));
        verifyNoInteractions(commentRepository, events);
    }

    @Test
    void mentioningAnOutsiderIsRefusedBeforeAnythingIsWritten() {
        givenActive(ada, COMMENT);
        when(mentions.newlyMentionedUserIds(eq(project), any(RichTextDocument.class), isNull()))
                .thenThrow(new InvalidMentionException("Only members of this project can be mentioned."));

        assertThrows(InvalidMentionException.class, () -> service.create(ada, PROJECT_ID, TASK_ID,
                new CommentRequest(doc(mention("user", 99, "X")))));
        verify(commentRepository, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void commentingOnAMissingOrDeletedTaskIs404() {
        givenActive(ada, COMMENT);
        when(taskRepository.findByIdAndProjectId(TASK_ID, PROJECT_ID)).thenReturn(Optional.empty());

        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                () -> service.create(ada, PROJECT_ID, TASK_ID, new CommentRequest(doc(text("Hi")))));
        assertEquals("Task not found.", e.getMessage());
    }

    // --- edit ---

    @Test
    void theAuthorEditsAndOnlyNewMentionsAreTold() {
        givenActive(ada, COMMENT);
        TaskComment comment = givenComment(ada, doc(mention("user", 31, "Damir")));
        when(mentions.newlyMentionedUserIds(eq(project), any(RichTextDocument.class), any(RichTextDocument.class)))
                .thenReturn(Set.of(17L));

        CommentResponse response = service.update(ada, PROJECT_ID, TASK_ID, COMMENT_ID,
                new CommentRequest(doc(mention("user", 31, "Damir"), mention("user", 17, "Mobeen"))));

        ArgumentCaptor<RichTextDocument> previous = ArgumentCaptor.forClass(RichTextDocument.class);
        verify(mentions).newlyMentionedUserIds(eq(project), any(RichTextDocument.class), previous.capture());
        assertEquals(Set.of(RichTextMention.user(31)), previous.getValue().mentions());
        assertNotNull(comment.getEditedAt());
        assertNotNull(response.editedAt());
        verify(events).publishEvent(new Mentioned(task, ada, Set.of(17L), "@Damir@Mobeen"));
        verify(events, never()).publishEvent(any(TaskCommented.class));
    }

    @Test
    void savingTheSameTextChangesNothing() {
        givenActive(ada, COMMENT);
        TaskComment comment = givenComment(ada, doc(text("Hi")));

        service.update(ada, PROJECT_ID, TASK_ID, COMMENT_ID, new CommentRequest(doc(text("Hi"))));

        assertNull(comment.getEditedAt());
        verify(commentRepository, never()).save(any());
        verifyNoInteractions(mentions, events);
    }

    @Test
    void nobodyEditsSomeoneElsesCommentNotEvenTheProjectManager() {
        givenActive(grace, COMMENT);
        TaskComment comment = givenComment(ada, doc(text("Hi")));

        assertThrows(AccessDeniedException.class, () -> service.update(grace, PROJECT_ID, TASK_ID, COMMENT_ID,
                new CommentRequest(doc(text("Changed")))));
        assertEquals(doc(text("Hi")), comment.getBody());
        verifyNoInteractions(events);
    }

    @Test
    void aDeletedOrAnotherTasksCommentIs404() {
        givenActive(ada, COMMENT);
        TaskComment comment = givenComment(ada, doc(text("Hi")));
        comment.setDeletedAt(Instant.now());

        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class, () -> service.update(ada,
                PROJECT_ID, TASK_ID, COMMENT_ID, new CommentRequest(doc(text("Changed")))));
        assertEquals("Comment not found.", e.getMessage());
        assertThrows(ResourceNotFoundException.class, () -> service.update(ada, PROJECT_ID, TASK_ID, 999L,
                new CommentRequest(doc(text("Changed")))));
    }

    // --- delete ---

    @Test
    void theAuthorDeletesTheirOwnCommentAndTheHistorySaysSo() {
        givenVisible(ada, VIEW_WORK, COMMENT);
        TaskComment comment = givenComment(ada, doc(text("Hi")));

        service.delete(ada, PROJECT_ID, TASK_ID, COMMENT_ID);

        assertTrue(comment.isDeleted());
        assertEquals(42L, comment.getDeletedById());
        verify(commentRepository).save(comment);
        verify(history).recordCommentDeleted(task, COMMENT_ID, ada);
    }

    @Test
    void theProjectManagerDeletesAnyComment() {
        givenVisible(grace, VIEW_WORK, COMMENT, MODERATE_COMMENTS);
        TaskComment comment = givenComment(ada, doc(text("Hi")));

        service.delete(grace, PROJECT_ID, TASK_ID, COMMENT_ID);

        assertTrue(comment.isDeleted());
        assertEquals(9L, comment.getDeletedById());
        verify(history).recordCommentDeleted(task, COMMENT_ID, grace);
    }

    @Test
    void anotherMemberCannotDeleteSomeoneElsesComment() {
        givenVisible(grace, VIEW_WORK, COMMENT);
        TaskComment comment = givenComment(ada, doc(text("Hi")));

        assertThrows(AccessDeniedException.class, () -> service.delete(grace, PROJECT_ID, TASK_ID, COMMENT_ID));
        assertFalse(comment.isDeleted());
        verifyNoInteractions(history);
    }

    @Test
    void anAuthorWhoBecameAViewerCannotDeleteTheirOldComment() {
        givenVisible(ada, VIEW_WORK);
        givenComment(ada, doc(text("Hi")));

        assertThrows(AccessDeniedException.class, () -> service.delete(ada, PROJECT_ID, TASK_ID, COMMENT_ID));
        verifyNoInteractions(history);
    }

    @Test
    void nothingIsDeletedInAnArchivedWorkspace() {
        workspace.setArchivedAt(Instant.now());
        givenVisible(ada, VIEW_WORK, COMMENT);
        TaskComment comment = givenComment(ada, doc(text("Hi")));

        assertThrows(WorkspaceArchivedException.class, () -> service.delete(ada, PROJECT_ID, TASK_ID, COMMENT_ID));
        assertFalse(comment.isDeleted());
        verifyNoInteractions(history);
    }

    // --- list ---

    @Test
    void theThreadShowsDeletedCommentsAsPlaceholdersAndCurrentMentionNames() {
        givenVisible(ada, VIEW_WORK, COMMENT);
        TaskComment mine = comment(1L, ada, doc(text("Mine "), mention("user", 31, "Old name")));
        TaskComment theirs = comment(2L, grace, doc(text("Theirs")));
        TaskComment deleted = comment(3L, grace, doc(text("Secret")));
        deleted.setDeletedAt(Instant.now());
        givenPage(mine, theirs, deleted);

        List<CommentResponse> thread = service.list(ada, PROJECT_ID, TASK_ID, PageRequest.of(0, 20)).content();

        assertEquals(List.of(new CommentResponse.MentionName("user", 31L, "Damir Gabaidullin")), thread.get(0).mentions());
        assertTrue(thread.get(0).canEdit());
        assertTrue(thread.get(0).canDelete());
        assertFalse(thread.get(1).canEdit());
        assertFalse(thread.get(1).canDelete());
        CommentResponse placeholder = thread.get(2);
        assertTrue(placeholder.deleted());
        assertNull(placeholder.body(), "a deleted comment's text is never returned");
        assertEquals(List.of(), placeholder.mentions());
        assertFalse(placeholder.canEdit());
        assertFalse(placeholder.canDelete());
        assertEquals(new CommentResponse.Author(9L, "Grace Hopper"), placeholder.author());
    }

    @Test
    void theProjectManagerMayDeleteEveryCommentButEditOnlyTheirOwn() {
        givenVisible(grace, VIEW_WORK, COMMENT, MODERATE_COMMENTS);
        givenPage(comment(1L, ada, doc(text("Ada's"))));

        CommentResponse response = service.list(grace, PROJECT_ID, TASK_ID, PageRequest.of(0, 20)).content().get(0);

        assertFalse(response.canEdit());
        assertTrue(response.canDelete());
    }

    @Test
    void anArchivedWorkspaceIsReadOnly() {
        workspace.setArchivedAt(Instant.now());
        givenVisible(ada, VIEW_WORK, COMMENT);
        givenPage(comment(1L, ada, doc(text("Mine"))));

        CommentResponse response = service.list(ada, PROJECT_ID, TASK_ID, PageRequest.of(0, 20)).content().get(0);

        assertFalse(response.canEdit());
        assertFalse(response.canDelete());
    }

    @Test
    void aTeamMentionShowsTheTeamsCurrentName() {
        givenVisible(ada, VIEW_WORK, COMMENT);
        givenPage(comment(1L, ada, doc(mention("team", 5, "Old team name"))));
        when(teamRepository.findAllById(any())).thenReturn(List.of(Team.builder().id(5L).name("Backend").build()));

        CommentResponse response = service.list(ada, PROJECT_ID, TASK_ID, PageRequest.of(0, 20)).content().get(0);

        assertEquals(List.of(new CommentResponse.MentionName("team", 5L, "Backend")), response.mentions());
    }

    @Test
    void readingNeedsViewWork() {
        when(access.require(PROJECT_ID, ada, VIEW_WORK))
                .thenThrow(new AccessDeniedException("Project permission required: VIEW_WORK"));

        assertThrows(AccessDeniedException.class, () -> service.list(ada, PROJECT_ID, TASK_ID, PageRequest.of(0, 20)));
        verifyNoInteractions(commentRepository);
    }

    // --- helpers ---

    private void givenActive(User user, ProjectPermission... permissions) {
        when(access.requireActive(PROJECT_ID, user, permissions[0])).thenReturn(result(permissions));
    }

    private void givenVisible(User user, ProjectPermission... permissions) {
        when(access.require(PROJECT_ID, user, VIEW_WORK)).thenReturn(result(permissions));
    }

    private ProjectAccessResult result(ProjectPermission... permissions) {
        WorkspaceMember member = WorkspaceMember.builder().workspace(workspace).build();
        return new ProjectAccessResult(project, member, null, Set.of(permissions));
    }

    private TaskComment givenComment(User author, String body) {
        TaskComment comment = comment(COMMENT_ID, author, body);
        when(commentRepository.findByIdAndTaskId(COMMENT_ID, TASK_ID)).thenReturn(Optional.of(comment));
        return comment;
    }

    private TaskComment comment(Long id, User author, String body) {
        return TaskComment.builder().id(id).task(task).author(author).body(body).build();
    }

    private void givenPage(TaskComment... comments) {
        PageRequest page = PageRequest.of(0, 20);
        when(commentRepository.findByTaskIdOrderByCreatedAtAscIdAsc(TASK_ID, page))
                .thenReturn(new PageImpl<>(List.of(comments), page, comments.length));
    }

    private TaskComment savedComment() {
        ArgumentCaptor<TaskComment> saved = ArgumentCaptor.forClass(TaskComment.class);
        verify(commentRepository).save(saved.capture());
        return saved.getValue();
    }

    /** A one-paragraph document in the normalized form the backend stores. */
    private static String doc(String... inline) {
        return "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[" + String.join(",", inline) + "]}]}";
    }

    private static String text(String text) {
        return "{\"type\":\"text\",\"text\":\"" + text + "\"}";
    }

    private static String mention(String kind, long id, String label) {
        return "{\"type\":\"mention\",\"attrs\":{\"kind\":\"" + kind + "\",\"id\":\"" + id + "\",\"label\":\"" + label + "\"}}";
    }
}