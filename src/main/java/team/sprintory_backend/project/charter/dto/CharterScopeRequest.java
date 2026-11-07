package team.sprintory_backend.project.charter.dto;

/** PUT replaces both fields. null clears a field; send unchanged values to preserve them. */
public record CharterScopeRequest(String inScope, String outOfScope) { }
