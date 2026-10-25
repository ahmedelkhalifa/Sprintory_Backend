package team.sprintory_backend.project.services;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.exceptionHandler.ResourceNotFoundException;
import team.sprintory_backend.project.dto.ProjectBoardColumnResponse;
import team.sprintory_backend.project.dto.ProjectBoardResponse;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectBoard;
import team.sprintory_backend.project.models.ProjectBoardColumn;
import team.sprintory_backend.project.models.ProjectPermission;
import team.sprintory_backend.project.repositories.ProjectBoardColumnRepository;
import team.sprintory_backend.project.repositories.ProjectBoardRepository;
import team.sprintory_backend.project.strategy.ProjectBoardStrategies;
import team.sprintory_backend.project.strategy.ProjectBoardTemplateStrategy;
import team.sprintory_backend.user.models.User;

@Service
@RequiredArgsConstructor
public class ProjectBoardService {
    private final ProjectBoardRepository boardRepository;
    private final ProjectBoardColumnRepository columnRepository;
    private final ProjectBoardStrategies strategies;
    private final ProjectAccess projectAccess;

    /** Called inside ProjectService.create's transaction: all rows commit or roll back together. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void initialize(Project project) {
        ProjectBoardTemplateStrategy strategy = strategies.forTemplate(project.getTemplate());
        ProjectBoard board = boardRepository.save(new ProjectBoard(project));
        List<ProjectBoardColumn> columns = new ArrayList<>();
        int position = 0;
        for (String name : strategy.defaultColumns()) {
            columns.add(new ProjectBoardColumn(board, name, position++));
        }
        columnRepository.saveAll(columns);
    }

    /** Read-only board metadata; VIEW_WORK requires actual project membership, even for admins. */
    @Transactional(readOnly = true)
    public ProjectBoardResponse get(User user, Long projectId) {
        Project project = projectAccess.require(projectId, user, ProjectPermission.VIEW_WORK).project();
        if (!boardRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project board not found.");
        }
        ProjectBoardTemplateStrategy strategy = strategies.forTemplate(project.getTemplate());
        List<ProjectBoardColumnResponse> columns = columnRepository
                .findByBoardProjectIdOrderByPositionAscIdAsc(projectId).stream()
                .map(ProjectBoardColumnResponse::from).toList();
        // Sprint/backlog modules will supply their actual work data in later stories.
        return new ProjectBoardResponse(projectId, project.getTemplate(), strategy.backlogEnabled(),
                strategy.sprintsEnabled(), strategy.wipLimitsEnabled(), null, columns);
    }
}
