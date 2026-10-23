package team.sprintory_backend.workspace.dto;

/** What the invitation page shows before the invitee logs in or registers. */
public record InvitationLookupResponse(String workspaceName, String email) {}