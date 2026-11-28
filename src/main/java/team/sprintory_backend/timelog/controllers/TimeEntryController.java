package team.sprintory_backend.timelog.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.timelog.dto.TimeEntryRequest;
import team.sprintory_backend.timelog.dto.TimeEntryResponse;
import team.sprintory_backend.timelog.dto.TimeSummaryResponse;
import team.sprintory_backend.timelog.services.TimeEntryService;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TimeEntryController {
    private final TimeEntryService time;

    @PostMapping("/tasks/{taskId}/time-entries")
    public ResponseEntity<TimeEntryResponse> create(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long taskId, @Valid @RequestBody TimeEntryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(time.create(principal.getUser(), taskId, request));
    }

    @PutMapping("/time-entries/{entryId}")
    public TimeEntryResponse update(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long entryId, @Valid @RequestBody TimeEntryRequest request) {
        return time.update(principal.getUser(), entryId, request);
    }

    @DeleteMapping("/time-entries/{entryId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long entryId) {
        time.delete(principal.getUser(), entryId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tasks/{taskId}/time-entries")
    public List<TimeEntryResponse> mine(@AuthenticationPrincipal AppUserDetails principal,
            @PathVariable Long taskId, @RequestParam(defaultValue = "true") boolean mine) {
        return time.mine(principal.getUser(), taskId, mine);
    }

    @GetMapping("/tasks/{taskId}/time-summary")
    public TimeSummaryResponse summary(@AuthenticationPrincipal AppUserDetails principal, @PathVariable Long taskId) {
        return time.summary(principal.getUser(), taskId);
    }
}
