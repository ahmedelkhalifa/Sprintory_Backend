package team.sprintory_backend.sprint.services;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.sprint.models.SprintStatus;
import team.sprintory_backend.sprint.repositories.SprintRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** PMP-42: the nightly burndown snapshot. */
class SprintSnapshotJobTest {

    @Test
    void snapshotsActiveSprintsAndSkipsOnesThatEndedSinceTheQuery() {
        LocalDate today = LocalDate.of(2026, 12, 2);
        SprintRepository sprints = mock(SprintRepository.class);
        SprintSnapshotWriter writer = mock(SprintSnapshotWriter.class);
        Sprint active = new Sprint(null, 1, "MOB Sprint 1", "Goal", null, today, today.plusDays(13));
        active.setStatus(SprintStatus.ACTIVE);
        Sprint closed = new Sprint(null, 2, "MOB Sprint 2", "Goal", null, today, today.plusDays(13));
        closed.setStatus(SprintStatus.COMPLETED);
        when(sprints.findActiveIds()).thenReturn(List.of(1L, 2L));
        when(sprints.findById(1L)).thenReturn(Optional.of(active));
        when(sprints.findById(2L)).thenReturn(Optional.of(closed));

        new SprintSnapshotJob(sprints, writer, mock(PlatformTransactionManager.class)).run(today);

        verify(writer).record(same(active), eq(today));
        verify(writer, never()).record(same(closed), any());
    }
}
