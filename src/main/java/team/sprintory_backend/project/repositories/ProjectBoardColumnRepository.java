package team.sprintory_backend.project.repositories;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.project.models.ProjectBoardColumn;

public interface ProjectBoardColumnRepository extends JpaRepository<ProjectBoardColumn, Long> {
    List<ProjectBoardColumn> findByBoardProjectIdOrderByPositionAscIdAsc(Long projectId);

    /** The column only if it is on this project's board; a task's status must be one (story 9). */
    Optional<ProjectBoardColumn> findByIdAndBoardProjectId(Long id, Long projectId);
}
