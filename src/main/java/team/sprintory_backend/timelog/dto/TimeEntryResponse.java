package team.sprintory_backend.timelog.dto;

import team.sprintory_backend.timelog.models.TimeEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

public record TimeEntryResponse(Long id, Long taskId, LocalDate workDate, int minutes,
                                String note, Instant createdAt, Instant updatedAt) {
    public static TimeEntryResponse from(TimeEntry entry) {
        return new TimeEntryResponse(entry.getId(), entry.getTaskId(), entry.getWorkDate(),
                entry.getMinutes(), entry.getNote(), toInstant(entry.getCreatedAt()), toInstant(entry.getUpdatedAt()));
    }

    private static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant();
    }
}
