package team.sprintory_backend.project.repositories;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectRole;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** Fetches the workspace too, so the access check can read it without another query. */
    @Query("select p from Project p join fetch p.workspace where p.id = :projectId")
    Optional<Project> findWithWorkspaceById(@Param("projectId") Long projectId);

    /** Keys are stored upper-case, so the caller passes the normalized key. */
    boolean existsByWorkspaceIdAndKey(Long workspaceId, String key);

    /**
     * Locks the project row until the transaction ends, so role changes in one project run one at
     * a time and two of them cannot both pass the "at least one Project Manager" check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :projectId")
    Optional<Project> lockById(@Param("projectId") Long projectId);

    /**
     * Locks the workspace's projects in which the user holds the role, in id order. Used before
     * removing someone from the workspace, so that a Project Manager stepping down at the same
     * moment cannot leave a project without one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id in (select pm.project.id from ProjectMember pm join pm.roles r "
            + "where pm.project.workspace.id = :workspaceId and pm.user.id = :userId and r = :role) "
            + "order by p.id")
    List<Project> lockAllWhereMemberHasRole(@Param("workspaceId") Long workspaceId,
                                            @Param("userId") Long userId,
                                            @Param("role") ProjectRole role);

    /**
     * One page of a workspace's projects whose name contains the search, for admins.
     * {@code pattern} is "%" for no search (see Pageables.containsPattern).
     */
    @Query("select p from Project p where p.workspace.id = :workspaceId and lower(p.name) like :pattern "
            + "order by p.name, p.id")
    Page<Project> searchByWorkspaceId(@Param("workspaceId") Long workspaceId,
                                      @Param("pattern") String pattern,
                                      Pageable pageable);

    /** The same, limited to the projects the user is a member of, for plain members. */
    @Query(value = "select pm.project from ProjectMember pm where pm.project.workspace.id = :workspaceId "
            + "and pm.user.id = :userId and lower(pm.project.name) like :pattern "
            + "order by pm.project.name, pm.project.id",
            countQuery = "select count(pm) from ProjectMember pm where pm.project.workspace.id = :workspaceId "
                    + "and pm.user.id = :userId and lower(pm.project.name) like :pattern")
    Page<Project> searchByWorkspaceIdAndMemberId(@Param("workspaceId") Long workspaceId,
                                                 @Param("userId") Long userId,
                                                 @Param("pattern") String pattern,
                                                 Pageable pageable);
}