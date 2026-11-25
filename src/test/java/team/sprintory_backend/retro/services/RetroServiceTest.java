package team.sprintory_backend.retro.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import team.sprintory_backend.audit.models.AuditAction;
import team.sprintory_backend.audit.models.AuditChange;
import team.sprintory_backend.audit.models.AuditTarget;
import team.sprintory_backend.audit.services.AuditLogService;
import team.sprintory_backend.comment.dto.CommentRequest;
import team.sprintory_backend.comment.dto.CommentResponse;
import team.sprintory_backend.common.richtext.InvalidRichTextException;
import team.sprintory_backend.common.richtext.RichTextDocument;
import team.sprintory_backend.common.richtext.RichTextMention;
import team.sprintory_backend.common.richtext.RichTextParser;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.mention.services.MentionService;
import team.sprintory_backend.notification.events.RetroClosed;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectBoardColumn;
import team.sprintory_backend.project.models.ProjectLifecycleState;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.retro.dto.RetroActionRequest;
import team.sprintory_backend.retro.dto.RetroActionResponse;
import team.sprintory_backend.retro.dto.RetroItemRequest;
import team.sprintory_backend.retro.dto.RetroItemResponse;
import team.sprintory_backend.retro.dto.RetroResponse;
import team.sprintory_backend.retro.dto.RetroUser;
import team.sprintory_backend.retro.dto.RetroVoteResponse;
import team.sprintory_backend.retro.exceptions.RetroConflictException;
import team.sprintory_backend.retro.models.RetroAction;
import team.sprintory_backend.retro.models.RetroColumn;
import team.sprintory_backend.retro.models.RetroItem;
import team.sprintory_backend.retro.models.RetroStatus;
import team.sprintory_backend.retro.models.RetroVote;
import team.sprintory_backend.retro.models.Retrospective;
import team.sprintory_backend.retro.repositories.RetroActionRepository;
import team.sprintory_backend.retro.repositories.RetroItemRepository;
import team.sprintory_backend.retro.repositories.RetroVoteRepository;
import team.sprintory_backend.retro.repositories.RetrospectiveRepository;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.sprint.models.SprintStatus;
import team.sprintory_backend.sprint.repositories.SprintRepository;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.models.TaskType;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.services.TaskApiService;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.exceptions.WorkspaceArchivedException;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

/** The retro rules of docs/retrospectives-docs.md §3: open, read, cards, votes, actions and close. */
class RetroServiceTest {

    private RetrospectiveRepository retros;
    private RetroItemRepository items;
    private RetroVoteRepository votes;
    private RetroActionRepository actions;
    private SprintRepository sprints;
    private ProjectRepository projects;
    private TaskRepository tasks;
    private TaskApiService taskApi;
    private ProjectAccess access;
    private MentionService mentions;
    private AuditLogService audit;
    private ApplicationEventPublisher events;
    private RetroService service;

    private Workspace workspace;
    private Project project;
    private Sprint sprint;
    private User actor;
    private final User damir = User.builder().id(31L).firstName("Damir").lastName("Gabaidullin")
            .email("damir@test.invalid").build();

    @BeforeEach
    void setUp() {
        retros = mock(RetrospectiveRepository.class);
        items = mock(RetroItemRepository.class);
        votes = mock(RetroVoteRepository.class);
        actions = mock(RetroActionRepository.class);
        sprints = mock(SprintRepository.class);
        projects = mock(ProjectRepository.class);
        tasks = mock(TaskRepository.class);
        taskApi = mock(TaskApiService.class);
        access = mock(ProjectAccess.class);
        mentions = mock(MentionService.class);
        audit = mock(AuditLogService.class);
        events = mock(ApplicationEventPublisher.class);
        service = new RetroService(retros, items, votes, actions, sprints, projects, tasks, taskApi, access,
                new RichTextParser(JsonMapper.builder().build()), mentions, audit, events);

        workspace = Workspace.builder().id(1L).name("Acme").slug("acme").build();
        project = Project.builder().id(10L).workspace(workspace).name("Mobile").key("MOB")
                .template(ProjectTemplate.SCRUM).build();
        sprint = new Sprint(project, 3, "MOB Sprint 3", "Ship login", null,
                LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 22));
        sprint.setId(42L);
        sprint.setStatus(SprintStatus.CANCELLED);
        sprint.setCompletedAt(Instant.parse("2026-11-20T16:40:00Z"));
        sprint.setCancelReason("The client dropped the login feature.");
        actor = User.builder().id(9L).firstName("Linus").lastName("Torvalds").email("linus@test.invalid").build();
    }

    private ProjectAccessResult accessAs(ProjectRole role) {
        WorkspaceMember member = WorkspaceMember.builder().workspace(workspace).user(actor).build();
        return new ProjectAccessResult(project, member, null, ProjectRole.permissionsOf(List.of(role)));
    }

    /** The sprint exists, the caller may manage retros, and the project row lock is free. */
    private void openableAs(ProjectRole role) {
        when(sprints.findProjectId(42L)).thenReturn(Optional.of(10L));
        when(access.requireActive(10L, actor, ProjectPermission.RETRO_MANAGE)).thenReturn(accessAs(role));
        when(projects.lockById(10L)).thenReturn(Optional.of(project));
        when(sprints.findById(42L)).thenReturn(Optional.of(sprint));
    }

    private Retrospective retro(long id) {
        Retrospective retro = new Retrospective(project, sprint, "MOB Sprint 3 Retrospective", actor);
        retro.setId(id);
        return retro;
    }

    // --- open (§3.1) ---

    @Test
    void openCreatesAnOpenRetroWithTheFixedTitle() {
        openableAs(ProjectRole.SCRUM_MASTER);
        when(retros.save(any(Retrospective.class))).thenAnswer(inv -> {
            Retrospective saved = inv.getArgument(0);
            saved.setId(7L);
            return saved;
        });

        RetroService.Opened opened = service.open(actor, 42L);

        assertTrue(opened.created());
        RetroResponse retro = opened.retro();
        assertEquals(7L, retro.id());
        assertEquals("MOB Sprint 3 Retrospective", retro.title());
        assertEquals(RetroStatus.OPEN, retro.status());
        assertEquals(9L, retro.createdBy().id());
        assertEquals("Linus Torvalds", retro.createdBy().name());
        assertEquals("The client dropped the login feature.", retro.sprint().cancelReason());
        assertEquals(SprintStatus.CANCELLED, retro.sprint().status());
        assertTrue(retro.canManage());
        assertTrue(retro.canContribute());
        assertEquals(3, retro.votesLeft());
    }

    @Test
    void openLocksTheProjectBeforeLoadingTheSprint() {
        openableAs(ProjectRole.SCRUM_MASTER);
        when(retros.save(any(Retrospective.class))).thenAnswer(inv -> inv.getArgument(0));

        service.open(actor, 42L);

        var order = inOrder(access, projects, sprints, retros);
        order.verify(access).requireActive(10L, actor, ProjectPermission.RETRO_MANAGE);
        order.verify(projects).lockById(10L);
        order.verify(sprints).findById(42L);
        order.verify(retros).findBySprintId(42L);
    }

    @Test
    void openReturnsTheExistingRetroUnchangedEvenWhenClosed() {
        openableAs(ProjectRole.PRODUCT_OWNER);
        Retrospective existing = retro(7L);
        existing.setStatus(RetroStatus.CLOSED);
        when(retros.findBySprintId(42L)).thenReturn(Optional.of(existing));

        RetroService.Opened opened = service.open(actor, 42L);

        assertFalse(opened.created());
        assertEquals(7L, opened.retro().id());
        assertEquals(RetroStatus.CLOSED, opened.retro().status());
        assertFalse(opened.retro().canManage(), "a closed retro accepts no changes");
        verify(retros, never()).save(any());
    }

    @Test
    void openRefusesASprintThatHasNotEnded() {
        openableAs(ProjectRole.SCRUM_MASTER);
        sprint.setStatus(SprintStatus.ACTIVE);

        RetroConflictException e = assertThrows(RetroConflictException.class, () -> service.open(actor, 42L));

        assertEquals(ErrorCode.RETRO_SPRINT_NOT_ENDED, e.getCode());
        verify(retros, never()).save(any());
    }

    @Test
    void openRefusesACompletedProjectEvenWhenARetroExists() {
        openableAs(ProjectRole.SCRUM_MASTER);
        project.setLifecycleStatus(ProjectLifecycleState.COMPLETED);

        RetroConflictException e = assertThrows(RetroConflictException.class, () -> service.open(actor, 42L));

        assertEquals(ErrorCode.CONFLICT, e.getCode());
        verify(projects, never()).lockById(any());
        verify(retros, never()).findBySprintId(any());
    }

    @Test
    void openAnswersSprintNotFoundForAProjectTheCallerCannotSee() {
        when(sprints.findProjectId(42L)).thenReturn(Optional.of(10L));
        when(access.requireActive(10L, actor, ProjectPermission.RETRO_MANAGE))
                .thenThrow(new ResourceNotFoundException("Project not found."));

        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class, () -> service.open(actor, 42L));

        assertEquals("Sprint not found.", e.getMessage());
    }

    @Test
    void openAnswersSprintNotFoundForAMissingSprint() {
        when(sprints.findProjectId(42L)).thenReturn(Optional.empty());

        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class, () -> service.open(actor, 42L));

        assertEquals("Sprint not found.", e.getMessage());
        verifyNoInteractions(access);
    }

    @Test
    void openPassesOnTheForbiddenAndArchivedAnswers() {
        when(sprints.findProjectId(42L)).thenReturn(Optional.of(10L));
        when(access.requireActive(10L, actor, ProjectPermission.RETRO_MANAGE))
                .thenThrow(new AccessDeniedException("no"))
                .thenThrow(new WorkspaceArchivedException("Workspace is archived."));

        assertThrows(AccessDeniedException.class, () -> service.open(actor, 42L));
        assertThrows(WorkspaceArchivedException.class, () -> service.open(actor, 42L));
        verify(retros, never()).save(any());
    }

    // --- get (§3.2) ---

    private void readableAs(ProjectRole role) {
        when(retros.findById(7L)).thenReturn(Optional.of(retro(7L)));
        when(access.require(10L, actor, ProjectPermission.VIEW_WORK)).thenReturn(accessAs(role));
    }

    @Test
    void aMemberCanContributeButNotManageAndSeesTheVotesLeft() {
        readableAs(ProjectRole.MEMBER);
        when(votes.findByRetroId(7L)).thenReturn(List.of(new RetroVote(card(1L, damir, doc(text("x"))), actor)));

        RetroResponse retro = service.get(actor, 7L);

        assertTrue(retro.canContribute());
        assertFalse(retro.canManage());
        assertEquals(3, retro.votesPerMember());
        assertEquals(2, retro.votesLeft());
        assertEquals(List.of(), retro.items());
        assertEquals(List.of(), retro.actions());
        assertNull(retro.previousActions());
    }

    @Test
    void aViewerOnlyReadsAndHasNoVotes() {
        readableAs(ProjectRole.VIEWER);

        RetroResponse retro = service.get(actor, 7L);

        assertFalse(retro.canContribute());
        assertFalse(retro.canManage());
        assertEquals(0, retro.votesLeft());
    }

    @Test
    void aClosedRetroIsReadOnlyForEveryone() {
        Retrospective closed = retro(7L);
        closed.setStatus(RetroStatus.CLOSED);
        closed.setClosedAt(Instant.parse("2026-11-26T11:00:00Z"));
        closed.setClosedByUser(actor);
        when(retros.findById(7L)).thenReturn(Optional.of(closed));
        when(access.require(10L, actor, ProjectPermission.VIEW_WORK)).thenReturn(accessAs(ProjectRole.SCRUM_MASTER));

        RetroResponse retro = service.get(actor, 7L);

        assertFalse(retro.canContribute());
        assertFalse(retro.canManage());
        assertEquals(9L, retro.closedBy().id());
        assertEquals(Instant.parse("2026-11-26T11:00:00Z"), retro.closedAt());
    }

    @Test
    void anArchivedProjectOrWorkspaceIsReadableButReadOnly() {
        readableAs(ProjectRole.SCRUM_MASTER);
        project.setLifecycleStatus(ProjectLifecycleState.ARCHIVED);
        assertFalse(service.get(actor, 7L).canManage());

        project.setLifecycleStatus(ProjectLifecycleState.ACTIVE);
        workspace.setArchivedAt(Instant.now());
        RetroResponse retro = service.get(actor, 7L);
        assertFalse(retro.canManage());
        assertFalse(retro.canContribute());
    }

    @Test
    void getAnswersRetroNotFoundForMissingAndInvisibleRetros() {
        when(retros.findById(7L)).thenReturn(Optional.empty());
        assertEquals("Retrospective not found.",
                assertThrows(ResourceNotFoundException.class, () -> service.get(actor, 7L)).getMessage());

        when(retros.findById(8L)).thenReturn(Optional.of(retro(8L)));
        when(access.require(10L, actor, ProjectPermission.VIEW_WORK))
                .thenThrow(new ResourceNotFoundException("Project not found."));
        assertEquals("Retrospective not found.",
                assertThrows(ResourceNotFoundException.class, () -> service.get(actor, 8L)).getMessage());
    }

    @Test
    void aWorkspaceAdminOutsideTheProjectGets403() {
        when(retros.findById(7L)).thenReturn(Optional.of(retro(7L)));
        when(access.require(10L, actor, ProjectPermission.VIEW_WORK)).thenThrow(new AccessDeniedException("no"));

        assertThrows(AccessDeniedException.class, () -> service.get(actor, 7L));
    }

    @Test
    void theBoardListsLiveCardsWithTheirVotesAndWhatTheCallerMayDo() {
        readableAs(ProjectRole.MEMBER);
        RetroItem mine = card(1L, actor, doc(text("Mine "), mention("user", 31, "Old name")));
        RetroItem theirs = card(2L, damir, doc(text("Theirs")));
        when(items.findByRetroIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(7L)).thenReturn(List.of(mine, theirs));
        when(votes.findByRetroId(7L)).thenReturn(List.of(new RetroVote(theirs, actor), new RetroVote(theirs, damir)));
        when(mentions.currentNames(any())).thenReturn(Map.of(RichTextMention.user(31L), "Damir Gabaidullin"));

        RetroResponse board = service.get(actor, 7L);

        assertEquals(List.of(1L, 2L), board.items().stream().map(RetroItemResponse::id).toList());
        RetroItemResponse first = board.items().get(0);
        assertEquals(List.of(new CommentResponse.MentionName("user", 31L, "Damir Gabaidullin")), first.mentions());
        assertEquals(0, first.voteCount());
        assertFalse(first.votedByMe());
        assertTrue(first.canEdit());
        assertTrue(first.canDelete());
        RetroItemResponse second = board.items().get(1);
        assertEquals("Damir Gabaidullin", second.author().name());
        assertEquals(2, second.voteCount());
        assertTrue(second.votedByMe());
        assertFalse(second.canEdit());
        assertFalse(second.canDelete());
        assertEquals(2, board.votesLeft());
    }

    @Test
    void aManagerMayDeleteEveryCardOnTheBoardButEditOnlyTheirOwn() {
        readableAs(ProjectRole.SCRUM_MASTER);
        when(items.findByRetroIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(7L))
                .thenReturn(List.of(card(2L, damir, doc(text("Theirs")))));

        RetroItemResponse card = service.get(actor, 7L).items().get(0);

        assertFalse(card.canEdit());
        assertTrue(card.canDelete());
    }

    // --- add a card (§3.3) ---

    /** The retro is free to lock and the caller passes the write checks with this role. */
    private Retrospective addableAs(ProjectRole role) {
        Retrospective retro = retro(7L);
        when(retros.lockById(7L)).thenReturn(Optional.of(retro));
        when(access.requireActive(10L, actor, ProjectPermission.RETRO_CONTRIBUTE)).thenReturn(accessAs(role));
        when(items.save(any(RetroItem.class))).thenAnswer(inv -> {
            RetroItem saved = inv.getArgument(0);
            saved.setId(512L);
            return saved;
        });
        return retro;
    }

    @Test
    void addLocksTheRetroThenSavesANormalizedCard() {
        Retrospective retro = addableAs(ProjectRole.MEMBER);
        when(mentions.currentNames(any())).thenReturn(Map.of(RichTextMention.user(31L), "Damir Gabaidullin"));
        String sent = "{\"type\":\"doc\",\"style\":\"x\",\"content\":[{\"type\":\"paragraph\",\"content\":["
                + text("Ask ") + "," + mention("user", 31, "Damir") + "]}]}";

        RetroItemResponse card = service.addItem(actor, 7L, new RetroItemRequest(RetroColumn.IDEAS, sent));

        ArgumentCaptor<RetroItem> saved = ArgumentCaptor.forClass(RetroItem.class);
        var order = inOrder(retros, access, mentions, items);
        order.verify(retros).lockById(7L);
        order.verify(access).requireActive(10L, actor, ProjectPermission.RETRO_CONTRIBUTE);
        order.verify(mentions).newlyMentionedUserIds(eq(project), any(RichTextDocument.class), isNull());
        order.verify(items).save(saved.capture());
        assertEquals(doc(text("Ask "), mention("user", 31, "Damir")), saved.getValue().getBody(), "stored normalized");
        assertSame(retro, saved.getValue().getRetro());
        assertSame(actor, saved.getValue().getAuthor());
        assertEquals(512L, card.id());
        assertEquals(RetroColumn.IDEAS, card.column());
        assertEquals(List.of(new CommentResponse.MentionName("user", 31L, "Damir Gabaidullin")), card.mentions());
        assertEquals(0, card.voteCount());
        assertNull(card.editedAt());
        assertTrue(card.canEdit());
        assertTrue(card.canDelete());
    }

    @Test
    void aCardIsNeverEmptyAndAtMost1000Characters() {
        addableAs(ProjectRole.MEMBER);

        assertThrows(InvalidRichTextException.class,
                () -> service.addItem(actor, 7L, new RetroItemRequest(RetroColumn.WENT_WELL, doc())));
        InvalidRichTextException tooLong = assertThrows(InvalidRichTextException.class,
                () -> service.addItem(actor, 7L, new RetroItemRequest(RetroColumn.WENT_WELL, doc(text("x".repeat(1001))))));
        assertEquals("A card can have at most 1000 characters.", tooLong.getMessage());
        verify(items, never()).save(any());

        service.addItem(actor, 7L, new RetroItemRequest(RetroColumn.WENT_WELL, doc(text("x".repeat(1000)))));
        verify(items).save(any(RetroItem.class));
    }

    @Test
    void addRefusesAClosedRetro() {
        addableAs(ProjectRole.SCRUM_MASTER).setStatus(RetroStatus.CLOSED);

        RetroConflictException e = assertThrows(RetroConflictException.class,
                () -> service.addItem(actor, 7L, new RetroItemRequest(RetroColumn.WENT_WELL, doc(text("Hi")))));

        assertEquals(ErrorCode.RETRO_CLOSED, e.getCode());
        verify(items, never()).save(any());
    }

    @Test
    void addAnswersRetroNotFoundForMissingAndInvisibleRetros() {
        RetroItemRequest request = new RetroItemRequest(RetroColumn.WENT_WELL, doc(text("Hi")));
        assertEquals("Retrospective not found.", assertThrows(ResourceNotFoundException.class,
                () -> service.addItem(actor, 7L, request)).getMessage());

        when(retros.lockById(8L)).thenReturn(Optional.of(retro(8L)));
        when(access.requireActive(10L, actor, ProjectPermission.RETRO_CONTRIBUTE))
                .thenThrow(new ResourceNotFoundException("Project not found."));
        assertEquals("Retrospective not found.", assertThrows(ResourceNotFoundException.class,
                () -> service.addItem(actor, 8L, request)).getMessage());
    }

    @Test
    void aViewerCannotAddCards() {
        when(retros.lockById(7L)).thenReturn(Optional.of(retro(7L)));
        when(access.requireActive(10L, actor, ProjectPermission.RETRO_CONTRIBUTE))
                .thenThrow(new AccessDeniedException("no"));

        assertThrows(AccessDeniedException.class,
                () -> service.addItem(actor, 7L, new RetroItemRequest(RetroColumn.WENT_WELL, doc(text("Hi")))));
        verify(items, never()).save(any());
    }

    // --- edit and delete a card (§3.4, §3.5) ---

    /** Card 512 of retro 7, written by {@code author}; the caller passes the write checks with this role. */
    private RetroItem editableCard(User author, ProjectRole role) {
        RetroItem card = card(512L, author, doc(text("Stand-ups run long")));
        when(items.findRetroIdById(512L)).thenReturn(Optional.of(7L));
        when(retros.lockById(7L)).thenReturn(Optional.of(card.getRetro()));
        when(items.findById(512L)).thenReturn(Optional.of(card));
        when(access.requireActive(10L, actor, ProjectPermission.RETRO_CONTRIBUTE)).thenReturn(accessAs(role));
        return card;
    }

    @Test
    void theAuthorEditsTheirCardAndOnlyNewMentionsAreChecked() {
        RetroItem card = editableCard(actor, ProjectRole.MEMBER);
        String before = card.getBody();
        when(votes.findByItemId(512L)).thenReturn(List.of(new RetroVote(card, damir)));
        String after = doc(text("Stand-ups run long "), mention("user", 31, "Damir"));

        RetroItemResponse response = service.editItem(actor, 512L, new CommentRequest(after));

        var order = inOrder(items, retros);
        order.verify(items).findRetroIdById(512L);
        order.verify(retros).lockById(7L);
        order.verify(items).findById(512L);
        ArgumentCaptor<RichTextDocument> previous = ArgumentCaptor.forClass(RichTextDocument.class);
        verify(mentions).newlyMentionedUserIds(eq(project), any(RichTextDocument.class), previous.capture());
        assertEquals(before, previous.getValue().json());
        assertEquals(after, card.getBody());
        assertNotNull(card.getEditedAt());
        assertEquals(card.getEditedAt(), response.editedAt());
        assertEquals(1, response.voteCount());
        assertFalse(response.votedByMe());
    }

    @Test
    void sendingTheSameTextAgainIsNotAnEdit() {
        RetroItem card = editableCard(actor, ProjectRole.MEMBER);

        service.editItem(actor, 512L, new CommentRequest(card.getBody()));

        assertNull(card.getEditedAt());
        verify(mentions, never()).newlyMentionedUserIds(any(), any(), any());
    }

    @Test
    void nobodyEditsSomeoneElsesCardNotEvenAManager() {
        RetroItem card = editableCard(damir, ProjectRole.SCRUM_MASTER);
        String before = card.getBody();

        assertThrows(AccessDeniedException.class,
                () -> service.editItem(actor, 512L, new CommentRequest(doc(text("Mine now")))));
        assertEquals(before, card.getBody());
    }

    @Test
    void theAuthorDeletesTheirCardAndItsVotesAreGivenBack() {
        RetroItem card = editableCard(actor, ProjectRole.MEMBER);

        service.deleteItem(actor, 512L);

        assertTrue(card.isDeleted());
        verify(votes).deleteByItemId(512L);
    }

    @Test
    void aManagerDeletesSomeoneElsesCard() {
        RetroItem card = editableCard(damir, ProjectRole.PRODUCT_OWNER);

        service.deleteItem(actor, 512L);

        assertTrue(card.isDeleted());
        verify(votes).deleteByItemId(512L);
    }

    @Test
    void aMemberCannotDeleteSomeoneElsesCard() {
        RetroItem card = editableCard(damir, ProjectRole.MEMBER);

        assertThrows(AccessDeniedException.class, () -> service.deleteItem(actor, 512L));
        assertFalse(card.isDeleted());
        verify(votes, never()).deleteByItemId(any());
    }

    @Test
    void aClosedRetroRefusesEditsAndDeletes() {
        RetroItem card = editableCard(actor, ProjectRole.SCRUM_MASTER);
        card.getRetro().setStatus(RetroStatus.CLOSED);

        assertEquals(ErrorCode.RETRO_CLOSED, assertThrows(RetroConflictException.class,
                () -> service.editItem(actor, 512L, new CommentRequest(doc(text("New"))))).getCode());
        assertEquals(ErrorCode.RETRO_CLOSED, assertThrows(RetroConflictException.class,
                () -> service.deleteItem(actor, 512L)).getCode());
        assertFalse(card.isDeleted());
        assertNull(card.getEditedAt());
    }

    @Test
    void deletedAndMissingCardsAreCardNotFound() {
        editableCard(actor, ProjectRole.MEMBER).setDeletedAt(Instant.now());

        assertEquals("Card not found.", assertThrows(ResourceNotFoundException.class,
                () -> service.editItem(actor, 512L, new CommentRequest(doc(text("New"))))).getMessage());
        assertEquals("Card not found.", assertThrows(ResourceNotFoundException.class,
                () -> service.deleteItem(actor, 512L)).getMessage());
        assertEquals("Card not found.", assertThrows(ResourceNotFoundException.class,
                () -> service.deleteItem(actor, 513L)).getMessage());
    }

    @Test
    void aCardInAProjectTheCallerCannotSeeIsCardNotFound() {
        editableCard(actor, ProjectRole.MEMBER);
        when(access.requireActive(10L, actor, ProjectPermission.RETRO_CONTRIBUTE))
                .thenThrow(new ResourceNotFoundException("Project not found."));

        assertEquals("Card not found.", assertThrows(ResourceNotFoundException.class,
                () -> service.deleteItem(actor, 512L)).getMessage());
    }

    // --- votes (§3.6, §3.7) ---

    @Test
    void aMemberVotesOnTheirOwnCardAfterTheRetroIsLocked() {
        RetroItem card = editableCard(actor, ProjectRole.MEMBER);
        when(votes.countByRetroIdAndUserId(7L, 9L)).thenReturn(1L);
        when(votes.countByItemId(512L)).thenReturn(3L);

        RetroVoteResponse response = service.vote(actor, 512L);

        var order = inOrder(retros, votes);
        order.verify(retros).lockById(7L);
        order.verify(votes).countByRetroIdAndUserId(7L, 9L);
        ArgumentCaptor<RetroVote> saved = ArgumentCaptor.forClass(RetroVote.class);
        order.verify(votes).save(saved.capture());
        assertSame(card, saved.getValue().getItem());
        assertSame(card.getRetro(), saved.getValue().getRetro());
        assertSame(actor, saved.getValue().getUser());
        assertEquals(new RetroVoteResponse(512L, 3, true, 1), response);
    }

    @Test
    void aSecondVoteOnTheSameCardIsAlreadyVoted() {
        editableCard(damir, ProjectRole.MEMBER);
        when(votes.existsByItemIdAndUserId(512L, 9L)).thenReturn(true);
        when(votes.countByRetroIdAndUserId(7L, 9L)).thenReturn(3L);

        RetroConflictException e = assertThrows(RetroConflictException.class, () -> service.vote(actor, 512L));

        assertEquals(ErrorCode.ALREADY_VOTED, e.getCode());
        verify(votes, never()).save(any());
    }

    @Test
    void aFourthVoteIsNoVotesLeft() {
        editableCard(damir, ProjectRole.MEMBER);
        when(votes.countByRetroIdAndUserId(7L, 9L)).thenReturn(3L);

        RetroConflictException e = assertThrows(RetroConflictException.class, () -> service.vote(actor, 512L));

        assertEquals(ErrorCode.NO_VOTES_LEFT, e.getCode());
        verify(votes, never()).save(any());
    }

    @Test
    void unvoteGivesTheVoteBack() {
        editableCard(damir, ProjectRole.MEMBER);
        when(votes.countByItemId(512L)).thenReturn(2L);

        RetroVoteResponse response = service.unvote(actor, 512L);

        verify(votes).deleteByItemIdAndUserId(512L, 9L);
        assertEquals(new RetroVoteResponse(512L, 2, false, 3), response);
    }

    @Test
    void aViewerCannotVote() {
        editableCard(damir, ProjectRole.VIEWER);
        when(access.requireActive(10L, actor, ProjectPermission.RETRO_CONTRIBUTE))
                .thenThrow(new AccessDeniedException("no"));

        assertThrows(AccessDeniedException.class, () -> service.vote(actor, 512L));
        assertThrows(AccessDeniedException.class, () -> service.unvote(actor, 512L));
        verify(votes, never()).save(any());
        verify(votes, never()).deleteByItemIdAndUserId(any(), any());
    }

    @Test
    void aClosedRetroRefusesVotesAndTheirRemoval() {
        editableCard(damir, ProjectRole.MEMBER).getRetro().setStatus(RetroStatus.CLOSED);

        assertEquals(ErrorCode.RETRO_CLOSED,
                assertThrows(RetroConflictException.class, () -> service.vote(actor, 512L)).getCode());
        assertEquals(ErrorCode.RETRO_CLOSED,
                assertThrows(RetroConflictException.class, () -> service.unvote(actor, 512L)).getCode());
        verify(votes, never()).save(any());
        verify(votes, never()).deleteByItemIdAndUserId(any(), any());
    }

    @Test
    void aDeletedCardCannotBeVotedOn() {
        editableCard(damir, ProjectRole.MEMBER).setDeletedAt(Instant.now());

        assertEquals("Card not found.",
                assertThrows(ResourceNotFoundException.class, () -> service.vote(actor, 512L)).getMessage());
        verify(votes, never()).save(any());
    }

    // --- actions (§3.8) ---

    /** Retro 7 is free to lock, the caller passes RETRO_MANAGE, and creating the task gives MOB-61. */
    private Retrospective actionableAs(ProjectRole role) {
        Retrospective retro = retro(7L);
        when(retros.lockById(7L)).thenReturn(Optional.of(retro));
        when(access.requireActive(10L, actor, ProjectPermission.RETRO_MANAGE)).thenReturn(accessAs(role));
        TaskResponse created = mock(TaskResponse.class);
        when(created.id()).thenReturn(260L);
        when(taskApi.create(eq(actor), eq(10L), any(CreateTaskRequest.class))).thenReturn(created);
        when(tasks.findById(260L)).thenReturn(Optional.of(task(260L, "MOB-61", column("To Do", BoardColumnCategory.TODO))));
        when(actions.save(any(RetroAction.class))).thenAnswer(inv -> inv.getArgument(0));
        return retro;
    }

    @Test
    void aManagerTurnsACardIntoABacklogTask() {
        Retrospective retro = actionableAs(ProjectRole.SCRUM_MASTER);
        RetroItem card = card(512L, damir, doc(text("Stand-ups run long")));
        when(items.findById(512L)).thenReturn(Optional.of(card));

        RetroActionResponse response = service.createAction(actor, 7L,
                new RetroActionRequest("Time-box stand-ups", 31L, 512L, null));

        ArgumentCaptor<CreateTaskRequest> request = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(taskApi).create(eq(actor), eq(10L), request.capture());
        assertEquals(new CreateTaskRequest("Time-box stand-ups", TaskType.TASK, null, 31L, null,
                List.of("retro-action"), null), request.getValue());
        ArgumentCaptor<RetroAction> saved = ArgumentCaptor.forClass(RetroAction.class);
        verify(actions).save(saved.capture());
        assertSame(retro, saved.getValue().getRetro());
        assertSame(card, saved.getValue().getItem());
        assertEquals(260L, saved.getValue().getTaskId());
        assertEquals("MOB-61", saved.getValue().getTaskKey());
        assertSame(actor, saved.getValue().getCreatedBy());
        assertEquals(512L, response.itemId());
        assertEquals(new RetroActionResponse.TaskRef(260L, "MOB-61", "Time-box stand-ups", "To Do",
                BoardColumnCategory.TODO, new RetroUser(31L, "Damir Gabaidullin"), null, null, false), response.task());
        verify(tasks, never()).findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(any());
    }

    @Test
    void aPlannedSprintGetsTheTaskAtItsEnd() {
        actionableAs(ProjectRole.PRODUCT_OWNER);
        Sprint planned = sprint(44L, project, 4, SprintStatus.PLANNED);
        when(sprints.findById(44L)).thenReturn(Optional.of(planned));
        Task first = task(249L, "MOB-49", column("To Do", BoardColumnCategory.TODO));
        first.setSprintPosition(1L);
        Task last = task(250L, "MOB-50", column("To Do", BoardColumnCategory.TODO));
        last.setSprintPosition(3L);
        when(tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(44L)).thenReturn(List.of(first, last));

        RetroActionResponse response = service.createAction(actor, 7L,
                new RetroActionRequest("Pair on reviews", 31L, null, 44L));

        Task created = tasks.findById(260L).orElseThrow();
        assertSame(planned, created.getSprint());
        assertEquals(4L, created.getSprintPosition());
        assertEquals(44L, response.task().sprintId());
        assertEquals("MOB Sprint 4", response.task().sprintName());
        assertNull(response.itemId());
    }

    @Test
    void theSprintMustBeAPlannedSprintOfThisProject() {
        actionableAs(ProjectRole.SCRUM_MASTER);
        when(sprints.findById(44L)).thenReturn(Optional.of(sprint(44L, project, 4, SprintStatus.ACTIVE)));
        Project other = Project.builder().id(11L).workspace(workspace).name("Web").key("WEB").build();
        when(sprints.findById(45L)).thenReturn(Optional.of(sprint(45L, other, 1, SprintStatus.PLANNED)));

        assertEquals("Actions can only go to the backlog or a planned sprint.", assertThrows(BadRequestException.class,
                () -> service.createAction(actor, 7L, new RetroActionRequest("x", 31L, null, 44L))).getMessage());
        assertEquals("Sprint not found.", assertThrows(ResourceNotFoundException.class,
                () -> service.createAction(actor, 7L, new RetroActionRequest("x", 31L, null, 45L))).getMessage());
        verify(actions, never()).save(any());
    }

    @Test
    void theCardMustBeALiveCardOfThisRetro() {
        actionableAs(ProjectRole.SCRUM_MASTER);
        RetroItem ofAnotherRetro = new RetroItem(retro(8L), RetroColumn.IDEAS, doc(text("x")), damir);
        ofAnotherRetro.setId(600L);
        when(items.findById(600L)).thenReturn(Optional.of(ofAnotherRetro));
        RetroItem deleted = card(601L, damir, doc(text("x")));
        deleted.setDeletedAt(Instant.now());
        when(items.findById(601L)).thenReturn(Optional.of(deleted));

        for (long itemId : new long[]{600L, 601L, 602L}) {
            assertEquals("Card not found.", assertThrows(ResourceNotFoundException.class,
                    () -> service.createAction(actor, 7L, new RetroActionRequest("x", 31L, itemId, null))).getMessage());
        }
        verify(taskApi, never()).create(any(), any(), any());
    }

    @Test
    void aClosedRetroCreatesNoTask() {
        actionableAs(ProjectRole.SCRUM_MASTER).setStatus(RetroStatus.CLOSED);

        RetroConflictException e = assertThrows(RetroConflictException.class,
                () -> service.createAction(actor, 7L, new RetroActionRequest("x", 31L, null, null)));

        assertEquals(ErrorCode.RETRO_CLOSED, e.getCode());
        verify(taskApi, never()).create(any(), any(), any());
    }

    @Test
    void aMemberCannotCreateActions() {
        when(retros.lockById(7L)).thenReturn(Optional.of(retro(7L)));
        when(access.requireActive(10L, actor, ProjectPermission.RETRO_MANAGE)).thenThrow(new AccessDeniedException("no"));

        assertThrows(AccessDeniedException.class,
                () -> service.createAction(actor, 7L, new RetroActionRequest("x", 31L, null, null)));
        verifyNoInteractions(taskApi);
    }

    @Test
    void theBoardShowsEachActionsLiveTaskAndADeletedTaskByItsKey() {
        readableAs(ProjectRole.MEMBER);
        Retrospective retro = retro(7L);
        RetroItem deletedCard = card(512L, damir, doc(text("x")));
        deletedCard.setDeletedAt(Instant.now());
        RetroAction live = new RetroAction(retro, deletedCard, 260L, "MOB-61", actor, Instant.parse("2026-11-26T10:30:00Z"));
        RetroAction gone = new RetroAction(retro, null, 261L, "MOB-62", actor, Instant.parse("2026-11-26T10:31:00Z"));
        when(actions.findByRetroIdOrderByCreatedAtAscIdAsc(7L)).thenReturn(List.of(live, gone));
        Task task = task(260L, "MOB-61", column("In Progress", BoardColumnCategory.IN_PROGRESS));
        task.setSprint(sprint(44L, project, 4, SprintStatus.PLANNED));
        when(tasks.findAllById(List.of(260L, 261L))).thenReturn(List.of(task));

        RetroResponse board = service.get(actor, 7L);

        RetroActionResponse first = board.actions().get(0);
        assertNull(first.itemId(), "its card was deleted later");
        assertEquals(Instant.parse("2026-11-26T10:30:00Z"), first.createdAt());
        assertEquals(new RetroActionResponse.TaskRef(260L, "MOB-61", "Time-box stand-ups", "In Progress",
                BoardColumnCategory.IN_PROGRESS, new RetroUser(31L, "Damir Gabaidullin"), 44L, "MOB Sprint 4", false),
                first.task());
        assertEquals(new RetroActionResponse.TaskRef(261L, "MOB-62", null, null, null, null, null, null, true),
                board.actions().get(1).task());
        assertNull(board.previousActions());
    }

    @Test
    void previousActionsAreTheUnfinishedOnesOfTheLatestEarlierRetro() {
        readableAs(ProjectRole.MEMBER);
        Retrospective earlier = givenEarlierRetroWithActionsOn(250L, 251L, 252L);
        when(tasks.findAllById(List.of(250L, 251L, 252L))).thenReturn(List.of(
                task(250L, "MOB-50", column("To Do", BoardColumnCategory.TODO)),
                task(251L, "MOB-51", column("Done", BoardColumnCategory.DONE))));   // MOB-52 was deleted

        RetroResponse.PreviousActions previous = service.get(actor, 7L).previousActions();

        assertEquals(earlier.getId(), previous.retroId());
        assertEquals("MOB Sprint 2", previous.sprintName());
        assertEquals(List.of("MOB-50"), previous.actions().stream().map(action -> action.task().key()).toList());
    }

    @Test
    void previousActionsIsNullWhenEverythingThereIsDone() {
        readableAs(ProjectRole.MEMBER);
        givenEarlierRetroWithActionsOn(251L);
        when(tasks.findAllById(List.of(251L)))
                .thenReturn(List.of(task(251L, "MOB-51", column("Done", BoardColumnCategory.DONE))));

        assertNull(service.get(actor, 7L).previousActions());
    }

    /** Retro 6 of sprint 2 is the latest before retro 7 (sprint 3), with one action per task id. */
    private Retrospective givenEarlierRetroWithActionsOn(Long... taskIds) {
        Retrospective earlier = new Retrospective(project, sprint(41L, project, 2, SprintStatus.COMPLETED),
                "MOB Sprint 2 Retrospective", actor);
        earlier.setId(6L);
        when(retros.findFirstByProjectIdAndSprintNumberLessThanOrderBySprintNumberDesc(10L, 3))
                .thenReturn(Optional.of(earlier));
        when(actions.findByRetroIdOrderByCreatedAtAscIdAsc(6L)).thenReturn(java.util.Arrays.stream(taskIds)
                .map(id -> new RetroAction(earlier, null, id, "MOB-" + (id - 200), actor, Instant.now()))
                .toList());
        return earlier;
    }

    // --- close (§3.9) ---

    @Test
    void aManagerClosesTheRetroWithAnAuditEntryAndThenTheEvent() {
        Retrospective retro = retro(7L);
        when(retros.lockById(7L)).thenReturn(Optional.of(retro));
        when(access.requireActive(10L, actor, ProjectPermission.RETRO_MANAGE))
                .thenReturn(accessAs(ProjectRole.PRODUCT_OWNER));

        RetroResponse closed = service.close(actor, 7L);

        assertEquals(RetroStatus.CLOSED, retro.getStatus());
        assertNotNull(retro.getClosedAt());
        assertSame(actor, retro.getClosedByUser());
        var order = inOrder(retros, access, audit, events);
        order.verify(retros).lockById(7L);
        order.verify(access).requireActive(10L, actor, ProjectPermission.RETRO_MANAGE);
        order.verify(audit).record(same(workspace), same(actor), eq(AuditAction.RETRO_CLOSED),
                eq(AuditTarget.sprint(sprint)), same(project), eq(AuditChange.of("status", "OPEN", "CLOSED")));
        order.verify(events).publishEvent(new RetroClosed(project, actor, sprint));
        assertEquals(RetroStatus.CLOSED, closed.status());
        assertEquals(9L, closed.closedBy().id());
        assertEquals(retro.getClosedAt(), closed.closedAt());
        assertFalse(closed.canManage());
        assertFalse(closed.canContribute());
    }

    @Test
    void closingTwiceIsRetroClosedAndWritesNothing() {
        Retrospective retro = retro(7L);
        retro.setStatus(RetroStatus.CLOSED);
        when(retros.lockById(7L)).thenReturn(Optional.of(retro));
        when(access.requireActive(10L, actor, ProjectPermission.RETRO_MANAGE))
                .thenReturn(accessAs(ProjectRole.SCRUM_MASTER));

        RetroConflictException e = assertThrows(RetroConflictException.class, () -> service.close(actor, 7L));

        assertEquals(ErrorCode.RETRO_CLOSED, e.getCode());
        assertNull(retro.getClosedByUser());
        verifyNoInteractions(audit, events);
    }

    @Test
    void refusedClosesWriteNothing() {
        Retrospective retro = retro(7L);
        when(retros.lockById(7L)).thenReturn(Optional.of(retro));
        when(access.requireActive(10L, actor, ProjectPermission.RETRO_MANAGE))
                .thenThrow(new AccessDeniedException("no"))
                .thenThrow(new ResourceNotFoundException("Project not found."))
                .thenReturn(accessAs(ProjectRole.SCRUM_MASTER));

        assertThrows(AccessDeniedException.class, () -> service.close(actor, 7L));
        assertEquals("Retrospective not found.",
                assertThrows(ResourceNotFoundException.class, () -> service.close(actor, 7L)).getMessage());
        project.setLifecycleStatus(ProjectLifecycleState.COMPLETED);
        assertEquals(ErrorCode.CONFLICT,
                assertThrows(RetroConflictException.class, () -> service.close(actor, 7L)).getCode());

        assertEquals(RetroStatus.OPEN, retro.getStatus());
        verifyNoInteractions(audit, events);
    }

    // --- fixtures ---

    private Sprint sprint(long id, Project owner, int number, SprintStatus status) {
        Sprint result = new Sprint(owner, number, "MOB Sprint " + number, null, null,
                LocalDate.of(2026, 11, 23), LocalDate.of(2026, 12, 6));
        result.setId(id);
        result.setStatus(status);
        return result;
    }

    private Task task(long id, String key, ProjectBoardColumn status) {
        return Task.builder().id(id).project(project).key(key).title("Time-box stand-ups")
                .status(status).assignee(damir).build();
    }

    private static ProjectBoardColumn column(String name, BoardColumnCategory category) {
        ProjectBoardColumn column = new ProjectBoardColumn(null, name, 0);
        column.setCategory(category);
        return column;
    }

    /** A card of retro 7. */
    private RetroItem card(long id, User author, String body) {
        RetroItem card = new RetroItem(retro(7L), RetroColumn.TO_IMPROVE, body, author);
        card.setId(id);
        return card;
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
