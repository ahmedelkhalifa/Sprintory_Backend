package team.sprintory_backend.task.repositories;

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