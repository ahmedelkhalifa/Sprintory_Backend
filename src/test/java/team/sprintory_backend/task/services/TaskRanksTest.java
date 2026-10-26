package team.sprintory_backend.task.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static team.sprintory_backend.task.services.TaskRanks.GAP;

class TaskRanksTest {

    @Test
    void lastIsOneGapAfterTheHighestOrTheFirstGapInAnEmptyList() {
        assertEquals(GAP, TaskRanks.last(null));
        assertEquals(5 * GAP, TaskRanks.last(4 * GAP));
    }

    @Test
    void betweenTakesTheMidpointOrOneGapBeyondASingleNeighbour() {
        assertEquals(GAP, TaskRanks.between(null, null));
        assertEquals(0L, TaskRanks.between(null, GAP));
        assertEquals(3 * GAP, TaskRanks.between(2 * GAP, null));
        assertEquals(GAP + GAP / 2, TaskRanks.between(GAP, 2 * GAP));
        assertEquals(-GAP - GAP / 2, TaskRanks.between(-2 * GAP, -GAP), "ranks may be negative");
    }

    @Test
    void betweenReportsNoRoomWhenTheNeighboursAreAdjacent() {
        assertEquals(11L, TaskRanks.between(10L, 12L));
        assertNull(TaskRanks.between(10L, 11L));
        assertNull(TaskRanks.between(10L, 10L));
    }
}