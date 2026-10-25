package team.sprintory_backend.workspace.repositories;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.workspace.models.Workspace;

import java.util.Optional;

@Repository
public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {
    boolean existsBySlug(String slug);

    /**
     * Locks the workspace row until the transaction ends, so role changes and removals in one
     * workspace run one at a time and two of them cannot both pass the "at least one admin" check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Workspace w where w.id = :workspaceId")
    Optional<Workspace> lockById(@Param("workspaceId") Long workspaceId);
}