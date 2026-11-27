package team.sprintory_backend.schedule.services;

import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.schedule.dto.ScheduleConflict;
import team.sprintory_backend.schedule.dto.ScheduleConflictType;
import team.sprintory_backend.schedule.dto.ScheduleDependency;
import team.sprintory_backend.schedule.dto.ScheduleMilestone;
import team.sprintory_backend.schedule.dto.ScheduleTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure, linear-time calculation over current tasks, links and milestones. No persistence. */
public final class ScheduleConflicts {
    private ScheduleConflicts() {}

    public static List<ScheduleConflict> find(List<ScheduleTask> tasks, List<ScheduleDependency> dependencies,
                                              List<ScheduleMilestone> milestones) {
        Map<Long, ScheduleTask> byId = new HashMap<>();
        for (ScheduleTask task : tasks) byId.put(task.id(), task);
        Map<Long, ScheduleMilestone> byMilestone = new HashMap<>();
        for (ScheduleMilestone milestone : milestones) byMilestone.put(milestone.id(), milestone);
        List<ScheduleConflict> result = new ArrayList<>();
        for (ScheduleDependency edge : dependencies) {
            ScheduleTask blocker = byId.get(edge.blockerId()), blocked = byId.get(edge.blockedId());
            if (blocker == null || blocked == null || !blocker.scheduled() || !blocked.scheduled()
                    || blocker.statusCategory() == BoardColumnCategory.DONE) continue;
            // Inclusive finish: a same-day start is a conflict; the next day is allowed.
            if (!blocked.startDate().isAfter(blocker.dueDate())) {
                result.add(new ScheduleConflict(ScheduleConflictType.DEPENDENCY,
                        List.of(blocker.id(), blocked.id()), edge.id(), null,
                        blocked.key() + " starts before " + blocker.key() + " ends"));
            }
        }
        for (ScheduleTask task : tasks) {
            if (!task.scheduled() || task.statusCategory() == BoardColumnCategory.DONE) continue;
            ScheduleMilestone milestone = byMilestone.get(task.milestoneId());
            if (milestone != null && milestone.targetDate() != null && task.dueDate().isAfter(milestone.targetDate())) {
                result.add(new ScheduleConflict(ScheduleConflictType.MILESTONE, List.of(task.id()),
                        null, milestone.id(), task.key() + " ends after milestone " + milestone.name()));
            }
        }
        return List.copyOf(result);
    }
}
