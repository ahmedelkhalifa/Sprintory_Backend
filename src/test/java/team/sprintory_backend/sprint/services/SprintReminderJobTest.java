package team.sprintory_backend.sprint.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import team.sprintory_backend.notification.events.SprintOverdue;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.sprint.models.SprintStatus;
import team.sprintory_backend.sprint.repositories.SprintRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** PMP-41: the daily reminder while an active sprint is past its end date. */
class SprintReminderJobTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    private SprintRepository sprints;
    private ApplicationEventPublisher events;
    private PlatformTransactionManager transactionManager;
    private SprintReminderJob job;
    private Project project;

    @BeforeEach
    void setUp() {
        sprints = mock(SprintRepository.class);
        events = mock(ApplicationEventPublisher.class);
        transactionManager = mock(PlatformTransactionManager.class);
        job = new SprintReminderJob(sprints, events, transactionManager);
        project = Project.builder().id(10L).name("Mobile App").key("MOB").build();
    }

    private Sprint sprint(long id, SprintStatus status, LocalDate end) {
        Sprint sprint = new Sprint(project, (int) id, "MOB Sprint " + id, "Goal", null, end.minusDays(13), end);
        sprint.setId(id);
        sprint.setStatus(status);
        when(sprints.findById(id)).thenReturn(Optional.of(sprint));
        return sprint;
    }

    @Test
    void anOverdueActiveSprintPublishesOneReminderWithTheDaysOverdue() {
        sprint(1, SprintStatus.ACTIVE, TODAY.minusDays(2));
        when(sprints.findOverdueActiveIds(TODAY)).thenReturn(List.of(1L));

        assertEquals(1, job.run(TODAY));

        ArgumentCaptor<SprintOverdue> event = ArgumentCaptor.forClass(SprintOverdue.class);
        verify(events).publishEvent(event.capture());
        assertSame(project, event.getValue().project());
        assertEquals("MOB Sprint 1", event.getValue().sprintName());
        assertEquals(2, event.getValue().daysOverdue());
        verify(transactionManager).commit(any());
    }

    @Test
    void aSprintClosedOrMovedSinceTheQueryIsSkipped() {
        sprint(1, SprintStatus.COMPLETED, TODAY.minusDays(2));
        sprint(2, SprintStatus.ACTIVE, TODAY);
        when(sprints.findOverdueActiveIds(TODAY)).thenReturn(List.of(1L, 2L, 3L));

        assertEquals(0, job.run(TODAY));
        verify(events, never()).publishEvent(any());
    }

    @Test
    void oneFailingSprintDoesNotStopTheOthers() {
        sprint(1, SprintStatus.ACTIVE, TODAY.minusDays(1));
        sprint(2, SprintStatus.ACTIVE, TODAY.minusDays(5));
        when(sprints.findOverdueActiveIds(TODAY)).thenReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("mail down")).doNothing().when(events).publishEvent(any(SprintOverdue.class));

        assertEquals(1, job.run(TODAY));
        verify(events, times(2)).publishEvent(any(SprintOverdue.class));
        verify(transactionManager).rollback(any());
    }

    @Test
    void noOverdueSprintsMeansNoTransactionAtAll() {
        when(sprints.findOverdueActiveIds(TODAY)).thenReturn(List.of());
        assertEquals(0, job.run(TODAY));
        verifyNoInteractions(transactionManager, events);
    }
}