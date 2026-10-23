package team.sprintory_backend.workspace.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Up to 50 addresses per request, so one call cannot send an unbounded number of emails. */
public record InviteMembersRequest(
        @NotEmpty @Size(max = 50) List<@NotBlank @Email String> emails
) {}