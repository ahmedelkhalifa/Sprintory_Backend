package team.sprintory_backend.notification.repositories;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import team.sprintory_backend.notification.models.EmailOutboxMessage;
import team.sprintory_backend.notification.models.EmailOutboxStatus;

public interface EmailOutboxRepository extends JpaRepository<EmailOutboxMessage, Long> {

    /** The emails whose next attempt is due, oldest first; the page caps one worker run. */
    @Query("select m.id from EmailOutboxMessage m where m.status = :status and m.nextAttemptAt <= :now "
            + "order by m.nextAttemptAt, m.id")
    List<Long> findDueIds(@Param("status") EmailOutboxStatus status, @Param("now") Instant now, Pageable page);

    /** Retention: finished (SENT or FAILED) emails older than the cutoff go; PENDING ones never. */
    @Modifying
    @Query("delete from EmailOutboxMessage m where m.status in :statuses and m.createdAt < :cutoff")
    int deleteFinishedBefore(@Param("statuses") Collection<EmailOutboxStatus> statuses,
                             @Param("cutoff") Instant cutoff);
}