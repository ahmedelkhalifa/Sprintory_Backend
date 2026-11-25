package team.sprintory_backend.jiraimport.services;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.comment.models.TaskComment;
import team.sprintory_backend.comment.repositories.TaskCommentRepository;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.jiraimport.dto.JiraImportPreview;
import team.sprintory_backend.jiraimport.dto.JiraImportResult;
import team.sprintory_backend.jiraimport.dto.JiraIssueMappingResponse;
import team.sprintory_backend.jiraimport.models.JiraIssueLink;
import team.sprintory_backend.jiraimport.models.JiraSprintLink;
import team.sprintory_backend.jiraimport.repositories.JiraIssueLinkRepository;
import team.sprintory_backend.jiraimport.repositories.JiraSprintLinkRepository;
import team.sprintory_backend.project.models.*;
import team.sprintory_backend.project.repositories.*;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.sprint.models.*;
import team.sprintory_backend.sprint.repositories.SprintRepository;
import team.sprintory_backend.task.models.*;
import team.sprintory_backend.task.repositories.TaskRepository;
import team.sprintory_backend.user.models.User;
import team.sprintory_backend.user.repositories.UserRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Opt-in, atomic, one-time import of current Jira state. Uses direct persistence deliberately:
 * replaying interactive create/start/move operations would emit misleading notifications,
 * generate fictional commitment/history, and break historical sprint recreation.
 */
@Service @RequiredArgsConstructor
public class JiraImportService {
    private static final long GAP = 1_000_000L;
    private static final int MAX_SPRINT_JSON_BYTES = 1_000_000;

    private final JiraCsvReader csvReader;
    private final ObjectMapper mapper;
    private final ProjectAccess projectAccess;
    private final ProjectRepository projects;
    private final ProjectBoardRepository boards;
    private final ProjectBoardColumnRepository columns;
    private final ProjectMemberRepository members;
    private final TaskRepository tasks;
    private final SprintRepository sprints;
    private final JiraIssueLinkRepository issueLinks;
    private final JiraSprintLinkRepository sprintLinks;
    private final TaskCommentRepository comments;
    private final UserRepository users;
    private final EntityManager em;

    @Value("${sprintory.jira-import.enabled:false}")
    private boolean enabled;

    public record SourceSprint(Long jiraId, String name, SprintStatus status, String goal,
                               LocalDate start, LocalDate end, Instant completedAt) {}

    @Transactional(readOnly = true)
    public JiraImportPreview preview(User actor, Long projectId, byte[] csv, byte[] sprintJson) {
        Project project = permitted(actor, projectId);
        JiraCsvReader.Parsed parsed = csvReader.parse(csv);
        List<SourceSprint> sourceSprints = parseSprints(sprintJson);
        return inspect(project, parsed, sourceSprints, sprintJson != null);
    }

    @Transactional(readOnly = true)
    public List<JiraIssueMappingResponse> mappings(User actor, Long projectId) {
        permitted(actor, projectId);
        return issueLinks.findByProjectId(projectId).stream()
                .map(JiraIssueMappingResponse::from)
                .sorted(Comparator.comparing(JiraIssueMappingResponse::jiraKey))
                .toList();
    }

    @Transactional
    public JiraImportResult execute(User actor, Long projectId, byte[] csv, byte[] sprintJson,
                                    String mappingJson, String confirmation) {
        if (!"IMPORT".equals(confirmation)) throw new BadRequestException("Type IMPORT to confirm this operation.");
        Project project = permitted(actor, projectId);
        // Project row serializes task-number allocation, sprint transitions and two simultaneous imports.
        project = projects.lockById(projectId)
                .orElseThrow(() -> new BadRequestException("Project no longer exists."));
        // Discard a potentially stale pre-lock Project instance before checking lifecycle.
        em.refresh(project);
        if (project.getLifecycleStatus() != ProjectLifecycleState.ACTIVE) {
            throw new BadRequestException("Only active projects can receive a Jira import.");
        }
        JiraCsvReader.Parsed parsed = csvReader.parse(csv);
        List<SourceSprint> metadata = parseSprints(sprintJson);
        JiraImportPreview preview = inspect(project, parsed, metadata, sprintJson != null);
        if (!preview.ready()) throw new BadRequestException("Import blocked: " + String.join("; ", preview.blockers()));
        Map<String, User> assignedUsers = mapUsers(projectId, mappingJson);

        Map<String, JiraCsvReader.Issue> byId = parsed.issues().stream()
                .collect(Collectors.toMap(JiraCsvReader.Issue::issueId, i -> i));
        Map<String, Long> existingByJiraId = new HashMap<>();
        Map<String, Task> parentTasks = new HashMap<>();
        for (JiraIssueLink link : issueLinks.findByProjectId(projectId)) {
            existingByJiraId.put(link.getJiraIssueId(), link.getTask().getId());
            parentTasks.put(link.getJiraIssueId(), link.getTask());
        }

        Map<String, Sprint> destinationSprints = new HashMap<>();
        Map<Long, Sprint> mappedSprintIds = new HashMap<>();
        for (JiraSprintLink link : sprintLinks.findByProjectId(projectId)) {
            mappedSprintIds.put(link.getJiraSprintId(), link.getSprint());
        }
        int createdSprints = 0;
        int number = sprints.maxNumber(projectId);
        for (SourceSprint source : metadata) {
            Sprint sprint = mappedSprintIds.get(source.jiraId());
            if (sprint == null) {
                sprint = new Sprint(project, ++number, source.name(), source.goal(), null,
                        source.start(), source.end());
                sprint.setStatus(source.status());
                sprint.setCompletedAt(source.completedAt());
                // Do not invent immutable sprint commitments, velocity or burndown snapshots.
                sprint = sprints.save(sprint);
                sprintLinks.save(new JiraSprintLink(project, sprint, source.jiraId()));
                createdSprints++;
            }
            destinationSprints.put(source.name(), sprint);
        }
        List<ProjectBoardColumn> currentColumns = columns.findByBoardProjectIdOrderByPositionAscIdAsc(projectId);
        Map<String, ProjectBoardColumn> statuses = new HashMap<>();
        for (ProjectBoardColumn c : currentColumns) statuses.put(c.getName().toLowerCase(Locale.ROOT), c);
        if (parsed.issues().stream().anyMatch(i -> "In Review".equalsIgnoreCase(i.status()))
                && !statuses.containsKey("in review") && preview.newTopLevelItems() + preview.newSubtasks() > 0) {
            int donePosition = currentColumns.stream().filter(c -> c.getCategory() == BoardColumnCategory.DONE)
                    .mapToInt(ProjectBoardColumn::getPosition).min().orElse(currentColumns.size());
            // The board has a unique (project, position) index. Two-phase ordering is mandatory:
            // shifting Done directly to its final position would collide with adjacent columns.
            for (int i = 0; i < currentColumns.size(); i++) currentColumns.get(i).setPosition(1000 + i);
            columns.saveAllAndFlush(currentColumns);
            ProjectBoardColumn inReview = new ProjectBoardColumn(boards.getReferenceById(projectId),
                    "In Review", donePosition);
            inReview.setCategory(BoardColumnCategory.IN_PROGRESS);
            statuses.put("in review", columns.saveAndFlush(inReview));
            for (int i = 0; i < currentColumns.size(); i++) {
                currentColumns.get(i).setPosition(i >= donePosition ? i + 1 : i);
            }
            columns.saveAllAndFlush(currentColumns);
        }
        Map<Long, Long> boardPositions = new HashMap<>();
        for (ProjectBoardColumn c : columns.findByBoardProjectIdOrderByPositionAscIdAsc(projectId)) {
            boardPositions.put(c.getId(), Optional.ofNullable(tasks.findMaxBoardPosition(projectId, c.getId())).orElse(0L));
        }
        long backlogPosition = Optional.ofNullable(tasks.findMaxBacklogRank(projectId)).orElse(0L);
        Map<Long, Long> sprintPositions = new HashMap<>();
        for (Sprint sprint : destinationSprints.values()) {
            long current = tasks.findBySprintIdAndParentIsNullOrderBySprintPositionAscIdAsc(sprint.getId())
                    .stream().map(Task::getSprintPosition).filter(Objects::nonNull).mapToLong(Long::longValue)
                    .max().orElse(0L);
            sprintPositions.put(sprint.getId(), current);
        }

        int top = 0, childCount = 0, commentCount = 0;
        List<JiraCsvReader.Issue> sorted = sorted(parsed.issues());
        for (JiraCsvReader.Issue issue : sorted) {
            if (!isTopLevel(issue) || existingByJiraId.containsKey(issue.issueId())) continue;
            Task task = newTask(project, issue, null, statuses, assignedUsers, destinationSprints,
                    boardPositions, sprintPositions, backlogPosition += GAP);
            parentTasks.put(issue.issueId(), task);
            issueLinks.save(new JiraIssueLink(project, task, issue.issueId(), issue.key(),
                    epicKey(issue, byId), issue.created(), issue.updated()));
            commentCount += importComment(issue, task, assignedUsers);
            top++;
        }
        for (JiraCsvReader.Issue issue : sorted) {
            if (!isSubtask(issue) || issue.parentId().isBlank() || existingByJiraId.containsKey(issue.issueId())) continue;
            Task parent = parentTasks.get(issue.parentId());
            if (parent == null) continue; // preview reports this as a blocker
            Task task = newTask(project, issue, parent, statuses, assignedUsers, destinationSprints,
                    boardPositions, sprintPositions, 0);
            issueLinks.save(new JiraIssueLink(project, task, issue.issueId(), issue.key(),
                    null, issue.created(), issue.updated()));
            commentCount += importComment(issue, task, assignedUsers);
            childCount++;
        }
        em.flush(); // Fail atomically before returning success.
        return new JiraImportResult(projectId, top, childCount, createdSprints, preview.alreadyImported(),
                preview.epicsSkipped(), preview.orphanSubtasksSkipped(), commentCount, preview.warnings());
    }

    private Project permitted(User user, Long projectId) {
        if (!enabled) throw new AccessDeniedException("Jira importer is disabled on this server.");
        var access = projectAccess.requireActive(projectId, user, ProjectPermission.EDIT_SETTINGS);
        if (!access.can(ProjectPermission.EDIT_TASKS) || !access.can(ProjectPermission.CONFIGURE_BOARD)
                || !access.can(ProjectPermission.VIEW_WORK)) {
            throw new AccessDeniedException("Project Manager permissions are required for a Jira import.");
        }
        Project project = access.project();
        if (project.getTemplate() != ProjectTemplate.SCRUM) {
            throw new BadRequestException("The Jira importer currently supports Scrum projects only.");
        }
        if (project.getLifecycleStatus() != ProjectLifecycleState.ACTIVE) {
            throw new BadRequestException("Only active projects can receive a Jira import.");
        }
        return project;
    }

    private JiraImportPreview inspect(Project project, JiraCsvReader.Parsed parsed, List<SourceSprint> sourceSprints,
                                      boolean sprintMetadataProvided) {
        List<String> warnings = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        Map<String, JiraCsvReader.Issue> byId = new HashMap<>();
        Set<String> keys = new HashSet<>();
        for (JiraCsvReader.Issue issue : parsed.issues()) {
            if (byId.putIfAbsent(issue.issueId(), issue) != null || !keys.add(issue.key())) {
                blockers.add("Duplicate Jira issue id/key: " + issue.key());
            }
        }
        List<JiraIssueLink> storedLinks = issueLinks.findByProjectId(project.getId());
        Map<String, JiraIssueLink> existingIds = storedLinks.stream()
                .collect(Collectors.toMap(JiraIssueLink::getJiraIssueId, l -> l));
        Set<String> already = existingIds.keySet();
        Map<String, JiraIssueLink> existingKeys = storedLinks.stream()
                .collect(Collectors.toMap(JiraIssueLink::getJiraKey, l -> l));
        int epics = 0, orphan = 0, top = 0, subtasks = 0, existing = 0;
        Map<String, JiraImportPreview.JiraAssignee> people = new TreeMap<>();
        Map<String, Integer> assigneeCount = new HashMap<>();
        Set<String> neededSprints = new HashSet<>();
        for (JiraCsvReader.Issue issue : parsed.issues()) {
            if (isEpic(issue)) { epics++; continue; }
            if (!isTopLevel(issue) && !isSubtask(issue)) {
                blockers.add("Unsupported Jira issue type: " + issue.type() + " (" + issue.key() + ")"); continue;
            }
            if (isSubtask(issue)) {
                JiraCsvReader.Issue parent = byId.get(issue.parentId());
                if (issue.parentId().isBlank() || parent == null || !isTopLevel(parent)) {
                    orphan++; warnings.add("Skipped orphan/unsupported subtask " + issue.key()); continue;
                }
            }
            if (already.contains(issue.issueId())) {
                JiraIssueLink saved = existingIds.get(issue.issueId());
                if (!saved.getJiraKey().equals(issue.key())) {
                    blockers.add("Jira issue identity changed for " + issue.key() + "; import is not a synchronization.");
                }
                existing++; continue;
            }
            if (existingKeys.containsKey(issue.key())) {
                blockers.add("Jira key " + issue.key() + " was previously imported under a different Jira ID.");
                continue;
            }
            if (issue.title().isBlank() || issue.title().length() > 255) {
                blockers.add("Missing or overlong title: " + issue.key());
            }
            if (issue.issueId().length() > 60 || issue.key().length() > 50) {
                blockers.add("Jira issue ID or key exceeds provenance field limits: " + issue.key());
            }
            if (!Set.of("To Do", "In Progress", "In Review", "Done").contains(issue.status())) {
                blockers.add("Unrecognized Jira status for " + issue.key() + ": " + issue.status());
            }
            if (!issue.priority().isBlank()) {
                try { TaskPriority.valueOf(issue.priority().toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException e) { blockers.add("Unknown priority on " + issue.key()); }
            }
            if (!issue.points().isBlank()) {
                try {
                    double points = Double.parseDouble(issue.points());
                    if (points < 0 || points > Integer.MAX_VALUE || Math.rint(points) != points || isSubtask(issue)) {
                        blockers.add("Unsupported estimate on " + issue.key() + ": " + issue.points());
                    }
                } catch (NumberFormatException e) { blockers.add("Invalid estimate on " + issue.key()); }
            }
            for (String label : issue.labels()) if (label.length() > 50) {
                blockers.add("Label longer than 50 characters on " + issue.key());
            }
            if (!issue.sprint().isBlank() && isTopLevel(issue)) neededSprints.add(issue.sprint());
            if (!issue.assigneeId().isBlank()) {
                people.putIfAbsent(issue.assigneeId(), new JiraImportPreview.JiraAssignee(
                        issue.assigneeId(), issue.assigneeName(), 0));
                assigneeCount.merge(issue.assigneeId(), 1, Integer::sum);
            }
            if (isTopLevel(issue)) top++; else subtasks++;
        }
        // One-time cutover: a new project must be empty, a migrated one may only be rechecked.
        if (storedLinks.isEmpty()) {
            if (project.getLastTaskNumber() > 0 || !sprints.findByProjectIdOrderByNumberAsc(project.getId()).isEmpty()) {
                blockers.add("Choose a new, empty Scrum project for the one-time Jira migration.");
            }
        } else if (top + subtasks > 0) {
            blockers.add("This project has already been migrated. New Jira issues are not appended; use a fresh destination project.");
        }
        if (!sprintMetadataProvided) {
            if (!neededSprints.isEmpty()) {
                warnings.add("No sprint information provided. Work items will be imported into the backlog without their original sprints.");
            }
        } else {
            for (String name : neededSprints) {
                if (sourceSprints.stream().noneMatch(s -> s.name().equals(name))) {
                    blockers.add("Missing sprint metadata for " + name + ". Export all sprints or remove the JSON file to import into the backlog instead.");
                }
            }
        }
        if (sourceSprints.stream().filter(s -> s.status() == SprintStatus.ACTIVE).count() > 1) {
            blockers.add("Jira export has more than one ACTIVE sprint.");
        }
        Map<Long, Sprint> imported = sprintLinks.findByProjectId(project.getId()).stream()
                .collect(Collectors.toMap(JiraSprintLink::getJiraSprintId, JiraSprintLink::getSprint));
        List<Sprint> existingSprints = sprints.findByProjectIdOrderByNumberAsc(project.getId());
        int newSprintCount = 0;
        for (SourceSprint s : sourceSprints) {
            if (imported.containsKey(s.jiraId())) continue;
            newSprintCount++;
            for (Sprint old : existingSprints) {
                if (s.status() == SprintStatus.ACTIVE && old.getStatus() == SprintStatus.ACTIVE) {
                    blockers.add("Target project already has an active sprint.");
                }
                if (old.getStartDate() != null && old.getEndDate() != null && s.start() != null
                        && !s.end().isBefore(old.getStartDate()) && !old.getEndDate().isBefore(s.start())) {
                    blockers.add("Sprint dates overlap target sprint " + old.getName());
                }
            }
        }
        for (int i = 0; i < sourceSprints.size(); i++) {
            for (int j = i + 1; j < sourceSprints.size(); j++) {
                SourceSprint a = sourceSprints.get(i), b = sourceSprints.get(j);
                if (a.start() != null && b.start() != null
                        && !a.end().isBefore(b.start()) && !b.end().isBefore(a.start())) {
                    blockers.add("Jira sprints overlap: " + a.name() + "/" + b.name());
                }
            }
        }
        if (!storedLinks.isEmpty() && newSprintCount > 0) {
            blockers.add("This project has already been migrated. New Jira sprints require a new destination project.");
        }
        Set<String> targetStatuses = columns.findByBoardProjectIdOrderByPositionAscIdAsc(project.getId()).stream()
                .map(c -> c.getName().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        for (JiraCsvReader.Issue issue : parsed.issues()) {
            if (!isTopLevel(issue) && !isSubtask(issue)) continue;
            if (already.contains(issue.issueId())) continue;
            String statusName = issue.status().toLowerCase(Locale.ROOT);
            if (!"in review".equals(statusName) && !targetStatuses.contains(statusName)) {
                blockers.add("Missing target board column " + issue.status() + " (" + issue.key() + ")");
            }
        }
        boolean needsReviewColumn = parsed.issues().stream().anyMatch(i -> "In Review".equalsIgnoreCase(i.status()))
                && columns.findByBoardProjectIdOrderByPositionAscIdAsc(project.getId()).stream()
                .noneMatch(c -> "In Review".equalsIgnoreCase(c.getName()))
                && top + subtasks > 0;
        if (needsReviewColumn) {
            if (columns.findByBoardProjectIdOrderByPositionAscIdAsc(project.getId()).size() >= 10) {
                blockers.add("The board already has 10 columns; cannot add In Review.");
            } else {
                warnings.add("An In Review column will be added before Done to preserve Jira statuses.");
            }
        }
        if (parsed.issues().stream().anyMatch(i -> (isTopLevel(i) || isSubtask(i))
                && i.priority().isBlank())) {
            warnings.add("Missing Jira priorities default to MEDIUM.");
        }
        if (epics > 0) warnings.add("Skipped " + epics + " Epics; imported children retain Jira Epic keys in provenance.");
        if (existing > 0) warnings.add(existing + " issues were already imported; they will not be updated.");
        if (top + subtasks > 0 && parsed.issues().stream().anyMatch(i -> !i.description().isBlank())) {
            warnings.add("Some Jira-specific description formatting may appear as plain text.");
        }
        if (parsed.issues().stream().anyMatch(i -> !i.comments().isEmpty())) {
            warnings.add("Imported comments show the import date; the original author and date are kept in the comment text.");
        }
        if (parsed.issues().stream().anyMatch(i -> !i.sprint().isBlank())) {
            warnings.add("Historical sprint commitments and velocity are not available in the CSV export.");
        }
        List<JiraImportPreview.JiraAssignee> assignees = people.values().stream()
                .map(p -> new JiraImportPreview.JiraAssignee(p.jiraAccountId(), p.displayName(),
                        assigneeCount.get(p.jiraAccountId()))).toList();
        return new JiraImportPreview(project.getId(), parsed.projectKey(), parsed.issues().size(),
                epics, orphan, existing, top, subtasks, newSprintCount, assignees,
                List.copyOf(warnings), List.copyOf(blockers), blockers.isEmpty());
    }

    private Map<String, User> mapUsers(Long projectId, String json) {
        if (json == null || json.isBlank()) return Map.of();
        Map<?, ?> source;
        try { source = mapper.readValue(json, Map.class); }
        catch (JacksonException e) { throw new BadRequestException("Invalid userMappings JSON object."); }
        Map<String, User> result = new HashMap<>();
        for (var entry : source.entrySet()) {
            String jiraId = String.valueOf(entry.getKey());
            Long userId;
            try { userId = Long.valueOf(String.valueOf(entry.getValue())); }
            catch (NumberFormatException e) { throw new BadRequestException("Invalid mapped user ID."); }
            if (!members.existsByProjectIdAndUserId(projectId, userId)) {
                throw new BadRequestException("Mapped user " + userId + " is not a member of the target project.");
            }
            result.put(jiraId, users.findById(userId)
                    .orElseThrow(() -> new BadRequestException("Mapped user doesn't exist.")));
        }
        return result;
    }

    private Task newTask(Project project, JiraCsvReader.Issue source, Task parent,
                         Map<String, ProjectBoardColumn> columnsByName, Map<String, User> assigned,
                         Map<String, Sprint> sprintByName, Map<Long, Long> boardPositions,
                         Map<Long, Long> sprintPositions, long rank) {
        projects.incrementTaskNumber(project.getId());
        int number = projects.findLastTaskNumber(project.getId());
        ProjectBoardColumn column = columnsByName.get(source.status().toLowerCase(Locale.ROOT));
        if (column == null) throw new BadRequestException("Status column missing: " + source.status());
        boolean subtask = parent != null;
        Sprint sprint = subtask ? null : sprintByName.get(source.sprint());
        Long sprintPosition = sprint == null ? null : sprintPositions.merge(sprint.getId(), GAP, Long::sum);
        long boardPosition = subtask ? 0 : boardPositions.merge(column.getId(), GAP, Long::sum);
        Integer estimate = source.points().isBlank() || subtask ? null
                : (int) Double.parseDouble(source.points());
        Set<String> labels = new LinkedHashSet<>(source.labels());
        Task task = Task.builder()
                .project(project).number(number).key(project.getKey() + "-" + number)
                .title(source.title()).type(isSubtask(source) ? TaskType.TASK : toType(source.type()))
                .priority(source.priority().isBlank() ? TaskPriority.MEDIUM
                        : TaskPriority.valueOf(source.priority().toUpperCase(Locale.ROOT)))
                .status(column).assignee(assigned.get(source.assigneeId()))
                .estimate(estimate).estimateMinutes(null)
                .description(richText(source.description()))
                .labels(labels).parent(parent).sprint(sprint).sprintPosition(sprintPosition)
                .boardPosition(boardPosition).backlogRank(rank).build();
        return tasks.save(task);
    }

    private int importComment(JiraCsvReader.Issue issue, Task task, Map<String, User> assigned) {
        int imported = 0;
        for (String raw : issue.comments()) {
            String[] parts = raw.split(";", 3);
            String originalDate = parts.length == 3 ? parts[0] : "unknown date";
            String originalAuthor = parts.length == 3 ? parts[1] : "unknown author";
            String body = parts.length == 3 ? parts[2] : raw;
            String note = "Imported from Jira (" + originalDate + ", " + originalAuthor + "):\n" + body;
            comments.save(TaskComment.builder().task(task).author(assigned.get(originalAuthor))
                    .body(richText(note)).build());
            imported++;
        }
        return imported;
    }

    /** Text is encoded as text nodes, never interpolated as markup or HTML. */
    private String richText(String source) {
        if (source == null || source.isBlank()) return null;
        ObjectNode root = mapper.createObjectNode(); root.put("type", "doc");
        ArrayNode paragraphs = root.putArray("content");
        for (String line : source.split("\\R", -1)) {
            ObjectNode paragraph = paragraphs.addObject(); paragraph.put("type", "paragraph");
            if (!line.isEmpty()) {
                ObjectNode value = paragraph.putArray("content").addObject();
                value.put("type", "text"); value.put("text", line);
            }
        }
        return mapper.writeValueAsString(root);
    }

    private List<SourceSprint> parseSprints(byte[] jsonBytes) {
        if (jsonBytes == null) return List.of();
        if (jsonBytes.length == 0) throw new BadRequestException("Sprint JSON is empty. Choose a valid file or remove it to import without sprints.");
        if (jsonBytes.length > MAX_SPRINT_JSON_BYTES) throw new BadRequestException("Sprint JSON is too large.");
        try {
            JsonNode root = mapper.readTree(jsonBytes);
            if (root == null || !root.isObject()) throw new BadRequestException("Sprint JSON must be a Jira export object.");
            JsonNode values = root.get("values");
            if (values == null || !values.isArray()) throw new BadRequestException("Sprint JSON must contain a values array.");
            validateSprintPage(root, values.size());
            List<SourceSprint> result = new ArrayList<>();
            Set<Long> seenIds = new HashSet<>();
            Set<String> seenNames = new HashSet<>();
            for (JsonNode sprint : values) {
                long id = sprint.get("id").asLong();
                String name = sprint.get("name").asText();
                String state = sprint.get("state").asText();
                SprintStatus status = switch (state.toLowerCase(Locale.ROOT)) {
                    case "closed" -> SprintStatus.COMPLETED;
                    case "active" -> SprintStatus.ACTIVE;
                    case "future" -> SprintStatus.PLANNED;
                    default -> throw new BadRequestException("Unsupported Jira sprint state: " + state);
                };
                if (id <= 0 || name.isBlank() || name.length() > 100
                        || !seenIds.add(id) || !seenNames.add(name)) {
                    throw new BadRequestException("Invalid or duplicate Jira sprint ID/name.");
                }
                JsonNode startNode = sprint.get("startDate"), endNode = sprint.get("endDate");
                boolean hasStart = startNode != null && !startNode.isNull();
                boolean hasEnd = endNode != null && !endNode.isNull();
                if (hasStart != hasEnd || (status != SprintStatus.PLANNED && !hasStart)) {
                    throw new BadRequestException("Sprint " + name + " has missing or incomplete dates.");
                }
                LocalDate start = hasStart ? Instant.parse(startNode.asText()).atZone(ZoneOffset.UTC).toLocalDate() : null;
                LocalDate end = hasEnd ? Instant.parse(endNode.asText()).atZone(ZoneOffset.UTC).toLocalDate() : null;
                if (hasStart && !end.isAfter(start)) throw new BadRequestException("Sprint " + name + " has invalid dates.");
                JsonNode complete = sprint.get("completeDate");
                Instant completedAt = complete == null || complete.isNull() ? null : Instant.parse(complete.asText());
                if (status == SprintStatus.COMPLETED && completedAt == null) {
                    throw new BadRequestException("Closed sprint " + name + " has no completeDate.");
                }
                result.add(new SourceSprint(id, name, status,
                        sprint.get("goal") == null ? "" : sprint.get("goal").asText(), start, end, completedAt));
            }
            return List.copyOf(result);
        } catch (JacksonException | NullPointerException | java.time.format.DateTimeParseException e) {
            throw new BadRequestException("Invalid Jira sprint JSON: " + e.getMessage());
        }
    }

    /** Accept legacy values-only exports, but never silently import a known partial page. */
    private static void validateSprintPage(JsonNode root, int count) {
        JsonNode last = root.get("isLast");
        JsonNode start = root.get("startAt");
        JsonNode total = root.get("total");
        if ((last != null && !last.isBoolean())
                || (start != null && (!start.isIntegralNumber() || !start.canConvertToLong() || start.asLong() < 0))
                || (total != null && (!total.isIntegralNumber() || !total.canConvertToLong() || total.asLong() < 0))) {
            throw new BadRequestException("Invalid sprint export pagination metadata.");
        }
        if ((last != null && !last.asBoolean()) || (start != null && start.asLong() != 0)
                || (total != null && total.asLong() != count)) {
            throw new BadRequestException("The sprint export is incomplete. Contact support for a complete sprint export, or remove the JSON file to import work items into the backlog instead.");
        }
    }

    private static List<JiraCsvReader.Issue> sorted(List<JiraCsvReader.Issue> issues) {
        return issues.stream().sorted(Comparator.comparing(JiraCsvReader.Issue::rank)
                .thenComparing(JiraCsvReader.Issue::key)).toList();
    }
    private static boolean isEpic(JiraCsvReader.Issue i) { return "Epic".equalsIgnoreCase(i.type()); }
    private static boolean isSubtask(JiraCsvReader.Issue i) { return "Subtask".equalsIgnoreCase(i.type()); }
    private static boolean isTopLevel(JiraCsvReader.Issue i) {
        return "Story".equalsIgnoreCase(i.type()) || "Task".equalsIgnoreCase(i.type())
                || "Bug".equalsIgnoreCase(i.type());
    }
    private static TaskType toType(String jiraType) {
        return TaskType.valueOf(jiraType.toUpperCase(Locale.ROOT));
    }
    private static String epicKey(JiraCsvReader.Issue i, Map<String, JiraCsvReader.Issue> byId) {
        JiraCsvReader.Issue parent = byId.get(i.parentId());
        return parent != null && isEpic(parent) ? parent.key() : null;
    }
}
