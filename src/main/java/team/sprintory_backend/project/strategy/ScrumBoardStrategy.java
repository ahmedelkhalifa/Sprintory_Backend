package team.sprintory_backend.project.strategy;

import java.util.List;
import org.springframework.stereotype.Component;
import team.sprintory_backend.project.models.ProjectTemplate;

@Component
public class ScrumBoardStrategy implements ProjectBoardTemplateStrategy {
    @Override public ProjectTemplate template() { return ProjectTemplate.SCRUM; }
    @Override public List<String> defaultColumns() { return List.of("To Do", "In Progress", "Done"); }
    @Override public boolean backlogEnabled() { return true; }
    @Override public boolean sprintsEnabled() { return true; }
    @Override public boolean wipLimitsEnabled() { return false; }
}
