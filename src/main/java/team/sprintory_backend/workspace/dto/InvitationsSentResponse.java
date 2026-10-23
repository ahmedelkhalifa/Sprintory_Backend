package team.sprintory_backend.workspace.dto;

import java.util.List;

/** Addresses that were emailed, and addresses skipped because they already belong to the workspace. */
public record InvitationsSentResponse(List<String> invited, List<String> alreadyMembers) {}