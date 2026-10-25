package team.sprintory_backend.project.strategy;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import team.sprintory_backend.project.models.ProjectTemplate;

/** Resolves a concrete initialization strategy; fails at startup on missing/duplicate templates. */
@Component
public class ProjectBoardStrategies {
    private final Map<ProjectTemplate, ProjectBoardTemplateStrategy> strategies;

    public ProjectBoardStrategies(List<ProjectBoardTemplateStrategy> implementations) {
        Map<ProjectTemplate, ProjectBoardTemplateStrategy> mapped = new EnumMap<>(ProjectTemplate.class);
        for (ProjectBoardTemplateStrategy strategy : implementations) {
            if (mapped.putIfAbsent(strategy.template(), strategy) != null) {
                throw new IllegalStateException("Duplicate board strategy: " + strategy.template());
            }
        }
        for (ProjectTemplate template : ProjectTemplate.values()) {
            if (!mapped.containsKey(template)) {
                throw new IllegalStateException("Missing board strategy: " + template);
            }
        }
        this.strategies = Map.copyOf(mapped);
    }

    public ProjectBoardTemplateStrategy forTemplate(ProjectTemplate template) {
        ProjectBoardTemplateStrategy strategy = strategies.get(template);
        if (strategy == null) throw new IllegalArgumentException("Unsupported project template: " + template);
        return strategy;
    }
}
