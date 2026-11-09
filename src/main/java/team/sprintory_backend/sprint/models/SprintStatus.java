package team.sprintory_backend.sprint.models;

/**
 * A sprint's life (R14, R15): many PLANNED, at most one ACTIVE per project. It ends as COMPLETED
 * (closed by the Scrum Master) or CANCELLED (Product Owner or Project Manager); both are read-only.
 */
public enum SprintStatus {
    PLANNED, ACTIVE, COMPLETED, CANCELLED;

    /** Closed or cancelled: no more changes to its items or settings. */
    public boolean isEnded() {
        return this == COMPLETED || this == CANCELLED;
    }
}