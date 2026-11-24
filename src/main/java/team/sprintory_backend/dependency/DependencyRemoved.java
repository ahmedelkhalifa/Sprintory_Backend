package team.sprintory_backend.dependency;
import team.sprintory_backend.user.models.User;
/** Both directional history entries describe this one link. */
public record DependencyRemoved(Long blockerId, String blockerKey, Long blockedId, String blockedKey, User actor) {}
