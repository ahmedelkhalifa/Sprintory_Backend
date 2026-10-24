package team.sprintory_backend.project.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.project.models.ProjectRole;
import team.sprintory_backend.user.models.Role;
import team.sprintory_backend.workspace.dto.ChangeWorkspaceRoleRequest;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** DTO validation for the R03 role endpoints (the service enforces project role combinations). */
class RoleRequestValidationTest {

    @Test
    void workspaceRoleMustBePresent() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertFalse(validator.validate(new ChangeWorkspaceRoleRequest(null)).isEmpty());
            assertTrue(validator.validate(new ChangeWorkspaceRoleRequest(Role.WORKSPACE_MEMBER)).isEmpty());
            assertTrue(validator.validate(new ChangeWorkspaceRoleRequest(Role.WORKSPACE_ADMIN)).isEmpty());
        }
    }

    @Test
    void projectRolesMustBePresentAndMayNotContainNull() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertFalse(validator.validate(new SetProjectRolesRequest(null)).isEmpty());
            Set<ProjectRole> withNull = new HashSet<>();
            withNull.add(null);
            assertFalse(validator.validate(new SetProjectRolesRequest(withNull)).isEmpty());
            assertTrue(validator.validate(new SetProjectRolesRequest(Set.of(ProjectRole.PROJECT_MANAGER))).isEmpty());
        }
    }

    @Test
    void emptyAndConflictingRoleSetsReachServiceForDomainSpecificErrors() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertTrue(validator.validate(new SetProjectRolesRequest(Set.of())).isEmpty());
            assertTrue(validator.validate(new SetProjectRolesRequest(
                    Set.of(ProjectRole.VIEWER, ProjectRole.MEMBER))).isEmpty());
        }
    }
}
