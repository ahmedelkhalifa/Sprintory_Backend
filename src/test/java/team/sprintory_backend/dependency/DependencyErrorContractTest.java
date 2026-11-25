package team.sprintory_backend.dependency;

import team.sprintory_backend.dependency.dto.DependencyTaskSummary;
import team.sprintory_backend.dependency.exceptions.DependencyConflict;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import team.sprintory_backend.exceptionHandler.*;
import team.sprintory_backend.project.models.BoardColumnCategory;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DependencyErrorContractTest {
    @Test void cycleUsesExistingEnvelopeAndStructuredPath() {
        var path=List.of("DEP-1","DEP-2","DEP-1");
        var response=new ExceptionHandler().handleDependencyConflict(new DependencyConflict(ErrorCode.DEPENDENCY_CYCLE,
                "This would create a cycle: DEP-1 -> DEP-2 -> DEP-1.",path,null),
                new MockHttpServletRequest("POST","/api/v1/tasks/1/dependencies"));
        assertEquals(409,response.getStatusCode().value());
        assertEquals(path,response.getBody().getCyclePath());
        assertEquals(ErrorCode.DEPENDENCY_CYCLE,response.getBody().getCode());
        assertNull(response.getBody().getOpenBlockers());
        assertEquals("/api/v1/tasks/1/dependencies",response.getBody().getPath());
    }
    @Test void warningIncludesOpenBlockersWithoutCyclePath() {
        var blockers=List.of(new DependencyTaskSummary(1L,"DEP-1","Blocker","To Do",BoardColumnCategory.TODO));
        var response=new ExceptionHandler().handleDependencyConflict(new DependencyConflict(ErrorCode.BLOCKED_TASK_CONFIRMATION_REQUIRED,
                "This task is blocked. Move it anyway?",null,blockers),new MockHttpServletRequest("PUT","/api/v1/projects/1/tasks/2/position"));
        assertEquals(409,response.getStatusCode().value());
        assertEquals(blockers,response.getBody().getOpenBlockers());
        assertNull(response.getBody().getCyclePath());
    }
}
