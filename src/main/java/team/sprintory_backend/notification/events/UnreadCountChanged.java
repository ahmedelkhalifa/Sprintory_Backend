package team.sprintory_backend.notification.events;

/**
 * Internal: someone's unread count changed by reading (open, read-all). The live stream pushes
 * the new count to all their tabs after the transaction commits. Producers never publish this.
 */
public record UnreadCountChanged(Long userId) {}
