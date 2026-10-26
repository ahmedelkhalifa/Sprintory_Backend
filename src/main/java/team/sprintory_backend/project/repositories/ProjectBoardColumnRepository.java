package team.sprintory_backend.project.repositories;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.project.models.ProjectBoardColumn;

public interface ProjectBoardColumnRepository extends JpaRepository<ProjectBoardColumn, Long> {
    List<ProjectBoardColumn> findByBoardProjectIdOrderByPositionAscIdAsc(Long projectId);
}
