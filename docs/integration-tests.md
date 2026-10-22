# Full-context tests with PostgreSQL

`SprintoryBackendApplicationTests` now starts the whole Spring Boot application against a
**disposable PostgreSQL 16** instance provided by Testcontainers. The application test extends
`AbstractIntegrationTest`, which loads the `test` profile and imports a Spring-managed container
configuration. `@ServiceConnection` supplies the actual JDBC connection details. No locally
running PostgreSQL instance, `.env` file, seeded developer admin, real JWT key, or Google/SMTP
credentials are needed.

## Run locally

Start Docker (no need to start a PostgreSQL container yourself). Then run:

```bash
bash ./mvnw -B clean verify
```

On the first run, Testcontainers downloads the PostgreSQL 16 Alpine image. The context test
verifies that the JDBC database is PostgreSQL and that the `UserRepository` works with the
schema created by Hibernate. Tests must **fail**, not silently skip, if Docker is unavailable.
Mock-based unit tests do not start containers.

## Test isolation

- `@ActiveProfiles("test")` prevents the `dev` admin seeder from running.
- `application-test.properties` supplies a deliberately unreachable JDBC placeholder; the
  actual temporary connection is injected by `@ServiceConnection`, which takes precedence.
- The test-only JWT signing secret is not a real credential and must not be used outside tests.
- `spring.jpa.hibernate.ddl-auto=create-drop` applies only to the disposable database.
- No production or developer database is touched.

## CI

The existing backend GitHub Actions workflow runs `mvn verify` on PRs targeting `main`.
GitHub-hosted Ubuntu runners have Docker; Testcontainers will manage the PostgreSQL container
for the context test. PRs targeting `features/auth_config` are not covered by the current CI
trigger, so run `clean verify` locally before merging the fix branch into that branch, then
verify again on the PR to `main`. No CI workflow change is included in this patch.
