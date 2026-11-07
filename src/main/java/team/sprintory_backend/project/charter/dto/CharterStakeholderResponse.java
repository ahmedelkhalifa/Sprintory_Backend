package team.sprintory_backend.project.charter.dto;

import team.sprintory_backend.project.charter.models.CharterStakeholder;
import team.sprintory_backend.project.charter.models.StakeholderLevel;
import team.sprintory_backend.user.models.User;

public record CharterStakeholderResponse(
        Long id, String name, String organization, String projectRole,
        StakeholderLevel interest, StakeholderLevel influence, String contactEmail,
        Long linkedUserId, String linkedUserName) {
    public static CharterStakeholderResponse from(CharterStakeholder s) {
        User linked = s.getLinkedUser();
        return new CharterStakeholderResponse(s.getId(), s.getName(), s.getOrganization(),
                s.getProjectRole(), s.getInterest(), s.getInfluence(), s.getContactEmail(),
                linked == null ? null : linked.getId(),
                linked == null ? null : (linked.getFirstName() + " " + linked.getLastName()).trim());
    }
}
