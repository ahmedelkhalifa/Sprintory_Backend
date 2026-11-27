package team.sprintory_backend.milestone.dto;

/**
 * A milestone's progress over its live top-level tasks (docs/milestones-docs.md §4), calculated on
 * every read. Unestimated tasks count 0 points.
 */
public record MilestoneProgress(long totalTasks, long doneTasks, long totalPoints, long donePoints,
                                Basis basis, Integer percent) {

    /** What {@code percent} is measured in: points as soon as any linked task has some. */
    public enum Basis { POINTS, TASKS }

    public static MilestoneProgress of(long totalTasks, long doneTasks, long totalPoints, long donePoints) {
        Basis basis = totalPoints > 0 ? Basis.POINTS : Basis.TASKS;
        Integer percent = null;
        if (totalTasks > 0) {
            percent = (int) (basis == Basis.POINTS ? donePoints * 100 / totalPoints : doneTasks * 100 / totalTasks);
        }
        return new MilestoneProgress(totalTasks, doneTasks, totalPoints, donePoints, basis, percent);
    }
}
