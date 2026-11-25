package team.sprintory_backend.retro.services;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.comment.dto.CommentRequest;
import team.sprintory_backend.comment.dto.CommentResponse;
import team.sprintory_backend.common.richtext.InvalidRichTextException;
import team.sprintory_backend.common.richtext.RichTextDocument;
import team.sprintory_backend.common.richtext.RichTextMention;
import team.sprintory_backend.common.richtext.RichTextParser;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.mention.services.MentionService;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectLifecycleState;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.repositories.ProjectRepository;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.retro.dto.RetroActionRequest;
import team.sprintory_backend.retro.dto.RetroActionResponse;
import team.sprintory_backend.retro.dto.RetroItemRequest;
import team.sprintory_backend.retro.dto.RetroItemResponse;
import team.sprintory_backend.retro.dto.RetroResponse;
import team.sprintory_backend.retro.dto.RetroUser;
import team.sprintory_backend.retro.dto.RetroVoteResponse;
import team.sprintory_backend.retro.exceptions.RetroConflictException;
import team.sprintory_backend.retro.models.RetroAction;
import team.sprintory_backend.retro.models.RetroItem;
import team.sprintory_backend.retro.models.RetroStatus;
import team.sprintory_backend.retro.models.RetroVote;
import team.sprintory_backend.retro.models.Retrospective;
import team.sprintory_backend.retro.repositories.RetroActionRepository;
import team.sprintory_backend.retro.repositories.RetroItemRepository;
import team.sprintory_backend.retro.repositories.RetroVoteRepository;
import team.sprintory_backend.retro.repositories.RetrospectiveRepository;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.sprint.models.SprintStatus;
import team.sprintory_backend.sprint.repositories.SprintRepository;
import team.sprintory_backend.task.dto.CreateTaskRequest;
import team.sprintory_backend.task.dto.TaskResponse;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.models.TaskType;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.task.services.TaskApiService;
import team.sprintory_backend.user.models.User;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Sprint retrospectives (R31, PMP-44); the rules of docs/retrospectives-docs.md live here.
 * Every write goes through {@link #writable}. Opening takes the project row lock; changes inside a
 * retro take the retro row lock ({@code RetrospectiveRepository.lockById}) before reading the cards.
 */
@Service
@RequiredArgsConstructor
public class RetroService {

    public static final int VOTES_PER_MEMBER = 3;
    /** At most this many characters of plain text per card, mentions counted as {@code @Name}. */
    public static final int CARD_MAX_LENGTH = 1_000;
    /** Every action task carries this label, so the board and the backlog can filter them. */
    public static final String ACTION_LABEL = "retro-action";

    private final RetrospectiveRepository retros;
    private final RetroItemRepository items;
    private final RetroVoteRepository votes;
    private final RetroActionRepository actions;
    private final SprintRepository sprints;
    private final ProjectRepository projects;
    private final TaskRepository tasks;
    private final TaskApiService taskApi;
    private final ProjectAccess access;
    private final RichTextParser richText;
    private final MentionService mentions;

    /** The result of {@link #open}: {@code created} decides between 201 and 200. */
    public record Opened(RetroResponse retro, boolean created) {
    }

    /**
     * Opens the retro of an ended sprint, or returns the one it already has (idempotent, §3.1).
     * The project row lock makes two simultaneous calls end in the same retro; the unique
     * constraint on {@code sprint_id} is the backstop.
     */
    @Transactional
    public Opened open(User actor, Long sprintId) {
        Long projectId = sprints.findProjectId(sprintId)
                .orElseThrow(() -> new ResourceNotFoundException("Sprint not found."));
        ProjectAccessResult checked = writable(actor, projectId, ProjectPermission.RETRO_MANAGE, "Sprint not found.");
        Project project = projects.lockById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Sprint not found."));
        Sprint sprint = sprints.findById(sprintId)
                .orElseThrow(() -> new ResourceNotFoundException("Sprint not found."));
        if (!sprint.getStatus().isEnded()) {
            throw RetroConflictException.sprintNotEnded();
        }
        Optional<Retrospective> existing = retros.findBySprintId(sprintId);
        if (existing.isPresent()) {
            return new Opened(response(existing.get(), actor, checked), false);
        }
        Retrospective retro = retros.save(
                new Retrospective(project, sprint, sprint.getName() + " Retrospective", actor));
        return new Opened(response(retro, actor, checked), true);
    }

    /** The whole board (§3.2). Readable in archived workspaces and completed or archived projects. */
    @Transactional(readOnly = true)
    public RetroResponse get(User actor, Long retroId) {
        Retrospective retro = retros.findById(retroId)
                .orElseThrow(() -> new ResourceNotFoundException("Retrospective not found."));
        ProjectAccessResult checked = readable(actor, retro.getProject().getId(), "Retrospective not found.");
        return response(retro, actor, checked);
    }

    /**
     * Adds a card (§3.3). The retro row is locked first, so a card never lands in a retro that is
     * being closed. New mentions are checked before saving; they are notified from step 8 on.
     */
    @Transactional
    public RetroItemResponse addItem(User actor, Long retroId, RetroItemRequest request) {
        Retrospective retro = retros.lockById(retroId)
                .orElseThrow(() -> new ResourceNotFoundException("Retrospective not found."));
        ProjectAccessResult checked = writable(actor, retro.getProject().getId(),
                ProjectPermission.RETRO_CONTRIBUTE, "Retrospective not found.");
        if (retro.isClosed()) {
            throw RetroConflictException.closed();
        }
        RichTextDocument body = parseCard(request.body());
        mentions.newlyMentionedUserIds(checked.project(), body, null);
        RetroItem item = items.save(new RetroItem(retro, request.column(), body.json(), actor));
        return itemResponse(item, body, mentions.currentNames(List.of(body)), List.of(), actor,
                true, checked.can(ProjectPermission.RETRO_MANAGE));
    }

    /**
     * The author replaces the text (§3.4); nobody else, not even a manager. The same text again
     * changes nothing; otherwise the card is marked edited and only new mentions are checked.
     */
    @Transactional
    public RetroItemResponse editItem(User actor, Long itemId, CommentRequest request) {
        RetroItem item = lockedItem(itemId);
        Retrospective retro = item.getRetro();
        ProjectAccessResult checked = writable(actor, retro.getProject().getId(),
                ProjectPermission.RETRO_CONTRIBUTE, "Card not found.");
        if (retro.isClosed()) {
            throw RetroConflictException.closed();
        }
        if (!item.isWrittenBy(actor)) {
            throw new AccessDeniedException("Only the author can edit a card.");
        }
        RichTextDocument body = parseCard(request.body());
        if (!body.json().equals(item.getBody())) {
            mentions.newlyMentionedUserIds(checked.project(), body, richText.parse(item.getBody()));
            item.setBody(body.json());
            item.setEditedAt(Instant.now());
        }
        return itemResponse(item, body, mentions.currentNames(List.of(body)), votes.findByItemId(itemId), actor,
                true, checked.can(ProjectPermission.RETRO_MANAGE));
    }

    /**
     * Deletes a card (§3.5): the author, or anyone with RETRO_MANAGE (every manager role also has
     * RETRO_CONTRIBUTE). The card is hidden for good and its votes are given back.
     */
    @Transactional
    public void deleteItem(User actor, Long itemId) {
        RetroItem item = lockedItem(itemId);
        Retrospective retro = item.getRetro();
        ProjectAccessResult checked = writable(actor, retro.getProject().getId(),
                ProjectPermission.RETRO_CONTRIBUTE, "Card not found.");
        if (retro.isClosed()) {
            throw RetroConflictException.closed();
        }
        if (!item.isWrittenBy(actor) && !checked.can(ProjectPermission.RETRO_MANAGE)) {
            throw new AccessDeniedException("Only the author or a retro manager can delete a card.");
        }
        item.setDeletedAt(Instant.now());
        votes.deleteByItemId(itemId);
    }

    /**
     * One vote on a card (§3.6): at most one per card and {@value #VOTES_PER_MEMBER} per retro.
     * The retro row lock makes two quick clicks wait for each other, so a 4th vote can never slip
     * in; the unique (card, person) constraint is the backstop. Voting on one's own card is allowed.
     */
    @Transactional
    public RetroVoteResponse vote(User actor, Long itemId) {
        RetroItem item = lockedItem(itemId);
        Retrospective retro = item.getRetro();
        writable(actor, retro.getProject().getId(), ProjectPermission.RETRO_CONTRIBUTE, "Card not found.");
        if (retro.isClosed()) {
            throw RetroConflictException.closed();
        }
        if (votes.existsByItemIdAndUserId(itemId, actor.getId())) {
            throw RetroConflictException.alreadyVoted();
        }
        long used = votes.countByRetroIdAndUserId(retro.getId(), actor.getId());
        if (used >= VOTES_PER_MEMBER) {
            throw RetroConflictException.noVotesLeft();
        }
        votes.save(new RetroVote(item, actor));
        return new RetroVoteResponse(itemId, (int) votes.countByItemId(itemId), true,
                (int) (VOTES_PER_MEMBER - used - 1));
    }

    /** Takes the caller's vote back (§3.7). Removing a vote one does not have is not an error. */
    @Transactional
    public RetroVoteResponse unvote(User actor, Long itemId) {
        RetroItem item = lockedItem(itemId);
        Retrospective retro = item.getRetro();
        writable(actor, retro.getProject().getId(), ProjectPermission.RETRO_CONTRIBUTE, "Card not found.");
        if (retro.isClosed()) {
            throw RetroConflictException.closed();
        }
        votes.deleteByItemIdAndUserId(itemId, actor.getId());
        long used = votes.countByRetroIdAndUserId(retro.getId(), actor.getId());
        return new RetroVoteResponse(itemId, (int) votes.countByItemId(itemId), false,
                (int) (VOTES_PER_MEMBER - used));
    }

    /**
     * Turns a decision into a real task (§3.8). The task is made by {@link TaskApiService#create},
     * exactly like {@code POST /projects/{id}/tasks} (key, first column, history, TaskCreated,
     * TaskAssigned to the owner), so the owner check and its 400 come from there. Every
     * RETRO_MANAGE role also has the EDIT_TASKS that it asks for.
     */
    @Transactional
    public RetroActionResponse createAction(User actor, Long retroId, RetroActionRequest request) {
        Retrospective retro = retros.lockById(retroId)
                .orElseThrow(() -> new ResourceNotFoundException("Retrospective not found."));
        Long projectId = retro.getProject().getId();
        writable(actor, projectId, ProjectPermission.RETRO_MANAGE, "Retrospective not found.");
        if (retro.isClosed()) {
            throw RetroConflictException.closed();
        }
        RetroItem item = request.itemId() == null ? null : items.findById(request.itemId())
                .filter(card -> card.getRetro().getId().equals(retroId) && !card.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Card not found."));

        TaskResponse created = taskApi.create(actor, projectId, new CreateTaskRequest(request.title(), TaskType.TASK,
                null, request.ownerId(), null, List.of(ACTION_LABEL), null));
        Task task = tasks.findById(created.id()).orElseThrow();
        if (request.sprintId() != null) {
            // Checked after create, which holds the project row lock that starting a sprint also
            // takes: the sprint cannot turn ACTIVE between this check and the move.
            Sprint sprint = sprints.findById(request.sprintId())
                    .filter(found -> found.getProject().getId().equals(projectId))
                    .orElseThrow(() -> new ResourceNotFoundException("Sprint not found."));
            if (sprint.getStatus() != SprintStatus.PLANNED) {
                throw new BadRequestException("Actions can only go to the backlog or a planned sprint.");
            }
            List<Task> planned = tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(sprint.getId());
            task.setSprint(sprint);
            task.setSprintPosition(planned.isEmpty() ? 1L : planned.get(planned.size() - 1).getSprintPosition() + 1);
        }
        RetroAction action = actions.save(new RetroAction(retro, item, task.getId(), task.getKey(), actor, Instant.now()));
        return actionResponse(action, task);
    }

    private RetroResponse response(Retrospective retro, User actor, ProjectAccessResult checked) {
        boolean changeable = retro.getStatus() == RetroStatus.OPEN
                && !checked.workspaceMember().getWorkspace().isArchived()
                && checked.project().getLifecycleStatus() == ProjectLifecycleState.ACTIVE;
        boolean contributor = checked.can(ProjectPermission.RETRO_CONTRIBUTE);
        boolean canContribute = changeable && contributor;
        boolean canManage = changeable && checked.can(ProjectPermission.RETRO_MANAGE);

        List<RetroVote> retroVotes = votes.findByRetroId(retro.getId());
        Map<Long, List<RetroVote>> votesByCard = retroVotes.stream()
                .collect(Collectors.groupingBy(vote -> vote.getItem().getId()));
        long myVotes = retroVotes.stream().filter(vote -> vote.getUser().getId().equals(actor.getId())).count();
        int votesLeft = contributor ? (int) (VOTES_PER_MEMBER - myVotes) : 0;

        List<RetroItem> cards = items.findByRetroIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(retro.getId());
        List<RichTextDocument> bodies = cards.stream().map(card -> richText.parse(card.getBody())).toList();
        Map<RichTextMention, String> names = mentions.currentNames(bodies);
        List<RetroItemResponse> itemResponses = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            RetroItem card = cards.get(i);
            itemResponses.add(itemResponse(card, bodies.get(i), names,
                    votesByCard.getOrDefault(card.getId(), List.of()), actor, canContribute, canManage));
        }
        // This retro's actions, and the unfinished ones of the project's latest earlier retro.
        List<RetroAction> ownActions = actions.findByRetroIdOrderByCreatedAtAscIdAsc(retro.getId());
        Optional<Retrospective> previous = retros.findFirstByProjectIdAndSprintNumberLessThanOrderBySprintNumberDesc(
                retro.getProject().getId(), retro.getSprint().getNumber());
        List<RetroAction> previousActions = previous
                .map(earlier -> actions.findByRetroIdOrderByCreatedAtAscIdAsc(earlier.getId()))
                .orElse(List.of());
        // One query for every task; a deleted task is not returned (@SQLRestriction on Task).
        List<Long> taskIds = Stream.concat(ownActions.stream(), previousActions.stream())
                .map(RetroAction::getTaskId).toList();
        Map<Long, Task> liveTasks = tasks.findAllById(taskIds).stream()
                .collect(Collectors.toMap(Task::getId, task -> task));
        List<RetroActionResponse> actionResponses = ownActions.stream()
                .map(action -> actionResponse(action, liveTasks.get(action.getTaskId())))
                .toList();
        List<RetroActionResponse> unfinished = previousActions.stream()
                .filter(action -> {
                    Task task = liveTasks.get(action.getTaskId());
                    return task != null && task.getStatus().getCategory() != BoardColumnCategory.DONE;
                })
                .map(action -> actionResponse(action, liveTasks.get(action.getTaskId())))
                .toList();
        RetroResponse.PreviousActions carried = unfinished.isEmpty() ? null : new RetroResponse.PreviousActions(
                previous.get().getId(), previous.get().getSprint().getName(), unfinished);

        return new RetroResponse(retro.getId(), retro.getProject().getId(), retro.getTitle(), retro.getStatus(),
                toInstant(retro.getCreatedAt()), RetroUser.of(retro.getCreatedByUser()),
                retro.getClosedAt(), RetroUser.of(retro.getClosedByUser()),
                RetroResponse.SprintSummary.of(retro.getSprint()),
                itemResponses, VOTES_PER_MEMBER, votesLeft, actionResponses, carried, canContribute, canManage);
    }

    /**
     * An action with its task as it is now; {@code task} is null once the task was deleted, and
     * then only the id and the key snapshot are shown. The card link is hidden once the card was
     * deleted (cards are soft-deleted, so the row and the link stay).
     */
    private static RetroActionResponse actionResponse(RetroAction action, Task task) {
        RetroActionResponse.TaskRef ref = task == null
                ? new RetroActionResponse.TaskRef(action.getTaskId(), action.getTaskKey(),
                        null, null, null, null, null, null, true)
                : new RetroActionResponse.TaskRef(task.getId(), task.getKey(), task.getTitle(),
                        task.getStatus().getName(), task.getStatus().getCategory(), RetroUser.of(task.getAssignee()),
                        task.getSprint() == null ? null : task.getSprint().getId(),
                        task.getSprint() == null ? null : task.getSprint().getName(), false);
        RetroItem card = action.getItem();
        return new RetroActionResponse(action.getId(), card == null || card.isDeleted() ? null : card.getId(),
                action.getCreatedAt(), ref);
    }

    /**
     * One card for the caller. {@code canContribute} / {@code canManage} are the board's flags
     * (retro open, project writable, permission held); the author edits, the author or a manager
     * deletes.
     */
    private static RetroItemResponse itemResponse(RetroItem item, RichTextDocument body,
                                                  Map<RichTextMention, String> names, List<RetroVote> cardVotes,
                                                  User actor, boolean canContribute, boolean canManage) {
        List<CommentResponse.MentionName> mentioned = body.mentions().stream()
                .map(mention -> new CommentResponse.MentionName(mention.kind().json(), mention.id(), names.get(mention)))
                .toList();
        boolean own = canContribute && item.isWrittenBy(actor);
        return new RetroItemResponse(item.getId(), item.getColumn(), RetroUser.of(item.getAuthor()), item.getBody(),
                mentioned, toInstant(item.getCreatedAt()), item.getEditedAt(), cardVotes.size(),
                cardVotes.stream().anyMatch(vote -> vote.getUser().getId().equals(actor.getId())),
                own, own || canManage);
    }

    /**
     * Locks the card's retro, then loads the card, so the card is never read before a close or
     * another delete has committed. Used by edit, delete, vote and unvote.
     */
    private RetroItem lockedItem(Long itemId) {
        Long retroId = items.findRetroIdById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Card not found."));
        retros.lockById(retroId).orElseThrow(() -> new ResourceNotFoundException("Card not found."));
        return items.findById(itemId)
                .filter(item -> !item.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Card not found."));
    }

    /** Not empty, at most {@value #CARD_MAX_LENGTH} characters of text; the format is checked by the parser. */
    private RichTextDocument parseCard(String json) {
        RichTextDocument body = richText.parse(json);
        if (body.isEmpty()) {
            throw new InvalidRichTextException("A card cannot be empty.");
        }
        if (body.plainText().length() > CARD_MAX_LENGTH) {
            throw new InvalidRichTextException("A card can have at most " + CARD_MAX_LENGTH + " characters.");
        }
        return body;
    }

    /** {@code VIEW_WORK}; a project the caller cannot see gives the resource's own 404. */
    private ProjectAccessResult readable(User actor, Long projectId, String notFoundMessage) {
        try {
            return access.require(projectId, actor, ProjectPermission.VIEW_WORK);
        } catch (ResourceNotFoundException e) {
            throw new ResourceNotFoundException(notFoundMessage);
        }
    }

    /**
     * The permission (403), an active workspace (409 {@code WORKSPACE_ARCHIVED}) and an active
     * project (409 {@code CONFLICT}); a project the caller cannot see gives the resource's own 404.
     */
    private ProjectAccessResult writable(User actor, Long projectId, ProjectPermission permission,
                                         String notFoundMessage) {
        ProjectAccessResult checked;
        try {
            checked = access.requireActive(projectId, actor, permission);
        } catch (ResourceNotFoundException e) {
            throw new ResourceNotFoundException(notFoundMessage);
        }
        if (checked.project().getLifecycleStatus() != ProjectLifecycleState.ACTIVE) {
            throw RetroConflictException.readOnlyProject();
        }
        return checked;
    }

    private static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }
}
