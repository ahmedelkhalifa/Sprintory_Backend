package team.sprintory_backend.task.services;

/**
 * Ranks that order tasks on the board and in the backlog. They are sparse ({@link #GAP} apart),
 * so moving a task takes the midpoint between its new neighbours and updates only that task.
 * When two neighbours have no room left between them, the caller renumbers the whole list once.
 * Ranks may be negative (a task moved before the first one).
 */
public final class TaskRanks {

    static final long GAP = 1_000_000L;

    private TaskRanks() {
    }

    /** The rank for the last place: after {@code highest}, or {@link #GAP} in an empty list. */
    static long last(Long highest) {
        return highest == null ? GAP : highest + GAP;
    }

    /**
     * Ranks for {@code count} tasks placed at the top of a list, in the given order, above
     * {@code lowest} (the current first rank; null for an empty list). Used when an ended sprint
     * returns its unfinished work to the top of the backlog (PMP-41).
     */
    public static long[] above(Long lowest, int count) {
        long first = (lowest == null ? 0 : lowest) - GAP * count;
        long[] ranks = new long[count];
        for (int i = 0; i < count; i++) {
            ranks[i] = first + GAP * i;
        }
        return ranks;
    }

    /**
     * A rank strictly between the two neighbours, where null means "no neighbour on that side".
     * Returns null when there is no room, i.e. the list has to be renumbered.
     */
    static Long between(Long previous, Long next) {
        if (previous == null && next == null) {
            return GAP;
        }
        if (previous == null) {
            return next - GAP;
        }
        if (next == null) {
            return previous + GAP;
        }
        long room = next - previous;
        return room > 1 ? previous + room / 2 : null;
    }
}