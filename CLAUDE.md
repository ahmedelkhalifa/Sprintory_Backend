# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Sprintory backend — a project management platform (Jira/Asana-like) for SFWE343 Software Analysis and Design, Fall 2026-27. Spring Boot 4.1.1 / Spring Security 7, Java 17, Postgres, Maven.

**The frontend is a separate repository and a separate application.** Never add frontend assets here, and assume the browser talks to this API cross-origin via CORS.

`docs/requirements.md` holds all 41 requirements (R01–R41) with their sprint assignments; use those IDs in branches, tickets and PR descriptions. `docs/api-conventions.md` is the team's API and Git contract — read it before adding endpoints.

## Commands

```bash
./mvnw.cmd -B compile                 # compile
./mvnw.cmd -B test                    # run tests
./mvnw.cmd -B -Dtest=SomeTest test         # one test class
./mvnw.cmd -B -Dtest=SomeTest#someMethod test   # one test method
./mvnw.cmd -B clean verify            # what CI runs
./mvnw.cmd spring-boot:run            # run (needs the env vars below)
```

If the wrapper reports `JAVA_HOME is not defined correctly`, it is not inherited from the IDE — set it to a JDK 17 install for the command, e.g. `JAVA_HOME=/c/path/to/jdk-17 ./mvnw.cmd -B compile`.

There is no linter or formatter configured.

## Configuration and environment

`application.properties` resolves everything from environment variables. **`.env` is gitignored and is loaded only by the IntelliJ EnvFile plugin** (wired in `.idea/workspace.xml`, itself gitignored). There is no `spring.config.import`, so Maven runs, plain terminal runs and CI see none of it. `.env.example` lists the key names.

Two traps that cost real time:

- An **empty** variable defeats its default. `COOKIE_SECURE=` makes `${COOKIE_SECURE:false}` resolve to `""`, which then fails to convert to `boolean` at startup. Give every key in `.env` a value.
- `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` have `:dummy` defaults so the context still starts without them. Google sign-in then fails at Google rather than at boot.

Mail defaults point to a local Mailpit (`localhost:1025`, inbox at http://localhost:8025): `docker run -p 1025:1025 -p 8025:8025 axllent/mailpit`. Without it the app still starts, but registration answers 503 because the verification email cannot be sent.

## Full-context integration tests (Testcontainers)

`SprintoryBackendApplicationTests` extends `AbstractIntegrationTest`, which activates the
`test` profile and imports `PostgresTestConfiguration`. The latter declares a PostgreSQL 16
Testcontainers bean with `@ServiceConnection`, managed for the Spring test-context lifetime.
`src/test/resources/application-test.properties` provides a **test-only** HS256 secret, an
unusable dummy JDBC URL as a guard, and Flyway enabled with `ddl-auto=validate`. The `@ServiceConnection`
replaces that dummy URL with the temporary container connection. `DevDataSeeder` is excluded
because it only runs with the `dev` profile. No real DB or auth secrets are needed.

Run `bash ./mvnw -B clean verify` (or `mvnw.cmd -B clean verify` on Windows) with Docker
running. The context test checks both PostgreSQL JDBC connectivity and repository/schema
wiring. Other mocked unit tests do **not** require Docker.

**Before merging to `main`, verify the full build both locally and in CI.** GitHub's hosted
Ubuntu runners provide Docker; Docker must be running locally. See `docs/integration-tests.md`.

## Database migrations (Flyway)

- **Dev** keeps `ddl-auto=update` and has Flyway off (`spring.flyway.enabled=false` in
  `application.properties`). **Prod** (`application-prod.properties`) and the context test run
  Flyway from `src/main/resources/db/migration` and then `ddl-auto=validate`.
- Because dev auto-updates, a changed entity works locally even without a migration, and
  production would then refuse to start. **Every entity change needs a new
  `V{n}__description.sql` in the same PR**; the context test fails in CI if one is missing.
- Never edit a migration that is merged — Flyway checksums it. Add a new version instead.
- Enum columns have check constraints, so adding an enum value also needs a migration.

## Deployment

**`docs/deployment.md` is the deployment runbook.** Production is one VPS running
`deploy/docker-compose.yml`: Postgres, the backend image pulled from Docker Hub (built from the root
`Dockerfile`, tests skipped because CI runs them), and Nginx Proxy Manager, which is configured in its
web UI — there is no Nginx config in this repo. The CD workflow that builds and pushes the image is
not written yet.

- Frontend and API must be `app.<domain>` and `api.<domain>` (same site), because the auth cookies
  are `SameSite=Lax`.
- `application-prod.properties` sets `server.forward-headers-strategy=framework` so Spring sees the
  proxy's `https`; the Google `redirect_uri` depends on it.
- Under compose, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and `SPRING_PROFILES_ACTIVE` come from the
  compose file, not from `deploy/.env`.

## R01 status

**`docs/auth-endpoints.md` is the authentication contract**: endpoints, flows, cookies, every error code, configuration, decisions, and an implementation-status table. Change the code and that file in the same PR.

All `AuthService` methods are implemented and unit-tested (`AuthServiceTest`, `OneTimeTokenServiceTest`, `AuthControllerTest`, `AuthRequestValidationTest`). These are mocked tests, so the real queries, Spring wiring and SMTP still need the manual end-to-end run with Mailpit before R01 can be closed. `pom.xml` configures Lombok explicitly as an annotation processor; keep that.

## Authentication architecture

This is the part that needs several files to understand.

**Stateless.** `SessionCreationPolicy.STATELESS`, no server-side session, nothing is persisted between requests except the token rows. "Logging someone in" *is* setting the cookie — there is no `saveContext` call to make.

**Three tokens, two mechanisms:**

| | Access token | Refresh token | Link token (verify email / reset password) |
|---|---|---|---|
| Form | HS256 JWT, `sub` = email | opaque 32 random bytes | opaque 32 random bytes |
| Storage | nowhere | `refresh_tokens` row, SHA-256 hash only | `one_time_tokens` row with a `purpose`, SHA-256 hash only |
| Carried in | `access_token` cookie, path `/` | `refresh_token` cookie, path `/api/v1/auth` | `?token=` of the emailed frontend link |
| Revocable | no, TTL only (15 min) | yes — the row *is* the validity | yes — consumed on use; a new link replaces older ones |

Random generation and hashing live in one place, `OpaqueTokens`, shared by `RefreshTokenService` and `OneTimeTokenService`. Because refresh tokens are not JWTs, they cannot be replayed through `JwtAuthenticationFilter`, which is why no `type` claim is needed. `verifyAndConsume` is single-use and returns the consumed row so `refresh` can carry over its `rememberMe` flag (persistent vs. session cookies, see `AuthCookieFactory`). Logout calls `revokeAllForToken`, which signs the owner out on every device; password reset calls `revokeAll(user)`. Expired rows are deliberately *not* deleted on the failure path (the throw would roll the delete back) — nightly `@Scheduled` jobs clear both tables, which is why the application class carries `@EnableScheduling`.

**Login order matters.** `SecurityConfig.authenticationProvider()` moves the enabled/locked checks to *after* the password check. Spring's default order would answer a wrong password on an unverified account with 403 `ACCOUNT_NOT_ACTIVATED`, revealing that the email is registered.

**Emails in `AuthService`.** `register` sends the verification email as the last step inside its transaction, so a delivery failure rolls the account back (503). `forgotPassword` catches and logs delivery failures on purpose: a 503 only for registered emails would reveal which addresses exist.

**Request path:** `JwtAuthenticationFilter` reads the `access_token` cookie, validates it, loads the user through `AppUserDetailsService`, checks `isEnabled()`, and populates the context. Any failure clears the context silently so `AppAuthenticationEntryPoint` produces the normal 401 JSON instead of a 500. Note that `isEnabled()` is only consulted when the token is presented — disabling a user does not invalidate a token already issued; revoking their refresh tokens bounds it to the access TTL.

**CSRF is disabled on purpose** (decided 21 Oct 2026). Consequence: `SameSite=Lax` on the auth cookies is the only CSRF protection left. **Do not move a cookie to `SameSite=None` without re-enabling CSRF**, and keep state-changing auth endpoints on POST.

**Google sign-in** (`security/oauth2/`):
- `CookieAuthorizationRequestRepository` exists because the only implementation Spring ships is session-backed and this app is stateless. Without it the callback fails with `authorization_request_not_found`. The cookie must be `SameSite=Lax` — `Strict` withholds it on exactly the cross-site navigation back from Google. It is not signed; someone able to set it could pin `state`.
- `AppOidcUserService extends OidcUserService`, **not** `DefaultOAuth2UserService` — the registration requests the `openid` scope, so the OIDC path runs and a plain `userService` would never be called. Wire it with `.oidcUserService(...)`.
- An email already registered with a password gets `USE_PASSWORD_LOGIN`; it never silently links accounts. Both handlers redirect to the separate frontend and never put tokens in the URL.
- Enabling `oauth2Login` also switches on Spring's generated `/login` page. Harmless, suppressible with `.loginPage(...)`.

## Workspaces (R02)

**`docs/workspaces-docs.md` is the workspaces contract** (endpoints, invitation flow, slugs, archive/delete, pagination rules, error codes, status table), **`docs/teams-docs.md` the teams contract** and **`docs/projects-docs.md` the projects contract**. Change the code and the matching file in the same PR.

- **Every access check goes through `workspace/services/WorkspaceAccess`.** `requireMember` / `requireAdmin` for reads and for archive/restore/delete; `requireActiveMember` / `requireActiveAdmin` for anything that changes data — they also refuse an archived workspace with 409 `WORKSPACE_ARCHIVED`. A non-member gets the same 404 as a missing workspace; a member without the role gets 403.
- **Permanent workspace delete relies on the database's `ON DELETE CASCADE`.** Every table that hangs off a workspace (or a user) must put `@OnDelete(action = OnDeleteAction.CASCADE)` on that `@ManyToOne`, or the delete fails on the foreign key.
- Invitations are addressed to an email, not a user, so they have their own table (`WorkspaceInvitation`) instead of `one_time_tokens`, but use the same `OpaqueTokens`. Replacing an invitation uses a bulk `@Modifying` delete on purpose: Hibernate flushes inserts before derived deletes, which would hit the (workspace, email) unique constraint.

## Roles and permissions (R03)

**`docs/roles-docs.md` is the roles contract** (roles, permission matrix, rules, error codes, status). Change the code and that file in the same PR.

- **Every check inside a project goes through `project/services/ProjectAccess`** and asks for a `ProjectPermission`, never a role name: `requireVisible` (404), `require(…, permission)` (403), `requireActive(…, permission)` (also 409 archived). A new feature (tasks, sprints, backlog, settings) adds one such call per action.
- **The matrix lives in `ProjectRole`**: each role lists its permissions, and `permissionsOf` combines a person's roles. Workspace admins get `ProjectPermission.WORKSPACE_ADMIN` in every project, which deliberately excludes `VIEW_WORK`. `ProjectTemplate` decides which roles a project offers.
- "At least one admin / Project Manager" checks run under a row lock (`WorkspaceRepository.lockById`, `ProjectRepository.lockById`). Keep that when adding a path that removes an admin or a Project Manager.
- Leaving a workspace is **not** an `ON DELETE CASCADE`: it deletes only the `workspace_members` row, so `WorkspaceService.removeMember` deletes the person's team and project rows of that workspace explicitly. A new per-workspace membership table needs the same.
- A new `ProjectRole` value needs a migration changing `ck_project_member_roles_role`; a new `ProjectPermission` needs only code and the matrix test.
- Story 6's endpoints and DTO fields are built by another developer, against `docs/roles-docs.md` §3.

## Projects and boards (R06)

The contract is `docs/projects-docs.md` (§3.1 create, §3.8 board, §3.9 settings).

- **The board is created with the project**, in `ProjectService.create`'s transaction, by `ProjectBoardService.initialize` (`Propagation.MANDATORY`). Default columns and the backlog/sprints/WIP flags come from one strategy per template in `project/strategy/`; `ProjectBoardStrategies` fails at startup if a template has no strategy or two. The flags are not stored: the template never changes.
- A new `ProjectTemplate` value needs a strategy class, a migration for `ck_projects_template`, and its roles in `ProjectRole`.
- **The project key never changes** (`updatable = false`): task keys are built from it. It is stored upper-case (`Locale.ROOT`) and unique per workspace (`uk_projects_workspace_key`); the service checks first for a clean 409 `PROJECT_KEY_TAKEN`, the constraint catches races. The column is `project_key` because `key` is an SQL keyword.
- The board is project *work*: `GET /board` needs `VIEW_WORK`, so a workspace admin who is not in the project gets 403. Settings need `EDIT_SETTINGS`.
- Board columns keep their ids. Change default columns of existing boards by renaming rows in a migration, not by deleting and recreating them, once tasks point at columns.

## Error response contract

One JSON shape (`docs/api-conventions.md` §4), produced from three places that must stay in sync: `exceptionHandler/ExceptionHandler` for anything thrown inside MVC, `AppAuthenticationEntryPoint` for 401, `AppAccessDeniedHandler` for 403. `ErrorCode` is the stable machine-readable vocabulary — add to the enum rather than inventing strings.

The advice class is literally named `ExceptionHandler`, which shadows the annotation, so every handler method uses the fully-qualified `@org.springframework.web.bind.annotation.ExceptionHandler`. Keep that; do not rename the class to work around it.

Bad credentials and unknown email return an **identical** body on purpose — the response must never reveal whether an address is registered. Same principle in `docs/api-conventions.md` §5: a project that does not exist and one the user cannot see both return 404.

Services should let their exceptions propagate; the advice maps them.

## Conventions in this codebase

- Packages are grouped by feature, then by kind: `user/{models,repositories}`, `workspace/{models,repositories,services,controllers,dto,exceptions}`, `team/{models,repositories,services,controllers,dto}`, `project/{models,repositories,services,controllers,dto,exceptions,strategy}`, `common/` for cross-feature helpers (`PageResponse`, `Pageables`), `security/auth/{models,repositories,services}`, `security/{config,handlers,jwt,oauth2,userdetails,exceptions}`. Put a new class in the matching subfolder rather than at the package root.
- **Edit the existing class rather than adding a parallel one.** Do not create `GlobalExceptionHandler` next to `ExceptionHandler`, or a `*V2`.
- Entities extend `audit/BaseEntity` for the four audit columns. `AuditorProvider` stamps `system` for anonymous and unauthenticated writes.
- `@Value` on a **`final`** field combined with `@RequiredArgsConstructor` silently produces a constructor with no parameter annotation and fails at startup. Use a non-final field or write the constructor explicitly.
- **Jackson 3**: `tools.jackson.databind.ObjectMapper`, not `com.fasterxml.jackson.databind`. Annotations are still `com.fasterxml.jackson.annotation`. `JacksonException` is unchecked, so no `throws` clause is needed. `jjwt-jackson` drags in Jackson 2 as well; the two coexist in different packages.
- **Users have no role** (decided 23 Oct 2026). Roles are per workspace, on `WorkspaceMember`: the creator of a workspace is its `WORKSPACE_ADMIN`, invitees join as `WORKSPACE_MEMBER`. Logins carry no authorities, so `hasRole(...)` / `@PreAuthorize` role checks never match — check roles in the service through `WorkspaceAccess` and, inside a project, permissions through `ProjectAccess`.
- Emails are stored trimmed and lower-cased with **`toLowerCase(Locale.ROOT)`** — a Turkish default locale turns `I` into a dotless `ı`. Every place that normalizes an email does it this way; keep it so.
- `Role` is `{WORKSPACE_MEMBER, WORKSPACE_ADMIN}` and is used only by `WorkspaceMember`; project roles are the separate `ProjectRole`. `Provider` is `{LOCAL, GOOGLE}`.
- The 8-character password minimum has **no backstop in the service layer** — it belongs on the request DTOs as `@Size(min = 8)`.