package team.sprintory_backend.notification.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.notification.dto.WatchResponse;
import team.sprintory_backend.notification.services.TaskWatcherService;
import team.sprintory_backend.security.userdetails.AppUserDetails;

/** R27: watch or unwatch a task. Contract: docs/notifications-docs.md §3.8–3.10. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}/watch")
@RequiredArgsConstructor
public class TaskWatchController {

    private final TaskWatcherService watcherService;

    @GetMapping
    public WatchResponse get(@AuthenticationPrincipal AppUserDetails principal,
                             @PathVariable Long projectId, @PathVariable Long taskId) {
        return watcherService.isWatching(principal.getUser(), projectId, taskId);
    }

    @PutMapping
    public WatchResponse watch(@AuthenticationPrincipal AppUserDetails principal,
                               @PathVariable Long projectId, @PathVariable Long taskId) {
        return watcherService.watch(principal.getUser(), projectId, taskId);
    }

    @DeleteMapping
    public WatchResponse unwatch(@AuthenticationPrincipal AppUserDetails principal,
                                 @PathVariable Long projectId, @PathVariable Long taskId) {
        return watcherService.unwatch(principal.getUser(), projectId, taskId);
    }
}