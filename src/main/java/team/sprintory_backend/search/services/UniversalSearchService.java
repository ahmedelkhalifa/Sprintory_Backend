package team.sprintory_backend.search.services;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.search.dto.UniversalSearchFilters;
import team.sprintory_backend.search.dto.UniversalSearchResult;
import team.sprintory_backend.search.repositories.UniversalSearchRepository;
import team.sprintory_backend.user.models.User;

/**
 * Search across the workspaces, projects, teams and tasks the user can see. Visibility is
 * enforced inside the query (see {@link UniversalSearchRepository}), so a scope the user cannot
 * see simply finds nothing; it never reveals whether that workspace or project exists.
 */
@Service
@RequiredArgsConstructor
public class UniversalSearchService {

    private final UniversalSearchRepository searchRepository;

    @Transactional(readOnly = true)
    public PageResponse<UniversalSearchResult> search(User user, UniversalSearchFilters filters,
                                                       Pageable pageable) {
        return PageResponse.from(searchRepository.search(user.getId(), filters, pageable));
    }
}