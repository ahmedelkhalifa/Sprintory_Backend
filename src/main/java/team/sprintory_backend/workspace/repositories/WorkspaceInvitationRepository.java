package team.sprintory_backend.workspace.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.workspace.models.WorkspaceInvitation;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitation, Long> {

    /** Fetches the workspace in the same query so the caller can read it after the transaction ends. */
    @Query("select i from WorkspaceInvitation i join fetch i.workspace where i.tokenHash = :tokenHash")
    Optional<WorkspaceInvitation> findByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * A bulk delete, not a derived one: it runs immediately, so the replacement invitation saved
     * right after it does not collide with the old row on the (workspace, email) constraint.
     */
    @Modifying
    @Query("delete from WorkspaceInvitation i where i.workspace.id = :workspaceId and i.email = :email")
    void deleteByWorkspaceIdAndEmail(@Param("workspaceId") Long workspaceId, @Param("email") String email);

    @Modifying
    @Query("delete from WorkspaceInvitation i where i.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}