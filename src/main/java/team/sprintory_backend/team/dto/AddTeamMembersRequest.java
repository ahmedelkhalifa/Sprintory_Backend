package team.sprintory_backend.team.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** User ids to add; every one must already be a member of the team's workspace. */
public record AddTeamMembersRequest(
        @NotEmpty @Size(max = 100) List<@NotNull Long> userIds
) {}