package team.sprintory_backend.notification.models;

/** PENDING until sent; FAILED after the last retry. */
public enum EmailOutboxStatus {
    PENDING,
    SENT,
    FAILED
}