package team.sprintory_backend.task;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import team.sprintory_backend.AbstractIntegrationTest;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * V11 (PMP-133) on a real PostgreSQL: a database at V10 with status moves in the old
 * {@code task_status_history} is migrated, and the moves must survive in {@code task_history}.
 * Runs in its own schema so the application's schema is untouched. Docker must be running.
 */
class TaskHistoryMigrationIntegrationTest extends AbstractIntegrationTest {

    private static final String SCHEMA = "v11_migration_check";

    @Autowired private DataSource dataSource;

    @AfterEach
    void dropSchema() {
        new JdbcTemplate(dataSource).execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void v11CopiesEveryStatusMoveWithTheActorsNameAndDropsTheOldTable() {
        migrateTo("10");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Long ada = id(jdbc, "insert into %s.users (first_name, last_name, email, enabled, provider) "
                + "values ('Ada', 'Lovelace', 'ada-v11@example.com', true, 'LOCAL') returning id");
        Long workspace = id(jdbc, "insert into %s.workspaces (name, slug) values ('Acme', 'acme-v11') returning id");
        Long project = id(jdbc, "insert into %s.projects (workspace_id, name, template, project_key) "
                + "values (" + workspace + ", 'Mobile App', 'KANBAN', 'MOB') returning id");
        jdbc.update(sql("insert into %s.project_boards (project_id) values (" + project + ")"));
        Long todo = column(jdbc, project, "To Do", 0, "TODO");
        Long done = column(jdbc, project, "Done", 1, "DONE");
        Long task = id(jdbc, "insert into %s.tasks (project_id, task_number, task_key, title, type, priority, "
                + "status_column_id, board_position, backlog_rank) "
                + "values (" + project + ", 1, 'MOB-1', 'Fix login', 'TASK', 'MEDIUM', " + done + ", 0, 0) returning id");
        jdbc.update(sql("insert into %s.task_status_history (task_id, from_column_id, to_column_id, "
                + "from_column_name, to_column_name, actor_id, changed_at) values "
                + "(" + task + ", " + todo + ", " + done + ", 'To Do', 'Done', " + ada + ", '2026-11-01T10:00:00Z'), "
                + "(" + task + ", " + done + ", " + todo + ", 'Done', 'To Do', 999999, '2026-11-01T11:00:00Z')"));

        migrateTo("11");

        List<Map<String, Object>> rows = jdbc.queryForList(sql(
                "select type, field, old_value, new_value, ref_id, actor_id, actor_name "
                        + "from %s.task_history where task_id = " + task + " order by occurred_at, id"));
        assertEquals(2, rows.size());
        assertEquals(Map.of("type", "FIELD_CHANGED", "field", "STATUS", "old_value", "To Do", "new_value", "Done",
                "actor_id", ada, "actor_name", "Ada Lovelace"), withoutNulls(rows.get(0)));
        assertEquals("Deleted user", rows.get(1).get("actor_name"));
        assertEquals(999999L, rows.get(1).get("actor_id"));
        assertNull(rows.get(1).get("ref_id"));
        assertNull(jdbc.queryForObject(sql("select to_regclass('%s.task_status_history')::text"), String.class));
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private static Long column(JdbcTemplate jdbc, Long project, String name, int position, String category) {
        return id(jdbc, "insert into %s.project_board_columns (project_id, name, sort_order, category) "
                + "values (" + project + ", '" + name + "', " + position + ", '" + category + "') returning id");
    }

    private static Long id(JdbcTemplate jdbc, String insertReturningId) {
        return jdbc.queryForObject(sql(insertReturningId), Long.class);
    }

    private static String sql(String template) {
        return template.formatted(SCHEMA);
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> row) {
        Map<String, Object> copy = new java.util.HashMap<>(row);
        copy.values().removeIf(java.util.Objects::isNull);
        return copy;
    }
}