package team.sprintory_backend.search.services;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.search.dto.SearchResultType;
import team.sprintory_backend.search.dto.UniversalSearchFilters;
import team.sprintory_backend.search.dto.UniversalSearchResult;
import team.sprintory_backend.search.repositories.UniversalSearchRepository;
import team.sprintory_backend.user.models.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The SQL and its visibility rules run against PostgreSQL in UniversalSearchIntegrationTest. */
class UniversalSearchServiceTest {

    @Test
    void searchesAsTheCallerAndReturnsThePage() {
        var repository = mock(UniversalSearchRepository.class);
        var service = new UniversalSearchService(repository);
        var user = User.builder().id(17L).build();
        var filters = new UniversalSearchFilters("auth", null, 3L, null);
        var pageable = PageRequest.of(1, 10);
        var hit = new UniversalSearchResult(SearchResultType.TASK, 55L, "Auth bug", "MOB · Mobile App",
                "/projects/8/tasks/55", 3L, 8L, "MOB-7");
        when(repository.search(17L, filters, pageable)).thenReturn(new PageImpl<>(List.of(hit), pageable, 11));

        PageResponse<UniversalSearchResult> page = service.search(user, filters, pageable);

        assertEquals(List.of(hit), page.content());
        assertEquals(11, page.totalElements());
        assertEquals(1, page.page());
    }
}