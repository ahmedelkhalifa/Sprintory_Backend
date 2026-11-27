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

    /**
     * The project of a live milestone, without loading it: a write locks the project first and
     * only then loads the milestone, so it never works on a copy read before a concurrent delete.
     */
    @Query("select m.project.id from Milestone m where m.id = :id")
    Optional<Long> findProjectIdById(@Param("id") Long id);

    /** A live milestone of this project: a task links only to its own project's milestones. */
    Optional<Milestone> findByIdAndProjectId(Long id, Long projectId);

    /** The same for a deleted milestone, for restore; native because JPA cannot see deleted rows. */
    @Query(value = "select project_id from milestones where id = :id and deleted_at is not null", nativeQuery = true)
    Optional<Long> findProjectIdOfDeletedById(@Param("id") Long id);

    /** A deleted milestone, for restore. */
    @Query(value = "select * from milestones where id = :id and deleted_at is not null", nativeQuery = true)
    Optional<Milestone> findDeletedById(@Param("id") Long id);
}
