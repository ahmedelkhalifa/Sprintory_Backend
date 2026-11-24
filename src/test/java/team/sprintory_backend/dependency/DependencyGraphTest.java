package team.sprintory_backend.dependency;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DependencyGraphTest {
    @Test void emptyAndSelf() {
        assertEquals(List.of(), DependencyGraph.cyclePath(Map.of(), 1L, 2L));
        assertEquals(List.of(1L, 1L), DependencyGraph.cyclePath(Map.of(), 1L, 1L));
    }
    @Test void chainAndReverse() {
        var graph = Map.of(1L, List.of(2L), 2L, List.of(3L));
        assertEquals(List.of(3L, 1L, 2L, 3L), DependencyGraph.cyclePath(graph, 3L, 1L));
        assertEquals(List.of(2L, 1L, 2L), DependencyGraph.cyclePath(graph, 2L, 1L));
        assertTrue(DependencyGraph.cyclePath(graph, 1L, 3L).isEmpty());
    }
    @Test void diamondAndDisconnected() {
        var graph = Map.of(1L, List.of(2L, 3L), 2L, List.of(4L), 3L, List.of(4L));
        assertEquals(List.of(4L, 1L, 2L, 4L), DependencyGraph.cyclePath(graph, 4L, 1L));
        assertTrue(DependencyGraph.cyclePath(graph, 4L, 5L).isEmpty());
    }
    @Test void longChainDoesNotUseRecursion() {
        Map<Long, List<Long>> graph = new HashMap<>();
        for (long n = 1; n < 10000; n++) graph.put(n, List.of(n + 1));
        assertEquals(10001, DependencyGraph.cyclePath(graph, 10000L, 1L).size());
    }
}
