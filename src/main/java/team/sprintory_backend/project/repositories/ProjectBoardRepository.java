package team.sprintory_backend.project.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.project.models.ProjectBoard;

public interface ProjectBoardRepository extends JpaRepository<ProjectBoard, Long> {
}
