package team.sprintory_backend.sprint;

import org.junit.jupiter.api.Test;
import team.sprintory_backend.sprint.services.SprintCapacityCalculator;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SprintCapacityCalculatorTest {
    @Test void lastThreeVelocitiesAverageAndAvailabilityScaling() {
        assertEquals(30, SprintCapacityCalculator.suggest(List.of(28, 30, 32), 50, 50));
        assertEquals(27, SprintCapacityCalculator.suggest(List.of(28, 30, 32), 45, 50));
    }

    @Test void noHistoryAndNoWorkingDaysHaveNoSuggestion() {
        assertNull(SprintCapacityCalculator.suggest(List.of(), 45, 50));
        assertNull(SprintCapacityCalculator.suggest(List.of(30), 0, 0));
    }

    @Test void workingDaysCountWeekendsCorrectly() {
        assertEquals(10, SprintCapacityCalculator.workingDays(
                LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 22)));
        assertEquals(0, SprintCapacityCalculator.workingDays(
                LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 8)));
    }
}
