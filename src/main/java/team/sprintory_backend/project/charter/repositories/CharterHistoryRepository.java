package team.sprintory_backend.project.charter.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import team.sprintory_backend.project.charter.models.CharterHistoryEntry;

public interface CharterHistoryRepository extends JpaRepository<CharterHistoryEntry, Long> {
    Page<CharterHistoryEntry> findByProjectIdOrderByOccurredAtDescIdDesc(Long projectId, Pageable pageable);
}
