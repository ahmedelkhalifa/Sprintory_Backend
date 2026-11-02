package team.sprintory_backend.audit.dto;

/** Someone who appears as an actor in a workspace's audit log, named as in their latest entry. */
public record AuditActorResponse(Long id, String name, String email) {
}