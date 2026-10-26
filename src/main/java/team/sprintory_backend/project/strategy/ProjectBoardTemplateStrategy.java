package team.sprintory_backend.project.strategy;

import java.util.List;
import team.sprintory_backend.project.models.ProjectTemplate;

/** Strategy: initializes a project's workflow; template choices are immutable after creation. */
public interface ProjectBoardTemplateStrategy {
    ProjectTemplate template();
    List<String> defaultColumns();
    boolean backlogEnabled();
    boolean sprintsEnabled();
    boolean wipLimitsEnabled();
}
