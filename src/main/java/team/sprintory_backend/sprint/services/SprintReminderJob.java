package team.sprintory_backend.sprint.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import team.sprintory_backend.notification.events.SprintOverdue;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.sprint.models.SprintStatus;
import team.sprintory_backend.sprint.repositories.SprintRepository;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * PMP-41: once a day, while an active sprint is past its end date, its Scrum Masters get a
 * reminder to close it. The job only publishes {@link SprintOverdue}; the notification listener
 * decides the recipients. Each sprint gets its own transaction (notifications are written inside
 * one), so a failure in one project never stops the reminders of the others.
 */
@Component
public class SprintReminderJob {

    private static final Logger log = LoggerFactory.getLogger(SprintReminderJob.class);

    private final SprintRepository sprints;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;

    public SprintReminderJob(SprintRepository sprints, ApplicationEventPublisher events,
                             PlatformTransactionManager transactionManager) {
        this.sprints = sprints;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "UTC")
    public int run() {
        return run(LocalDate.now(ZoneOffset.UTC));
    }

    /** Sends today's reminders; returns how many sprints got one. */
    int run(LocalDate today) {
        int sent = 0;
        for (Long sprintId : sprints.findOverdueActiveIds(today)) {
            try {
                Boolean published = transactions.execute(status -> remind(sprintId, today));
                if (Boolean.TRUE.equals(published)) sent++;
            } catch (RuntimeException e) {
                log.warn("Overdue reminder failed for sprint {}", sprintId, e);
            }
        }
        if (sent > 0) log.info("Sent overdue-sprint reminders for {} sprint(s)", sent);
        return sent;
    }

    private boolean remind(Long sprintId, LocalDate today) {
        // Re-read inside the transaction: the sprint may have been closed since the id query.
        Sprint sprint = sprints.findById(sprintId).orElse(null);
        if (sprint == null || sprint.getStatus() != SprintStatus.ACTIVE || !sprint.getEndDate().isBefore(today)) {
            return false;
        }
        events.publishEvent(new SprintOverdue(sprint.getProject(), sprint.getName(),
                ChronoUnit.DAYS.between(sprint.getEndDate(), today)));
        return true;
    }
}