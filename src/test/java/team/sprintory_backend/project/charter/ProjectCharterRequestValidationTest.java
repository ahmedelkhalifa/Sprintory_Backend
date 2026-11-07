package team.sprintory_backend.project.charter;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.project.charter.dto.CharterGoalRequest;
import team.sprintory_backend.project.charter.dto.CharterStakeholderRequest;
import static org.junit.jupiter.api.Assertions.*;

/** DTO validation is enforced by @Valid on every mutating controller endpoint. */
class ProjectCharterRequestValidationTest {
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsMissingAndOversizedGoalTitles() {
        assertFalse(VALIDATOR.validate(new CharterGoalRequest("  ", null)).isEmpty());
        assertFalse(VALIDATOR.validate(new CharterGoalRequest(null, null)).isEmpty());
        assertFalse(VALIDATOR.validate(new CharterGoalRequest("x".repeat(201), null)).isEmpty());
        assertTrue(VALIDATOR.validate(new CharterGoalRequest("Valid goal", null)).isEmpty());
    }

    @Test
    void rejectsInvalidEmailAndMissingStakeholderName() {
        assertFalse(VALIDATOR.validate(new CharterStakeholderRequest("Acme", null, null,
                null, null, "not-an-email", null)).isEmpty());
        assertFalse(VALIDATOR.validate(new CharterStakeholderRequest("", null, null,
                null, null, null, null)).isEmpty());
        assertTrue(VALIDATOR.validate(new CharterStakeholderRequest("Acme", null, null,
                null, null, "contact@acme.test", null)).isEmpty());
    }
}
