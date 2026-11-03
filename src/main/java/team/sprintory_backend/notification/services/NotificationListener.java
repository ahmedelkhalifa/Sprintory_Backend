package team.sprintory_backend.notification.services;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
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
import team.sprintory_backend.notification.models.NotificationChannel;
import team.sprintory_backend.notification.models.NotificationType;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.repositories.ProjectMemberRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

/**
 * The Observer side of R27: one synchronous listener per event, running inside the publishing
 * action's transaction. It decides who is told, never the producer (docs/notifications-docs.md §5):
 * <ol>
 *   <li>the actor is never a recipient (security events have no actor);</li>
 *   <li>for project and task events, only people in the project right now;</li>
 *   <li>one notification per person per event: a mention replaces the comment notification;</li>
 *   <li>each recipient's preferences decide the channels.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class NotificationListener {

    private final NotificationService notifications;
    private final NotificationPreferenceService preferences;
    private final TaskWatcherService watchers;
    private final ProjectMemberRepository projectMembers;
    private final UserRepository users;

    // --- tasks ---

    @EventListener
    public void on(TaskCreated event) {
        watchers.autoWatch(event.task(), event.actor());
    }

    @EventListener
    public void on(TaskAssigned event) {
        // Assigning yourself also makes you a watcher; it just sends nothing.
        watchers.autoWatch(event.task(), event.assignee());
        deliver(NotificationType.TASK_ASSIGNED, NotificationSubject.task(event.task(), event.actor()),
                List.of(event.assignee().getId()),
                NotificationMessages.assigned(event.actor(), event.task()), null);
    }

    @EventListener
    public void on(TaskStatusChanged event) {
        deliver(NotificationType.TASK_STATUS_CHANGED, NotificationSubject.task(event.task(), event.actor()),
                watchers.watcherIdsOf(event.task().getId()),
                NotificationMessages.statusChanged(event.actor(), event.task(), event.fromStatus(), event.toStatus()),
                null);
    }

    @EventListener
    public void on(TaskCommented event) {
        watchers.autoWatch(event.task(), event.actor());
        NotificationSubject subject = NotificationSubject.task(event.task(), event.actor());
        String excerpt = NotificationMessages.excerpt(event.plainText());

        deliver(NotificationType.MENTIONED, subject, event.mentionedUserIds(),
                NotificationMessages.mentioned(event.actor(), event.task()), excerpt);

        Set<Long> watching = new LinkedHashSet<>(watchers.watcherIdsOf(event.task().getId()));
        watching.removeAll(event.mentionedUserIds());
        deliver(NotificationType.TASK_COMMENTED, subject, watching,
                NotificationMessages.commented(event.actor(), event.task()), excerpt);
    }

    @EventListener
    public void on(Mentioned event) {
        deliver(NotificationType.MENTIONED, NotificationSubject.task(event.task(), event.actor()),
                event.userIds(), NotificationMessages.mentioned(event.actor(), event.task()),
                NotificationMessages.excerpt(event.plainText()));
    }

    // --- projects ---

    @EventListener
    public void on(ProjectMemberAdded event) {
        deliver(NotificationType.PROJECT_MEMBER_ADDED, NotificationSubject.project(event.project(), event.actor()),
                List.of(event.member().getId()), NotificationMessages.memberAdded(event.actor(), event.project()),
                null);
    }

    @EventListener
    public void on(ProjectRolesChanged event) {
        deliver(NotificationType.PROJECT_ROLES_CHANGED, NotificationSubject.project(event.project(), event.actor()),
                List.of(event.member().getId()),
                NotificationMessages.rolesChanged(event.actor(), event.project(), event.roles()), null);
    }

    // --- sprints (PMP-119, PMP-122) ---

    @EventListener
    public void on(SprintStarted event) {
        notifyProject(NotificationType.SPRINT_STARTED, event.project(), event.actor(),
                NotificationMessages.sprintStarted(event.actor(), event.project(), event.sprintName()));
    }

    @EventListener
    public void on(SprintCompleted event) {
        notifyProject(NotificationType.SPRINT_COMPLETED, event.project(), event.actor(),
                NotificationMessages.sprintCompleted(event.actor(), event.project(), event.sprintName()));
    }

    @EventListener
    public void on(SprintCancelled event) {
        notifyProject(NotificationType.SPRINT_CANCELLED, event.project(), event.actor(),
                NotificationMessages.sprintCancelled(event.actor(), event.project(), event.sprintName()));
    }

    // --- workspace and security: no project, so no member filter ---

    @EventListener
    public void on(WorkspaceInvited event) {
        // An email without an account gets only the invitation email, sent by the invite action.
        users.findByEmail(event.email()).ifPresent(invitee ->
                deliver(NotificationType.WORKSPACE_INVITATION,
                        NotificationSubject.workspace(event.workspace(), event.inviter()),
                        List.of(invitee.getId()), NotificationMessages.invited(event.inviter(), event.workspace()),
                        null));
    }

    @EventListener
    public void on(PasswordReset event) {
        deliver(NotificationType.PASSWORD_RESET, NotificationSubject.system(), List.of(event.user().getId()),
                NotificationMessages.passwordReset(), null);
    }

    @EventListener
    public void on(RepeatedLoginFailures event) {
        deliver(NotificationType.LOGIN_FAILED_REPEATED, NotificationSubject.system(),
                List.of(event.user().getId()), NotificationMessages.loginFailures(event.failedAttempts()), null);
    }

    // --- delivery ---

    private void notifyProject(NotificationType type, Project project, User actor, String message) {
        deliver(type, NotificationSubject.project(project, actor),
                projectMembers.findUserIdsByProjectId(project.getId()), message, null);
    }

    private void deliver(NotificationType type, NotificationSubject subject, Collection<Long> candidates,
                         String message, String excerpt) {
        Set<Long> recipients = new LinkedHashSet<>(candidates);
        if (subject.actor() != null) {
            recipients.remove(subject.actor().getId());
        }
        if (recipients.isEmpty()) {
            return;
        }
        if (subject.project() != null) {
            recipients.retainAll(projectMembers.findUserIdsByProjectId(subject.project().getId()));
        }
        for (Long recipientId : recipients) {
            if (preferences.isEnabled(recipientId, type.category(), NotificationChannel.IN_APP)) {
                notifications.create(recipientId, type, subject, message, excerpt);
            }
        }
    }
}