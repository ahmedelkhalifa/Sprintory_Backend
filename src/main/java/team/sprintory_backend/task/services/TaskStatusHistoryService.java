package team.sprintory_backend.task.services;

import lombok.RequiredArgsConstructor;
import team.sprintory_backend.attachment.models.TaskAttachment;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.audit.models.AuditTarget;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.notification.events.TaskStatusChanged;
import team.sprintory_backend.project.models.ProjectBoardColumn;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.task.api.TaskHistoryOrder;
import team.sprintory_backend.task.dto.TaskHistoryEntryResponse;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.models.TaskHistoryEntry;
import team.sprintory_backend.task.models.TaskHistoryField;
import team.sprintory_backend.task.models.TaskHistoryType;
import team.sprintory_backend.task.repositories.TaskHistoryRepository;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.user.models.User;

import java.time.Instant;
import java.util.Objects;

/**
 * The single writer and reader of a task's activity feed (R10, docs/task-history-docs.md §5).
 * The name is kept from PMP-125, when the feed held status moves only.
 *
 * <p>Every writer runs inside the action's own transaction ({@code MANDATORY}), after the change
 * succeeded, so a rollback removes the entry too. Values are display snapshots (names, not
 * ids); comment text, descriptions, tokens and links are never stored.
 */
@Service
@RequiredArgsConstructor
public class TaskStatusHistoryService {

    private final TaskHistoryRepository historyRepository;
    private final ProjectAccess projectAccess;
    private final TaskRepository taskRepository;
    private final ApplicationEventPublisher events;

    /**
     * A move to another column, and the watchers are told (R27). Every status change comes
     * through here: task and subtask edit, board drag and column delete.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Task task, ProjectBoardColumn from, ProjectBoardColumn to, User actor) {
        if (!from.getId().equals(to.getId())) {
            save(task, TaskHistoryType.FIELD_CHANGED, TaskHistoryField.STATUS,
                    from.getName(), to.getName(), null, actor);
            events.publishEvent(new TaskStatusChanged(task, actor, from.getName(), to.getName()));
        }
    }

    /** The task or subtask was created. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCreated(Task task, User actor) {
        save(task, TaskHistoryType.CREATED, null, null, null, null, actor);
    }

    /**
     * One field changed from {@code oldValue} to {@code newValue}, both as displayed. Writes
     * nothing when they are equal. For {@link TaskHistoryField#DESCRIPTION} pass the old and new
     * texts so the change can be detected; they are not stored. Use {@link #record} for status.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordChange(Task task, TaskHistoryField field, String oldValue, String newValue, User actor) {
        if (field == TaskHistoryField.STATUS) {
            throw new IllegalArgumentException("Status changes go through record(task, from, to, actor).");
        }
        if (Objects.equals(oldValue, newValue)) {
            return;
        }
        boolean withText = field != TaskHistoryField.DESCRIPTION;
        save(task, TaskHistoryType.FIELD_CHANGED, field,
                withText ? oldValue : null, withText ? newValue : null, null, actor);
    }

    /**
     * One entry per field that differs between {@code before} and {@code after}, in a fixed order.
     * Used by task and subtask edits; status moves are recorded separately through {@link #record}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordChanges(Task task, TaskSnapshot before, TaskSnapshot after, User actor) {
        recordChange(task, TaskHistoryField.TITLE, before.title(), after.title(), actor);
        recordChange(task, TaskHistoryField.TYPE, before.type(), after.type(), actor);
        recordChange(task, TaskHistoryField.PRIORITY, before.priority(), after.priority(), actor);
        recordChange(task, TaskHistoryField.ASSIGNEE, before.assignee(), after.assignee(), actor);
        recordChange(task, TaskHistoryField.ESTIMATE, before.estimate(), after.estimate(), actor);
        recordChange(task, TaskHistoryField.ESTIMATE_MINUTES, before.estimateMinutes(), after.estimateMinutes(), actor);
        recordChange(task, TaskHistoryField.LABELS, before.labels(), after.labels(), actor);
        recordChange(task, TaskHistoryField.DESCRIPTION, before.description(), after.description(), actor);
    }

    /** A comment of the task was deleted; its text is never copied. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCommentDeleted(Task task, Long commentId, User actor) {
        save(task, TaskHistoryType.COMMENT_DELETED, null, null, null, commentId, actor);
    }

    /** On the parent: a subtask was created, or a subtask deleted on its own was restored. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSubtaskAdded(Task parent, Task subtask, User actor) {
        save(parent, TaskHistoryType.SUBTASK_ADDED, null, null, label(subtask), subtask.getId(), actor);
    }

    /** On the parent: one of its subtasks was deleted on its own. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSubtaskRemoved(Task parent, Task subtask, User actor) {
        save(parent, TaskHistoryType.SUBTASK_REMOVED, null, label(subtask), null, subtask.getId(), actor);
    }

    /** A file was attached (R25); the entry holds its file name, never a link. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAttachmentAdded(Task task, TaskAttachment attachment, User actor) {
        save(task, TaskHistoryType.ATTACHMENT_ADDED, null, null, attachment.getFileName(), attachment.getId(), actor);
    }

    /** An attachment was deleted, by its uploader or the Project Manager (R25). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAttachmentRemoved(Task task, TaskAttachment attachment, User actor) {
        save(task, TaskHistoryType.ATTACHMENT_REMOVED, null, attachment.getFileName(), null, attachment.getId(), actor);
    }

    /** A sprint membership change; null from/to means product backlog. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSprintScopeChanged(Task task, String from, String to, User actor, Long sprintId) {
        if (!Objects.equals(from, to)) {
            save(task, TaskHistoryType.SPRINT_SCOPE_CHANGED, null, from, to, sprintId, actor);
        }
    }

    /** The task or subtask was restored after a delete (PMP-131). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordRestored(Task task, User actor) {
        save(task, TaskHistoryType.RESTORED, null, null, null, null, actor);
    }

    /**
     * One page of the task's feed in the given order (newest first by default). Needs VIEW_WORK;
     * a task the caller cannot see is 404. Any sort on {@code pageable} is replaced by the order's.
     */
    @Transactional(readOnly = true)
    public PageResponse<TaskHistoryEntryResponse> list(User user, Long projectId, Long taskId,
                                                       TaskHistoryOrder order, Pageable pageable) {
        projectAccess.require(projectId, user, ProjectPermission.VIEW_WORK);
        taskRepository.findByIdAndProjectId(taskId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found."));
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), order.sort());
        return PageResponse.from(historyRepository.findByTaskId(taskId, sorted)
                .map(TaskHistoryEntryResponse::from));
    }

    /** How a subtask is shown in its parent's feed, e.g. {@code MOB-57 Write tests}. */
    private static String label(Task subtask) {
        return subtask.getKey() + " " + subtask.getTitle();
    }

    private void save(Task task, TaskHistoryType type, TaskHistoryField field, String oldValue,
                      String newValue, Long refId, User actor) {
        historyRepository.save(TaskHistoryEntry.builder()
                .task(task)
                .type(type)
                .field(field)
                .oldValue(oldValue)
                .newValue(newValue)
                .refId(refId)
                .actorId(actor.getId())
                .actorName(AuditTarget.displayName(actor))
                .occurredAt(Instant.now())
                .build());
    }
}