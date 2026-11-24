package team.sprintory_backend.dependency;

import java.util.*;

/** Pure directed graph: returns a closed walk for a proposed edge, or an empty list. */
public final class DependencyGraph {
    private DependencyGraph() {}

    public static List<Long> cyclePath(Map<Long, List<Long>> edges, Long blocker, Long blocked) {
        if (blocker.equals(blocked)) return List.of(blocker, blocker);
        Map<Long, Long> parents = new HashMap<>();
        Deque<Long> queue = new ArrayDeque<>();
        parents.put(blocked, null);
        queue.add(blocked);
        while (!queue.isEmpty()) {
            Long current = queue.removeFirst();
            if (current.equals(blocker)) {
                List<Long> path = new ArrayList<>();
                for (Long node = current; node != null; node = parents.get(node)) path.add(node);
                Collections.reverse(path);
                path.add(0, blocker);
                return List.copyOf(path);
            }
            for (Long next : edges.getOrDefault(current, List.of())) {
                if (!parents.containsKey(next)) {
                    parents.put(next, current);
                    queue.addLast(next);
                }
            }
        }
        return List.of();
    }
}
