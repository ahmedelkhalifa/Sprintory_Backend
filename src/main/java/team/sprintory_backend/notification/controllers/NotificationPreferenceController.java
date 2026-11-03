package team.sprintory_backend.notification.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.notification.dto.NotificationPreferencesResponse;
import team.sprintory_backend.notification.dto.UpdateNotificationPreferencesRequest;
import team.sprintory_backend.notification.services.NotificationPreferenceService;
import team.sprintory_backend.security.userdetails.AppUserDetails;

/** R27: the caller's own notification preferences. Contract: docs/notifications-docs.md §3.6–3.7. */
@RestController
@RequestMapping("/api/v1/notification-preferences")
@RequiredArgsConstructor
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;

    @GetMapping
    public NotificationPreferencesResponse get(@AuthenticationPrincipal AppUserDetails principal) {
        return preferenceService.get(principal.getUser());
    }

    /** Partial: only the listed categories change; a null channel is unchanged. */
    @PutMapping
    public NotificationPreferencesResponse update(@AuthenticationPrincipal AppUserDetails principal,
                                                  @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return preferenceService.update(principal.getUser(), request);
    }
}