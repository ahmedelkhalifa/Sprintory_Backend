package team.sprintory_backend.notification.services;

import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.audit.models.AuditTarget;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.notification.dto.NotificationResponse;
import team.sprintory_backend.notification.dto.NotificationTargetResponse;
import team.sprintory_backend.notification.dto.UnreadCountResponse;
import team.sprintory_backend.notification.events.NotificationCreated;
import team.sprintory_backend.notification.events.UnreadCountChanged;
import team.sprintory_backend.notification.models.Notification;
import team.sprintory_backend.notification.models.NotificationType;
import team.sprintory_backend.notification.repositories.NotificationRepository;
import team.sprintory_backend.notification.repositories.NotificationSpecifications;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;

/**
 * In-app notifications (R27): written by {@link NotificationListener} inside the action that
 * caused them, read through the bell. Everything is scoped to the caller: someone else's
 * notification is "not found". Contract: docs/notifications-docs.md.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Newest first; the id keeps notifications of the same instant in a stable order. */
    static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final NotificationRepository repository;
    private final UserRepository userRepository;
    private final ProjectAccess projectAccess;
    private final TaskRepository taskRepository;
    private final ApplicationEventPublisher events;

    // Not final: @Value on a final field with @RequiredArgsConstructor fails at startup.
    @Value("${app.notifications.retention-days}")
    private int retentionDays;

    /**
     * Stores one notification with name snapshots taken from {@code subject}. MANDATORY: calling it
     * outside a transaction is an error, not a silently separate commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Notification create(Long recipientId, NotificationType type, NotificationSubject subject,
                               String message, String excerpt) {
        Notification.NotificationBuilder notification = Notification.builder()
                .recipient(userRepository.getReferenceById(recipientId))
                .type(type)
                .message(NotificationMessages.limit(message, NotificationMessages.MESSAGE_LIMIT))
                .excerpt(excerpt)
                .createdAt(Instant.now());
        if (subject.workspace() != null) {
            notification.workspace(subject.workspace()).workspaceName(subject.workspace().getName());
        }
        if (subject.project() != null) {
            notification.projectId(subject.project().getId())
                    .projectKey(subject.project().getKey())
                    .projectName(subject.project().getName());
        }
        if (subject.task() != null) {
            notification.taskId(subject.task().getId())
                    .taskKey(subject.task().getKey())
                    .taskTitle(subject.task().getTitle());
        }
        if (subject.actor() != null) {
            notification.actorId(subject.actor().getId())
                    .actorName(AuditTarget.displayName(subject.actor()));
        }
        Notification saved = repository.save(notification.build());
        // Pushed to the recipient's open tabs only after this transaction commits.
        events.publishEvent(new NotificationCreated(recipientId, NotificationResponse.from(saved)));
        return saved;
    }

    /**
     * One page of the caller's notifications, newest first. Notifications from projects the
     * caller can no longer see are still listed; opening them answers 404.
     */
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(User user, boolean unreadOnly, Long workspaceId, Pageable pageable) {
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST);
        return PageResponse.from(repository
                .findAll(NotificationSpecifications.forRecipient(user.getId(), unreadOnly, workspaceId), sorted)
                .map(NotificationResponse::from));
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(User user) {
        return new UnreadCountResponse(repository.countByRecipientIdAndReadAtIsNull(user.getId()));
    }

    /**
     * Marks the notification read, then says where to go. Access is checked now, not when it
     * was created: a task or project the caller can no longer see answers 404, and the
     * notification stays read (the click still counts), hence no rollback for that 404.
     */
    @Transactional(noRollbackFor = ResourceNotFoundException.class)
    public NotificationTargetResponse open(User user, Long notificationId) {
        Notification notification = repository.findByIdAndRecipientId(notificationId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found."));
        if (!notification.isRead()) {
            notification.markRead(Instant.now());
            // Commits even when the 404 below is thrown, so the other tabs still get the new count.
            events.publishEvent(new UnreadCountChanged(user.getId()));
        }

        Long workspaceId = (notification.getWorkspace() == null) ? null : notification.getWorkspace().getId();
        if (notification.getTaskId() != null) {
            requireTaskVisible(user, notification.getProjectId(), notification.getTaskId());
            return NotificationTargetResponse.task(workspaceId, notification.getProjectId(),
                    notification.getTaskId(), notification.getTaskKey());
        }
        if (notification.getProjectId() != null) {
            projectAccess.requireVisible(notification.getProjectId(), user);
            return NotificationTargetResponse.project(workspaceId, notification.getProjectId());
        }
        return NotificationTargetResponse.none();
    }

    /** Marks every unread notification of the caller read, in all workspaces. */
    @Transactional
    public UnreadCountResponse readAll(User user) {
        if (repository.markAllRead(user.getId(), Instant.now()) > 0) {
            events.publishEvent(new UnreadCountChanged(user.getId()));
        }
        return new UnreadCountResponse(0);
    }

    /**
     * Retention (a team choice, docs/notifications-docs.md §14): notifications older than
     * {@code app.notifications.retention-days}, read or not, are deleted once a night.
     */
    @Scheduled(cron = "0 50 3 * * *")
    @Transactional
    public void deleteExpired() {
        int deleted = repository.deleteCreatedBefore(Instant.now().minus(Duration.ofDays(retentionDays)));
        if (deleted > 0) {
            log.info("Deleted {} notifications older than {} days", deleted, retentionDays);
        }
    }

    /**
     * The same rule as every task endpoint (VIEW_WORK, task in the project, not deleted), but a
     * 403 becomes 404 here: from a notification, "no longer yours" and "gone" look the same.
     */
    private void requireTaskVisible(User user, Long projectId, Long taskId) {
        try {
            projectAccess.require(projectId, user, ProjectPermission.VIEW_WORK);
        } catch (AccessDeniedException e) {
            throw new ResourceNotFoundException("Task not found.");
        }
        taskRepository.findByIdAndProjectId(taskId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found."));
    }
}