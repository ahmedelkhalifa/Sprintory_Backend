package team.sprintory_backend.retro.models;

/** A retrospective is OPEN until a manager closes it; CLOSED is read-only and final (R31). */
public enum RetroStatus {
    OPEN,
    CLOSED
}
