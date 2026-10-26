package team.sprintory_backend.search.repositories;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import team.sprintory_backend.common.Pageables;
import team.sprintory_backend.search.dto.SearchResultType;
import team.sprintory_backend.search.dto.UniversalSearchFilters;
import team.sprintory_backend.search.dto.UniversalSearchResult;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Universal search: one {@code UNION ALL} over workspaces, projects, teams and tasks, filtered
 * in SQL to what the user may see, sorted and paged by the database.
 *
 * <p>Visibility must match the rest of the API: workspaces the user is a member of; projects
 * and teams of those workspaces, all of them for an admin, otherwise only the user's own; tasks
 * only from projects the user is a member of (VIEW_WORK; every project role has it, a workspace
 * admin outside the project has not), never deleted ones. This is native SQL, so the
 * {@code @SQLRestriction} on {@code Task} does not apply: {@code deleted_at is null} is written out.
 *
 * <p>Only fixed SQL fragments are concatenated; every user value is a bound parameter.
 */
@Repository
@RequiredArgsConstructor
public class UniversalSearchRepository {

    private static final String LIKE = " like :pattern escape '\\'";

    private final NamedParameterJdbcTemplate jdbc;

    public Page<UniversalSearchResult> search(Long userId, UniversalSearchFilters filters, Pageable pageable) {
        List<String> parts = new ArrayList<>();
        boolean projectScope = filters.projectId() != null;
        if (filters.types().contains(SearchResultType.WORKSPACE) && !projectScope) {
            parts.add(workspaces(filters));
        }
        if (filters.types().contains(SearchResultType.PROJECT)) {
            parts.add(projects(filters));
        }
        if (filters.types().contains(SearchResultType.TEAM) && !projectScope) {
            parts.add(teams(filters));
        }
        if (filters.types().contains(SearchResultType.TASK)) {
            parts.add(tasks(filters));
        }
        if (parts.isEmpty()) {
            // e.g. only workspaces or teams asked for, inside one project: nothing can match.
            return new PageImpl<>(List.of(), pageable, 0);
        }

        String q = filters.q().toLowerCase(Locale.ROOT);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("exact", q)
                .addValue("prefix", Pageables.escapeLike(q) + "%")
                .addValue("pattern", "%" + Pageables.escapeLike(q) + "%")
                .addValue("workspaceId", filters.workspaceId())
                .addValue("projectId", filters.projectId())
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());

        String hits = String.join(" union all ", parts);
        long total = jdbc.queryForObject("select count(*) from (" + hits + ") hits", params, Long.class);
        if (total == 0 || pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }
        // Best match first, then alphabetical; type and id make the order total, so pages never overlap.
        List<UniversalSearchResult> content = jdbc.query("select * from (" + hits + ") hits "
                        + "order by match_rank, lower(title), type_order, id limit :limit offset :offset",
                params, UniversalSearchRepository::toResult);
        return new PageImpl<>(content, pageable, total);
    }

    /*
     * Every part names all its columns: a UNION takes its column names from its first part, and
     * which part comes first depends on the types and scope asked for.
     */

    private static String workspaces(UniversalSearchFilters filters) {
        return columns("'WORKSPACE'", 0, "w.id", "w.name", "cast(null as varchar)", "w.id",
                "cast(null as bigint)", "cast(null as varchar)", rank("w.name", null))
                + "from workspaces w "
                + "join workspace_members wm on wm.workspace_id = w.id and wm.user_id = :userId "
                + "where lower(w.name)" + LIKE
                + (filters.workspaceId() != null ? " and w.id = :workspaceId" : "");
    }

    private static String projects(UniversalSearchFilters filters) {
        return columns("'PROJECT'", 1, "p.id", "p.name", "w.name", "p.workspace_id", "p.id",
                "cast(null as varchar)", rank("p.name", "p.project_key"))
                + "from projects p "
                + "join workspaces w on w.id = p.workspace_id "
                + "join workspace_members wm on wm.workspace_id = p.workspace_id and wm.user_id = :userId "
                + "where (wm.role = 'WORKSPACE_ADMIN' or exists (select 1 from project_members pm "
                + "where pm.project_id = p.id and pm.user_id = :userId)) "
                + "and (lower(p.name)" + LIKE + " or lower(p.project_key)" + LIKE + ")"
                + (filters.workspaceId() != null ? " and p.workspace_id = :workspaceId" : "")
                + (filters.projectId() != null ? " and p.id = :projectId" : "");
    }

    private static String teams(UniversalSearchFilters filters) {
        return columns("'TEAM'", 2, "t.id", "t.name", "w.name", "t.workspace_id", "cast(null as bigint)",
                "cast(null as varchar)", rank("t.name", null))
                + "from teams t "
                + "join workspaces w on w.id = t.workspace_id "
                + "join workspace_members wm on wm.workspace_id = t.workspace_id and wm.user_id = :userId "
                + "where (wm.role = 'WORKSPACE_ADMIN' or exists (select 1 from team_members tm "
                + "where tm.team_id = t.id and tm.user_id = :userId)) "
                + "and lower(t.name)" + LIKE
                + (filters.workspaceId() != null ? " and t.workspace_id = :workspaceId" : "");
    }

    private static String tasks(UniversalSearchFilters filters) {
        return columns("'TASK'", 3, "t.id", "t.title", "p.project_key || ' · ' || p.name", "p.workspace_id",
                "t.project_id", "t.task_key", rank("t.title", "t.task_key"))
                + "from tasks t "
                + "join projects p on p.id = t.project_id "
                + "join project_members pm on pm.project_id = t.project_id and pm.user_id = :userId "
                + "join workspace_members wm on wm.workspace_id = p.workspace_id and wm.user_id = :userId "
                + "where t.deleted_at is null "
                + "and (lower(t.title)" + LIKE + " or lower(t.task_key)" + LIKE + ")"
                + (filters.workspaceId() != null ? " and p.workspace_id = :workspaceId" : "")
                + (filters.projectId() != null ? " and t.project_id = :projectId" : "");
    }

    private static String columns(String type, int typeOrder, String id, String title, String subtitle,
                                  String workspaceId, String projectId, String taskKey, String matchRank) {
        return "select " + type + " as type, " + typeOrder + " as type_order, " + id + " as id, "
                + title + " as title, " + subtitle + " as subtitle, " + workspaceId + " as workspace_id, "
                + projectId + " as project_id, " + taskKey + " as task_key, " + matchRank + " as match_rank ";
    }

    /** 0 for an exact match on either field, 1 for a prefix match, 2 for "contains". */
    private static String rank(String field, String otherField) {
        String exact = "lower(" + field + ") = :exact";
        String prefix = "lower(" + field + ") like :prefix escape '\\'";
        if (otherField != null) {
            exact += " or lower(" + otherField + ") = :exact";
            prefix += " or lower(" + otherField + ") like :prefix escape '\\'";
        }
        return "case when " + exact + " then 0 when " + prefix + " then 1 else 2 end";
    }

    private static UniversalSearchResult toResult(ResultSet row, int rowNumber) throws SQLException {
        SearchResultType type = SearchResultType.valueOf(row.getString("type"));
        long id = row.getLong("id");
        Long projectId = row.getObject("project_id", Long.class);
        String path = switch (type) {
            case WORKSPACE -> "/workspaces/" + id;
            case PROJECT -> "/projects/" + id;
            case TEAM -> "/teams/" + id;
            case TASK -> "/projects/" + projectId + "/tasks/" + id;
        };
        return new UniversalSearchResult(type, id, row.getString("title"), row.getString("subtitle"), path,
                row.getObject("workspace_id", Long.class), projectId, row.getString("task_key"));
    }
}