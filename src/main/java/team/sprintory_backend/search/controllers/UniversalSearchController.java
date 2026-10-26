package team.sprintory_backend.search.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import team.sprintory_backend.common.Pageables;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.search.dto.UniversalSearchFilters;
import team.sprintory_backend.search.dto.UniversalSearchResult;
import team.sprintory_backend.search.services.UniversalSearchService;
import team.sprintory_backend.security.userdetails.AppUserDetails;

/** Authenticated universal-search endpoint. */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class UniversalSearchController {
    private final UniversalSearchService universalSearchService;

    @GetMapping
    public PageResponse<UniversalSearchResult> search(@AuthenticationPrincipal AppUserDetails principal,
                                                       @RequestParam String q,
                                                       @RequestParam(required = false) String types,
                                                       @RequestParam(required = false) Long workspaceId,
                                                       @RequestParam(required = false) Long projectId,
                                                       Pageable pageable) {
        return universalSearchService.search(principal.getUser(),
                UniversalSearchFilters.fromQuery(q, types, workspaceId, projectId),
                Pageables.withoutSort(pageable));
    }
}
