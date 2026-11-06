package team.sprintory_backend.project.charter.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import team.sprintory_backend.project.charter.models.StakeholderLevel;

public record CharterStakeholderRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 200) String organization,
        @Size(max = 100) String projectRole,
        StakeholderLevel interest,
        StakeholderLevel influence,
        @Email @Size(max = 254) String contactEmail,
        Long linkedUserId) { }
