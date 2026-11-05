package team.sprintory_backend.comment.services;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.audit.models.AuditTarget;
import team.sprintory_backend.comment.dto.CommentRequest;
import team.sprintory_backend.comment.dto.CommentResponse;
import team.sprintory_backend.comment.models.TaskComment;
import team.sprintory_backend.comment.repositories.TaskCommentRepository;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.common.richtext.InvalidRichTextException;
import team.sprintory_backend.common.richtext.RichTextDocument;
import team.sprintory_backend.common.richtext.RichTextMention;
import team.sprintory_backend.common.richtext.RichTextParser;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.mention.services.MentionService;
import team.sprintory_backend.notification.events.Mentioned;
import team.sprintory_backend.notification.events.TaskCommented;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.services.TaskStatusHistoryService;
import team.sprintory_backend.team.models.Team;
import team.sprintory_backend.team.repositories.TeamRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import team.sprintory_backend.workspace.services.WorkspaceAccess;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Comments on tasks (R10, PMP-133). Contract: docs/comments-docs.md.
 *
 * <p>Reading needs VIEW_WORK; posting, and editing or deleting one's own comment, need COMMENT
 * in an active workspace; deleting anyone's comment needs MODERATE_COMMENTS (the Project
 * Manager). Nobody edits someone else's comment. Deleting is soft and writes a history entry
 * without the text. Mentions follow docs/mentions-docs.md §4.
 */
@Service
@RequiredArgsConstructor
public class CommentService {

    /** At most this many characters of plain text, mentions counted as {@code @Name}. */
    public static final int MAX_LENGTH = 10_000;

    private final ProjectAccess projectAccess;
    private final TaskRepository taskRepository;
    private final TaskCommentRepository commentRepository;
    private final RichTextParser richText;
    private final MentionService mentions;
    private final TaskStatusHistoryService history;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final ApplicationEventPublisher events;

    /** One page of the task's thread, oldest first, deleted comments included as placeholders. */
    @Transactional(readOnly = true)
    public PageResponse<CommentResponse> list(User user, Long projectId, Long taskId, Pageable pageable) {
        ProjectAccessResult access = projectAccess.require(projectId, user, ProjectPermission.VIEW_WORK);
        requireTask(projectId, taskId);
        Page<TaskComment> page = commentRepository.findByTaskIdOrderByCreatedAtAscIdAsc(taskId, pageable);

        Map<Long, RichTextDocument> bodies = new HashMap<>();
        for (TaskComment comment : page.getContent()) {
            if (!comment.isDeleted()) {
                bodies.put(comment.getId(), richText.parse(comment.getBody()));
            }
        }
        Map<RichTextMention, String> names = currentNames(bodies.values());
        return PageResponse.from(page.map(comment ->
                response(comment, bodies.get(comment.getId()), names, access, user)));
    }

    /**
     * Posts a comment. The author starts watching the task; the watchers get TASK_COMMENTED and
     * the mentioned people MENTIONED (the notification listener decides, see R27).
     */
    @Transactional
    public CommentResponse create(User user, Long projectId, Long taskId, CommentRequest request) {
        ProjectAccessResult access = projectAccess.requireActive(projectId, user, ProjectPermission.COMMENT);
        Task task = requireTask(projectId, taskId);
        RichTextDocument body = parseBody(request.body());
        Set<Long> mentioned = mentions.newlyMentionedUserIds(access.project(), body, null);

        TaskComment comment = commentRepository.save(TaskComment.builder()
                .task(task).author(user).body(body.json()).build());
        events.publishEvent(new TaskCommented(task, user, comment.getId(), body.plainText(), mentioned));
        return response(comment, body, currentNames(List.of(body)), access, user);
    }

    /**
     * The author replaces the body. The same text again changes nothing; otherwise the comment is
     * marked edited and only newly added mentions are told. Watchers are not told about edits.
     */
    @Transactional
    public CommentResponse update(User user, Long projectId, Long taskId, Long commentId, CommentRequest request) {
        ProjectAccessResult access = projectAccess.requireActive(projectId, user, ProjectPermission.COMMENT);
        Task task = requireTask(projectId, taskId);
        TaskComment comment = requireComment(taskId, commentId);
        if (!comment.isWrittenBy(user)) {
            throw new AccessDeniedException("Only the author can edit a comment.");
        }
        RichTextDocument body = parseBody(request.body());
        if (!body.json().equals(comment.getBody())) {
            Set<Long> mentioned = mentions.newlyMentionedUserIds(access.project(), body,
                    richText.parse(comment.getBody()));
            comment.setBody(body.json());
            comment.setEditedAt(Instant.now());
            commentRepository.save(comment);
            if (!mentioned.isEmpty()) {
                events.publishEvent(new Mentioned(task, user, mentioned, body.plainText()));
            }
        }
        return response(comment, body, currentNames(List.of(body)), access, user);
    }

    /**
     * Soft delete by the author (with COMMENT) or the Project Manager (MODERATE_COMMENTS). The
     * placeholder stays in the thread, and the task's history says who deleted it, without the text.
     */
    @Transactional
    public void delete(User user, Long projectId, Long taskId, Long commentId) {
        ProjectAccessResult access = projectAccess.require(projectId, user, ProjectPermission.VIEW_WORK);
        Task task = requireTask(projectId, taskId);
        TaskComment comment = requireComment(taskId, commentId);
        if (!mayDelete(comment, access, user)) {
            throw new AccessDeniedException("Only the author or the Project Manager can delete a comment.");
        }
        WorkspaceAccess.requireNotArchived(access.workspaceMember());

        comment.setDeletedAt(Instant.now());
        comment.setDeletedById(user.getId());
        commentRepository.save(comment);
        history.recordCommentDeleted(task, comment.getId(), user);
    }

    // --- rules ---

    private static boolean mayEdit(TaskComment comment, ProjectAccessResult access, User user) {
        return !comment.isDeleted() && comment.isWrittenBy(user) && access.can(ProjectPermission.COMMENT);
    }

    private static boolean mayDelete(TaskComment comment, ProjectAccessResult access, User user) {
        return !comment.isDeleted() && (access.can(ProjectPermission.MODERATE_COMMENTS)
                || comment.isWrittenBy(user) && access.can(ProjectPermission.COMMENT));
    }

    /** Not empty, at most {@value #MAX_LENGTH} characters of text; the format is checked by the parser. */
    private RichTextDocument parseBody(String json) {
        RichTextDocument body = richText.parse(json);
        if (body.isEmpty()) {
            throw new InvalidRichTextException("A comment cannot be empty.");
        }
        if (body.plainText().length() > MAX_LENGTH) {
            throw new InvalidRichTextException("A comment can have at most " + MAX_LENGTH + " characters.");
        }
        return body;
    }

    /** Missing, deleted and other projects' tasks all give the same 404. */
    private Task requireTask(Long projectId, Long taskId) {
        return taskRepository.findByIdAndProjectId(taskId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found."));
    }

    /** Missing, deleted and other tasks' comments all give the same 404. */
    private TaskComment requireComment(Long taskId, Long commentId) {
        return commentRepository.findByIdAndTaskId(commentId, taskId)
                .filter(comment -> !comment.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Comment not found."));
    }

    // --- responses ---

    /** The current names of everyone and every team mentioned in these bodies, in two queries. */
    private Map<RichTextMention, String> currentNames(Iterable<RichTextDocument> bodies) {
        Set<Long> userIds = new HashSet<>();
        Set<Long> teamIds = new HashSet<>();
        for (RichTextDocument body : bodies) {
            userIds.addAll(body.userIds());
            teamIds.addAll(body.teamIds());
        }
        Map<RichTextMention, String> names = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (User mentioned : userRepository.findAllById(userIds)) {
                names.put(RichTextMention.user(mentioned.getId()), AuditTarget.displayName(mentioned));
            }
        }
        if (!teamIds.isEmpty()) {
            for (Team team : teamRepository.findAllById(teamIds)) {
                names.put(RichTextMention.team(team.getId()), team.getName());
            }
        }
        return names;
    }

    /** {@code body} is null for a deleted comment. */
    private static CommentResponse response(TaskComment comment, RichTextDocument body,
                                            Map<RichTextMention, String> names,
                                            ProjectAccessResult access, User user) {
        boolean active = !archived(access);
        List<CommentResponse.MentionName> mentioned = new ArrayList<>();
        if (body != null) {
            for (RichTextMention mention : body.mentions()) {
                mentioned.add(new CommentResponse.MentionName(mention.kind().json(), mention.id(), names.get(mention)));
            }
        }
        User author = comment.getAuthor();
        return new CommentResponse(comment.getId(), comment.getTask().getId(),
                author == null ? null : new CommentResponse.Author(author.getId(), AuditTarget.displayName(author)),
                comment.isDeleted() ? null : comment.getBody(),
                List.copyOf(mentioned),
                toInstant(comment.getCreatedAt()),
                comment.getEditedAt(),
                comment.isDeleted(),
                comment.getDeletedAt(),
                active && mayEdit(comment, access, user),
                active && mayDelete(comment, access, user));
    }

    private static boolean archived(ProjectAccessResult access) {
        return access.workspaceMember() != null && access.workspaceMember().getWorkspace().isArchived();
    }

    /** The audit columns are local date-times written in the server's zone. */
    private static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }
}