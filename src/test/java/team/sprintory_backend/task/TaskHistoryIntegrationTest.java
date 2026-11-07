package team.sprintory_backend.task;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.mention.exceptions.InvalidMentionException;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.task.api.TaskHistoryOrder;
import team.sprintory_backend.task.dto.CreateSubtaskRequest;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.TaskHistoryEntryResponse;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.dto.UpdateSubtaskRequest;
import team.sprintory_backend.task.dto.UpdateTaskRequest;
import team.sprintory_backend.task.models.TaskHistoryField;
import team.sprintory_backend.task.models.TaskHistoryType;
import team.sprintory_backend.task.models.TaskPriority;
import team.sprintory_backend.task.services.SubtaskService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The task activity feed (R10, PMP-133) against a real PostgreSQL, through the real task and
 * subtask services. Docker must be running. Each test is rolled back.
 */
@Transactional
class TaskHistoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EntityManager em;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository workspaceMembers;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectService projectService;
    @Autowired private TaskApiService tasks;
    @Autowired private SubtaskService subtasks;

    private User owner;
    private User grace;
    private User viewer;
    private User outsider;
    private Long projectId;

    /** Owner created a Kanban project; Grace is a Member, Vera a Viewer; Otto is only in the workspace. */
    @BeforeEach
    void setUp() {
        owner = user("Owner", "Smith", "owner-h@example.com");
        grace = user("Grace", "Hopper", "grace-h@example.com");
        viewer = user("Vera", "Viewer", "vera-h@example.com");
        outsider = user("Otto", "Outside", "otto-h@example.com");
        Workspace workspace = workspaces.save(Workspace.builder().name("History").slug("history-test").build());
        for (User u : List.of(owner, grace, viewer, outsider)) {
            workspaceMembers.save(WorkspaceMember.builder().workspace(workspace).user(u)
                    .role(Role.WORKSPACE_MEMBER).build());
        }
        projectId = projectService.create(owner, workspace.getId(),
                new CreateProjectRequest("Product", "HIS", ProjectTemplate.KANBAN, null)).id();
        member(grace, ProjectRole.MEMBER);
        member(viewer, ProjectRole.VIEWER);
        em.flush();
    }

    @Test
    void changingPriorityFromLowToHighShowsWhoWhenAndLowToHigh() {
        TaskResponse task = tasks.create(owner, projectId,
                new CreateTaskRequest("Fix login", null, TaskPriority.LOW, null, null, null, null));

        tasks.update(grace, projectId, task.id(), new UpdateTaskRequest(null, null, TaskPriority.HIGH,
                null, null, null, null, null, false, false, false));

        List<TaskHistoryEntryResponse> feed = feed(task.id());
        assertEquals(2, feed.size());
        assertEquals(TaskHistoryType.CREATED, feed.get(0).type());
        assertEquals("Owner Smith", feed.get(0).actor().name());
        TaskHistoryEntryResponse change = feed.get(1);
        assertEquals(TaskHistoryType.FIELD_CHANGED, change.type());
        assertEquals(TaskHistoryField.PRIORITY, change.field());
        assertEquals("LOW", change.oldValue());
        assertEquals("HIGH", change.newValue());
        assertEquals(new TaskHistoryEntryResponse.Actor(grace.getId(), "Grace Hopper"), change.actor());
        assertNotNull(change.occurredAt());
    }

    @Test
    void oneEditWritesOneEntryPerChangedFieldAndASaveWithoutChangesWritesNone() {
        TaskResponse task = tasks.create(owner, projectId,
                new CreateTaskRequest("Fix login", null, null, null, 3, List.of("backend"), null));

        tasks.update(owner, projectId, task.id(), new UpdateTaskRequest("Fix login on Safari", null, null,
                null, grace.getId(), 5, List.of("ui", "backend"), null, false, false, false));
        tasks.update(owner, projectId, task.id(), new UpdateTaskRequest("Fix login on Safari", null, null,
                null, grace.getId(), 5, List.of("backend", "ui"), null, false, false, false));

        List<TaskHistoryEntryResponse> changes = feed(task.id()).subList(1, feed(task.id()).size());
        assertEquals(List.of(TaskHistoryField.TITLE, TaskHistoryField.ASSIGNEE, TaskHistoryField.ESTIMATE,
                TaskHistoryField.LABELS), changes.stream().map(TaskHistoryEntryResponse::field).toList());
        assertEquals("Grace Hopper", changes.get(1).newValue());
        assertNull(changes.get(1).oldValue(), "it was unassigned");
        assertEquals("backend, ui", changes.get(3).newValue());
    }

    @Test
    void aSubtaskHasItsOwnFeedAndItsParentShowsItAddedRemovedAndAddedBack() {
        TaskResponse parent = tasks.create(owner, projectId,
                new CreateTaskRequest("Parent", null, null, null, null, null, null));
        TaskResponse child = subtasks.create(owner, projectId, parent.id(),
                new CreateSubtaskRequest("Write tests", null, null, 60));
        subtasks.update(grace, projectId, parent.id(), child.id(),
                new UpdateSubtaskRequest(null, null, null, 90, false, false));
        tasks.delete(owner, projectId, child.id());
        em.flush();
        em.clear();
        tasks.restore(owner, projectId, child.id());

        String label = child.key() + " Write tests";
        List<TaskHistoryEntryResponse> parentFeed = feed(parent.id());
        assertEquals(List.of(TaskHistoryType.CREATED, TaskHistoryType.SUBTASK_ADDED, TaskHistoryType.SUBTASK_REMOVED,
                TaskHistoryType.SUBTASK_ADDED), parentFeed.stream().map(TaskHistoryEntryResponse::type).toList());
        assertEquals(label, parentFeed.get(1).newValue());
        assertEquals(child.id(), parentFeed.get(1).refId());
        assertEquals(label, parentFeed.get(2).oldValue());

        List<TaskHistoryEntryResponse> childFeed = feed(child.id());
        assertEquals(List.of(TaskHistoryType.CREATED, TaskHistoryType.FIELD_CHANGED, TaskHistoryType.RESTORED),
                childFeed.stream().map(TaskHistoryEntryResponse::type).toList());
        assertEquals(TaskHistoryField.ESTIMATE_MINUTES, childFeed.get(1).field());
        assertEquals("60", childFeed.get(1).oldValue());
        assertEquals("90", childFeed.get(1).newValue());
    }

    @Test
    void aSubtaskKeepsItsDescriptionAndLabelsAndRecordsTheirChanges() {
        TaskResponse parent = tasks.create(owner, projectId,
                new CreateTaskRequest("Parent", null, null, null, null, null, null));
        String mentionGrace = "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":["
                + "{\"type\":\"mention\",\"attrs\":{\"kind\":\"user\",\"id\":\"" + grace.getId()
                + "\",\"label\":\"Grace\"}}]}]}";
        TaskResponse child = subtasks.create(owner, projectId, parent.id(), new CreateSubtaskRequest(
                "Write tests", null, null, null, List.of("qa", "QA"), mentionGrace));
        assertEquals(List.of("qa"), child.labels());
        assertEquals(mentionGrace, child.description());

        TaskResponse edited = subtasks.update(owner, projectId, parent.id(), child.id(), new UpdateSubtaskRequest(
                null, null, null, null, null, null, List.of("qa", "backend"), null, true));
        assertEquals(List.of("backend", "qa"), edited.labels());
        assertNull(edited.description());

        List<TaskHistoryEntryResponse> changes = feed(child.id()).subList(1, feed(child.id()).size());
        assertEquals(List.of(TaskHistoryField.LABELS, TaskHistoryField.DESCRIPTION),
                changes.stream().map(TaskHistoryEntryResponse::field).toList());
        assertNull(changes.get(1).oldValue(), "a description change never stores the text");

        String mentionOutsider = mentionGrace.replace(grace.getId().toString(), outsider.getId().toString());
        assertThrows(InvalidMentionException.class, () -> subtasks.update(owner, projectId, parent.id(), child.id(),
                new UpdateSubtaskRequest(null, null, null, null, null, null, null, mentionOutsider, null)));
    }

    @Test
    void restoringAParentWritesOneEntryOnTheParentAndNoneOnItsSubtasks() {
        TaskResponse parent = tasks.create(owner, projectId,
                new CreateTaskRequest("Parent", null, null, null, null, null, null));
        TaskResponse child = subtasks.create(owner, projectId, parent.id(),
                new CreateSubtaskRequest("Child", null, null, null));
        tasks.delete(owner, projectId, parent.id());
        em.flush();
        em.clear();

        tasks.restore(owner, projectId, parent.id());

        assertEquals(List.of(TaskHistoryType.CREATED, TaskHistoryType.SUBTASK_ADDED, TaskHistoryType.RESTORED),
                feed(parent.id()).stream().map(TaskHistoryEntryResponse::type).toList());
        assertEquals(List.of(TaskHistoryType.CREATED),
                feed(child.id()).stream().map(TaskHistoryEntryResponse::type).toList());
    }

    @Test
    void viewersReadTheFeedAndNonMembersGet404() {
        TaskResponse task = tasks.create(owner, projectId,
                new CreateTaskRequest("Fix login", null, null, null, null, null, null));

        assertEquals(1, tasks.history(viewer, projectId, task.id(), TaskHistoryOrder.NEWEST,
                PageRequest.of(0, 20)).totalElements());
        assertThrows(ResourceNotFoundException.class,
                () -> tasks.history(outsider, projectId, task.id(), TaskHistoryOrder.NEWEST, PageRequest.of(0, 20)));
    }

    @Test
    void theFeedIsNewestFirstByDefaultOrOldestFirstOnRequestAndPagesNeverOverlap() {
        TaskResponse task = tasks.create(owner, projectId,
                new CreateTaskRequest("Fix login", null, TaskPriority.LOW, null, null, null, null));
        tasks.update(owner, projectId, task.id(), new UpdateTaskRequest(null, null, TaskPriority.HIGH,
                null, null, null, null, null, false, false, false));
        tasks.update(owner, projectId, task.id(), new UpdateTaskRequest("Fix login on Safari", null, null,
                null, null, null, null, null, false, false, false));

        TaskHistoryOrder byDefault = TaskHistoryOrder.fromQuery(null);
        List<TaskHistoryEntryResponse> first = tasks.history(owner, projectId, task.id(), byDefault,
                PageRequest.of(0, 2)).content();
        List<TaskHistoryEntryResponse> second = tasks.history(owner, projectId, task.id(), byDefault,
                PageRequest.of(1, 2)).content();
        assertEquals(List.of(TaskHistoryField.TITLE, TaskHistoryField.PRIORITY),
                first.stream().map(TaskHistoryEntryResponse::field).toList());
        assertEquals(List.of(TaskHistoryType.CREATED), second.stream().map(TaskHistoryEntryResponse::type).toList());

        assertEquals(List.of(TaskHistoryType.CREATED, TaskHistoryType.FIELD_CHANGED, TaskHistoryType.FIELD_CHANGED),
                tasks.history(owner, projectId, task.id(), TaskHistoryOrder.fromQuery("Oldest"),
                        PageRequest.of(0, 20)).content().stream().map(TaskHistoryEntryResponse::type).toList());
        assertEquals(TaskHistoryField.PRIORITY, feed(task.id()).get(1).field());
    }

    // --- helpers ---

    /** The whole feed, oldest first, so the tests read it in the order things happened. */
    private List<TaskHistoryEntryResponse> feed(Long taskId) {
        return tasks.history(owner, projectId, taskId, TaskHistoryOrder.OLDEST, PageRequest.of(0, 100)).content();
    }

    private void member(User user, ProjectRole role) {
        em.persist(ProjectMember.builder().project(projectRepository.getReferenceById(projectId)).user(user)
                .roles(new HashSet<>(Set.of(role))).build());
    }

    private User user(String firstName, String lastName, String email) {
        return users.save(User.builder().firstName(firstName).lastName(lastName).email(email).enabled(true).build());
    }
}