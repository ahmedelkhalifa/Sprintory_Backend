# Project board initialization (R06)

This implementation adds **initial board creation** to `POST /api/v1/workspaces/{workspaceId}/projects` without changing its request/response shape. `template` remains required and immutable. The creator continues to become `PROJECT_MANAGER` and the workspace must be active.

## Strategy pattern

`ProjectBoardTemplateStrategy` defines the template-specific workflow columns and supported features. `ScrumBoardStrategy` and `KanbanBoardStrategy` implement it. `ProjectBoardStrategies` resolves exactly one strategy for each `ProjectTemplate` and fails fast on duplicates/missing strategies. `ProjectBoardService.initialize` uses the strategy inside `ProjectService.create`'s transaction. If initializing the board fails, project and creator-membership inserts roll back too.

| Template | Ordered default columns | Backlog | Sprints | WIP limits |
|---|---|---|---|---|
| `SCRUM` | To Do, In Progress, Done | enabled | enabled | disabled |
| `KANBAN` | To Do, In Progress, In Review, Done | disabled | disabled | enabled, unset by default |

Both start with **zero tasks, no created/active sprints, and null WIP limits**. Feature flags come from the template strategy rather than mutable database columns; the template is immutable.

## Persistence and migration

Flyway `V4__project_boards.sql` creates `project_boards` (one per project) and `project_board_columns` (stable IDs, name, zero-based order, nullable positive WIP limit), cascaded on project deletion. It **backfills all existing SCRUM/KANBAN projects** with their initial boards and columns. Never edit V1–V3. The backend uses this migration on startup.

## Reading

`GET /api/v1/projects/{projectId}/board` requires `VIEW_WORK` via `ProjectAccess`, so a workspace admin who is not a project member cannot view it. Responds with `projectId`, `template`, `backlogEnabled`, `sprintsEnabled`, `wipLimitsEnabled`, `activeSprintId` (null until sprint lifecycle exists), and the ordered `columns` (`id`, `name`, `position`, `wipLimit`). This endpoint does not modify the board.

## Deliberately out of scope

Board column CRUD/reordering, task movement, sprint lifecycle/backlog items, editable project settings, and unique project keys are follow-up stories. The persisted column model is designed for those operations, but they must enforce `CONFIGURE_BOARD` and account for columns containing tasks once tasks exist. Avoid implying a Kanban backlog UI or active Scrum sprint exists before those features are built.

## Verification

Run `bash ./mvnw verify` with Docker available. The new integration test uses Testcontainers PostgreSQL and checks database persistence, project-manager membership, board isolation, order, blank WIP, and cascade deletion. Unit tests check both strategies and fail-fast registration.
