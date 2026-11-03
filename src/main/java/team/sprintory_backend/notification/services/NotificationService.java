package team.sprintory_backend.notification.services;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    /** Newest first; the id keeps notifications of the same instant in a stable order. */
    static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final NotificationRepository repository;
    private final UserRepository userRepository;
    private final ProjectAccess projectAccess;
    private final TaskRepository taskRepository;

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
        return repository.save(notification.build());
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
        notification.markRead(Instant.now());

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
        repository.markAllRead(user.getId(), Instant.now());
        return new UnreadCountResponse(0);
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