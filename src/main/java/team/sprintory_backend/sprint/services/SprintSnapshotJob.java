package team.sprintory_backend.sprint.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import team.sprintory_backend.sprint.models.Sprint;
import team.sprintory_backend.sprint.models.SprintStatus;
import team.sprintory_backend.sprint.repositories.SprintRepository;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * PMP-42: at the end of every day (UTC), saves the burndown snapshot of every active sprint
 * (docs/burndown-docs.md §5). Each sprint gets its own transaction, so one failure never stops
 * the others; a missed day is carried forward by the chart.
 */
@Component
public class SprintSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(SprintSnapshotJob.class);

    private final SprintRepository sprints;
    private final SprintSnapshotWriter snapshots;
    private final TransactionTemplate transactions;

    public SprintSnapshotJob(SprintRepository sprints, SprintSnapshotWriter snapshots,
                             PlatformTransactionManager transactionManager) {
        this.sprints = sprints;
        this.snapshots = snapshots;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "0 55 23 * * *", zone = "UTC")
    public void run() {
        run(LocalDate.now(ZoneOffset.UTC));
    }

    void run(LocalDate today) {
        for (Long sprintId : sprints.findActiveIds()) {
            try {
                transactions.executeWithoutResult(status -> {
                    // Re-read inside the transaction: the sprint may have ended since the id query.
                    Sprint sprint = sprints.findById(sprintId).orElse(null);
                    if (sprint != null && sprint.getStatus() == SprintStatus.ACTIVE) {
                        snapshots.record(sprint, today);
                    }
                });
            } catch (RuntimeException e) {
                log.warn("Burndown snapshot failed for sprint {}", sprintId, e);
            }
        }
    }
}
