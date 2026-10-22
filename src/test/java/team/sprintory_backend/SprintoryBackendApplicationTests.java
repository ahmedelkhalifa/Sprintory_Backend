package team.sprintory_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import team.sprintory_backend.user.repositories.UserRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Context startup must use an actual disposable PostgreSQL instance. */
class SprintoryBackendApplicationTests extends AbstractIntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserRepository userRepository;

    @Test
    void contextLoads() throws SQLException {
        assertFalse(Arrays.asList(environment.getActiveProfiles()).contains("dev"),
                "The developer account seeder must not run during integration tests");
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
        }
        // Also exercises Hibernate mappings, JPA repository wiring and test schema creation.
        userRepository.count();
    }
}
