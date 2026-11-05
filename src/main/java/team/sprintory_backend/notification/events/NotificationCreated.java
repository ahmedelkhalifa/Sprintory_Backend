package team.sprintory_backend.notification.events;

import team.sprintory_backend.notification.dto.NotificationResponse;

/**
 * Internal: a notification row was written. The live stream pushes it after the transaction
 * commits, so a rolled-back action never reaches a browser. Producers never publish this.
 */
public record NotificationCreated(Long recipientId, NotificationResponse notification) {}
