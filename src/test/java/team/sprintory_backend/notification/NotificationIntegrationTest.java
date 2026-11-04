package team.sprintory_backend.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.AbstractIntegrationTest;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.notification.dto.NotificationResponse;
import team.sprintory_backend.notification.models.NotificationType;
import team.sprintory_backend.notification.services.EmailOutboxService;
import team.sprintory_backend.notification.services.NotificationMessages;
import team.sprintory_backend.notification.services.NotificationService;
import team.sprintory_backend.notification.services.NotificationSubject;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.project.dto.ProjectResponse;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.models.ProjectTemplate;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectService;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.services.TaskApiService;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;
import team.sprintory_backend.workspace.models.WorkspaceMember;
import team.sprintory_backend.workspace.repositories.WorkspaceMemberRepository;
import team.sprintory_backend.workspace.repositories.WorkspaceRepository;

/**
 * Notifications (R27) against a real PostgreSQL: the listener through real producers, the bell
 * queries, open's 404-but-read rule, the outbox queries and the nightly cleanups. Docker must be
 * running. Each test is rolled back.
 */
@Transactional
class NotificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EntityManager entityManager;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectService projects;
    @Autowired private TaskApiService tasks;
    @Autowired private NotificationService notifications;
    @Autowired private EmailOutboxService outbox;
    @Autowired private PlatformTransactionManager transactionManager;

    private User owner;
    private User grace;
    private Workspace workspace;
    private ProjectResponse scrum;

    /** Acme: Owner created the Scrum project (its Project Manager); Grace is a member of it. */
    @BeforeEach
    void setUp() {
        owner = userRepository.save(user("Owner", "owner-n@example.com"));
        grace = userRepository.save(user("Grace", "grace-n@example.com"));
        workspace = workspaceRepository.save(Workspace.builder().name("Acme").slug("acme-notifications").build());
        join(workspace, owner);
        join(workspace, grace);
        scrum = projects.create(owner, workspace.getId(),
                new CreateProjectRequest("Mobile App", "MOB", ProjectTemplate.SCRUM, null));
        entityManager.persist(ProjectMember.builder().project(projectRepository.getReferenceById(scrum.id()))
                .user(grace).roles(new HashSet<>(Set.of(ProjectRole.MEMBER))).build());
        entityManager.flush();
    }

    // --- the listener behind a real producer ---

    @Test
    void anAssignmentWritesTheNotificationTheEmailAndTheWatchersInTheActionsTransaction() {
        assignToGrace("Fix login");
        entityManager.flush();

        assertEquals(1, count("select count(*) from notifications where recipient_id = :id "
                + "and type = 'TASK_ASSIGNED' and task_key = 'MOB-1'", grace.getId()));
        assertEquals(1, count("select count(*) from email_outbox where recipient_email = 'grace-n@example.com' "
                + "and status = 'PENDING' and subject = '[Sprintory] MOB-1 Fix login: you were assigned'", null));
        // The creator and the assignee watch the task.
        assertEquals(2, count("select count(*) from task_watchers w join tasks t on t.id = w.task_id "
                + "where t.project_id = :id", scrum.id()));
        // The owner acted, so nothing for them.
        assertEquals(0, count("select count(*) from notifications where recipient_id = :id", owner.getId()));

        // Roll the action back: the notification and its email go with it.
        TestTransaction.end();
        TestTransaction.start();
        assertEquals(0, count("select count(*) from notifications where task_key = 'MOB-1' and recipient_id = :id",
                grace.getId()));
        assertEquals(0, count("select count(*) from email_outbox where recipient_email = 'grace-n@example.com'", null));
    }

    // --- the bell ---

    @Test
    void theBellListsNewestFirstFiltersByWorkspaceCountsAndReadsAll() {
        assignToGrace("First");
        assignToGrace("Second");
        // A notification from another workspace.
        Workspace other = workspaceRepository.save(Workspace.builder().name("Other").slug("other-notifications").build());
        notifications.create(grace.getId(), NotificationType.WORKSPACE_INVITATION,
                NotificationSubject.workspace(other, owner), "invited", null);
        entityManager.flush();
        entityManager.clear();

        List<String> all = keysOrMessages(notifications.list(grace, false, null, PageRequest.of(0, 20)).content());
        assertEquals(List.of("invited", "MOB-2", "MOB-1"), all, "newest first");
        assertEquals(List.of("MOB-2", "MOB-1"),
                keysOrMessages(notifications.list(grace, false, workspace.getId(), PageRequest.of(0, 20)).content()));
        assertEquals(3, notifications.unreadCount(grace).count());

        notifications.readAll(grace);
        entityManager.clear();

        assertEquals(0, notifications.unreadCount(grace).count());
        assertTrue(notifications.list(grace, true, null, PageRequest.of(0, 20)).content().isEmpty());
        assertEquals(0, count("select count(*) from notifications where recipient_id = :id and read_at is null",
                grace.getId()));
    }

    @Test
    void openingAfterLeavingTheProjectIs404ButTheNotificationStaysRead() {
        assignToGrace("Fix login");
        entityManager.flush();
        Long notificationId = notifications.list(grace, false, null, PageRequest.of(0, 1)).content().get(0).id();

        projects.removeMember(owner, scrum.id(), grace.getId());
        entityManager.flush();
        entityManager.clear();
        assertEquals(0, count("select count(*) from task_watchers where user_id = :id", grace.getId()),
                "leaving the project removed her watcher rows");

        assertThrows(ResourceNotFoundException.class, () -> notifications.open(grace, notificationId));

        // noRollbackFor: the 404 did not doom the transaction, so the read is kept. Joining the
        // running (test) transaction shows its rollback-only flag; committing a join does nothing.
        TransactionStatus joined = transactionManager.getTransaction(TransactionDefinition.withDefaults());
        assertFalse(joined.isRollbackOnly());
        transactionManager.commit(joined);
        entityManager.flush();
        assertEquals(1, count("select count(*) from notifications where id = :id and read_at is not null",
                notificationId));
    }

    @Test
    void someoneElsesNotificationCannotBeOpened() {
        assignToGrace("Fix login");
        entityManager.flush();
        Long notificationId = notifications.list(grace, false, null, PageRequest.of(0, 1)).content().get(0).id();

        assertThrows(ResourceNotFoundException.class, () -> notifications.open(owner, notificationId));
        entityManager.flush();
        assertEquals(1, count("select count(*) from notifications where id = :id and read_at is null", notificationId));
    }

    // --- the outbox and the cleanups ---

    @Test
    void aQueuedEmailIsDueAtOnceAndOnlyFinishedOldEmailsAreCleanedUp() {
        assignToGrace("Fix login");
        entityManager.flush();
        Long emailId = ((Number) entityManager.createNativeQuery(
                "select id from email_outbox where recipient_email = 'grace-n@example.com'").getSingleResult()).longValue();

        assertTrue(outbox.dueIds(50).contains(emailId));

        // An old sent email goes; an old pending one stays.
        entityManager.createNativeQuery("insert into email_outbox (recipient_email, subject, body_text, body_html, "
                + "status, attempts, next_attempt_at, created_at) values ('old@example.com', 's', 't', 'h', 'PENDING', 2, "
                + "now(), now() - interval '100 days')").executeUpdate();
        entityManager.createNativeQuery("update email_outbox set status = 'SENT', next_attempt_at = null, "
                + "created_at = now() - interval '100 days' where id = :id").setParameter("id", emailId).executeUpdate();

        outbox.deleteFinished();

        assertEquals(0, count("select count(*) from email_outbox where id = :id", emailId));
        assertEquals(1, count("select count(*) from email_outbox where recipient_email = 'old@example.com'", null));
        assertFalse(outbox.dueIds(50).contains(emailId));
    }

    @Test
    void theNightlyCleanupDeletesNotificationsOlderThan90DaysReadOrNot() {
        assignToGrace("Old");
        assignToGrace("New");
        entityManager.flush();
        entityManager.createNativeQuery("update notifications set created_at = now() - interval '91 days' "
                + "where task_key = 'MOB-1'").executeUpdate();

        notifications.deleteExpired();
        entityManager.clear();

        assertEquals(List.of("MOB-2"),
                keysOrMessages(notifications.list(grace, false, null, PageRequest.of(0, 20)).content()));
    }

    @Test
    void aSecurityNotificationNeedsNoWorkspace() {
        notifications.create(grace.getId(), NotificationType.PASSWORD_RESET, NotificationSubject.system(),
                NotificationMessages.passwordReset(), null);
        entityManager.flush();
        entityManager.clear();

        NotificationResponse stored = notifications.list(grace, false, null, PageRequest.of(0, 1)).content().get(0);
        assertEquals(NotificationType.PASSWORD_RESET, stored.type());
        assertEquals(null, stored.workspace());
        assertTrue(notifications.list(grace, false, workspace.getId(), PageRequest.of(0, 20)).content().isEmpty(),
                "a workspace filter never matches a security notification");
    }

    // --- helpers ---

    private TaskResponse assignToGrace(String title) {
        return tasks.create(owner, scrum.id(), new CreateTaskRequest(title, null, null, grace.getId(), null, null, null));
    }

    private static List<String> keysOrMessages(List<NotificationResponse> page) {
        return page.stream().map(n -> n.task() != null ? n.task().key() : n.message()).toList();
    }

    /** Counts rows straight from the database, past the persistence context. */
    private long count(String sql, Long id) {
        var query = entityManager.createNativeQuery(sql);
        if (id != null) {
            query.setParameter("id", id);
        }
        return ((Number) query.getSingleResult()).longValue();
    }

    private void join(Workspace target, User user) {
        workspaceMemberRepository.save(WorkspaceMember.builder().workspace(target).user(user)
                .role(Role.WORKSPACE_MEMBER).build());
    }

    private static User user(String firstName, String email) {
        return User.builder().firstName(firstName).lastName("Tester").email(email).enabled(true).build();
    }
}