package team.sprintory_backend.project.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.project.models.ProjectMember;

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

    /** Returns the number of rows removed; 0 means the user was not in the project. */
    @Modifying
    @Query("delete from ProjectMember pm where pm.project.id = :projectId and pm.user.id = :userId")
    int deleteByProjectIdAndUserId(@Param("projectId") Long projectId, @Param("userId") Long userId);
}