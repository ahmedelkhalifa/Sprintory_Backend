package team.sprintory_backend.notification.services;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.notification.events.Mentioned;
import team.sprintory_backend.notification.events.PasswordReset;
import team.sprintory_backend.notification.events.ProjectMemberAdded;
import team.sprintory_backend.notification.events.ProjectRolesChanged;
import team.sprintory_backend.notification.events.RepeatedLoginFailures;
import team.sprintory_backend.notification.events.SprintCancelled;
import team.sprintory_backend.notification.events.SprintCompleted;
import team.sprintory_backend.notification.events.SprintStarted;
import team.sprintory_backend.notification.events.TaskAssigned;
import team.sprintory_backend.notification.events.TaskCommented;
import team.sprintory_backend.notification.events.TaskCreated;
import team.sprintory_backend.notification.events.TaskStatusChanged;
import team.sprintory_backend.notification.events.WorkspaceInvited;
import team.sprintory_backend.notification.models.NotificationCategory;
import team.sprintory_backend.notification.models.NotificationChannel;
import team.sprintory_backend.notification.models.NotificationType;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.models.Workspace;

/** Who gets told, for every event of docs/notifications-docs.md §5. */
class NotificationListenerTest {

    private static final Long PROJECT_ID = 15L;
    private static final Long TASK_ID = 230L;

    private NotificationService notifications;
    private NotificationEmails emails;
    private NotificationPreferenceService preferences;
    private TaskWatcherService watchers;
    private ProjectMemberRepository projectMembers;
    private UserRepository users;
    private NotificationListener listener;

    private final User damir = user(31L, "Damir", "Gabaidullin");
    private final User ada = user(17L, "Ada", "Lovelace");
    private final User grace = user(18L, "Grace", "Hopper");
    private final User linus = user(19L, "Linus", "Torvalds");
    private final User outsider = user(99L, "Out", "Sider");
    private Workspace workspace;
    private Project project;
    private Task task;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationService.class);
        emails = mock(NotificationEmails.class);
        preferences = mock(NotificationPreferenceService.class);
        watchers = mock(TaskWatcherService.class);
        projectMembers = mock(ProjectMemberRepository.class);
        users = mock(UserRepository.class);
        listener = new NotificationListener(notifications, emails, preferences, watchers, projectMembers, users);

        workspace = Workspace.builder().id(4L).name("Acme").slug("acme").build();
        project = Project.builder().id(PROJECT_ID).workspace(workspace).name("Mobile App").key("MOB").build();
        task = Task.builder().id(TASK_ID).project(project).key("MOB-42").title("Fix login").build();

        // Everyone but the outsider is in the project; every preference is on unless a test says otherwise.
        when(projectMembers.findUserIdsByProjectId(PROJECT_ID)).thenReturn(List.of(31L, 17L, 18L, 19L));
        when(preferences.isEnabled(anyLong(), any(), any())).thenReturn(true);
    }

    private static User user(Long id, String first, String last) {
        return User.builder().id(id).firstName(first).lastName(last).email(first.toLowerCase() + "@example.com").build();
    }

    private void verifyNotified(Long recipientId, NotificationType type, String message) {
        verify(notifications).create(eq(recipientId), eq(type), any(NotificationSubject.class), eq(message), isNull());
    }

    private void verifyNotNotified(Long recipientId) {
        verify(notifications, never()).create(eq(recipientId), any(), any(), any(), any());
    }

    // --- task created / assigned ---

    @Test
    void creatingATaskMakesTheCreatorAWatcherAndNotifiesNobody() {
        listener.on(new TaskCreated(task, damir));

        verify(watchers).autoWatch(task, damir);
        verifyNoInteractions(notifications);
    }

    @Test
    void assigningNotifiesTheAssigneeAndMakesThemAWatcher() {
        listener.on(new TaskAssigned(task, damir, ada));

        verify(watchers).autoWatch(task, ada);
        verifyNotified(17L, NotificationType.TASK_ASSIGNED, "Damir Gabaidullin assigned you MOB-42 \"Fix login\"");
    }

    @Test
    void theNotificationCarriesTheTaskAsItsSubject() {
        listener.on(new TaskAssigned(task, damir, ada));

        verify(notifications).create(eq(17L), eq(NotificationType.TASK_ASSIGNED),
                eq(new NotificationSubject(workspace, project, task, damir)), anyString(), isNull());
    }

    @Test
    void assigningYourselfWatchesButSendsNothing() {
        listener.on(new TaskAssigned(task, ada, ada));

        verify(watchers).autoWatch(task, ada);
        verifyNoInteractions(notifications, emails);
    }

    @Test
    void anAssigneeWhoTurnedOffInAppGetsNoBellEntryButStillTheEmail() {
        when(preferences.isEnabled(17L, NotificationCategory.ASSIGNMENT, NotificationChannel.IN_APP)).thenReturn(false);

        listener.on(new TaskAssigned(task, damir, ada));

        verifyNoInteractions(notifications);
        verify(emails).queue(17L, NotificationType.TASK_ASSIGNED, NotificationSubject.task(task, damir),
                "Damir Gabaidullin assigned you MOB-42 \"Fix login\"", null);
    }

    // --- the email channel ---

    @Test
    void emailOnQueuesTheEmailWithTheSameTextAsTheBell() {
        listener.on(new TaskAssigned(task, damir, ada));

        verify(emails).queue(17L, NotificationType.TASK_ASSIGNED, NotificationSubject.task(task, damir),
                "Damir Gabaidullin assigned you MOB-42 \"Fix login\"", null);
    }

    @Test
    void emailOffForStatusChangesGivesTheBellEntryButNoEmail() {
        // Acceptance criterion: "I turn off email for status change ... in-app but no email".
        when(watchers.watcherIdsOf(TASK_ID)).thenReturn(List.of(17L));
        when(preferences.isEnabled(17L, NotificationCategory.STATUS_CHANGE, NotificationChannel.EMAIL)).thenReturn(false);

        listener.on(new TaskStatusChanged(task, damir, "To Do", "Done"));

        verify(notifications).create(eq(17L), eq(NotificationType.TASK_STATUS_CHANGED), any(), any(), any());
        verifyNoInteractions(emails);
    }

    @Test
    void aMentionEmailCarriesTheExcerpt() {
        listener.on(new Mentioned(task, damir, Set.of(17L), "@Ada Lovelace please check"));

        verify(emails).queue(eq(17L), eq(NotificationType.MENTIONED), any(), any(), eq("@Ada Lovelace please check"));
    }

    @Test
    void anInvitationNeverQueuesAnEmailBecauseTheInvitationEmailIsAlreadySent() {
        when(users.findByEmail("out@example.com")).thenReturn(Optional.of(outsider));

        listener.on(new WorkspaceInvited(workspace, ada, "out@example.com"));

        verify(notifications).create(eq(99L), eq(NotificationType.WORKSPACE_INVITATION), any(), any(), any());
        verifyNoInteractions(emails);
    }

    @Test
    void securityNotificationsAreAlsoEmailed() {
        listener.on(new PasswordReset(ada));

        verify(emails).queue(17L, NotificationType.PASSWORD_RESET, NotificationSubject.system(),
                NotificationMessages.passwordReset(), null);
    }

    // --- status change ---

    @Test
    void aStatusChangeNotifiesWatchersButNotTheActor() {
        when(watchers.watcherIdsOf(TASK_ID)).thenReturn(List.of(31L, 17L, 18L));

        listener.on(new TaskStatusChanged(task, damir, "To Do", "In Progress"));

        String message = "Damir Gabaidullin moved MOB-42 \"Fix login\" from To Do to In Progress";
        verifyNotified(17L, NotificationType.TASK_STATUS_CHANGED, message);
        verifyNotified(18L, NotificationType.TASK_STATUS_CHANGED, message);
        verifyNotNotified(31L);
    }

    @Test
    void aWatcherNoLongerInTheProjectIsSkipped() {
        when(watchers.watcherIdsOf(TASK_ID)).thenReturn(List.of(17L, 99L));

        listener.on(new TaskStatusChanged(task, damir, "To Do", "Done"));

        verifyNotified(17L, NotificationType.TASK_STATUS_CHANGED,
                "Damir Gabaidullin moved MOB-42 \"Fix login\" from To Do to Done");
        verifyNotNotified(99L);
    }

    @Test
    void aStatusChangeWithNoOtherWatchersQueriesNoMembers() {
        when(watchers.watcherIdsOf(TASK_ID)).thenReturn(List.of(31L));

        listener.on(new TaskStatusChanged(task, damir, "To Do", "Done"));

        verifyNoInteractions(notifications);
        verify(projectMembers, never()).findUserIdsByProjectId(anyLong());
    }

    @Test
    void turningOffStatusChangesInAppSilencesOnlyThatPerson() {
        when(watchers.watcherIdsOf(TASK_ID)).thenReturn(List.of(17L, 18L));
        when(preferences.isEnabled(18L, NotificationCategory.STATUS_CHANGE, NotificationChannel.IN_APP))
                .thenReturn(false);

        listener.on(new TaskStatusChanged(task, damir, "To Do", "Done"));

        verify(notifications).create(eq(17L), any(), any(), any(), any());
        verifyNotNotified(18L);
    }

    // --- comments and mentions (PMP-133 / PMP-136 producers) ---

    @Test
    void aCommentMakesTheCommenterAWatcherAndNotifiesTheOtherWatchersWithAnExcerpt() {
        when(watchers.watcherIdsOf(TASK_ID)).thenReturn(List.of(31L, 17L, 18L));

        listener.on(new TaskCommented(task, damir, 7L, "Looks   good\nto me", Set.of()));

        verify(watchers).autoWatch(task, damir);
        String message = "Damir Gabaidullin commented on MOB-42 \"Fix login\"";
        verify(notifications).create(17L, NotificationType.TASK_COMMENTED,
                NotificationSubject.task(task, damir), message, "Looks good to me");
        verify(notifications).create(18L, NotificationType.TASK_COMMENTED,
                NotificationSubject.task(task, damir), message, "Looks good to me");
        verifyNotNotified(31L);
    }

    @Test
    void aMentionedWatcherGetsOnlyTheMention() {
        when(watchers.watcherIdsOf(TASK_ID)).thenReturn(List.of(17L, 18L));

        listener.on(new TaskCommented(task, damir, 7L, "@Ada Lovelace please check", Set.of(17L)));

        verify(notifications).create(eq(17L), eq(NotificationType.MENTIONED), any(),
                eq("Damir Gabaidullin mentioned you in MOB-42 \"Fix login\""), eq("@Ada Lovelace please check"));
        verify(notifications, never()).create(eq(17L), eq(NotificationType.TASK_COMMENTED), any(), any(), any());
        verify(notifications).create(eq(18L), eq(NotificationType.TASK_COMMENTED), any(), any(), any());
    }

    @Test
    void aMentionReachesSomeoneWhoDoesNotWatch() {
        when(watchers.watcherIdsOf(TASK_ID)).thenReturn(List.of());

        listener.on(new TaskCommented(task, damir, 7L, "@Linus Torvalds fyi", Set.of(19L)));

        verify(notifications).create(eq(19L), eq(NotificationType.MENTIONED), any(), any(), eq("@Linus Torvalds fyi"));
    }

    @Test
    void mentioningYourselfOrAnOutsiderSendsNothing() {
        when(watchers.watcherIdsOf(TASK_ID)).thenReturn(List.of());

        listener.on(new TaskCommented(task, damir, 7L, "note", Set.of(31L, 99L)));

        verifyNoInteractions(notifications);
    }

    @Test
    void aMentionInADescriptionNotifiesTheNewlyMentioned() {
        listener.on(new Mentioned(task, damir, Set.of(17L, 18L), "Pairing with @Ada Lovelace and @Grace Hopper"));

        verify(notifications).create(eq(17L), eq(NotificationType.MENTIONED), any(),
                eq("Damir Gabaidullin mentioned you in MOB-42 \"Fix login\""), any());
        verify(notifications).create(eq(18L), eq(NotificationType.MENTIONED), any(), any(), any());
    }

    @Test
    void aMentionWithMentionsTurnedOffInAppCreatesNothing() {
        when(preferences.isEnabled(17L, NotificationCategory.MENTION, NotificationChannel.IN_APP)).thenReturn(false);

        listener.on(new Mentioned(task, damir, Set.of(17L), "hi"));

        verifyNoInteractions(notifications);
    }

    // --- project membership ---

    @Test
    void beingAddedToAProjectNotifiesThatPerson() {
        listener.on(new ProjectMemberAdded(project, damir, ada));

        verify(notifications).create(17L, NotificationType.PROJECT_MEMBER_ADDED,
                NotificationSubject.project(project, damir),
                "Damir Gabaidullin added you to the project \"Mobile App\"", null);
    }

    @Test
    void addingYourselfSendsNothing() {
        listener.on(new ProjectMemberAdded(project, ada, ada));

        verifyNoInteractions(notifications);
    }

    @Test
    void aRoleChangeListsTheNewRolesInTheirFixedOrder() {
        listener.on(new ProjectRolesChanged(project, damir, ada, Set.of(ProjectRole.MEMBER, ProjectRole.PROJECT_MANAGER)));

        verifyNotified(17L, NotificationType.PROJECT_ROLES_CHANGED,
                "Damir Gabaidullin changed your roles in \"Mobile App\" to Project Manager, Member");
    }

    // --- sprints ---

    @Test
    void aStartedSprintNotifiesEveryMemberButTheActor() {
        listener.on(new SprintStarted(project, damir, "MOB Sprint 4"));

        String message = "Damir Gabaidullin started \"MOB Sprint 4\" in \"Mobile App\"";
        verifyNotified(17L, NotificationType.SPRINT_STARTED, message);
        verifyNotified(18L, NotificationType.SPRINT_STARTED, message);
        verifyNotified(19L, NotificationType.SPRINT_STARTED, message);
        verifyNotNotified(31L);
    }

    @Test
    void completedAndCancelledSprintsUseTheirOwnTypes() {
        listener.on(new SprintCompleted(project, damir, "MOB Sprint 4"));
        listener.on(new SprintCancelled(project, damir, "MOB Sprint 5"));

        verifyNotified(17L, NotificationType.SPRINT_COMPLETED,
                "Damir Gabaidullin completed \"MOB Sprint 4\" in \"Mobile App\"");
        verifyNotified(17L, NotificationType.SPRINT_CANCELLED,
                "Damir Gabaidullin cancelled \"MOB Sprint 5\" in \"Mobile App\"");
    }

    // --- invitation ---

    @Test
    void anInvitationNotifiesAnExistingAccountWithoutAProjectFilter() {
        when(users.findByEmail("out@example.com")).thenReturn(Optional.of(outsider));

        listener.on(new WorkspaceInvited(workspace, ada, "out@example.com"));

        verify(notifications).create(99L, NotificationType.WORKSPACE_INVITATION,
                NotificationSubject.workspace(workspace, ada),
                "Ada Lovelace invited you to the workspace \"Acme\". Open the invitation email to join.", null);
        verify(projectMembers, never()).findUserIdsByProjectId(anyLong());
    }

    @Test
    void anInvitationToAnEmailWithoutAnAccountCreatesNothing() {
        when(users.findByEmail("new@example.com")).thenReturn(Optional.empty());

        listener.on(new WorkspaceInvited(workspace, ada, "new@example.com"));

        verifyNoInteractions(notifications);
    }

    // --- security: no actor, so the person is notified about their own account ---

    @Test
    void aPasswordResetNotifiesThePersonThemself() {
        listener.on(new PasswordReset(ada));

        verify(notifications).create(17L, NotificationType.PASSWORD_RESET, NotificationSubject.system(),
                NotificationMessages.passwordReset(), null);
    }

    @Test
    void repeatedLoginFailuresNotifyTheAccountOwnerWithTheCount() {
        listener.on(new RepeatedLoginFailures(ada, 5));

        verifyNotified(17L, NotificationType.LOGIN_FAILED_REPEATED,
                "There were 5 failed sign-in attempts on your account. If this wasn't you, change your password.");
    }
}