package team.sprintory_backend.milestone.models;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static team.sprintory_backend.milestone.models.MilestoneStatus.*;

/** The status rule of docs/milestones-docs.md §4, with the PMP-46 acceptance criteria. */
class MilestoneStatusTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);
    private static final LocalDate PAST = TODAY.minusDays(1);
    private static final LocalDate FUTURE = TODAY.plusDays(10);

    @Test
    void noTasksIsEmptyWhateverTheDate() {
        assertEquals(EMPTY, MilestoneStatus.of(TODAY, FUTURE, 0, 0));
        assertEquals(EMPTY, MilestoneStatus.of(TODAY, PAST, 0, 0));
    }

    @Test
    void allTasksDoneIsReachedEvenAfterTheDate() {
        // criterion 17
        assertEquals(REACHED, MilestoneStatus.of(TODAY, FUTURE, 3, 3));
        assertEquals(REACHED, MilestoneStatus.of(TODAY, PAST, 3, 3));
    }

    @Test
    void anOpenTaskAfterTheDateIsOverdue() {
        // criterion 18
        assertEquals(OVERDUE, MilestoneStatus.of(TODAY, PAST, 2, 3));
    }

    @Test
    void anOpenTaskOnOrBeforeTheDateIsUpcoming() {
        assertEquals(UPCOMING, MilestoneStatus.of(TODAY, FUTURE, 2, 3));
        assertEquals(UPCOMING, MilestoneStatus.of(TODAY, TODAY, 0, 3), "the target date itself is not overdue");
    }

    @Test
    void reachedIsNotPermanent() {
        // criterion 20: an open task added to a reached milestone
        assertEquals(REACHED, MilestoneStatus.of(TODAY, FUTURE, 3, 3));
        assertEquals(UPCOMING, MilestoneStatus.of(TODAY, FUTURE, 3, 4));
        assertEquals(OVERDUE, MilestoneStatus.of(TODAY, PAST, 3, 4));
    }
}
