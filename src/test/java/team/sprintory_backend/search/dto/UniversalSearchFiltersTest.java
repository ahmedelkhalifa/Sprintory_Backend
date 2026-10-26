package team.sprintory_backend.search.dto;

import org.junit.jupiter.api.Test;
import team.sprintory_backend.exceptionHandler.BadRequestException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UniversalSearchFiltersTest {
    @Test
    void queryIsRequiredAndBounded() {
        assertThrows(BadRequestException.class, () -> UniversalSearchFilters.fromQuery(null, null, null, null));
        assertThrows(BadRequestException.class, () -> UniversalSearchFilters.fromQuery(" ", null, null, null));
        assertThrows(BadRequestException.class, () -> UniversalSearchFilters.fromQuery("x".repeat(256), null, null, null));
    }

    @Test
    void defaultsToAllResourceTypes() {
        var filters = UniversalSearchFilters.fromQuery(" login ", null, null, null);
        assertEquals("login", filters.q());
        assertEquals(Set.of(SearchResultType.values()), filters.types());
    }

    @Test
    void parsesTypedCommaSeparatedFilter() {
        var filters = UniversalSearchFilters.fromQuery("xyz", " task,PROJECT,task ", 5L, 9L);
        assertEquals(Set.of(SearchResultType.TASK, SearchResultType.PROJECT), filters.types());
        assertEquals(5L, filters.workspaceId());
        assertEquals(9L, filters.projectId());
    }

    @Test
    void rejectsUnknownOrEmptyResourceTypes() {
        assertThrows(BadRequestException.class, () -> UniversalSearchFilters.fromQuery("x", "TASK,USER", null, null));
        assertThrows(BadRequestException.class, () -> UniversalSearchFilters.fromQuery("x", "TASK,", null, null));
    }

    @Test
    void rejectsNonpositiveScopes() {
        assertThrows(BadRequestException.class, () -> UniversalSearchFilters.fromQuery("x", null, 0L, null));
        assertThrows(BadRequestException.class, () -> UniversalSearchFilters.fromQuery("x", null, null, -1L));
    }
}
