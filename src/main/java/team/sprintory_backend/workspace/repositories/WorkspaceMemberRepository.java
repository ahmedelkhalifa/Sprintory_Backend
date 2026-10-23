package team.sprintory_backend.workspace.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.workspace.models.WorkspaceMember;

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

    /** The workspaces a user belongs to, with their role in each. */
    @Query("select m from WorkspaceMember m join fetch m.workspace "
            + "where m.user.id = :userId order by m.workspace.name")
    List<WorkspaceMember> findAllByUserId(@Param("userId") Long userId);

    @Query("select m from WorkspaceMember m join fetch m.user "
            + "where m.workspace.id = :workspaceId order by m.user.firstName, m.user.lastName")
    List<WorkspaceMember> findAllByWorkspaceId(@Param("workspaceId") Long workspaceId);
}