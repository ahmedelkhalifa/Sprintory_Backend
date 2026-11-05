package team.sprintory_backend.notification.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import team.sprintory_backend.notification.email.EmailTemplateFactory;
import team.sprintory_backend.notification.email.EmailTemplateRenderer;
import team.sprintory_backend.notification.email.RenderedEmail;
import team.sprintory_backend.notification.models.NotificationType;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

/**
 * Builds the email of one notification (docs/notifications-docs.md §10) and puts it in the outbox:
 * a short summary, a button to the item and a link to the preferences page; never a full comment
 * and never a token. Called by {@link NotificationListener} inside the action's transaction.
 */
@Component
public class NotificationEmails {

    private static final Logger log = LoggerFactory.getLogger(NotificationEmails.class);
    static final int SUBJECT_LIMIT = 255;

    private final UserRepository users;
    private final EmailTemplateFactory templates;
    private final EmailTemplateRenderer renderer;
    private final EmailOutboxService outbox;

    // Not final: @Value on a final field with @RequiredArgsConstructor fails at startup.
    @Value("${app.frontend-url}")
    private String frontendUrl;
    @Value("${app.notifications.task-path}")
    private String taskPath;
    @Value("${app.notifications.project-path}")
    private String projectPath;
    @Value("${app.notifications.preferences-path}")
    private String preferencesPath;

    public NotificationEmails(UserRepository users, EmailTemplateFactory templates, EmailTemplateRenderer renderer,
                              EmailOutboxService outbox) {
        this.users = users;
        this.templates = templates;
        this.renderer = renderer;
        this.outbox = outbox;
    }

    /**
     * Queues the email for one recipient. If it cannot be built (it never should), the problem is
     * logged and the email skipped: the action and the bell entry still stand.
     */
    public void queue(Long recipientId, NotificationType type, NotificationSubject subject, String message,
                      String excerpt) {
        User recipient = users.findById(recipientId).orElse(null);
        if (recipient == null) {
            return;
        }
        RenderedEmail email;
        try {
            String body = (excerpt == null) ? message : message + "\n\n\"" + excerpt + "\"";
            email = renderer.render(templates.notification(recipient.getFirstName(), subjectLine(type, subject), body,
                    actionLabel(subject), actionUrl(subject), frontendUrl + preferencesPath));
        } catch (RuntimeException e) {
            log.warn("Could not build the {} email for user {}: {}", type, recipientId, e.toString());
            return;
        }
        outbox.enqueue(recipient.getEmail(), email);
    }

    /** {@code [Sprintory] MOB-42 Fix login: you were assigned}; one line, at most 255 characters. */
    static String subjectLine(NotificationType type, NotificationSubject subject) {
        String line = switch (type) {
            case TASK_ASSIGNED -> task(subject) + ": you were assigned";
            case TASK_STATUS_CHANGED -> task(subject) + ": status changed";
            case TASK_COMMENTED -> task(subject) + ": new comment";
            case MENTIONED -> task(subject) + ": you were mentioned";
            case PROJECT_MEMBER_ADDED -> "You were added to " + subject.project().getName();
            case PROJECT_ROLES_CHANGED -> "Your roles in " + subject.project().getName() + " changed";
            case SPRINT_STARTED -> "A sprint started in " + subject.project().getName();
            case SPRINT_COMPLETED -> "A sprint was completed in " + subject.project().getName();
            case SPRINT_CANCELLED -> "A sprint was cancelled in " + subject.project().getName();
            case WORKSPACE_INVITATION -> "You were invited to " + subject.workspace().getName();
            case PASSWORD_RESET -> "Your password was changed";
            case LOGIN_FAILED_REPEATED -> "Failed sign-in attempts on your account";
        };
        // A newline in a subject would let text inject mail headers; titles are user input.
        String oneLine = ("[Sprintory] " + line).replaceAll("[\\r\\n]+", " ");
        return NotificationMessages.limit(oneLine, SUBJECT_LIMIT);
    }

    private static String task(NotificationSubject subject) {
        return subject.task().getKey() + " " + subject.task().getTitle();
    }

    private static String actionLabel(NotificationSubject subject) {
        if (subject.task() != null) return "Open task";
        if (subject.project() != null) return "Open project";
        return "Open Sprintory";
    }

    /** The frontend route of the item; {workspaceId}, {projectId}, {taskId} and {taskKey} are filled in. */
    private String actionUrl(NotificationSubject subject) {
        if (subject.task() != null) {
            return frontendUrl + fill(taskPath, subject)
                    .replace("{taskId}", String.valueOf(subject.task().getId()))
                    .replace("{taskKey}", subject.task().getKey());
        }
        if (subject.project() != null) {
            return frontendUrl + fill(projectPath, subject);
        }
        return frontendUrl;
    }

    private static String fill(String path, NotificationSubject subject) {
        return path.replace("{workspaceId}", String.valueOf(subject.project().getWorkspace().getId()))
                .replace("{projectId}", String.valueOf(subject.project().getId()));
    }
}