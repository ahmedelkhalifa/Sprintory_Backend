package team.sprintory_backend.project.charter.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import team.sprintory_backend.project.charter.models.CharterStakeholder;
import java.util.List;
import java.util.Optional;

public interface CharterStakeholderRepository extends JpaRepository<CharterStakeholder, Long> {
    @Query("select s from CharterStakeholder s left join fetch s.linkedUser "
            + "where s.project.id = :projectId order by s.id asc")
    List<CharterStakeholder> findByProjectIdOrderByIdAsc(@Param("projectId") Long projectId);
    Optional<CharterStakeholder> findByIdAndProjectId(Long id, Long projectId);
}
