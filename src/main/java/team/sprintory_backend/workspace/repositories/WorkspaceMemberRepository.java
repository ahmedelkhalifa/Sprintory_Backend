package team.sprintory_backend.workspace.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.workspace.models.WorkspaceMember;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    /** Fetches the workspace too, so access checks can return it without another query. */
    @Query("select m from WorkspaceMember m join fetch m.workspace "
            + "where m.workspace.id = :workspaceId and m.user.id = :userId")
    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(@Param("workspaceId") Long workspaceId,
                                                         @Param("userId") Long userId);

    boolean existsByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    /** The memberships of the given users in one workspace; users who are not members are absent. */
    @Query("select m from WorkspaceMember m join fetch m.user "
            + "where m.workspace.id = :workspaceId and m.user.id in :userIds")
    List<WorkspaceMember> findAllByWorkspaceIdAndUserIdIn(@Param("workspaceId") Long workspaceId,
                                                          @Param("userIds") Collection<Long> userIds);

    /** The workspaces a user belongs to, with their role in each. */
    @Query("select m from WorkspaceMember m join fetch m.workspace "
            + "where m.user.id = :userId order by m.workspace.name")
    List<WorkspaceMember> findAllByUserId(@Param("userId") Long userId);

    /**
     * One page of a workspace's members whose first name, last name or email contains the search.
     * {@code pattern} is "%" for no search (see Pageables.containsPattern). The count query is
     * written out because a fetch join cannot be counted.
     */
    @Query(value = "select m from WorkspaceMember m join fetch m.user u where m.workspace.id = :workspaceId "
            + "and (lower(u.firstName) like :pattern or lower(u.lastName) like :pattern "
            + "or lower(u.email) like :pattern) order by u.firstName, u.lastName, u.id",
            countQuery = "select count(m) from WorkspaceMember m join m.user u where m.workspace.id = :workspaceId "
                    + "and (lower(u.firstName) like :pattern or lower(u.lastName) like :pattern "
                    + "or lower(u.email) like :pattern)")
    Page<WorkspaceMember> searchByWorkspaceId(@Param("workspaceId") Long workspaceId,
                                              @Param("pattern") String pattern,
                                              Pageable pageable);
}