package team.sprintory_backend;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Extend this for tests that need the complete Spring context and a real PostgreSQL database.
 * Unit tests with mocked dependencies should not extend this class or require Docker.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
public abstract class AbstractIntegrationTest {
}
