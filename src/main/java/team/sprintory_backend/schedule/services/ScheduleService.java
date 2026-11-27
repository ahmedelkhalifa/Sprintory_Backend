package team.sprintory_backend.schedule.services;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.models.ProjectLifecycleState;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.services.ProjectAccess;
import team.sprintory_backend.project.services.ProjectAccessResult;
import team.sprintory_backend.schedule.api.ScheduleFilters;
import team.sprintory_backend.schedule.dto.ScheduleConflict;
import team.sprintory_backend.schedule.dto.ScheduleDependency;
import team.sprintory_backend.schedule.dto.ScheduleMilestone;
import team.sprintory_backend.schedule.dto.ScheduleResponse;
import team.sprintory_backend.schedule.dto.ScheduleTask;
import team.sprintory_backend.schedule.dto.UnscheduledTask;
import team.sprintory_backend.schedule.repositories.ScheduleRepository;
import team.sprintory_backend.user.models.User;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ScheduleService {
    private final ProjectAccess access;
    private final ScheduleRepository repository;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ScheduleResponse get(User user, Long projectId, ScheduleFilters filters) {
        ProjectAccessResult checked = access.requireVisible(projectId, user);
        if (checked.projectMember() == null) throw new ResourceNotFoundException("Project not found.");
        if (!checked.can(ProjectPermission.VIEW_WORK)) throw new AccessDeniedException("Project permission required: VIEW_WORK");
        filters.validate();
        // Project-wide like the unscheduled panel: the filters narrow task rows, never the diamonds.
        List<ScheduleMilestone> milestones = repository.findMilestones(projectId);
        List<ScheduleTask> all = repository.findRows(projectId);
        Map<Long, ScheduleTask> index = new HashMap<>();
        for (ScheduleTask row : all) index.put(row.id(), row);
        List<ScheduleDependency> links = repository.findLinks(projectId).stream()
                .filter(link -> index.containsKey(link.blockerId()) && index.containsKey(link.blockedId())).toList();

        List<ScheduleTask> scheduled = all.stream().filter(ScheduleTask::scheduled).toList();
        LocalDate earliest = scheduled.stream().map(ScheduleTask::startDate).min(LocalDate::compareTo).orElse(null);
        LocalDate latest = scheduled.stream().map(ScheduleTask::dueDate).max(LocalDate::compareTo).orElse(null);
        List<ScheduleTask> visible = scheduled.stream().filter(filters::matches).toList();
        Set<Long> visibleIds = new HashSet<>();
        visible.forEach(row -> visibleIds.add(row.id()));
        List<ScheduleDependency> arrows = links.stream()
                .filter(link -> visibleIds.contains(link.blockerId()) && visibleIds.contains(link.blockedId())).toList();
        // Evaluate the full graph first; a filter cannot make a visible task appear conflict-free.
        List<ScheduleConflict> conflicts = ScheduleConflicts.find(all, links, milestones).stream()
                .filter(conflict -> conflict.taskIds().stream().anyMatch(visibleIds::contains)).toList();
        boolean canEdit = checked.can(ProjectPermission.EDIT_TASKS)
                && checked.project().getLifecycleStatus() == ProjectLifecycleState.ACTIVE
                && !checked.workspaceMember().getWorkspace().isArchived();
        return new ScheduleResponse(LocalDate.now(), earliest, latest, canEdit, true, visible, arrows,
                milestones, conflicts, unscheduled(all, links, index));
    }

    private static List<UnscheduledTask> unscheduled(List<ScheduleTask> all, List<ScheduleDependency> links,
                                                     Map<Long, ScheduleTask> index) {
        Map<Long, List<String>> incoming = new HashMap<>(), outgoing = new HashMap<>();
        for (ScheduleDependency link : links) {
            incoming.computeIfAbsent(link.blockedId(), id -> new ArrayList<>()).add(index.get(link.blockerId()).key());
            outgoing.computeIfAbsent(link.blockerId(), id -> new ArrayList<>()).add(index.get(link.blockedId()).key());
        }
        return all.stream().filter(row -> !row.scheduled()).map(row -> {
            List<String> blockers = incoming.getOrDefault(row.id(), List.of());
            List<String> blocked = outgoing.getOrDefault(row.id(), List.of());
            List<String> parts = new ArrayList<>();
            if (!blockers.isEmpty()) parts.add("blocked by " + String.join(", ", blockers));
            if (!blocked.isEmpty()) parts.add("blocks " + String.join(", ", blocked));
            String hint = parts.isEmpty() ? null : String.join("; ", parts) + ", set dates to see it on the chart";
            return new UnscheduledTask(row.id(), row.key(), row.title(), row.type(), row.statusCategory(),
                    row.statusId(), row.statusName(), row.assigneeId(), row.assignee(), row.sprintId(), row.milestoneId(),
                    List.copyOf(blockers), List.copyOf(blocked), hint);
        }).toList();
    }
}
