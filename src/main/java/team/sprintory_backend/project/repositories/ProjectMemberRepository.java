package team.sprintory_backend.project.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.project.models.ProjectMember;
import team.sprintory_backend.project.models.ProjectRole;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {

    /**
     * One page of a project's members whose first name, last name or email contains the search.
     * {@code pattern} is "%" for no search (see Pageables.containsPattern). The count query is
     * written out because a fetch join cannot be counted.
     */
    @Query(value = "select pm from ProjectMember pm join fetch pm.user u where pm.project.id = :projectId "
            + "and (lower(u.firstName) like :pattern or lower(u.lastName) like :pattern "
            + "or lower(u.email) like :pattern) order by u.firstName, u.lastName, u.id",
            countQuery = "select count(pm) from ProjectMember pm join pm.user u where pm.project.id = :projectId "
                    + "and (lower(u.firstName) like :pattern or lower(u.lastName) like :pattern "
                    + "or lower(u.email) like :pattern)")
    Page<ProjectMember> searchByProjectId(@Param("projectId") Long projectId,
                                          @Param("pattern") String pattern,
                                          Pageable pageable);

    boolean existsByProjectIdAndUserId(Long projectId, Long userId);

    /** The user's membership in the project with its roles loaded, or empty if they are not in it. */
    @Query("select pm from ProjectMember pm left join fetch pm.roles "
            + "where pm.project.id = :projectId and pm.user.id = :userId")
    Optional<ProjectMember> findWithRolesByProjectIdAndUserId(@Param("projectId") Long projectId,
                                                              @Param("userId") Long userId);

    /** How many members of the project hold the role, e.g. for "at least one Project Manager". */
    @Query("select count(pm) from ProjectMember pm join pm.roles r where pm.project.id = :projectId and r = :role")
    long countByProjectIdAndRole(@Param("projectId") Long projectId, @Param("role") ProjectRole role);

    /**
     * The workspace's projects in which the user is the only holder of the role. Called with
     * PROJECT_MANAGER only: removing the only PM from the workspace is refused, while the only
     * Scrum Master or Product Owner may leave.
     */
    @Query("select pm.project from ProjectMember pm join pm.roles r "
            + "where pm.project.workspace.id = :workspaceId and pm.user.id = :userId and r = :role "
            + "and (select count(other) from ProjectMember other join other.roles otherRole "
            + "where other.project = pm.project and otherRole = :role) = 1 "
            + "order by pm.project.name, pm.project.id")
    List<Project> findProjectsWhereOnlyHolder(@Param("workspaceId") Long workspaceId,
                                              @Param("userId") Long userId,
                                              @Param("role") ProjectRole role);

    /**
     * Removes the user from every project of the workspace, for leaving it. ON DELETE CASCADE does
     * not cover this: leaving deletes only the workspace membership, which project rows do not
     * reference. Their role rows do go with them through the cascade.
     */
    @Modifying
    @Query("delete from ProjectMember pm where pm.user.id = :userId "
            + "and pm.project.id in (select p.id from Project p where p.workspace.id = :workspaceId)")
    int deleteByWorkspaceIdAndUserId(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId);

    /** Returns the number of rows removed; 0 means the user was not in the project. */
    @Modifying
    @Query("delete from ProjectMember pm where pm.project.id = :projectId and pm.user.id = :userId")
    int deleteByProjectIdAndUserId(@Param("projectId") Long projectId, @Param("userId") Long userId);
}