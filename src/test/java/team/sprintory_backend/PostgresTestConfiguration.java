package team.sprintory_backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared database fixture for full application-context integration tests.
 * Spring owns the container bean so its lifetime follows the cached test context.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresqlContainer() {
        return new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("sprintory_test")
                .withUsername("sprintory_test")
                .withPassword("sprintory_test");
    }
}
