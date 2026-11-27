package team.sprintory_backend.milestone.dto;

import org.junit.jupiter.api.Test;
import team.sprintory_backend.milestone.dto.MilestoneProgress.Basis;
import team.sprintory_backend.milestone.models.MilestoneStatus;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The progress rule of docs/milestones-docs.md §4, with the PMP-46 acceptance criteria. */
class MilestoneProgressTest {

    @Test
    void pointsOfDoneTasksOverAllPoints() {
        // criterion 15: tasks of 5, 3 and 2 points, the 5-point one done
        MilestoneProgress progress = MilestoneProgress.of(3, 1, 10, 5);

        assertEquals(new MilestoneProgress(3, 1, 10, 5, Basis.POINTS, 50), progress);
    }

    @Test
    void noEstimatesAtAllUsesTheTaskCount() {
        // criterion 16
        MilestoneProgress progress = MilestoneProgress.of(3, 1, 0, 0);

        assertEquals(Basis.TASKS, progress.basis());
        assertEquals(33, progress.percent(), "rounded down");
    }

    @Test
    void oneEstimateIsEnoughForPoints() {
        // one 4-point task plus two unestimated ones, the 4-point one done
        MilestoneProgress progress = MilestoneProgress.of(3, 1, 4, 4);

        assertEquals(Basis.POINTS, progress.basis());
        assertEquals(100, progress.percent());
        assertEquals(MilestoneStatus.UPCOMING,
                MilestoneStatus.of(LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 1), 1, 3),
                "100% of the points is not reached while unestimated tasks are open");
    }

    @Test
    void percentIsRoundedDownSoItShows100OnlyWhenAllPointsAreDone() {
        assertEquals(99, MilestoneProgress.of(2, 1, 200, 199).percent());
        assertEquals(100, MilestoneProgress.of(2, 2, 200, 200).percent());
    }

    @Test
    void noTasksHasNoPercent() {
        MilestoneProgress progress = MilestoneProgress.of(0, 0, 0, 0);

        assertEquals(Basis.TASKS, progress.basis());
        assertNull(progress.percent(), "an empty milestone is never shown as 100%");
    }
}
