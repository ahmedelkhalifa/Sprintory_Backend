package team.sprintory_backend.sprint;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.models.*;
import team.sprintory_backend.project.repositories.*;
import team.sprintory_backend.project.services.ProjectBoardService;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.sprint.dto.*;
import team.sprintory_backend.sprint.exceptions.*;
import team.sprintory_backend.sprint.models.*;
import team.sprintory_backend.sprint.repositories.*;
import team.sprintory_backend.sprint.services.SprintService;
import team.sprintory_backend.task.dto.*;
import team.sprintory_backend.task.models.*;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.services.TaskApiService;
import team.sprintory_backend.task.services.SubtaskService;
import team.sprintory_backend.user.models.*;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.*;
import team.sprintory_backend.workspace.repositories.*;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL + Flyway (V15, V16); each method rolls back its data after execution. */
@Transactional
class SprintPlanningIntegrationTest extends AbstractIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private SprintService sprints;
    @Autowired private SprintRepository sprintRepository;
    @Autowired private SprintCommitmentRepository commitments;
    @Autowired private SprintOutcomeRepository outcomes;
    @Autowired private ProjectService projects;
    @Autowired private ProjectBoardService boards;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository members;
    @Autowired private TaskApiService tasks;
    @Autowired private SubtaskService subtasks;
    @Autowired private TaskRepository taskRepository;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository workspaceMembers;

    private User sm, pm, viewer, outsider;
    private Long scrumId, kanbanId;
    private Workspace ws;

    @BeforeEach void setup() {
        pm = user("Parker", "Manager", "pmp119-pm@test.invalid");
        sm = user("Sam", "Master", "pmp119-sm@test.invalid");
        viewer = user("Val", "Viewer", "pmp119-viewer@test.invalid");
        outsider = user("Olga", "Other", "pmp119-other@test.invalid");
        ws = workspaces.save(Workspace.builder().name("Sprint planning").slug("sprint-pmp119").build());
        for (User u : List.of(pm, sm, viewer, outsider)) {
            workspaceMembers.save(WorkspaceMember.builder().workspace(ws).user(u).role(Role.WORKSPACE_MEMBER).build());
        }
        scrumId = projects.create(pm, ws.getId(), new CreateProjectRequest("Scrum", "SPR", ProjectTemplate.SCRUM, null)).id();
        kanbanId = projects.create(pm, ws.getId(), new CreateProjectRequest("Kanban", "KAN", ProjectTemplate.KANBAN, null)).id();
        add(sm, ProjectRole.SCRUM_MASTER);
        add(viewer, ProjectRole.VIEWER);
        em.flush();
    }

    @Test void defaultsAndPermissionsAndKanbanRestriction() {
        SprintResponse sprint = create("Aim");
        assertEquals("SPR Sprint 1", sprint.name());
        assertEquals(SprintStatus.PLANNED, sprint.status());
        assertEquals(13, java.time.temporal.ChronoUnit.DAYS.between(sprint.startDate(), sprint.endDate()));
        assertEquals(1, sprints.list(viewer, scrumId).size());
        assertThrows(AccessDeniedException.class, () -> sprints.create(pm, scrumId, req("Blocked")));
        assertThrows(AccessDeniedException.class, () -> sprints.create(viewer, scrumId, req("Blocked")));
        assertThrows(ResourceNotFoundException.class, () -> sprints.list(outsider, scrumId));
        // A project manager lacks MANAGE_SPRINTS, but the Scrum Master does have it.
        assertThrows(BadRequestException.class, () -> sprints.list(pm, kanbanId));
    }

    @Test void projectSprintLengthCanBeConfiguredForNewSprintsByManagerOnly() {
        assertEquals(14, sprints.projectSprintLength(viewer, scrumId));
        assertThrows(AccessDeniedException.class, () -> sprints.setProjectSprintLength(sm, scrumId,
                new SprintLengthRequest(21)));
        assertEquals(21, sprints.setProjectSprintLength(pm, scrumId, new SprintLengthRequest(21)));
        SprintResponse sprint = create("Goal");
        assertEquals(20, java.time.temporal.ChronoUnit.DAYS.between(sprint.startDate(), sprint.endDate()));
    }

    @Test void overlappingDatesAreRefusedAndDefaultsAdvanceSequentially() {
        SprintResponse a = create("A");
        assertThrows(BadRequestException.class, () -> sprints.create(sm, scrumId,
                new SprintRequest(null, "B", null, a.startDate(), a.endDate())));
        SprintResponse b = create("B");
        assertTrue(b.startDate().isAfter(a.endDate()));
        assertThrows(BadRequestException.class, () -> sprints.update(sm, b.id(),
                new SprintRequest(null, null, null, a.startDate(), a.endDate())));
    }

    @Test void movingTasksPreservesBacklogRankAndSubtasksInheritSprint() {
        TaskResponse a = createTask("A", 10);
        TaskResponse b = createTask("B", null);
        long oldRank = taskRepository.findById(a.id()).orElseThrow().getBacklogRank();
        SprintResponse sprint = create("Aim");
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(a.id(), sprint.id(), null));
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(b.id(), sprint.id(), a.id()));
        assertEquals(List.of(a.id(), b.id()), sprints.get(viewer, sprint.id()).taskIds());
        assertEquals(List.of(b.id()), sprints.get(viewer, sprint.id()).unestimatedTaskIds());
        assertEquals(sprint.id(), tasks.get(pm, scrumId, a.id()).sprintId());
        TaskResponse child = subtasks.create(pm, scrumId, a.id(),
                new CreateSubtaskRequest("Child", null, null, 60));
        assertEquals(sprint.id(), child.sprintId());
        assertEquals(sprint.id(), subtasks.list(viewer, scrumId, a.id()).get(0).sprintId());
        assertThrows(BadRequestException.class, () -> sprints.move(sm, scrumId,
                new MoveSprintTaskRequest(child.id(), sprint.id(), null)));
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(a.id(), null, null));
        assertNull(tasks.get(pm, scrumId, a.id()).sprintId());
        assertNull(subtasks.list(viewer, scrumId, a.id()).get(0).sprintId());
        assertEquals(oldRank, taskRepository.findById(a.id()).orElseThrow().getBacklogRank());
        assertEquals(List.of(b.id()), sprints.get(viewer, sprint.id()).taskIds());
    }

    @Test void goalAndItemsRequiredActiveOnlyOneAndCommitmentFrozen() {
        SprintResponse a = create("");
        SprintResponse b = create("Another");
        assertThrows(BadRequestException.class, () -> sprints.start(sm, a.id(), new StartSprintRequest(null, null, null)));
        sprints.update(sm, a.id(), new SprintRequest(null, "Aim", null, null, null));
        assertThrows(BadRequestException.class, () -> sprints.start(sm, a.id(), new StartSprintRequest(null, null, null)));
        TaskResponse item = createTask("Work", 30);
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(item.id(), a.id(), null));
        SprintResponse running = sprints.start(sm, a.id(), new StartSprintRequest(null, null, null));
        assertEquals(SprintStatus.ACTIVE, running.status());
        assertEquals(30, running.committedPoints());
        assertEquals(a.id(), boards.get(pm, scrumId).activeSprintId());
        assertEquals(List.of(item.id()), sprints.committedItems(sm, a.id()).stream()
                .map(SprintCommitmentResponse::taskId).toList());
        assertThrows(SprintAlreadyActiveException.class, () -> sprints.start(sm, b.id(),
                new StartSprintRequest(null, null, null)));
        TaskResponse extra = createTask("Extra", 5);
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(extra.id(), a.id(), item.id()));
        assertEquals(30, sprints.get(viewer, a.id()).committedPoints());
        assertEquals(SprintScopeChange.Action.ADDED,
                sprints.scopeHistory(viewer, a.id(), PageRequest.of(0, 10)).content().get(0).action());
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(extra.id(), null, null));
        assertEquals(SprintScopeChange.Action.REMOVED,
                sprints.scopeHistory(viewer, a.id(), PageRequest.of(0, 10)).content().get(0).action());
    }

    @Test void softCapacityWarningDoesNotPreventSavingAndFirstSprintHasNoSuggestion() {
        TaskResponse a = createTask("A", 33);
        TaskResponse b = createTask("B", null);
        SprintResponse sprint = sprints.create(sm, scrumId, new SprintRequest(null, "Goal", 27, null, null));
        assertNull(sprints.suggestion(sm, sprint.id()).suggestedCapacity());
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(a.id(), sprint.id(), null));
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(b.id(), sprint.id(), a.id()));
        SprintResponse read = sprints.get(viewer, sprint.id());
        assertTrue(read.overCapacity());
        assertEquals(33, read.selectedPoints());
        assertEquals(27, read.capacity());
        assertEquals(List.of(b.id()), read.unestimatedTaskIds());
    }

    @Test void threeCompletedVelocitiesAndAvailabilityCalculateExpectedSuggestion() {
        for (int n = 1; n <= 3; n++) {
            User teammate = user("Teammate" + n, "Developer", "pmp119-extra-" + n + "@test.invalid");
            workspaceMembers.save(WorkspaceMember.builder().workspace(ws).user(teammate)
                    .role(Role.WORKSPACE_MEMBER).build());
            add(teammate, ProjectRole.MEMBER);
        }
        em.flush();
        for (int velocity : List.of(28, 30, 32)) {
            SprintResponse sprint = create("Goal");
            TaskResponse item = createTask("Work " + velocity, velocity);
            sprints.move(sm, scrumId, new MoveSprintTaskRequest(item.id(), sprint.id(), null));
            sprints.start(sm, sprint.id(), new StartSprintRequest(null, null, null));
            var done = boards.get(pm, scrumId).columns().stream()
                    .filter(c -> c.category() == BoardColumnCategory.DONE).findFirst().orElseThrow();
            Task task = taskRepository.findById(item.id()).orElseThrow();
            task.setStatus(em.getReference(ProjectBoardColumn.class, done.id()));
            em.flush();
            sprints.complete(sm, sprint.id(), closing(null, false));
        }
        SprintResponse planned = create("Next goal");
        CapacitySuggestionResponse suggestion = sprints.suggestion(viewer, planned.id());
        assertEquals(30, suggestion.suggestedCapacity());
        assertEquals(30.0, suggestion.averageVelocity());
        assertEquals(50, suggestion.normalPersonDays());
        assertTrue(suggestion.members().stream().noneMatch(m -> m.userId().equals(viewer.getId())));
        assertThrows(BadRequestException.class, () -> sprints.setAvailability(sm, planned.id(), viewer.getId(),
                new AvailabilityRequest(2)));
        CapacitySuggestionResponse reduced = sprints.setAvailability(sm, planned.id(), pm.getId(),
                new AvailabilityRequest(5));
        assertEquals(45, reduced.availablePersonDays());
        assertEquals(27, reduced.suggestedCapacity());
    }


    @Test void aNewViewerDoesNotChangeCapacitySuggestion() {
        SprintResponse sprint = create("Capacity");
        CapacitySuggestionResponse before = sprints.suggestion(sm, sprint.id());
        User anotherViewer = user("Second", "Viewer", "pmp119-extra-viewer@test.invalid");
        workspaceMembers.save(WorkspaceMember.builder().workspace(ws).user(anotherViewer)
                .role(Role.WORKSPACE_MEMBER).build());
        add(anotherViewer, ProjectRole.VIEWER);
        em.flush();
        CapacitySuggestionResponse after = sprints.suggestion(sm, sprint.id());
        assertEquals(before.normalPersonDays(), after.normalPersonDays());
        assertEquals(before.availablePersonDays(), after.availablePersonDays());
        assertEquals(before.members(), after.members());
        assertFalse(after.members().stream().anyMatch(m -> m.userId().equals(anotherViewer.getId())));
    }

    @Test void taskFilterIsDatabaseBackedAndDeleteRestoresBacklogIncludingTombstones() {
        TaskResponse live = createTask("Live", 2);
        TaskResponse deleted = createTask("Deleted", 1);
        SprintResponse sprint = create("Goal");
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(live.id(), sprint.id(), null));
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(deleted.id(), sprint.id(), live.id()));
        var page = org.springframework.data.domain.PageRequest.of(0, 100);
        assertEquals(2, tasks.list(pm, scrumId, new team.sprintory_backend.task.api.TaskSearchFilters(
                null, null, null, null, null, null, sprint.id(), null),
                team.sprintory_backend.task.api.TaskListOrder.BACKLOG, page).content().size());
        assertEquals(0, tasks.list(pm, scrumId, new team.sprintory_backend.task.api.TaskSearchFilters(
                null, null, null, null, null, null, null, true),
                team.sprintory_backend.task.api.TaskListOrder.BACKLOG, page).content().size());
        tasks.delete(pm, scrumId, deleted.id());
        // Simulate separate REST requests with separate persistence contexts.
        em.flush();
        em.clear();
        sprints.delete(sm, sprint.id());
        em.flush();
        em.clear();
        assertNull(tasks.get(pm, scrumId, live.id()).sprintId());
        assertNull(tasks.restore(pm, scrumId, deleted.id()).sprintId());
        assertEquals(2, tasks.list(pm, scrumId, new team.sprintory_backend.task.api.TaskSearchFilters(
                null, null, null, null, null, null, null, true),
                team.sprintory_backend.task.api.TaskListOrder.BACKLOG, page).content().size());
    }

    @Test void completedSprintRejectsChangesAndEarlyCloseAllowsNextSprint() {
        TaskResponse item = createTask("Done", 5);
        SprintResponse current = create("Goal");
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(item.id(), current.id(), null));
        sprints.start(sm, current.id(), null);
        sprints.complete(sm, current.id(), closing(null, false));
        assertThrows(SprintConflictException.class, () -> sprints.update(sm, current.id(), req("Changed")));
        assertThrows(SprintConflictException.class, () -> sprints.delete(sm, current.id()));
        // The completed sprint's original planned end is no longer a date barrier.
        SprintResponse next = create("Next");
        assertTrue(next.startDate().isBefore(current.endDate()));
    }

    @Test void completeCanCarryOverToExistingAndToNewSprint() {
        TaskResponse unfinished = createTask("Still open", 4);
        SprintResponse running = create("Goal");
        SprintResponse planned = create("Planned");
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(unfinished.id(), running.id(), null));
        sprints.start(sm, running.id(), null);
        CompleteSprintResponse finished = sprints.complete(sm, running.id(), closing(planned.id(), false));
        assertEquals(1, finished.carriedOver());
        assertEquals(planned.id(), tasks.get(pm, scrumId, unfinished.id()).sprintId());
        TaskResponse second = createTask("More work", 6);
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(second.id(), planned.id(), null));
        sprints.start(sm, planned.id(), null);
        CompleteSprintResponse newSprint = sprints.complete(sm, planned.id(), closing(null, true));
        assertEquals(2, newSprint.carriedOver());
        assertNotNull(newSprint.toSprint());
        assertEquals(newSprint.toSprint().id(), tasks.get(pm, scrumId, second.id()).sprintId());
    }

    @Test void closeSendsUnfinishedWorkToTheTopOfTheBacklogAndFreezesTheResult() {
        TaskResponse waiting = createTask("Already in the backlog", 1);
        TaskResponse first = createTask("First", 3);
        TaskResponse second = createTask("Second", 2);
        TaskResponse done = createTask("Done", 8);
        SprintResponse sprint = create("Goal");
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(first.id(), sprint.id(), null));
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(second.id(), sprint.id(), first.id()));
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(done.id(), sprint.id(), second.id()));
        TaskResponse child = subtasks.create(pm, scrumId, first.id(), new CreateSubtaskRequest("Child", null, null, 30));
        sprints.start(sm, sprint.id(), null);
        var doneColumn = boards.get(pm, scrumId).columns().stream()
                .filter(c -> c.category() == BoardColumnCategory.DONE).findFirst().orElseThrow();
        taskRepository.findById(done.id()).orElseThrow().setStatus(em.getReference(ProjectBoardColumn.class, doneColumn.id()));
        em.flush();

        // PMP-40: the board shows the active sprint next to the project-wide counts.
        var board = boards.get(sm, scrumId);
        assertEquals(13, board.activeSprint().totalPoints());
        assertEquals(8, board.activeSprint().donePoints());
        var todoColumn = board.columns().stream().filter(c -> c.id().equals(first.statusId())).findFirst().orElseThrow();
        assertEquals(2L, todoColumn.sprintTaskCount());
        assertEquals(1L, todoColumn.sprintSubtaskCount());
        assertEquals(3, todoColumn.taskCount(), "the backlog task still counts project-wide");

        assertThrows(EarlyCloseConfirmationRequiredException.class, () -> sprints.complete(sm, sprint.id(),
                new CompleteSprintRequest(null, false, SprintGoalAchieved.PARTIALLY, null, false)));
        assertThrows(AccessDeniedException.class, () -> sprints.complete(pm, sprint.id(), closing(null, false)));
        CompleteSprintResponse closed = sprints.complete(sm, sprint.id(),
                new CompleteSprintRequest(null, false, SprintGoalAchieved.PARTIALLY, " Ran out of time ", true));
        em.flush();
        em.clear();

        assertEquals(2, closed.carriedOver());
        assertEquals(SprintStatus.COMPLETED, closed.sprint().status());
        assertEquals(8, closed.sprint().completedPoints());
        assertEquals(5, closed.sprint().unfinishedPoints());
        assertEquals(SprintGoalAchieved.PARTIALLY, closed.sprint().goalAchieved());
        assertEquals("Ran out of time", closed.sprint().goalNote());
        var backlog = tasks.list(pm, scrumId, new team.sprintory_backend.task.api.TaskSearchFilters(
                        null, null, null, null, null, null, null, true),
                team.sprintory_backend.task.api.TaskListOrder.BACKLOG, PageRequest.of(0, 100)).content();
        assertEquals(List.of(first.id(), second.id(), waiting.id()), backlog.stream().map(TaskResponse::id).toList(),
                "unfinished work goes to the top, in sprint order");
        assertNull(subtasks.list(viewer, scrumId, first.id()).get(0).sprintId(), "the subtask moves with its parent");
        assertEquals(1, tasks.get(pm, scrumId, first.id()).carriedOverCount());
        assertEquals(0, tasks.get(pm, scrumId, done.id()).carriedOverCount());
        assertEquals(0, tasks.get(pm, scrumId, child.id()).carriedOverCount());
        assertEquals(sprint.id(), tasks.get(pm, scrumId, done.id()).sprintId(), "done work stays linked");
        var entry = tasks.history(pm, scrumId, first.id(), team.sprintory_backend.task.api.TaskHistoryOrder.NEWEST,
                PageRequest.of(0, 20)).content().stream()
                .filter(e -> e.type() == TaskHistoryType.CARRIED_OVER).findFirst().orElseThrow();
        assertEquals(sprint.name(), entry.oldValue());
        assertNull(entry.newValue(), "to the backlog");
        assertEquals(sprint.id(), entry.refId());
        assertEquals(List.of(SprintOutcome.Result.CARRIED_OVER, SprintOutcome.Result.CARRIED_OVER, SprintOutcome.Result.DONE),
                outcomes.findBySprintIdOrderByPositionAsc(sprint.id()).stream().map(SprintOutcome::getOutcome).toList());

        // A later estimate change never touches the frozen result.
        taskRepository.findById(done.id()).orElseThrow().setEstimate(13);
        em.flush();
        assertEquals(8, sprints.get(viewer, sprint.id()).completedPoints());
        assertThrows(SprintConflictException.class, () -> sprints.move(sm, scrumId,
                new MoveSprintTaskRequest(waiting.id(), sprint.id(), null)), "an ended sprint takes no items");
    }

    @Test void onlyTheProductOwnerOrProjectManagerCancelsAndNeedsAReason() {
        User po = user("Polly", "Owner", "pmp41-po@test.invalid");
        User dev = user("Dev", "Eloper", "pmp41-dev@test.invalid");
        for (User u : List.of(po, dev)) {
            workspaceMembers.save(WorkspaceMember.builder().workspace(ws).user(u).role(Role.WORKSPACE_MEMBER).build());
        }
        add(po, ProjectRole.PRODUCT_OWNER);
        add(dev, ProjectRole.MEMBER);
        em.flush();
        TaskResponse waiting = createTask("Already in the backlog", 1);
        TaskResponse open = createTask("Open", 5);
        TaskResponse done = createTask("Done", 12);
        SprintResponse sprint = create("Goal");
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(open.id(), sprint.id(), null));
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(done.id(), sprint.id(), open.id()));
        sprints.start(sm, sprint.id(), null);
        var doneColumn = boards.get(pm, scrumId).columns().stream()
                .filter(c -> c.category() == BoardColumnCategory.DONE).findFirst().orElseThrow();
        taskRepository.findById(done.id()).orElseThrow().setStatus(em.getReference(ProjectBoardColumn.class, doneColumn.id()));
        em.flush();

        CancelSprintRequest why = new CancelSprintRequest("Goal became obsolete");
        for (User denied : List.of(sm, dev, viewer)) {
            assertThrows(AccessDeniedException.class, () -> sprints.cancel(denied, sprint.id(), why), denied.getEmail());
        }
        assertThrows(ResourceNotFoundException.class, () -> sprints.cancel(outsider, sprint.id(), why));
        assertThrows(BadRequestException.class, () -> sprints.cancel(po, sprint.id(), new CancelSprintRequest(" ")));

        CompleteSprintResponse cancelled = sprints.cancel(po, sprint.id(), why);
        em.flush();
        em.clear();

        assertEquals(SprintStatus.CANCELLED, cancelled.sprint().status());
        assertEquals("Goal became obsolete", cancelled.sprint().cancelReason());
        assertEquals(12, cancelled.sprint().completedPoints());
        assertEquals(1, cancelled.carriedOver());
        assertEquals(sprint.id(), tasks.get(pm, scrumId, done.id()).sprintId(), "done items stay linked");
        var backlog = tasks.list(pm, scrumId, new team.sprintory_backend.task.api.TaskSearchFilters(
                        null, null, null, null, null, null, null, true),
                team.sprintory_backend.task.api.TaskListOrder.BACKLOG, PageRequest.of(0, 100)).content();
        assertEquals(List.of(open.id(), waiting.id()), backlog.stream().map(TaskResponse::id).toList());
        assertEquals(1, tasks.get(pm, scrumId, open.id()).carriedOverCount());
        assertTrue(sprints.list(viewer, scrumId).stream().noneMatch(s -> s.id().equals(sprint.id())));

        // The PM may cancel too, and a cancelled sprint never counts toward velocity.
        SprintResponse second = create("Second");
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(open.id(), second.id(), null));
        sprints.start(sm, second.id(), null);
        sprints.cancel(pm, second.id(), why);
        em.flush();
        assertNull(sprints.suggestion(viewer, create("Third").id()).suggestedCapacity(),
                "no completed sprint yet: no velocity suggestion");
    }

    @Test void deletingAndRestoringActiveSprintTaskWritesBothHistories() {
        TaskResponse item = createTask("Scope", 1);
        SprintResponse sprint = create("Goal");
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(item.id(), sprint.id(), null));
        sprints.start(sm, sprint.id(), null);
        tasks.delete(pm, scrumId, item.id());
        assertEquals(SprintScopeChange.Action.REMOVED,
                sprints.scopeHistory(sm, sprint.id(), PageRequest.of(0, 20)).content().get(0).action());
        TaskResponse restored = tasks.restore(pm, scrumId, item.id());
        assertEquals(sprint.id(), restored.sprintId());
        assertEquals(SprintScopeChange.Action.ADDED,
                sprints.scopeHistory(sm, sprint.id(), PageRequest.of(0, 20)).content().get(0).action());
        assertTrue(tasks.history(pm, scrumId, item.id(), team.sprintory_backend.task.api.TaskHistoryOrder.NEWEST,
                PageRequest.of(0, 20)).content().stream().anyMatch(e ->
                    e.type() == team.sprintory_backend.task.models.TaskHistoryType.SPRINT_SCOPE_CHANGED));
    }

    @Test void restoreIntoCompletedSprintGoesToBacklog() {
        TaskResponse item = createTask("Old scope", 5);
        SprintResponse sprint = create("Goal");
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(item.id(), sprint.id(), null));
        sprints.start(sm, sprint.id(), null);
        tasks.delete(pm, scrumId, item.id());
        sprints.complete(sm, sprint.id(), closing(null, false));
        assertNull(tasks.restore(pm, scrumId, item.id()).sprintId());
    }

    @Test void bothDatesAreRequiredOnCreateUpdateAndStart() {
        SprintResponse sprint = create("Goal");
        assertThrows(BadRequestException.class, () -> sprints.create(sm, scrumId,
                new SprintRequest(null, "Goal", null, sprint.endDate().plusDays(2), null)));
        assertThrows(BadRequestException.class, () -> sprints.update(sm, sprint.id(),
                new SprintRequest(null, null, null, sprint.startDate().plusDays(1), null)));
        TaskResponse item = createTask("Item", 1);
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(item.id(), sprint.id(), null));
        assertThrows(BadRequestException.class, () -> sprints.start(sm, sprint.id(),
                new StartSprintRequest(null, sprint.endDate().plusDays(2), null)));
    }

    private SprintRequest req(String goal) { return new SprintRequest(null, goal, null, null, null); }
    private SprintResponse create(String goal) { return sprints.create(sm, scrumId, req(goal)); }
    /** A close that answers the goal question and confirms closing before the end date. */
    private CompleteSprintRequest closing(Long carryOverSprintId, boolean createSprint) {
        return new CompleteSprintRequest(carryOverSprintId, createSprint, SprintGoalAchieved.YES, null, true);
    }
    private TaskResponse createTask(String title, Integer estimate) {
        return tasks.create(pm, scrumId, new CreateTaskRequest(title, TaskType.STORY, TaskPriority.MEDIUM,
                null, estimate, List.of(), null));
    }
    private User user(String first, String last, String email) {
        return users.save(User.builder().firstName(first).lastName(last).email(email).enabled(true).build());
    }
    private void add(User user, ProjectRole role) {
        members.save(ProjectMember.builder().project(projectRepository.getReferenceById(scrumId))
                .user(user).roles(new java.util.HashSet<>(Set.of(role))).build());
    }
}
