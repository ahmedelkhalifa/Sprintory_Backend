package team.sprintory_backend.comment;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.comment.dto.CommentRequest;
import team.sprintory_backend.comment.dto.CommentResponse;
import team.sprintory_backend.comment.services.CommentService;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.api.TaskHistoryOrder;
import team.sprintory_backend.task.dto.TaskHistoryEntryResponse;
import team.sprintory_backend.task.models.TaskHistoryType;
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
 * Comments (R10, PMP-133) and their mentions (PMP-136) against a real PostgreSQL, through the
 * real services, down to the notifications and the task history. Covers the acceptance criteria
 * of PMP-26 and PMP-31 part A. Docker must be running. Each test is rolled back.
 */
@Transactional
class CommentIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EntityManager em;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository workspaceMembers;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectService projectService;
    @Autowired private TaskApiService tasks;
    @Autowired private CommentService comments;

    private User owner;
    private User grace;
    private User mobeen;
    private User viewer;
    private User outsider;
    private Long projectId;
    private Long taskId;

    /**
     * Owner created the project (its Project Manager) and the task, so Owner watches it. Grace and
     * Mobeen are Members, Vera a Viewer; Otto is only in the workspace.
     */
    @BeforeEach
    void setUp() {
        owner = user("Owner", "Smith", "owner-c@example.com");
        grace = user("Grace", "Hopper", "grace-c@example.com");
        mobeen = user("Mobeen", "Ahmed", "mobeen-c@example.com");
        viewer = user("Vera", "Viewer", "vera-c@example.com");
        outsider = user("Otto", "Outside", "otto-c@example.com");
        Workspace workspace = workspaces.save(Workspace.builder().name("Comments").slug("comments-test").build());
        for (User u : List.of(owner, grace, mobeen, viewer, outsider)) {
            workspaceMembers.save(WorkspaceMember.builder().workspace(workspace).user(u)
                    .role(Role.WORKSPACE_MEMBER).build());
        }
        projectId = projectService.create(owner, workspace.getId(),
                new CreateProjectRequest("Mobile App", "COM", ProjectTemplate.SCRUM, null)).id();
        member(grace, ProjectRole.MEMBER);
        member(mobeen, ProjectRole.MEMBER);
        member(viewer, ProjectRole.VIEWER);
        taskId = tasks.create(owner, projectId,
                new CreateTaskRequest("Fix login", null, null, null, null, null, null)).id();
        em.flush();
    }

    // --- PMP-26: comments ---

    @Test
    void aMemberPostsAndEveryoneSeesAuthorTimeAndText() {
        CommentResponse posted = comments.create(grace, projectId, taskId, new CommentRequest(doc(text("Looks good"))));
        em.flush();

        CommentResponse seen = thread(viewer).get(0);
        assertEquals(posted.id(), seen.id());
        assertEquals(new CommentResponse.Author(grace.getId(), "Grace Hopper"), seen.author());
        assertNotNull(seen.createdAt());
        assertEquals(doc(text("Looks good")), seen.body());
        assertFalse(seen.canEdit(), "a Viewer can only read");
    }

    @Test
    void anotherMemberCannotEditOrDeleteItButTheProjectManagerDeletesIt() {
        Long id = comments.create(grace, projectId, taskId, new CommentRequest(doc(text("Mine")))).id();

        assertThrows(AccessDeniedException.class, () -> comments.update(mobeen, projectId, taskId, id,
                new CommentRequest(doc(text("Changed")))));
        assertThrows(AccessDeniedException.class, () -> comments.delete(mobeen, projectId, taskId, id));
        assertThrows(AccessDeniedException.class, () -> comments.update(owner, projectId, taskId, id,
                new CommentRequest(doc(text("Changed")))), "the PM deletes but never edits");

        comments.delete(owner, projectId, taskId, id);
        em.flush();
        em.clear();

        CommentResponse placeholder = thread(grace).get(0);
        assertTrue(placeholder.deleted());
        assertNull(placeholder.body());
        assertFalse(placeholder.canDelete());
        TaskHistoryEntryResponse last = lastHistoryEntry();
        assertEquals(TaskHistoryType.COMMENT_DELETED, last.type());
        assertEquals(id, last.refId());
        assertEquals("Owner Smith", last.actor().name());
        assertNull(last.oldValue(), "comment text is never copied into history");
    }

    @Test
    void theAuthorEditsAndTheCommentShowsEdited() {
        Long id = comments.create(grace, projectId, taskId, new CommentRequest(doc(text("Frist")))).id();
        em.flush();

        CommentResponse same = comments.update(grace, projectId, taskId, id, new CommentRequest(doc(text("Frist"))));
        assertNull(same.editedAt(), "the same text is not an edit");
        CommentResponse edited = comments.update(grace, projectId, taskId, id, new CommentRequest(doc(text("First"))));
        em.flush();
        em.clear();

        assertNotNull(edited.editedAt());
        assertEquals(doc(text("First")), thread(grace).get(0).body());
        assertNotNull(thread(grace).get(0).editedAt());
    }

    @Test
    void aViewerReadsButCannotPostAndANonMemberGets404() {
        comments.create(grace, projectId, taskId, new CommentRequest(doc(text("Hi"))));

        assertThrows(AccessDeniedException.class,
                () -> comments.create(viewer, projectId, taskId, new CommentRequest(doc(text("Me too")))));
        assertEquals(1, thread(viewer).size());
        assertThrows(ResourceNotFoundException.class,
                () -> comments.list(outsider, projectId, taskId, PageRequest.of(0, 20)));
        assertThrows(ResourceNotFoundException.class,
                () -> comments.create(outsider, projectId, taskId, new CommentRequest(doc(text("Hi")))));
    }

    @Test
    void theThreadIsOldestFirstAndPaged() {
        for (String text : List.of("one", "two", "three")) {
            comments.create(grace, projectId, taskId, new CommentRequest(doc(text(text))));
            em.flush();
        }

        var first = comments.list(grace, projectId, taskId, PageRequest.of(0, 2));
        var second = comments.list(grace, projectId, taskId, PageRequest.of(1, 2));

        assertEquals(3, first.totalElements());
        assertEquals(List.of(doc(text("one")), doc(text("two"))),
                first.content().stream().map(CommentResponse::body).toList());
        assertEquals(List.of(doc(text("three"))), second.content().stream().map(CommentResponse::body).toList());
    }

    // --- notifications and mentions (PMP-31 part A, R27) ---

    @Test
    void postingTellsWatchersAndMentionedPeopleAndMakesTheAuthorAWatcher() {
        comments.create(grace, projectId, taskId,
                new CommentRequest(doc(text("Can you check, "), mention(mobeen))));
        em.flush();

        assertEquals(1, notifications(owner, "TASK_COMMENTED"), "Owner watches the task they created");
        assertEquals(1, notifications(mobeen, "MENTIONED"));
        assertEquals(0, notifications(mobeen, "TASK_COMMENTED"), "a mention replaces the comment notification");
        assertEquals(0, notifications(grace, "TASK_COMMENTED") + notifications(grace, "MENTIONED"),
                "the author is never told about their own comment");
        assertEquals(1, count("select count(*) from task_watchers where task_id = :task and user_id = :user",
                taskId, grace.getId()), "commenting makes you a watcher");
    }

    @Test
    void editingTellsOnlyNewlyMentionedPeopleAndNotTheWatchers() {
        Long id = comments.create(grace, projectId, taskId,
                new CommentRequest(doc(mention(mobeen)))).id();
        em.flush();

        comments.update(grace, projectId, taskId, id, new CommentRequest(doc(mention(mobeen), mention(viewer))));
        em.flush();

        assertEquals(1, notifications(mobeen, "MENTIONED"), "already mentioned before the edit");
        assertEquals(1, notifications(viewer, "MENTIONED"), "a Viewer can be mentioned");
        assertEquals(1, notifications(owner, "TASK_COMMENTED"), "watchers are not told about edits");
    }

    @Test
    void mentionsShowTheCurrentName() {
        comments.create(grace, projectId, taskId, new CommentRequest(doc(mention(mobeen))));
        em.flush();
        mobeen.setFirstName("Mo");
        users.save(mobeen);
        em.flush();

        assertEquals("Mo Ahmed", thread(grace).get(0).mentions().get(0).name());
    }

    // --- helpers ---

    private List<CommentResponse> thread(User reader) {
        return comments.list(reader, projectId, taskId, PageRequest.of(0, 20)).content();
    }

    private TaskHistoryEntryResponse lastHistoryEntry() {
        return tasks.history(owner, projectId, taskId, TaskHistoryOrder.NEWEST, PageRequest.of(0, 1)).content().get(0);
    }

    private long notifications(User recipient, String type) {
        return count("select count(*) from notifications where recipient_id = :user and type = '" + type
                + "' and task_id = :task", taskId, recipient.getId());
    }

    private long count(String sql, Long task, Long user) {
        return ((Number) em.createNativeQuery(sql).setParameter("task", task).setParameter("user", user)
                .getSingleResult()).longValue();
    }

    private void member(User user, ProjectRole role) {
        em.persist(ProjectMember.builder().project(projectRepository.getReferenceById(projectId)).user(user)
                .roles(new HashSet<>(Set.of(role))).build());
    }

    private User user(String firstName, String lastName, String email) {
        return users.save(User.builder().firstName(firstName).lastName(lastName).email(email).enabled(true).build());
    }

    private static String doc(String... inline) {
        return "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[" + String.join(",", inline) + "]}]}";
    }

    private static String text(String text) {
        return "{\"type\":\"text\",\"text\":\"" + text + "\"}";
    }

    private static String mention(User user) {
        return "{\"type\":\"mention\",\"attrs\":{\"kind\":\"user\",\"id\":\"" + user.getId() + "\",\"label\":\""
                + user.getFirstName() + "\"}}";
    }
}