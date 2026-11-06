package team.sprintory_backend.chat.repositories;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.chat.models.ProjectChatMessage;

import java.util.List;
import java.util.Optional;

public interface ProjectChatMessageRepository extends JpaRepository<ProjectChatMessage, Long> {
    List<ProjectChatMessage> findByProjectIdOrderByIdDesc(Long projectId, Pageable pageable);
    List<ProjectChatMessage> findByProjectIdAndIdLessThanOrderByIdDesc(Long projectId, Long before, Pageable pageable);
    Optional<ProjectChatMessage> findByIdAndProjectId(Long id, Long projectId);
}
