package team.sprintory_backend.task.repositories;

import team.sprintory_backend.project.models.BoardColumnCategory;
import team.sprintory_backend.dependency.models.TaskDependency;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import team.sprintory_backend.common.Pageables;
import team.sprintory_backend.task.api.TaskSearchFilters;
import team.sprintory_backend.task.models.Task;
import team.sprintory_backend.task.models.TaskPriority;
import team.sprintory_backend.task.models.TaskType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The query behind a project's task list (story 9). Every given filter narrows the result (AND);
 * {@code q} matches the title or the key. Deleted tasks are left out by {@code Task}'s
 * {@code @SQLRestriction}.
 */
public final class TaskSpecifications {

    private static final char ESCAPE = '\\';

    private TaskSpecifications() {
    }

    public static Specification<Task> matching(Long projectId, TaskSearchFilters filters) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("project").get("id"), projectId));
            predicates.add(cb.isNull(root.get("parent")));
            if (filters.type() != null) {
                predicates.add(cb.equal(root.get("type"), TaskType.valueOf(filters.type())));
            }
            if (filters.priority() != null) {
                predicates.add(cb.equal(root.get("priority"), TaskPriority.valueOf(filters.priority())));
            }
            if (filters.statusId() != null) {
                predicates.add(cb.equal(root.get("status").get("id"), filters.statusId()));
            }
            if (filters.assigneeId() != null) {
                predicates.add(cb.equal(root.get("assignee").get("id"), filters.assigneeId()));
            }
            if (filters.sprintId() != null) {
                predicates.add(cb.equal(root.get("sprint").get("id"), filters.sprintId()));
            } else if (Boolean.TRUE.equals(filters.noSprint())) {
                predicates.add(cb.isNull(root.get("sprint")));
            }
            if (Boolean.TRUE.equals(filters.blockedOnly())) {
                Subquery<Integer> open = query.subquery(Integer.class);
                Root<TaskDependency> link = open.from(TaskDependency.class);
                Root<Task> blocker = open.from(Task.class);
                open.select(cb.literal(1)).where(
                    cb.equal(link.get("blockedId"), root.get("id")),
                    cb.equal(link.get("projectId"), projectId),
                    cb.equal(link.get("blockerId"), blocker.get("id")),
                    cb.isNull(blocker.get("deletedAt")),
                    cb.notEqual(blocker.get("status").get("category"), BoardColumnCategory.DONE));
                predicates.add(cb.exists(open));
            }
            if (filters.label() != null) {
                // "exists" rather than a join, so a task is listed once whatever its labels.
                Subquery<Integer> labelled = query.subquery(Integer.class);
                Root<Task> task = labelled.correlate(root);
                Join<Task, String> label = task.join("labels");
                labelled.select(cb.literal(1))
                        .where(cb.equal(cb.lower(label), filters.label().toLowerCase(Locale.ROOT)));
                predicates.add(cb.exists(labelled));
            }
            if (filters.q() != null) {
                String pattern = "%" + Pageables.escapeLike(filters.q().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), pattern, ESCAPE),
                        cb.like(cb.lower(root.get("key")), pattern, ESCAPE)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}