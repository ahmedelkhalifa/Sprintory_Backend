package team.sprintory_backend.timelog.dto;

import java.time.LocalDate;

public record TimeSummaryResponse(long ownMinutes, long subtasksMinutes, long totalMinutes,
                                  boolean canLog, LocalDate today) { }
