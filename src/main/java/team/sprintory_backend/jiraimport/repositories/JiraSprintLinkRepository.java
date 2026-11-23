package team.sprintory_backend.jiraimport.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.jiraimport.models.JiraSprintLink;
import java.util.List;

public interface JiraSprintLinkRepository extends JpaRepository<JiraSprintLink, Long> {
    List<JiraSprintLink> findByProjectId(Long projectId);
}
