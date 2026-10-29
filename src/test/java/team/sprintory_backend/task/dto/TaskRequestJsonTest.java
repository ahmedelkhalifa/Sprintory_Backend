package team.sprintory_backend.task.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Reads real JSON: Jackson 3 rejects a missing field for a primitive, which record-built tests never see. */
class TaskRequestJsonTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void partialUpdateWithoutClearFlagsIsReadAndFlagsDefaultToFalse() {
        UpdateTaskRequest request = mapper.readValue("{\"estimate\":10}", UpdateTaskRequest.class);
        assertEquals(10, request.estimate());
        assertFalse(request.clearAssignee());
        assertFalse(request.clearEstimate());
        assertFalse(request.clearDescription());
    }

    @Test
    void clearFlagSentAsTrueIsKept() {
        UpdateTaskRequest request = mapper.readValue("{\"clearEstimate\":true}", UpdateTaskRequest.class);
        assertTrue(request.clearEstimate());
        assertFalse(request.clearAssignee());
    }

    @Test
    void clearFlagSentAsNullMeansFalse() {
        UpdateTaskRequest request = mapper.readValue("{\"clearEstimate\":null}", UpdateTaskRequest.class);
        assertFalse(request.clearEstimate());
    }
}