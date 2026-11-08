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

/** Real PostgreSQL + Flyway V15; each method rolls back its data after execution. */
@Transactional
class SprintPlanningIntegrationTest extends AbstractIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private SprintService sprints;
    @Autowired private SprintRepository sprintRepository;
    @Autowired private SprintCommitmentRepository commitments;
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
        for (int n = 1; n <= 2; n++) {
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
            sprints.complete(sm, sprint.id(), new CompleteSprintRequest(null, false));
        }
        SprintResponse planned = create("Next goal");
        CapacitySuggestionResponse suggestion = sprints.suggestion(viewer, planned.id());
        assertEquals(30, suggestion.suggestedCapacity());
        assertEquals(30.0, suggestion.averageVelocity());
        assertEquals(50, suggestion.normalPersonDays());
        CapacitySuggestionResponse reduced = sprints.setAvailability(sm, planned.id(), pm.getId(),
                new AvailabilityRequest(5));
        assertEquals(45, reduced.availablePersonDays());
        assertEquals(27, reduced.suggestedCapacity());
    }

    private SprintRequest req(String goal) { return new SprintRequest(null, goal, null, null, null); }
    private SprintResponse create(String goal) { return sprints.create(sm, scrumId, req(goal)); }
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
