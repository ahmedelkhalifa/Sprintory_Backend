package team.sprintory_backend.sprint;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.sprint.dto.*;
import static org.junit.jupiter.api.Assertions.*;

class SprintRequestValidationTest {
    @Test void invalidCapacityAndNameAreRejectedBeforeService() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(validator.validate(new SprintRequest("x".repeat(101), null, null, null, null)).isEmpty());
            assertFalse(validator.validate(new SprintRequest(null, null, -1, null, null)).isEmpty());
            assertFalse(validator.validate(new AvailabilityRequest(-1)).isEmpty());
            assertFalse(validator.validate(new MoveSprintTaskRequest(null, null, null)).isEmpty());
            assertFalse(validator.validate(new SprintLengthRequest(1)).isEmpty());
            assertFalse(validator.validate(new team.sprintory_backend.project.dto.UpdateProjectRequest(
                    null, null, 1)).isEmpty());
        }
    }

    @Test void closeNeedsTheGoalAnswerAndCancelNeedsAReason() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var yes = team.sprintory_backend.sprint.models.SprintGoalAchieved.YES;
            assertTrue(validator.validate(new CompleteSprintRequest(null, null, yes, null, null)).isEmpty());
            assertFalse(validator.validate(new CompleteSprintRequest(null, null, null, null, null)).isEmpty());
            assertFalse(validator.validate(new CompleteSprintRequest(null, null, yes, "x".repeat(1001), null)).isEmpty());
            assertTrue(validator.validate(new CancelSprintRequest("Goal became obsolete")).isEmpty());
            assertFalse(validator.validate(new CancelSprintRequest(null)).isEmpty());
            assertFalse(validator.validate(new CancelSprintRequest("   ")).isEmpty());
            assertFalse(validator.validate(new CancelSprintRequest("x".repeat(1001))).isEmpty());
        }
    }

    @Test void missingFlagsDefaultToFalse() {
        var request = new CompleteSprintRequest(null, null, team.sprintory_backend.sprint.models.SprintGoalAchieved.NO,
                null, null);
        assertFalse(request.createSprint());
        assertFalse(request.confirmEarlyClose());
    }
}
