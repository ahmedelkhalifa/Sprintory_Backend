package team.sprintory_backend.sprint.services;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Capacity recommendation only: nobody is forced to accept it. */
public final class SprintCapacityCalculator {
    private SprintCapacityCalculator() {}

    public static int workingDays(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)) return 0;
        if (ChronoUnit.DAYS.between(start, end) > 366) throw new IllegalArgumentException("Sprint too long");
        int count = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) count++;
        }
        return count;
    }

    /** A null recommendation means no historical velocity; round to nearest whole point. */
    public static Integer suggest(List<Integer> completedVelocities, int availablePersonDays, int normalPersonDays) {
        if (completedVelocities.isEmpty() || normalPersonDays == 0) return null;
        double mean = completedVelocities.stream().mapToInt(Integer::intValue).average().orElse(0);
        return (int) Math.round(mean * availablePersonDays / normalPersonDays);
    }
}
