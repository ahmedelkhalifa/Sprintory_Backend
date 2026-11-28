package team.sprintory_backend.sprint;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.models.*;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectBoardService;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.sprint.dto.*;
import team.sprintory_backend.sprint.models.*;
import team.sprintory_backend.sprint.repositories.SprintDailySnapshotRepository;
import team.sprintory_backend.sprint.repositories.SprintRepository;
import team.sprintory_backend.sprint.services.SprintService;
import team.sprintory_backend.sprint.services.SprintSnapshotJob;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** PMP-42 on real PostgreSQL (V22): snapshots, burndown and velocity; each method rolls back. */
@Transactional
class BurndownVelocityIntegrationTest extends AbstractIntegrationTest {
    @Autowired private EntityManager em;
    @Autowired private SprintService sprints;
    @Autowired private SprintSnapshotJob job;
    @Autowired private SprintRepository sprintRepository;
    @Autowired private SprintDailySnapshotRepository snapshots;
    @Autowired private ProjectService projects;
    @Autowired private ProjectBoardService boards;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository members;
    @Autowired private TaskApiService tasks;
    @Autowired private TaskRepository taskRepository;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository workspaceMembers;

    private User pm, sm, viewer;
    private Long scrumId;

    @BeforeEach void setup() {
        pm = user("Parker", "Manager", "pmp42-pm@test.invalid");
        sm = user("Sam", "Master", "pmp42-sm@test.invalid");
        viewer = user("Val", "Viewer", "pmp42-viewer@test.invalid");
        Workspace ws = workspaces.save(Workspace.builder().name("Burndown").slug("burndown-pmp42").build());
        for (User u : List.of(pm, sm, viewer)) {
            workspaceMembers.save(WorkspaceMember.builder().workspace(ws).user(u).role(Role.WORKSPACE_MEMBER).build());
        }
        scrumId = projects.create(pm, ws.getId(), new CreateProjectRequest("Scrum", "BRN", ProjectTemplate.SCRUM, null)).id();
        add(sm, ProjectRole.SCRUM_MASTER);
        add(viewer, ProjectRole.VIEWER);
        em.flush();
    }

    @Test void burndownFollowsTheSprintAndStaysFrozenAfterClose() {
        TaskResponse done = createTask("Done", 5);
        TaskResponse open = createTask("Open", 3);
        TaskResponse unestimated = createTask("No estimate", null);
        SprintResponse sprint = sprints.create(sm, scrumId, new SprintRequest(null, "Goal", null, null, null));
        for (TaskResponse task : List.of(done, open, unestimated)) {
            sprints.move(sm, scrumId, new MoveSprintTaskRequest(task.id(), sprint.id(), null));
        }
        sprints.start(sm, sprint.id(), null);
        markDone(done.id());

        // The job runs twice on the start day: still one row for the day.
        job.run();
        job.run();
        assertEquals(1, snapshots.findBySprintIdOrderBySnapshotDateAsc(sprint.id()).size());

        BurndownResponse points = sprints.burndown(viewer, sprint.id(), BurndownUnit.POINTS, BurndownDays.WORKING);
        assertTrue(points.hasDailyData());
        assertEquals(8L, points.summary().committed());
        assertEquals(5L, points.summary().done());
        assertEquals(3L, points.summary().remaining());
        assertEquals(1L, points.summary().unestimatedTasks());
        assertNotNull(points.status());
        BurndownResponse count = sprints.burndown(viewer, sprint.id(), BurndownUnit.TASKS, BurndownDays.WORKING);
        assertEquals(3L, count.summary().committed());
        assertEquals(2L, count.summary().remaining());

        sprints.complete(sm, sprint.id(), new CompleteSprintRequest(null, false, SprintGoalAchieved.YES, null, true));
        em.flush();
        em.clear();

        // Recorded before carry-over moved the open tasks out, so the last day still shows what was left.
        var rows = snapshots.findBySprintIdOrderBySnapshotDateAsc(sprint.id());
        assertEquals(1, rows.size());
        assertEquals(3, rows.get(0).getRemainingPoints());
        BurndownResponse closed = sprints.burndown(viewer, sprint.id(), BurndownUnit.POINTS, BurndownDays.WORKING);
        assertEquals(SprintStatus.COMPLETED, closed.sprintStatus());
        assertEquals(LocalDate.now(ZoneOffset.UTC), closed.endedOn());
        assertEquals(3L, closed.summary().remaining());
        assertNull(closed.status());
    }

    @Test void velocityShowsCompletedSprintsWithPointsOnly() {
        SprintResponse completed = runSprint(5);
        sprints.complete(sm, completed.id(), new CompleteSprintRequest(null, false, SprintGoalAchieved.YES, null, true));
        SprintResponse cancelled = runSprint(8);
        sprints.cancel(pm, cancelled.id(), new CancelSprintRequest("Obsolete"));
        // A Jira-imported sprint has no points.
        Sprint imported = new Sprint(projectRepository.getReferenceById(scrumId), 50, "Imported", "", null, null, null);
        imported.setStatus(SprintStatus.COMPLETED);
        imported.setCompletedAt(Instant.now());
        sprintRepository.save(imported);
        em.flush();

        VelocityResponse velocity = sprints.velocity(viewer, scrumId, 6);

        assertEquals(List.of(completed.id()), velocity.sprints().stream().map(VelocityResponse.SprintPoints::id).toList());
        assertEquals(5.0, velocity.averageVelocity());
        assertEquals(1, velocity.averageOf());
    }

    /** Starts a sprint holding one done task of {@code points}. */
    private SprintResponse runSprint(int points) {
        TaskResponse task = createTask("Work " + points, points);
        SprintResponse sprint = sprints.create(sm, scrumId, new SprintRequest(null, "Goal", null, null, null));
        sprints.move(sm, scrumId, new MoveSprintTaskRequest(task.id(), sprint.id(), null));
        sprints.start(sm, sprint.id(), null);
        markDone(task.id());
        return sprint;
    }

    private void markDone(Long taskId) {
        var doneColumn = boards.get(pm, scrumId).columns().stream()
                .filter(c -> c.category() == BoardColumnCategory.DONE).findFirst().orElseThrow();
        taskRepository.findById(taskId).orElseThrow().setStatus(em.getReference(ProjectBoardColumn.class, doneColumn.id()));
        em.flush();
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
                .user(user).roles(new HashSet<>(Set.of(role))).build());
    }
}
