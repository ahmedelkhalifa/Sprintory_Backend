package team.sprintory_backend.search.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.search.dto.SearchResultType;
import team.sprintory_backend.search.dto.UniversalSearchFilters;
import team.sprintory_backend.search.dto.UniversalSearchResult;
import team.sprintory_backend.search.services.UniversalSearchService;
import team.sprintory_backend.security.userdetails.AppUserDetails;
import team.sprintory_backend.user.models.User;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UniversalSearchControllerTest {
    @Test
    void delegatesNormalizedSearchAndDropsClientSort() {
        var service = mock(UniversalSearchService.class);
        var controller = new UniversalSearchController(service);
        var user = User.builder().id(17L).firstName("Ada").lastName("Lovelace")
                .email("ada@example.com").build();
        var principal = new AppUserDetails(user);
        var filters = new UniversalSearchFilters("auth", Set.of(SearchResultType.TASK,
                SearchResultType.PROJECT), 1L, null);
        var expectedPage = PageRequest.of(0, 20);
        var hit = new UniversalSearchResult(SearchResultType.PROJECT, 9L,
                "Auth", null, "/projects/9", 1L, 9L, null);
        var response = new PageResponse<>(List.of(hit), 0, 20, 1, 1);
        when(service.search(user, filters, expectedPage)).thenReturn(response);

        assertSame(response, controller.search(principal, " auth ", "task,project", 1L,
                null, PageRequest.of(0, 20, Sort.by("anything"))));
        verify(service).search(user, filters, expectedPage);
    }
}
