package team.sprintory_backend.retro.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import team.sprintory_backend.comment.dto.CommentRequest;
import team.sprintory_backend.comment.dto.CommentResponse;
import team.sprintory_backend.common.richtext.InvalidRichTextException;
import team.sprintory_backend.common.richtext.RichTextDocument;
import team.sprintory_backend.common.richtext.RichTextMention;
import team.sprintory_backend.common.richtext.RichTextParser;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.mention.services.MentionService;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectLifecycleState;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.retro.dto.RetroItemRequest;
import team.sprintory_backend.retro.dto.RetroItemResponse;
import team.sprintory_backend.retro.dto.RetroResponse;
import team.sprintory_backend.retro.exceptions.RetroConflictException;
import team.sprintory_backend.retro.models.RetroColumn;
import team.sprintory_backend.retro.models.RetroItem;
import team.sprintory_backend.retro.models.RetroStatus;
import team.sprintory_backend.retro.models.RetroVote;
import team.sprintory_backend.retro.models.Retrospective;
import team.sprintory_backend.retro.repositories.RetroItemRepository;
import team.sprintory_backend.retro.repositories.RetroVoteRepository;
import team.sprintory_backend.retro.repositories.RetrospectiveRepository;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.sprint.models.SprintStatus;
import team.sprintory_backend.sprint.repositories.SprintRepository;
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
import static org.mockito.Mockito.*;

/** Opening and reading a retro, and its cards (docs/retrospectives-docs.md §3.1–§3.5). */
class RetroServiceTest {

    private RetrospectiveRepository retros;
    private RetroItemRepository items;
    private RetroVoteRepository votes;
    private SprintRepository sprints;
    private ProjectRepository projects;
    private ProjectAccess access;
    private MentionService mentions;
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
        sprints = mock(SprintRepository.class);
        projects = mock(ProjectRepository.class);
        access = mock(ProjectAccess.class);
        mentions = mock(MentionService.class);
        service = new RetroService(retros, items, votes, sprints, projects, access,
                new RichTextParser(JsonMapper.builder().build()), mentions);

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

    // --- fixtures ---

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
