package team.sprintory_backend.project.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.project.models.Project;

import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** Fetches the workspace too, so the access check can read it without another query. */
    @Query("select p from Project p join fetch p.workspace where p.id = :projectId")
    Optional<Project> findWithWorkspaceById(@Param("projectId") Long projectId);

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