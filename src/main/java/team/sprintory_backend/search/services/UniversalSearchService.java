package team.sprintory_backend.search.services;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import team.sprintory_backend.common.dto.PageResponse;
import team.sprintory_backend.search.dto.UniversalSearchFilters;
import team.sprintory_backend.search.dto.UniversalSearchResult;
import team.sprintory_backend.user.models.User;

/**
 * Universal search contract: search visible workspaces, projects, teams and non-deleted tasks.
 * TODO(PMP-105/PMP-104): implement using scoped DB queries (never aggregate unfiltered rows).
 *
 * For tasks, require project VIEW_WORK membership; workspace administrators who are not project
 * members must not see task names. Projects/teams follow existing visibility rules. Combine all
 * selected resource kinds into one deterministic, paginated result with correct totalElements.
 */
@Service
public class UniversalSearchService {
    public PageResponse<UniversalSearchResult> search(User user, UniversalSearchFilters filters,
                                                       Pageable pageable) {
        // TODO(PMP-104): wire task search after the entity/repository is available.
        // TODO(PMP-105): implement scoped cross-resource search with one stable result order.
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "Universal search is pending database search integration.");
    }
}
