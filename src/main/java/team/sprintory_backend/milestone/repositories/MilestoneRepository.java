package team.sprintory_backend.milestone.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.milestone.models.Milestone;

import java.util.List;
import java.util.Optional;

/** Every JPA query here sees live milestones only ({@code @SQLRestriction} on {@link Milestone}). */
@Repository
public interface MilestoneRepository extends JpaRepository<Milestone, Long> {

    /** For the limit of 50 per project. */
    long countByProjectId(Long projectId);

    /** A live milestone of the project with this name, ignoring case; for create and restore. */
    boolean existsByProjectIdAndNameIgnoreCase(Long projectId, String name);

    /** As above, other than the milestone being edited. */
    boolean existsByProjectIdAndNameIgnoreCaseAndIdNot(Long projectId, String name, Long id);

    /** The project's milestones in list order, with their creator. */
    @Query("""
            select m from Milestone m left join fetch m.createdByUser
            where m.project.id = :projectId
            order by m.targetDate, lower(m.name), m.id""")
    List<Milestone> findListByProjectId(@Param("projectId") Long projectId);

    /** A deleted milestone, for restore; native because the JPA queries cannot see deleted rows. */
    @Query(value = "select * from milestones where id = :id and deleted_at is not null", nativeQuery = true)
    Optional<Milestone> findDeletedById(@Param("id") Long id);
}
