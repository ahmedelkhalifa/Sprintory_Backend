package team.sprintory_backend.jiraimport.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.jiraimport.models.JiraIssueLink;
import java.util.List;

public interface JiraIssueLinkRepository extends JpaRepository<JiraIssueLink, Long> {
    List<JiraIssueLink> findByProjectId(Long projectId);
}
