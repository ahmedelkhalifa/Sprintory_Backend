package team.sprintory_backend.workspace.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.project.dto.AddProjectMembersRequest;
import team.sprintory_backend.project.dto.AddTeamToProjectRequest;
import team.sprintory_backend.project.dto.CreateProjectRequest;
import team.sprintory_backend.team.dto.AddTeamMembersRequest;
import team.sprintory_backend.team.dto.CreateTeamRequest;
import team.sprintory_backend.team.dto.UpdateTeamRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/** The request DTO rules for workspaces, invitations, teams and projects (R02). */
class WorkspaceRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    // --- names ---

    @Test
    void namesAreRequiredAndAtMostOneHundredCharacters() {
        String tooLong = "a".repeat(101);
        String longest = "a".repeat(100);

        for (String bad : new String[]{null, "", "   ", tooLong}) {
            assertFalse(validator.validate(new CreateWorkspaceRequest(bad)).isEmpty(), "workspace: " + bad);
            assertFalse(validator.validate(new UpdateWorkspaceRequest(bad)).isEmpty(), "rename: " + bad);
            assertFalse(validator.validate(new CreateTeamRequest(bad)).isEmpty(), "team: " + bad);
            assertFalse(validator.validate(new UpdateTeamRequest(bad)).isEmpty(), "team rename: " + bad);
            assertFalse(validator.validate(new CreateProjectRequest(bad)).isEmpty(), "project: " + bad);
        }
        assertTrue(validator.validate(new CreateWorkspaceRequest(longest)).isEmpty());
        assertTrue(validator.validate(new CreateTeamRequest(longest)).isEmpty());
        assertTrue(validator.validate(new CreateProjectRequest(longest)).isEmpty());
    }

    // --- invitations ---

    @Test
    void inviteNeedsOneToFiftyValidEmails() {
        List<String> fifty = Collections.nCopies(50, "a@example.com");
        List<String> fiftyOne = Collections.nCopies(51, "a@example.com");

        assertTrue(validator.validate(new InviteMembersRequest(List.of("grace@example.com"))).isEmpty());
        assertTrue(validator.validate(new InviteMembersRequest(fifty)).isEmpty());
        assertFalse(validator.validate(new InviteMembersRequest(List.of())).isEmpty());
        assertFalse(validator.validate(new InviteMembersRequest(null)).isEmpty());
        assertFalse(validator.validate(new InviteMembersRequest(fiftyOne)).isEmpty());
    }

    @Test
    void inviteNamesTheInvalidEntry() {
        Set<ConstraintViolation<InviteMembersRequest>> violations = validator.validate(
                new InviteMembersRequest(List.of("grace@example.com", "not-an-email", " ")));

        // Entry 1 is not an email; entry 2 is blank, which breaks both @NotBlank and @Email.
        Set<String> refused = violations.stream()
                .map(v -> v.getPropertyPath().toString().substring(0, "emails[0]".length()))
                .collect(Collectors.toSet());
        assertEquals(Set.of("emails[1]", "emails[2]"), refused);
    }

    @Test
    void invitationTokenIsRequired() {
        assertFalse(validator.validate(new InvitationTokenRequest(" ")).isEmpty());
        assertFalse(validator.validate(new InvitationTokenRequest(null)).isEmpty());
        assertTrue(validator.validate(new InvitationTokenRequest("tok")).isEmpty());
    }

    // --- user ids ---

    @Test
    void userIdListsNeedOneToOneHundredNonNullIds() {
        List<Long> hundred = LongStream.rangeClosed(1, 100).boxed().toList();
        List<Long> hundredOne = LongStream.rangeClosed(1, 101).boxed().toList();
        List<Long> withNull = new ArrayList<>(Arrays.asList(1L, null));

        assertTrue(validator.validate(new AddTeamMembersRequest(hundred)).isEmpty());
        assertTrue(validator.validate(new AddProjectMembersRequest(hundred)).isEmpty());
        for (List<Long> bad : Arrays.asList(null, List.<Long>of(), hundredOne, withNull)) {
            assertFalse(validator.validate(new AddTeamMembersRequest(bad)).isEmpty(), "team: " + bad);
            assertFalse(validator.validate(new AddProjectMembersRequest(bad)).isEmpty(), "project: " + bad);
        }
    }

    @Test
    void addingATeamToAProjectNeedsTheTeamId() {
        assertFalse(validator.validate(new AddTeamToProjectRequest(null)).isEmpty());
        assertTrue(validator.validate(new AddTeamToProjectRequest(4L)).isEmpty());
    }
}