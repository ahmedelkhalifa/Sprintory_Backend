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
- Leaving a workspace is **not** an `ON DELETE CASCADE`: it deletes only the `workspace_members` row, so `WorkspaceService.removeMember` deletes the person's team and project rows of that workspace explicitly, and unassigns them from its tasks. A new per-workspace membership table needs the same, and every path that takes someone out of a project must call `TaskRepository.unassignInProject` (or `unassignInWorkspace`) and delete their task watchers (see Notifications below).
- A new `ProjectRole` value needs a migration changing `ck_project_member_roles_role`; a new `ProjectPermission` needs only code and the matrix test.
- Story 6's endpoints and DTO fields are built by another developer, against `docs/roles-docs.md` §3.

## Projects and boards (R06)

The contract is `docs/projects-docs.md` (§3.1 create, §3.8 board, §3.9 settings); column configuration and WIP limits (R16, PMP-28) are in `docs/board-docs.md`.

- **The board is created with the project**, in `ProjectService.create`'s transaction, by `ProjectBoardService.initialize` (`Propagation.MANDATORY`). Default columns and the backlog/sprints/WIP flags come from one strategy per template in `project/strategy/`; `ProjectBoardStrategies` fails at startup if a template has no strategy or two. The flags are not stored: the template never changes.
- A new `ProjectTemplate` value needs a strategy class, a migration for `ck_projects_template`, and its roles in `ProjectRole`.
- **The project key never changes** (`updatable = false`): task keys are built from it. It is stored upper-case (`Locale.ROOT`) and unique per workspace (`uk_projects_workspace_key`); the service checks first for a clean 409 `PROJECT_KEY_TAKEN`, the constraint catches races. The column is `project_key` because `key` is an SQL keyword.
- `GET /board` needs `VIEW_WORK` **or** `CONFIGURE_BOARD`. A workspace admin who is not in the project (they hold `CONFIGURE_BOARD` through `ProjectPermission.WORKSPACE_ADMIN`) therefore sees the columns and their task counts, but every task endpoint still answers them 403. Adding, renaming, reordering, recategorizing and deleting columns need `CONFIGURE_BOARD` (Project Managers and workspace admins). Settings need `EDIT_SETTINGS`.
- **Columns have a category** (`TODO` / `IN_PROGRESS` / `DONE`), independent of the name. "Done" means a `DONE`-category column, never a name or the rightmost position. A board always keeps at least one `TODO` and one `DONE` column, and 2–10 columns in total. Kanban's Backlog is fixed at position 0 and stays `TODO`.
- **Columns are soft-deleted** (`deleted_at`, `@SQLRestriction`). Names and positions are unique only among live columns (partial unique indexes in V7). Deleting a column that holds tasks needs a target column; the tasks move there and each move gets a STATUS entry in `task_history`.
- **WIP limits are soft**, Kanban only, never on Backlog or `DONE` columns: a move past the limit succeeds and returns a warning. Counts are top-level tasks only (`TaskRepository.countByProjectIdAndStatusIdAndParentIsNull`); subtasks never count.
- Board columns keep their ids. Change default columns of existing boards by renaming rows in a migration, not by deleting and recreating them, once tasks point at columns.
- V7 backfills categories and swaps the position constraint for partial indexes; dev's `ddl-auto=update` does neither, so a dev database created before V7 must be recreated.

## Tasks (R09) and universal search

Contracts: `docs/tasks-docs.md` and `docs/search-docs.md`. Damir built the controllers, DTOs and filters; the rules live in `task/services/TaskApiService`.

- **Soft delete.** `Task` carries `@SQLRestriction("deleted_at is null")`, so no JPA query returns a deleted task. **Native SQL does not get that filter**: write `deleted_at is null` yourself (as `UniversalSearchRepository` does).
- **Keys** (`MOB-42`): `ProjectRepository.incrementTaskNumber` (an UPDATE, which also takes the project row lock) then `findLastTaskNumber`. `Project.lastTaskNumber` is `updatable = false` so a project save never writes back a stale value. The counter never decreases: keys are never reused.
- **Status is a board column** (`status_column_id`), with **no cascade** on purpose. Scrum's "backlog" means "in no sprint"; Kanban's backlog is its first column.
- **Order:** `board_position` and `backlog_rank` are sparse (`TaskRanks.GAP`, 1,000,000 apart); a move takes the midpoint and renumbers the list only when there is no room. Every create and move runs under the project row lock (`incrementTaskNumber` or `ProjectRepository.lockById`). Read a column's max **before** the moving task joins it, or the auto-flush counts the task itself.
- **Universal search** is one native `UNION ALL` in `search/repositories/UniversalSearchRepository`. Every part must alias all its columns (a UNION takes its names from whichever part comes first). Its visibility rules duplicate `ProjectAccess` and the team rules: change both together.
- `JpaSpecificationExecutor` adds `delete(Specification)`, so Mockito's `delete(any())` on `TaskRepository` is ambiguous; use `any(Task.class)`.
- `TaskApiService` publishes `TaskCreated` and `TaskAssigned` (R27); keep publishing `TaskAssigned` only when the assignee changes to another person.
- **Subtasks (PMP-132) are `Task` rows with a `parent`**, one level only, and they share the project's key sequence. Their estimate is `estimateMinutes`, never story points. They are created, listed and edited through `SubtaskService` (the task PATCH refuses them), but delete and restore go through `TaskApiService`. The task list, board cards and WIP counts show top-level tasks only (`parent is null`). Moving a parent into a `DONE` column while subtasks are open needs `confirmOpenSubtasks` (409 `OPEN_SUBTASKS_CONFIRMATION_REQUIRED`), whether by a task move or by deleting a column into a `DONE` column (`ProjectBoardService.delete`). A parent's delete cascades to the children that were live (`deleted_with_parent_id`), and its restore brings back exactly those. Contract: tasks-docs §11.

## Comments, mentions and task history (R10, R26)

Contracts: `docs/comments-docs.md`, `docs/mentions-docs.md` (also the rich-text format) and `docs/task-history-docs.md`. Change the code and the matching file in the same PR. Both tables are in `V11__task_history.sql`.

- **Rich text is TipTap JSON, checked by `common/richtext/RichTextParser`**: an allow-list of nodes and marks, `http`/`https`/`mailto` links only, mentions as `{kind, id, label}`. It refuses anything else (`400 INVALID_RICH_TEXT`) and stores a normalized copy; no HTML is ever stored. Comments, task descriptions and chat messages (PMP-137) all go through it. A new node, mark or attribute is a change to the parser and to mentions-docs §2. Descriptions saved before 4 Nov 2026 may still be plain text: code reading a stored description must tolerate that (see `TaskApiService.storedDescription`).
- **Mentions go through `mention/services/MentionService.newlyMentionedUserIds(project, text, previous)`**, which compares with the previous text, checks only the new mentions (a non-member → `400 INVALID_MENTION`) and expands `@team` to its members in the project. Call it **before** writing anything, then publish `Mentioned` (or `TaskCommented` for a new comment) with the returned ids. The listener drops the author and non-members; never decide recipients in the producer.
- **History has one writer: `task/services/TaskStatusHistoryService`** (the PMP-125 name is kept). Its writers are `MANDATORY`, so call them inside the action's transaction:
  - `record` for status moves (it also publishes `TaskStatusChanged`)
  - `recordChanges(task, TaskSnapshot before, TaskSnapshot after, actor)` for edits: take `TaskSnapshot.of(task)` before changing anything
  - `recordCreated`, `recordCommentDeleted`, `recordSubtaskAdded` / `recordSubtaskRemoved`, `recordRestored` for the rest
- **History is append-only and stores display snapshots.** `TaskHistoryEntry` is `@Immutable`; values are names, not ids; `actor_id` has no foreign key. Never store description or comment text, a token or a link. A new `TaskHistoryType` or `TaskHistoryField` needs a migration changing `ck_task_history_type` / `ck_task_history_field` and its row in task-history-docs §3.
- **Comments are soft-deleted but stay listed** as "Comment deleted" placeholders, so `TaskComment` has **no** `@SQLRestriction`: check `isDeleted()` and never return the body of a deleted comment. Only the author edits (with `COMMENT`); the author or `MODERATE_COMMENTS` (Project Manager only) deletes. Saving the same text is not an edit.
- Existing tests that read task history filter to STATUS entries, because every task now also has a CREATED entry.
- `GET /history` is newest first by default; `?order=oldest` (`task/api/TaskHistoryOrder`) reverses it. Like the task list, it uses `order`, never Spring's `?sort=`, which is dropped by `Pageables.withoutSort`.

## Project chat (R26 part B, PMP-137)

Contract: `docs/project-chat-docs.md`, built by Damir. One persistent channel per project, `project_chat_messages` in `V12__project_chat.sql`.

- **Writes arrive over STOMP** (native WebSocket at `/ws/chat`), not REST; history is the only REST endpoint (`GET /projects/{id}/chat/messages`, cursor `before` + `limit`). `ChatService` publishes `ChatChanged`, and `ChatEventBroadcaster` sends it to `/topic/projects/{id}/chat` only after commit.
- **The socket has its own credential.** The access token is in an HttpOnly cookie, so `POST /api/v1/chat/socket-token` issues a 2-minute JWT with `token_use=chat_stomp`, accepted only on STOMP `CONNECT`. `JwtService.isValid` refuses that token for HTTP; keep that. `SecurityConfig` permits `/ws/chat` because the handshake itself is not authenticated.
- **`ChatStompSecurityInterceptor` checks every frame.** Inbound, SUBSCRIBE needs project membership and SEND needs `requireActive(COMMENT)`, on allow-listed destinations only. Outbound, it re-checks membership on **every** delivery, so a removed member stops receiving at once, and it drops any destination that is not a project chat topic. `ChatService` checks again; never trust the destination alone.
- Same rules as comments: `RichTextParser`, `MentionService.newlyMentionedUserIds`, author-only edit with `COMMENT`, delete by the author or `MODERATE_COMMENTS`, soft delete with a "Message deleted" placeholder. New mentions publish `notification/events/ProjectChatMentioned`, which `NotificationListener` turns into a project-level `MENTIONED` (a chat has no task, so it is not `Mentioned`).
- **A refused chat action is reported to its sender only**, on `/user/queue/errors`, by `ChatStompController`'s `@MessageExceptionHandler`, with the REST error shape and codes. A new exception that chat can throw needs its mapping there as well as in `exceptionHandler/ExceptionHandler`. The interceptor lets out only the session's own error queue.
- Workspace admins who are not project members cannot read or write the chat.
- `spring.task.execution.mode=force` keeps `applicationTaskExecutor` (used by the notification SSE stream) after WebSocket registers its own executors; without it the context fails to start.

## Attachments (R25, PMP-30 / PMP-141 / PMP-33)

**`docs/attachments-docs.md` is the attachments contract** (endpoints, type rules, WebP compression, headers, errors, clean-up, decisions). Change the code and that file in the same PR. Table and history types are in `V14__task_attachments.sql`.

- **Upload is all or nothing, in `AttachmentService.upload`:** count and sizes first (5 files, 20 MB), then every file into a temporary file, typed by `FileTypeDetector` from its content and compressed by `ImageOptimizer`, and only then stored and saved. A `TransactionSynchronization` deletes the stored files if the transaction does not commit; keep it on any new path that stores files.
- **The type comes from the content, never the extension or `Content-Type`,** and the extension must belong to the detected type. A new allowed type is an `AttachmentType` value, a signature in `FileTypeDetector`, a migration changing `ck_task_attachments_file_type`, and a row in the doc's §3.1. Never allow HTML or SVG.
- **The user's file name is display text only** (`AttachmentFileNames`). On disk a file is named by its `storage_key` (a UUID) under `app.attachments.dir`, and `LocalAttachmentStorage.pathOf` refuses anything that is not a key. PNG/JPEG/WebP become `.webp` when that is smaller; GIFs are never converted.
- **A deleted task hides its attachments.** `TaskAttachment` has `@SQLRestriction`, but that does not cover its task: download, preview and delete load through `TaskAttachmentRepository.findLiveWithTaskById`, which checks `t.deletedAt is null` itself. Someone who cannot see the project gets the same 404 "Attachment not found." as for a missing id.
- **Permissions:** `VIEW_WORK` to list and download, `EDIT_TASKS` to upload and delete one's own, `MODERATE_COMMENTS` (PM) to delete anyone's. Delete is soft and writes `ATTACHMENT_REMOVED` (`TaskStatusHistoryService.recordAttachmentRemoved`).
- **Downloads** always send `Content-Disposition: attachment`, `nosniff`, `Content-Security-Policy: default-src 'none'; sandbox`; only `/preview` (images) is `inline`. The size limit lives twice: `AttachmentService.MAX_FILE_BYTES` and `spring.servlet.multipart.max-file-size`; both answer 413 `FILE_TOO_LARGE`. Change them together.
- **`AttachmentCleanupJob`** (04:10) removes rows deleted over 30 days ago, then stored files without a row, then stale temp files, never touching files younger than a day. Its two queries are native on purpose (they must see soft-deleted rows).
- Deployment: the files are in the `sprintory-attachments` volume; the `Dockerfile` creates `/app/data/attachments` owned by `spring` so the volume is writable without root. Back it up with the database (`docs/deployment.md` §6).

## Audit log (R05)

**`docs/audit-log-docs.md` is the audit log contract** (endpoints, the action catalogue, frontend flows, decisions). Change the code and that file in the same PR.

- **Entries are written by a direct call, inside the action's own transaction:** `AuditLogService.record(workspace, actor, action, target, project, changes...)`, after the change succeeded. It is `Propagation.MANDATORY`, so a rollback removes the entry and a call outside a transaction fails. Refused actions therefore leave nothing.
- **Log only real changes.** Return before `record` on a no-op (same name, same roles, someone already a member). Every service test checks both "entry written" and "no entry when refused or unchanged".
- **Take snapshots before deleting or renaming.** Names are stored on the entry (`AuditTarget.user/team/project/task/...` builds them); `actor_id` and `project_id` have no foreign key on purpose, so entries outlive people and projects. Only `workspace_id` cascades.
- **Never put a token, password or link into an `AuditChange`.**
- The log is append-only: `AuditLogEntry` is `@Immutable`, and there is no update or delete endpoint. Reading needs `WorkspaceAccess.requireAdmin` (workspace admins only; archived workspaces stay readable).
- **A new `AuditAction` needs a migration** changing `ck_audit_log_entries_action`, plus a row in the doc's §4. `TASK_RESTORED` already exists for PMP-131.
- Security events have no workspace: `recordInAllWorkspacesOf(subject, ...)` writes one entry per workspace of the person. Repeated failed logins are counted in memory by `security/auth/services/LoginFailureTracker`, which writes in its own transaction (`REQUIRES_NEW`) because `login` has none; any failure there is swallowed so the 401 never changes.
- Cascaded removals are not logged separately (leaving a workspace writes one `MEMBER_LEFT`, not one entry per team and project).

## Notifications (R27)

**`docs/notifications-docs.md` is the notifications contract** (endpoints, the SSE stream, the event catalogue, preferences, watching, email delivery, decisions). Change the code and that file in the same PR.

- **Observer pattern, unlike the audit log.** A producer publishes one event record from `notification/events/` with `ApplicationEventPublisher`, after the change succeeded and inside its transaction (`TaskAssigned`, `TaskStatusChanged`, `ProjectMemberAdded`, ...). It never decides who is told. `notification/services/NotificationListener` does: it drops the actor, keeps only current project members, applies each recipient's preferences and does the auto-watch. Publish only real changes (same assignee, same roles: nothing). Comments and mentions publish `TaskCommented` and `Mentioned` (see "Comments, mentions and task history" below); sprints (PMP-119/122) will publish the sprint events that already exist; see the doc's §15.
- **Everything is written in the action's transaction** (`NotificationService.create` is `MANDATORY`), so a rollback removes the notification and its queued email. The live push happens only after commit (`@TransactionalEventListener(AFTER_COMMIT)` in `NotificationStreamService`).
- **Status changes all go through `TaskStatusHistoryService.record`**, which publishes `TaskStatusChanged`. A new path that moves a task between columns must call it.
- **Watchers:** every path that takes someone out of a project or workspace must delete their `task_watchers` rows (`TaskWatcherRepository.deleteInProject` / `deleteInWorkspace`), next to the `unassignIn...` call. Those two deletes are native SQL on purpose: they must include soft-deleted tasks.
- **The SSE stream request must never query the database.** Open-in-view keeps that request's EntityManager, and any connection it borrows, for the whole life of the stream (10 minutes); one query would pin a pooled connection per open bell. That is why the first count is read on `applicationTaskExecutor`. `SecurityConfig` permits `DispatcherType.ASYNC` so a stream's end does not log a failed 401; keep it.
- **Notification emails go through the outbox, never `EmailService`.** `NotificationEmails` renders and `EmailOutboxService.enqueue` queues inside the action; `EmailOutboxWorker` sends every 15 s outside any transaction and retries (1 min … 6 h, then `FAILED`). Verification, reset and invitation emails stay on `EmailService`, sent inline (their 503 behaviour is part of R01/R02).
- **Never put a full comment, a token or a link with a token into a notification or its email.** Text comes from `NotificationMessages`; excerpts are at most 200 characters.
- **A new `NotificationType` or `NotificationCategory` needs a migration** changing `ck_notifications_type` or `ck_notification_preferences_category`, plus its row in the doc's §5/§6 and a subject in `NotificationEmails.subjectLine` (the `switch` has no default, so the compiler asks for it). Defaults and the locked flag live on `NotificationCategory`; only switches that differ from the default are stored.
- Retention is 90 days (`app.notifications.retention-days`), cleaned nightly at 03:50 and 03:55.

## Error response contract

One JSON shape (`docs/api-conventions.md` §4), produced from three places that must stay in sync: `exceptionHandler/ExceptionHandler` for anything thrown inside MVC, `AppAuthenticationEntryPoint` for 401, `AppAccessDeniedHandler` for 403. `ErrorCode` is the stable machine-readable vocabulary — add to the enum rather than inventing strings.

The advice class is literally named `ExceptionHandler`, which shadows the annotation, so every handler method uses the fully-qualified `@org.springframework.web.bind.annotation.ExceptionHandler`. Keep that; do not rename the class to work around it.

Bad credentials and unknown email return an **identical** body on purpose — the response must never reveal whether an address is registered. Same principle in `docs/api-conventions.md` §5: a project that does not exist and one the user cannot see both return 404.

Services should let their exceptions propagate; the advice maps them.

## Conventions in this codebase

- Packages are grouped by feature, then by kind: `user/{models,repositories}`, `workspace/{models,repositories,services,controllers,dto,exceptions}`, `team/{models,repositories,services,controllers,dto}`, `project/{models,repositories,services,controllers,dto,exceptions,strategy}`, `task/{models,repositories,services,controllers,dto,api}`, `search/{controllers,dto,services,repositories}`, `audit/{models,repositories,services,controllers,dto}` (plus `BaseEntity`/`AuditorProvider` at its root), `notification/{events,models,repositories,services,controllers,dto,email}`, `comment/{models,repositories,services,controllers,dto}`, `mention/{services,controllers,dto,exceptions}`, `chat/{models,repositories,services,controllers,dto,events,websocket}`, `attachment/{models,repositories,services,controllers,dto,exceptions,storage}`, `common/` for cross-feature helpers (`PageResponse`, `Pageables`, `richtext/`), `security/auth/{models,repositories,services}`, `security/{config,handlers,jwt,oauth2,userdetails,exceptions}`. Put a new class in the matching subfolder rather than at the package root.
- **Edit the existing class rather than adding a parallel one.** Do not create `GlobalExceptionHandler` next to `ExceptionHandler`, or a `*V2`.
- Entities extend `audit/BaseEntity` for the four audit columns. `AuditorProvider` stamps `system` for anonymous and unauthenticated writes. Event-like rows with their own `Instant` timestamp do not (`AuditLogEntry`, `TaskHistoryEntry`, `Notification`, `TaskWatcher`, `EmailOutboxMessage`).
- `@Value` on a **`final`** field combined with `@RequiredArgsConstructor` silently produces a constructor with no parameter annotation and fails at startup. Use a non-final field or write the constructor explicitly.
- **Jackson 3**: `tools.jackson.databind.ObjectMapper`, not `com.fasterxml.jackson.databind`. Annotations are still `com.fasterxml.jackson.annotation`. `JacksonException` is unchecked, so no `throws` clause is needed. `jjwt-jackson` drags in Jackson 2 as well; the two coexist in different packages.
- **No primitive fields (`boolean`, `int`, …) in request DTOs.** Jackson 3 rejects a missing or `null` value for a primitive with 400 "Malformed request body". Use the wrapper type (`Boolean`) and default it in the record's compact constructor, as `UpdateTaskRequest` and `LoginRequest` do.
- **Users have no role** (decided 23 Oct 2026). Roles are per workspace, on `WorkspaceMember`: the creator of a workspace is its `WORKSPACE_ADMIN`, invitees join as `WORKSPACE_MEMBER`. Logins carry no authorities, so `hasRole(...)` / `@PreAuthorize` role checks never match — check roles in the service through `WorkspaceAccess` and, inside a project, permissions through `ProjectAccess`.
- Emails are stored trimmed and lower-cased with **`toLowerCase(Locale.ROOT)`** — a Turkish default locale turns `I` into a dotless `ı`. Every place that normalizes an email does it this way; keep it so.
- `Role` is `{WORKSPACE_MEMBER, WORKSPACE_ADMIN}` and is used only by `WorkspaceMember`; project roles are the separate `ProjectRole`. `Provider` is `{LOCAL, GOOGLE}`.
- The 8-character password minimum has **no backstop in the service layer** — it belongs on the request DTOs as `@Size(min = 8)`.