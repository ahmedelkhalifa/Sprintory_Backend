package team.sprintory_backend.project.strategy;

import java.util.List;
import org.junit.jupiter.api.Test;
import team.sprintory_backend.project.models.ProjectTemplate;
import static org.junit.jupiter.api.Assertions.*;

class ProjectBoardStrategiesTest {
    private final ProjectBoardStrategies strategies = new ProjectBoardStrategies(
            List.of(new ScrumBoardStrategy(), new KanbanBoardStrategy()));

    @Test
    void scrumDefaultsAndFeatures() {
        var scrum = strategies.forTemplate(ProjectTemplate.SCRUM);
        assertEquals(List.of("To Do", "In Progress", "Done"), scrum.defaultColumns());
        assertTrue(scrum.backlogEnabled());
        assertTrue(scrum.sprintsEnabled());
        assertFalse(scrum.wipLimitsEnabled());
    }

    @Test
    void kanbanDefaultsAndFeatures() {
        var kanban = strategies.forTemplate(ProjectTemplate.KANBAN);
        assertEquals(List.of("To Do", "In Progress", "In Review", "Done"), kanban.defaultColumns());
        assertFalse(kanban.backlogEnabled());
        assertFalse(kanban.sprintsEnabled());
        assertTrue(kanban.wipLimitsEnabled());
    }

    @Test
    void missingOrDuplicateStrategiesFailFast() {
        assertThrows(IllegalStateException.class, () -> new ProjectBoardStrategies(
                List.of(new ScrumBoardStrategy())));
        assertThrows(IllegalStateException.class, () -> new ProjectBoardStrategies(
                List.of(new ScrumBoardStrategy(), new ScrumBoardStrategy(), new KanbanBoardStrategy())));
    }
}
