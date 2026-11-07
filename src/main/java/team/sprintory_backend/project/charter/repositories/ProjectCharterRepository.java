package team.sprintory_backend.project.charter.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.project.charter.models.ProjectCharter;

public interface ProjectCharterRepository extends JpaRepository<ProjectCharter, Long> { }
