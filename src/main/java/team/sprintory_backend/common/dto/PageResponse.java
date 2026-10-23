package team.sprintory_backend.common.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * The one JSON shape for every paginated list. Our own type rather than Spring's Page, so the
 * contract stays stable and shows only what the frontend needs. {@code page} is 0-based.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}