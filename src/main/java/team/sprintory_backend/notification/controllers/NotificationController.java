package team.sprintory_backend.notification.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.common.Pageables;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.notification.dto.NotificationResponse;
import team.sprintory_backend.notification.dto.NotificationTargetResponse;
import team.sprintory_backend.notification.dto.UnreadCountResponse;
import team.sprintory_backend.notification.services.NotificationService;
import team.sprintory_backend.security.userdetails.AppUserDetails;

/**
 * R27: the caller's own notifications, across all workspaces. There is no endpoint to create,
 * edit or delete one. Contract: docs/notifications-docs.md §3.1–3.4.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /** {@code ?unreadOnly=&workspaceId=}; {@code ?page=&size=} pages (max 100), newest first. */
    @GetMapping
    public PageResponse<NotificationResponse> list(@AuthenticationPrincipal AppUserDetails principal,
                                                   @RequestParam(required = false) Boolean unreadOnly,
                                                   @RequestParam(required = false) Long workspaceId,
                                                   Pageable pageable) {
        if (workspaceId != null && workspaceId <= 0) {
            throw new BadRequestException("'workspaceId' must be a positive ID.");
        }
        return notificationService.list(principal.getUser(), Boolean.TRUE.equals(unreadOnly), workspaceId,
                Pageables.withoutSort(pageable));
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(@AuthenticationPrincipal AppUserDetails principal) {
        return notificationService.unreadCount(principal.getUser());
    }

    /** Marks it read and says where to navigate; 404 when the target can no longer be seen. */
    @PostMapping("/{id}/open")
    public NotificationTargetResponse open(@AuthenticationPrincipal AppUserDetails principal,
                                           @PathVariable Long id) {
        return notificationService.open(principal.getUser(), id);
    }

    @PostMapping("/read-all")
    public UnreadCountResponse readAll(@AuthenticationPrincipal AppUserDetails principal) {
        return notificationService.readAll(principal.getUser());
    }
}