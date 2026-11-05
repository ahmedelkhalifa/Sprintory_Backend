package team.sprintory_backend.team.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.team.models.Team;

import java.util.List;
import java.util.Optional;

@Repository
public interface TeamRepository extends JpaRepository<Team, Long> {

    /** Fetches the workspace too, so the access check can read it without another query. */
    @Query("select t from Team t join fetch t.workspace where t.id = :teamId")
    Optional<Team> findWithWorkspaceById(@Param("teamId") Long teamId);

    /**
     * One page of a workspace's teams whose name contains the search, for admins.
     * {@code pattern} is "%" for no search (see Pageables.containsPattern).
     */
    @Query("select t from Team t where t.workspace.id = :workspaceId and lower(t.name) like :pattern "
            + "order by t.name, t.id")
    Page<Team> searchByWorkspaceId(@Param("workspaceId") Long workspaceId,
                                   @Param("pattern") String pattern,
                                   Pageable pageable);

    /** The same, limited to the teams the user belongs to, for plain members. */
    @Query(value = "select tm.team from TeamMember tm where tm.team.workspace.id = :workspaceId "
            + "and tm.user.id = :userId and lower(tm.team.name) like :pattern order by tm.team.name, tm.team.id",
            countQuery = "select count(tm) from TeamMember tm where tm.team.workspace.id = :workspaceId "
                    + "and tm.user.id = :userId and lower(tm.team.name) like :pattern")
    Page<Team> searchByWorkspaceIdAndMemberId(@Param("workspaceId") Long workspaceId,
                                              @Param("userId") Long userId,
                                              @Param("pattern") String pattern,
                                              Pageable pageable);

    /**
     * Teams that can be @mentioned in a project (PMP-136): the workspace's teams whose name
     * contains the search and that have at least one member in the project, with how many of
     * their members are in it. {@code limit} caps the list; pass {@code PageRequest.of(0, n)}.
     */
    @Query("select t.id as id, t.name as name, count(tm) as membersInProject "
            + "from TeamMember tm join tm.team t "
            + "where t.workspace.id = :workspaceId and lower(t.name) like :pattern "
            + "and tm.user.id in (select pm.user.id from ProjectMember pm where pm.project.id = :projectId) "
            + "group by t.id, t.name order by t.name, t.id")
    List<TeamInProject> searchWithMembersInProject(@Param("workspaceId") Long workspaceId,
                                                   @Param("projectId") Long projectId,
                                                   @Param("pattern") String pattern,
                                                   Pageable limit);

    /** One row of {@link #searchWithMembersInProject}. */
    interface TeamInProject {
        Long getId();

        String getName();

        long getMembersInProject();
    }
}