package team.sprintory_backend.team.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.team.models.TeamMember;
import team.sprintory_backend.user.models.User;

import java.util.List;

@Repository
public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

    /**
     * One page of a team's members whose first name, last name or email contains the search.
     * {@code pattern} is "%" for no search (see Pageables.containsPattern). The count query is
     * written out because a fetch join cannot be counted.
     */
    @Query(value = "select tm from TeamMember tm join fetch tm.user u where tm.team.id = :teamId "
            + "and (lower(u.firstName) like :pattern or lower(u.lastName) like :pattern "
            + "or lower(u.email) like :pattern) order by u.firstName, u.lastName, u.id",
            countQuery = "select count(tm) from TeamMember tm join tm.user u where tm.team.id = :teamId "
                    + "and (lower(u.firstName) like :pattern or lower(u.lastName) like :pattern "
                    + "or lower(u.email) like :pattern)")
    Page<TeamMember> searchByTeamId(@Param("teamId") Long teamId,
                                    @Param("pattern") String pattern,
                                    Pageable pageable);

    boolean existsByTeamIdAndUserId(Long teamId, Long userId);

    /** Everyone currently in the team, for copying a team into a project. */
    @Query("select tm.user from TeamMember tm where tm.team.id = :teamId")
    List<User> findUsersByTeamId(@Param("teamId") Long teamId);

    /**
     * Removes the user from every team of the workspace, for leaving it. ON DELETE CASCADE does
     * not cover this: leaving deletes only the workspace membership, which team rows do not reference.
     */
    @Modifying
    @Query("delete from TeamMember tm where tm.user.id = :userId "
            + "and tm.team.id in (select t.id from Team t where t.workspace.id = :workspaceId)")
    int deleteByWorkspaceIdAndUserId(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId);

    /** Returns the number of rows removed; 0 means the user was not in the team. */
    @Modifying
    @Query("delete from TeamMember tm where tm.team.id = :teamId and tm.user.id = :userId")
    int deleteByTeamIdAndUserId(@Param("teamId") Long teamId, @Param("userId") Long userId);
}