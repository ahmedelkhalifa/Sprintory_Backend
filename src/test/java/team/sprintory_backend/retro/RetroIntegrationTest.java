package team.sprintory_backend.retro;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.audit.models.AuditAction;
import team.sprintory_backend.audit.models.AuditChange;
import team.sprintory_backend.audit.models.AuditLogEntry;
import team.sprintory_backend.audit.models.AuditTargetType;
import team.sprintory_backend.audit.repositories.AuditLogRepository;
import team.sprintory_backend.comment.dto.CommentRequest;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ErrorCode;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.notification.dto.NotificationResponse;
import team.sprintory_backend.notification.models.NotificationType;
import team.sprintory_backend.notification.services.NotificationService;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.project.models.ProjectBoardColumn;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectBoardService;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.retro.dto.RetroActionRequest;
import team.sprintory_backend.retro.dto.RetroActionResponse;
import team.sprintory_backend.retro.dto.RetroItemRequest;
import team.sprintory_backend.retro.dto.RetroItemResponse;
import team.sprintory_backend.retro.dto.RetroResponse;
import team.sprintory_backend.retro.dto.RetroVoteResponse;
import team.sprintory_backend.retro.exceptions.RetroConflictException;
import team.sprintory_backend.retro.models.RetroColumn;
import team.sprintory_backend.retro.models.RetroStatus;
import team.sprintory_backend.retro.models.Retrospective;
import team.sprintory_backend.retro.repositories.RetrospectiveRepository;
import team.sprintory_backend.retro.services.RetroService;
import team.sprintory_backend.sprint.dto.CompleteSprintRequest;
import team.sprintory_backend.sprint.dto.MoveSprintTaskRequest;
import team.sprintory_backend.sprint.dto.SprintRequest;
import team.sprintory_backend.sprint.dto.SprintResponse;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.sprint.models.SprintGoalAchieved;
import team.sprintory_backend.sprint.repositories.SprintRepository;
import team.sprintory_backend.sprint.services.SprintService;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.models.TaskPriority;
import team.sprintory_backend.task.models.TaskType;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.services.TaskApiService;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint retrospectives (PMP-44) on real PostgreSQL + Flyway (V18): the queries, constraints,
 * locks and cascades the mocked tests cannot reach. Each method rolls back its data; refused calls
 * come last in a method, because a refused call may leave half-written rows until the rollback.
 */
@Transactional
class RetroIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EntityManager em;
    @Autowired private RetroService retros;
    @Autowired private RetrospectiveRepository retroRepository;
    @Autowired private SprintService sprints;
    @Autowired private SprintRepository sprintRepository;
    @Autowired private ProjectService projects;
    @Autowired private ProjectBoardService boards;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository members;
    @Autowired private TaskApiService tasks;
    @Autowired private TaskRepository taskRepository;
    @Autowired private NotificationService notifications;
    @Autowired private AuditLogRepository auditLog;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository workspaceMembers;

    private User pm, sm, dev, viewer, outsider;
    private Long scrumId;

    @BeforeEach
    void setUp() {
        pm = user("Parker", "Manager", "pmp44-pm@test.invalid");
        sm = user("Sam", "Master", "pmp44-sm@test.invalid");
        dev = user("Dana", "Developer", "pmp44-dev@test.invalid");
        viewer = user("Val", "Viewer", "pmp44-viewer@test.invalid");
        outsider = user("Olga", "Other", "pmp44-other@test.invalid");
        Workspace ws = workspaces.save(Workspace.builder().name("Retros").slug("retros-pmp44").build());
        for (User u : List.of(pm, sm, dev, viewer, outsider)) {
            workspaceMembers.save(WorkspaceMember.builder().workspace(ws).user(u).role(Role.WORKSPACE_MEMBER).build());
        }
        scrumId = projects.create(pm, ws.getId(), new CreateProjectRequest("Retro app", "RET", ProjectTemplate.SCRUM, null)).id();
        add(sm, ProjectRole.SCRUM_MASTER);
        add(dev, ProjectRole.MEMBER);
        add(viewer, ProjectRole.VIEWER);
        em.flush();
    }

    @Test
    void openingIsIdempotentAndTheSprintShowsItsRetro() {
        Long sprintId = endedSprint("Ship login");

        // Ending the sprint reminds the other retro managers only.
        assertEquals(1, count(pm, NotificationType.RETRO_REMINDER, sprintId));
        assertEquals(0, count(sm, NotificationType.RETRO_REMINDER, sprintId), "the person who ended it");
        assertEquals(0, count(dev, NotificationType.RETRO_REMINDER, sprintId));

        RetroService.Opened first = retros.open(sm, sprintId);
        RetroService.Opened again = retros.open(pm, sprintId);
        assertTrue(first.created());
        assertFalse(again.created());
        assertEquals(first.retro().id(), again.retro().id());
        assertEquals("RET Sprint 1 Retrospective", first.retro().title());
        assertEquals(1, count(dev, NotificationType.RETRO_OPENED, sprintId), "only the first open tells anyone");
        assertEquals(0, count(sm, NotificationType.RETRO_OPENED, sprintId));
        em.flush();
        em.clear();

        SprintResponse sprint = sprints.get(viewer, sprintId);
        assertTrue(sprint.hasRetro());
        assertEquals(first.retro().id(), sprint.retroId());
        SprintResponse inHistory = sprints.history(viewer, scrumId).stream()
                .filter(s -> s.id().equals(sprintId)).findFirst().orElseThrow();
        assertEquals(first.retro().id(), inHistory.retroId());

        // The database allows one retro per sprint, whatever the service does.
        Sprint ended = sprintRepository.findById(sprintId).orElseThrow();
        assertThrows(DataIntegrityViolationException.class, () -> retroRepository.saveAndFlush(
                new Retrospective(ended.getProject(), ended, "Duplicate", pm)));
    }

    @Test
    void cardsAndVotesFollowTheirLimits() {
        Long sprintId = endedSprint("Goal");
        Long retroId = retros.open(sm, sprintId).retro().id();
        RetroItemResponse a = retros.addItem(dev, retroId, card(RetroColumn.WENT_WELL, text("Pairing worked")));
        RetroItemResponse b = retros.addItem(dev, retroId, card(RetroColumn.TO_IMPROVE,
                text("Ask ") + "," + mention(pm)));
        RetroItemResponse c = retros.addItem(sm, retroId, card(RetroColumn.IDEAS, text("Shorter stand-ups")));
        RetroItemResponse d = retros.addItem(sm, retroId, card(RetroColumn.IDEAS, text("Demo on Fridays")));
        RetroItemResponse e = retros.addItem(sm, retroId, card(RetroColumn.IDEAS, text("Team lunch")));

        NotificationResponse mentioned = notificationsOf(pm).stream()
                .filter(n -> n.type() == NotificationType.MENTIONED).findFirst().orElseThrow();
        assertEquals(sprintId, mentioned.sprint().id());
        assertEquals("Ask @Parker", mentioned.excerpt());

        assertNull(retros.editItem(dev, a.id(), new CommentRequest(a.body())).editedAt(), "the same text is no edit");
        assertNotNull(retros.editItem(dev, a.id(), new CommentRequest(doc(text("Pairing worked well")))).editedAt());

        retros.vote(dev, a.id());
        retros.vote(dev, c.id());
        RetroVoteResponse third = retros.vote(dev, d.id());
        assertEquals(new RetroVoteResponse(d.id(), 1, true, 0), third);
        retros.vote(sm, c.id());
        retros.deleteItem(sm, c.id());   // gives Dana's and Sam's votes back
        em.flush();
        em.clear();

        RetroResponse board = retros.get(dev, retroId);
        assertEquals(List.of(a.id(), b.id(), d.id(), e.id()), board.items().stream().map(RetroItemResponse::id).toList());
        assertEquals(1, board.votesLeft());
        RetroItemResponse first = board.items().get(0);
        assertEquals(1, first.voteCount());
        assertTrue(first.votedByMe());
        assertTrue(first.canEdit());
        assertFalse(board.items().get(3).canDelete(), "a Member deletes only their own cards");
        assertFalse(retros.unvote(dev, b.id()).votedByMe(), "taking back a vote you do not have is fine");
        assertEquals(0, retros.vote(dev, b.id()).votesLeft());

        assertEquals(ErrorCode.ALREADY_VOTED,
                assertThrows(RetroConflictException.class, () -> retros.vote(dev, a.id())).getCode());
        assertEquals(ErrorCode.NO_VOTES_LEFT,
                assertThrows(RetroConflictException.class, () -> retros.vote(dev, e.id())).getCode());
        assertThrows(AccessDeniedException.class,
                () -> retros.addItem(viewer, retroId, card(RetroColumn.IDEAS, text("Viewers only read"))));
        assertThrows(AccessDeniedException.class,
                () -> retros.editItem(sm, a.id(), new CommentRequest(doc(text("Not mine")))));
        assertEquals("Card not found.",
                assertThrows(ResourceNotFoundException.class, () -> retros.vote(dev, c.id())).getMessage());
    }

    @Test
    void actionsAreRealTasksAndTheUnfinishedOnesShowInTheNextRetro() {
        Long firstSprintId = endedSprint("First");
        SprintResponse planned = sprints.create(sm, scrumId, new SprintRequest(null, "Second", null, null, null));
        TaskResponse alreadyPlanned = createTask("Planned work", 3);
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(alreadyPlanned.id(), planned.id(), null));
        Long retroId = retros.open(sm, firstSprintId).retro().id();
        RetroItemResponse card = retros.addItem(dev, retroId, card(RetroColumn.TO_IMPROVE, text("Stand-ups run long")));

        RetroActionResponse inSprint = retros.createAction(sm, retroId,
                new RetroActionRequest("Time-box stand-ups", dev.getId(), card.id(), planned.id()));
        RetroActionResponse done = retros.createAction(pm, retroId,
                new RetroActionRequest("Update the definition of done", dev.getId(), null, null));
        RetroActionResponse dropped = retros.createAction(pm, retroId,
                new RetroActionRequest("Buy a timer", sm.getId(), null, null));

        TaskResponse task = tasks.get(pm, scrumId, inSprint.task().id());
        assertEquals(TaskType.TASK, task.type());
        assertEquals(List.of("retro-action"), task.labels());
        assertEquals(dev.getId(), task.assigneeId());
        assertEquals(planned.id(), task.sprintId());
        assertEquals(List.of(alreadyPlanned.id(), task.id()), sprints.get(viewer, planned.id()).taskIds(),
                "the action goes last in the sprint");
        assertEquals(card.id(), inSprint.itemId());
        assertNull(tasks.get(pm, scrumId, done.task().id()).sprintId(), "no sprint: the backlog");
        assertTrue(notificationsOf(dev).stream().anyMatch(n -> n.type() == NotificationType.TASK_ASSIGNED
                && n.task().id().equals(task.id())), "the owner is told as for any assignment");

        var doneColumn = boards.get(pm, scrumId).columns().stream()
                .filter(c -> c.category() == BoardColumnCategory.DONE).findFirst().orElseThrow();
        taskRepository.findById(done.task().id()).orElseThrow()
                .setStatus(em.getReference(ProjectBoardColumn.class, doneColumn.id()));
        tasks.delete(pm, scrumId, dropped.task().id());
        em.flush();
        em.clear();

        List<RetroActionResponse> actions = retros.get(viewer, retroId).actions();
        assertEquals(List.of(inSprint.id(), done.id(), dropped.id()), actions.stream().map(RetroActionResponse::id).toList());
        assertEquals(BoardColumnCategory.DONE, actions.get(1).task().statusCategory());
        RetroActionResponse.TaskRef deleted = actions.get(2).task();
        assertTrue(deleted.deleted());
        assertEquals(dropped.task().key(), deleted.key());
        assertNull(deleted.title());

        // The next sprint ends; its retro follows up only the unfinished action.
        sprints.start(sm, planned.id(), null);
        sprints.complete(sm, planned.id(), closing());
        Long nextRetroId = retros.open(sm, planned.id()).retro().id();
        RetroResponse.PreviousActions previous = retros.get(viewer, nextRetroId).previousActions();
        assertEquals(retroId, previous.retroId());
        assertEquals("RET Sprint 1", previous.sprintName());
        assertEquals(List.of(inSprint.id()), previous.actions().stream().map(RetroActionResponse::id).toList());

        assertThrows(BadRequestException.class, () -> retros.createAction(sm, nextRetroId,
                new RetroActionRequest("Into an ended sprint", dev.getId(), null, firstSprintId)));
        assertThrows(BadRequestException.class, () -> retros.createAction(sm, nextRetroId,
                new RetroActionRequest("Owned by an outsider", outsider.getId(), null, null)));
        assertThrows(AccessDeniedException.class, () -> retros.createAction(dev, nextRetroId,
                new RetroActionRequest("Members cannot", dev.getId(), null, null)));
    }

    @Test
    void closingIsAuditedToldAndFreezesTheRetro() {
        Long sprintId = endedSprint("Goal");
        Long retroId = retros.open(sm, sprintId).retro().id();
        RetroItemResponse card = retros.addItem(dev, retroId, card(RetroColumn.WENT_WELL, text("Good sprint")));

        RetroResponse closed = retros.close(pm, retroId);
        assertEquals(RetroStatus.CLOSED, closed.status());
        assertEquals(pm.getId(), closed.closedBy().id());
        assertFalse(closed.canManage());
        assertFalse(closed.canContribute());
        em.flush();
        em.clear();

        List<AuditLogEntry> entries = auditLog.findAll().stream()
                .filter(entry -> entry.getAction() == AuditAction.RETRO_CLOSED && scrumId.equals(entry.getProjectId()))
                .toList();
        assertEquals(1, entries.size());
        AuditLogEntry entry = entries.get(0);
        assertEquals(AuditTargetType.SPRINT, entry.getTargetType());
        assertEquals(sprintId, entry.getTargetId());
        assertEquals("RET Sprint 1", entry.getTargetName());
        assertEquals(pm.getId(), entry.getActorId());
        assertEquals(List.of(AuditChange.of("status", "OPEN", "CLOSED")), entry.getChanges());
        assertEquals(1, count(dev, NotificationType.RETRO_CLOSED, sprintId));
        assertEquals(0, count(pm, NotificationType.RETRO_CLOSED, sprintId), "the closer");
        assertEquals(RetroStatus.CLOSED, retros.open(sm, sprintId).retro().status(), "opening again returns it");

        assertEquals(ErrorCode.RETRO_CLOSED, assertThrows(RetroConflictException.class,
                () -> retros.addItem(dev, retroId, card(RetroColumn.IDEAS, text("Too late")))).getCode());
        assertEquals(ErrorCode.RETRO_CLOSED, assertThrows(RetroConflictException.class,
                () -> retros.vote(dev, card.id())).getCode());
        assertEquals(ErrorCode.RETRO_CLOSED, assertThrows(RetroConflictException.class,
                () -> retros.createAction(sm, retroId, new RetroActionRequest("Late", dev.getId(), null, null))).getCode());
        assertEquals(ErrorCode.RETRO_CLOSED, assertThrows(RetroConflictException.class,
                () -> retros.close(sm, retroId)).getCode());
    }

    // --- fixtures ---

    /** A sprint with one task, started and closed by the Scrum Master; returns its id. */
    private Long endedSprint(String goal) {
        SprintResponse sprint = sprints.create(sm, scrumId, new SprintRequest(null, goal, null, null, null));
        TaskResponse item = createTask("Work for " + goal, 2);
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(item.id(), sprint.id(), null));
        sprints.start(sm, sprint.id(), null);
        sprints.complete(sm, sprint.id(), closing());
        return sprint.id();
    }

    /** Answers the goal question and confirms closing before the end date. */
    private static CompleteSprintRequest closing() {
        return new CompleteSprintRequest(null, false, SprintGoalAchieved.YES, null, true);
    }

    private TaskResponse createTask(String title, Integer estimate) {
        return tasks.create(pm, scrumId, new CreateTaskRequest(title, TaskType.STORY, TaskPriority.MEDIUM,
                null, estimate, List.of(), null));
    }

    private List<NotificationResponse> notificationsOf(User user) {
        return notifications.list(user, false, null, PageRequest.of(0, 100)).content();
    }

    private long count(User user, NotificationType type, Long sprintId) {
        return notificationsOf(user).stream()
                .filter(n -> n.type() == type && n.sprint() != null && n.sprint().id().equals(sprintId))
                .count();
    }

    private static RetroItemRequest card(RetroColumn column, String inline) {
        return new RetroItemRequest(column, doc(inline));
    }

    private static String doc(String inline) {
        return "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[" + inline + "]}]}";
    }

    private static String text(String text) {
        return "{\"type\":\"text\",\"text\":\"" + text + "\"}";
    }

    private static String mention(User user) {
        return "{\"type\":\"mention\",\"attrs\":{\"kind\":\"user\",\"id\":\"" + user.getId() + "\",\"label\":\""
                + user.getFirstName() + "\"}}";
    }

    private User user(String first, String last, String email) {
        return users.save(User.builder().firstName(first).lastName(last).email(email).enabled(true).build());
    }

    private void add(User user, ProjectRole role) {
        members.save(ProjectMember.builder().project(projectRepository.getReferenceById(scrumId))
                .user(user).roles(new HashSet<>(Set.of(role))).build());
    }
}
