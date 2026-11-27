package team.sprintory_backend.schedule.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.schedule.api.ScheduleFilters;
import team.sprintory_backend.schedule.dto.ScheduleResponse;
import team.sprintory_backend.schedule.services.ScheduleService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.task.models.TaskType;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/schedule")
@RequiredArgsConstructor
public class ScheduleController {
    private final ScheduleService schedule;

    @GetMapping
    public ScheduleResponse get(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long projectId,
                                @RequestParam(required = false) Long assigneeId,
                                @RequestParam(required = false) Long statusId,
                                @RequestParam(required = false) BoardColumnCategory statusCategory,
                                @RequestParam(required = false) Long milestoneId,
                                @RequestParam(required = false) Long sprintId,
                                @RequestParam(required = false) TaskType type,
                                @RequestParam(required = false) Boolean hideDone,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return schedule.get(principal.getUser(), projectId, new ScheduleFilters(assigneeId, statusId,
                statusCategory, milestoneId, sprintId, type, hideDone, from, to));
    }
}
