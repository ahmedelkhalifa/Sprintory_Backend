package team.sprintory_backend.milestone.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/** docs/milestones-docs.md §3.2 and §3.7: what {@code @Valid} refuses with 400 VALIDATION_ERROR. */
class MilestoneRequestValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final LocalDate DATE = LocalDate.of(2026, 12, 15);

    @Test
    void nameAndDateAreRequired() {
        assertFalse(VALIDATOR.validate(new MilestoneRequest(null, DATE, null)).isEmpty());
        assertFalse(VALIDATOR.validate(new MilestoneRequest("   ", DATE, null)).isEmpty());
        assertFalse(VALIDATOR.validate(new MilestoneRequest("Beta release", null, null)).isEmpty());
        assertTrue(VALIDATOR.validate(new MilestoneRequest("Beta release", DATE, null)).isEmpty());
    }

    @Test
    void lengthsAreCheckedAfterTrimming() {
        assertTrue(VALIDATOR.validate(new MilestoneRequest("  " + "x".repeat(100) + "  ", DATE, null)).isEmpty());
        assertFalse(VALIDATOR.validate(new MilestoneRequest("x".repeat(101), DATE, null)).isEmpty());
        assertTrue(VALIDATOR.validate(new MilestoneRequest("Beta", DATE, "d".repeat(500))).isEmpty());
        assertFalse(VALIDATOR.validate(new MilestoneRequest("Beta", DATE, "d".repeat(501))).isEmpty());
    }

    @Test
    void nameAndDescriptionAreTrimmedAndABlankDescriptionIsNone() {
        MilestoneRequest request = new MilestoneRequest("  Beta release ", DATE, "  Pilot build  ");
        assertEquals("Beta release", request.name());
        assertEquals("Pilot build", request.description());
        assertNull(new MilestoneRequest("Beta", DATE, "   ").description());
    }

    /** §3.7: 1–100 ids, none of them null. */
    @Test
    void taskIdsAreOneToAHundredIdsWithoutNull() {
        List<Long> hundred = LongStream.rangeClosed(1, 100).boxed().toList();
        List<Long> withNull = new ArrayList<>(List.of(1L));
        withNull.add(null);

        assertTrue(VALIDATOR.validate(new MilestoneTasksRequest(hundred)).isEmpty());
        assertFalse(VALIDATOR.validate(new MilestoneTasksRequest(null)).isEmpty());
        assertFalse(VALIDATOR.validate(new MilestoneTasksRequest(List.of())).isEmpty());
        assertFalse(VALIDATOR.validate(new MilestoneTasksRequest(
                LongStream.rangeClosed(1, 101).boxed().toList())).isEmpty());
        assertFalse(VALIDATOR.validate(new MilestoneTasksRequest(withNull)).isEmpty());
    }
}
