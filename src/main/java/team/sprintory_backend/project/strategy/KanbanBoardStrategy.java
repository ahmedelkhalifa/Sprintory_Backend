package team.sprintory_backend.project.strategy;

import java.util.List;
import org.springframework.stereotype.Component;
import team.sprintory_backend.project.models.ProjectTemplate;

@Component
public class KanbanBoardStrategy implements ProjectBoardTemplateStrategy {
    @Override public ProjectTemplate template() { return ProjectTemplate.KANBAN; }
    @Override public List<String> defaultColumns() {
        return List.of("Backlog", "To Do", "In Progress", "Done");
    }
    @Override public boolean backlogEnabled() { return false; }
    @Override public boolean sprintsEnabled() { return false; }
    @Override public boolean wipLimitsEnabled() { return true; }
}
