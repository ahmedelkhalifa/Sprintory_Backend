package team.sprintory_backend.schedule.services;

import org.junit.jupiter.api.Test;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.schedule.dto.ScheduleConflictType;
import team.sprintory_backend.schedule.dto.ScheduleDependency;
import team.sprintory_backend.schedule.dto.ScheduleMilestone;
import team.sprintory_backend.schedule.dto.ScheduleTask;
import team.sprintory_backend.task.models.TaskType;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleConflictsTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    private static final ScheduleDependency LINK = new ScheduleDependency(1L, 10L, 11L);

    private ScheduleTask task(long id, LocalDate start, LocalDate due, BoardColumnCategory category, Long milestone) {
        return new ScheduleTask(id, "PMP-" + id, "Task", start, due, category, 1L, "Status",
                TaskType.TASK, null, null, null, milestone);
    }

    @Test void inclusiveBoundaryAndNextDay() {
        var blocker = task(10, DAY.minusDays(2), DAY, BoardColumnCategory.TODO, null);
        var overlap = task(11, DAY, DAY.plusDays(3), BoardColumnCategory.TODO, null);
        var next = task(11, DAY.plusDays(1), DAY.plusDays(3), BoardColumnCategory.TODO, null);
        var conflict = ScheduleConflicts.find(List.of(blocker, overlap), List.of(LINK), List.of()).get(0);
        assertEquals(ScheduleConflictType.DEPENDENCY, conflict.type());
        assertEquals(List.of(10L, 11L), conflict.taskIds());
        assertEquals(1L, conflict.dependencyId());
        assertEquals("PMP-11 starts before PMP-10 ends", conflict.message());
        assertTrue(ScheduleConflicts.find(List.of(blocker, next), List.of(LINK), List.of()).isEmpty());
    }

    @Test void doneBlockerUnscheduledAndMissingEndpointsAreIgnored() {
        var done = task(10, DAY, DAY, BoardColumnCategory.DONE, null);
        var unscheduled = task(10, null, null, BoardColumnCategory.TODO, null);
        var blocked = task(11, DAY, DAY, BoardColumnCategory.TODO, null);
        for (var rows : List.of(List.of(done, blocked), List.of(unscheduled, blocked), List.of(blocked))) {
            assertTrue(ScheduleConflicts.find(rows, List.of(LINK), List.of()).isEmpty());
        }
    }

    @Test void milestoneLateOpenTaskOnly() {
        var milestone = new ScheduleMilestone(5L, "Beta release", DAY);
        var late = task(11, DAY, DAY.plusDays(1), BoardColumnCategory.IN_PROGRESS, 5L);
        var onTime = task(12, DAY, DAY, BoardColumnCategory.TODO, 5L);
        var done = task(13, DAY, DAY.plusDays(1), BoardColumnCategory.DONE, 5L);
        var unscheduled = task(14, null, null, BoardColumnCategory.TODO, 5L);
        var conflicts = ScheduleConflicts.find(List.of(late, onTime, done, unscheduled), List.of(), List.of(milestone));
        assertEquals(1, conflicts.size());
        assertEquals(ScheduleConflictType.MILESTONE, conflicts.get(0).type());
        assertEquals("PMP-11 ends after milestone Beta release", conflicts.get(0).message());
        assertEquals(5L, conflicts.get(0).milestoneId());
    }
}
