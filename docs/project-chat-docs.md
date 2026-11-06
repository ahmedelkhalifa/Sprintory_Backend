# PMP-137 — Project chat backend (review update, 6 November 2026)

## Status and dependencies

Project chat is one persistent channel per project. Backend: Spring Boot 4.x, PostgreSQL, native WebSocket + STOMP. This feature belongs to **PMP-137**, and its frontend to **PMP-139**. Comments and task-description mentions belong to **PMP-136**; they share the canonical rich-text contract in [`mentions-docs.md` §2](./mentions-docs.md#2-rich-text-format).

- **Migration:** `V12__project_chat.sql` (**V11** belongs to task comments and mentions). Do not rename/rewrite an already-applied production migration.
- **Shared rich text:** `common/richtext/RichTextParser`, not `ChatContentSanitizer`.
- **Mention validation/expansion:** `mention/services/MentionService.newlyMentionedUserIds(project, text, previousText)`.
- **Project-chat mention event:** `chat/events/ProjectChatMentioned` (project, actor, messageId, userIds, plainText). Chat publishes this inside the write transaction. **The notification listener is not implemented by PMP-137:** Ahmed owns consumer wiring, preference channels, email, and recipient deduplication. A chat mention is *not yet guaranteed to send a bell/email notification* until that separate listener is merged.
- **Moderation:** author may edit/delete own messages with `COMMENT`; `MODERATE_COMMENTS` lets a PM **delete** any member's message, **not edit** it. Never authorize by hard-coded role names.

## Authentication

The normal HTTP access JWT is in an HttpOnly cookie, inaccessible to browser JavaScript. An authenticated client first calls:

```http
POST /api/v1/chat/socket-token
Cookie: (existing Sprintory session cookies)
```

Response: `{ "token": "<short-lived JWT>", "expiresInSeconds": 120 }`. This purpose-limited token is only accepted on STOMP `CONNECT` and is not a REST access token. Keep it in memory; fetch a fresh one for **every reconnect**.

Native WebSocket endpoint: `ws://localhost:8080/ws/chat`, or `wss://.../ws/chat` under HTTPS. STOMP `CONNECT` header: `Authorization: Bearer <token>`. The origin must match backend `app.frontend-url`.

## STOMP contract

**Requests still use a TipTap document as a JSON object** in `content` (even though the shared parser's `parse` method accepts a JSON string internally). This preserves the previously published chat STOMP contract.

| Action | Destination | Frame payload |
| --- | --- | --- |
| Subscribe | `/topic/projects/{projectId}/chat` | none |
| Send | `/app/projects/{projectId}/chat.send` | `{ "content": { "type":"doc", "content":[...] } }` |
| Edit | `/app/projects/{projectId}/chat.edit` | `{ "messageId": 123, "content": { "type":"doc", "content":[...] } }` |
| Delete | `/app/projects/{projectId}/chat.delete` | `{ "messageId": 123 }` |

After the database transaction commits, a `ChatChanged` frame is sent on the project topic:

```json
{
  "type": "CREATED",
  "projectId": 42,
  "message": {
    "id": 123,
    "projectId": 42,
    "authorId": 7,
    "authorName": "Jane Doe",
    "content": {"type":"doc","content":[{"type":"paragraph","content":[
      {"type":"text","text":"Hello "},
      {"type":"mention","attrs":{"kind":"team","id":"5","label":"Backend"}}
    ]}]},
    "createdAt": "2026-11-06T12:00:00Z",
    "editedAt": null,
    "deleted": false,
    "mentions": [{"kind":"team","id":5,"name":"Backend"}]
  }
}
```

- Types: `CREATED`, `EDITED`, `DELETED`.
- Deleted message: `content: null`, `deleted: true`, `mentions: []`; show **“Message deleted”**.
- `editedAt != null` indicates **“(edited)”** on a non-deleted message.
- `mentions` is an **additive response field** containing the **current names** of mentioned users/teams, matching the comments contract. Render these names in preference to the possibly stale `attrs.label` fallback. Deleted or missing accounts can have `name: null`.
- On STOMP errors, the client should handle a broker `ERROR` / disconnection. Rich text violations are `INVALID_RICH_TEXT`, invalid new mentions `INVALID_MENTION` in shared application error handling; do not rely on message text.

## REST history

`GET /api/v1/projects/{projectId}/chat/messages?limit=50&before={oldestReceivedMessageId}`

Requires the existing HttpOnly cookie. `limit` 1..100, default 50. `before` is an exclusive positive message ID. Response is `{ "messages": [...], "nextBefore": 123, "hasMore": true }`. Messages in each page are **oldest first** and include deleted tombstones. `nextBefore` is null when no older page exists. Message objects have the same shape as STOMP events, including `mentions`.

## Authorization and editing

- Subscribe/history: **current project members** only, Viewers included.
- Send: active-project member with `COMMENT` permission, Viewer denied.
- Edit: **author only**, with `COMMENT`, and only if the message exists and is not deleted.
- Delete: author with `COMMENT`, **or** member holding `MODERATE_COMMENTS`; no one can delete a message without active project access. Repeated deletes are idempotent.
- Edit of an already deleted message: `404 RESOURCE_NOT_FOUND` (matching comments, not 400).
- STOMP destinations are allowlisted. Outbound topic frames recheck the subscriber's current membership **on every delivery**, so revoked members stop receiving further project events immediately.
- Archived workspaces/projects remain readable when backend access permits but cannot be written to.

## Rich-text and mention handling

Chat uses the **same** `RichTextParser` as comments and task descriptions. The canonical full list of supported nodes, marks, attribute behavior, and errors is [`mentions-docs.md` §2](./mentions-docs.md#2-rich-text-format). Key points:

- Supported nodes: `doc`, `paragraph`, `heading` (level 1–3), `bulletList`, `orderedList` (`start`), `listItem`, `blockquote`, `codeBlock` (`language`), `horizontalRule`, `text`, `hardBreak`, `mention`.
- Marks: `bold`, `italic`, `underline`, `strike`, `code`, `link` (`href` must use `http:`, `https:` or `mailto:`). Default editor extras such as `class`, `target`, `rel` and unknown attributes are **dropped**, not rejected. Unknown nodes/marks, unsafe URLs and invalid document structure are **rejected** with `INVALID_RICH_TEXT`.
- Mentions: `{"type":"mention","attrs":{"kind":"user","id":"31","label":"Damir"}}` or `kind:"team"` with the team's ID. Missing `kind` defaults to `user`; the ID is stable and the label is display fallback only.
- Newly added mentions are checked with `MentionService`, returning **`INVALID_MENTION`** for an outside-project user or a team with no members in this project. `@team` expands to **members of that team who are also current project members**. An old retained mention is allowed when editing after its user has left; compare the previous text before applying validation.
- On edit, only **newly added** user/team references generate `ProjectChatMentioned`. An unchanged body, a removed mention or editing unrelated text does **not** trigger another mention event. The event carries deduplicated IDs; the notification listener must exclude the author.
- A message must not be blank and can contain at most **10,000 plain-text characters**. The shared parser imposes a depth limit of 20 and 50 distinct mentions. Chat does not maintain its old separate 400-node/15-depth rejection rules.
- HTML is never stored; literal `<script>` is harmless text. **Frontend must render TipTap JSON structurally and never use `dangerouslySetInnerHTML`.**

## Notifications handoff (Ahmed)

**Decision: a separate project-level event, not optional task on `Mentioned`.** A chat has no task, and a nullable task would weaken the existing task notification contract. The producer emits:

```java
new ProjectChatMentioned(Project project, User actor, Long messageId,
                        Set<Long> userIds, String plainText)
```

Ahmed should add a listener for this record, using the same `MENTIONED` preference/channel logic, `NotificationSubject.project(...)`, safe project-level linking, no full chat text in email, and removing the actor from recipients. Chat itself does not send email or create bell records. The `ProjectChatMentioned` event class can live in `chat.events`; the notification module imports it. Keep event delivery in the write transaction so notifications/outbox records roll back with the action. A notification must not disclose project content to former members.

## Tests and local verification

After merging the latest `origin/main` (which contains `RichTextParser`, `MentionService` and the V11 comments migration), apply the PMP-137 review patch. Run from the backend repository root:

```bash
# Docker must be available for Testcontainers' disposable PostgreSQL
bash ./mvnw clean verify > "$HOME/Downloads/sprintory_chat_review_tests.log" 2>&1
rg 'Tests run: [0-9]+, Failures:|BUILD (SUCCESS|FAILURE)' "$HOME/Downloads/sprintory_chat_review_tests.log" | tail -10
```

`ChatStompIntegrationTest` uses two actual STOMP clients and PostgreSQL; verifies immediate delivery and removal revocation. `ChatServiceTest` verifies the shared parser and normalization, author-only edits, permission-based deletes, deleted-message 404, mention event emission and edit diff behavior. The backend notification listener **still needs integration and its own tests for project chat mention delivery**.

**Spring Boot 4 executor setting:** `spring.task.execution.mode=force` in `application.properties` keeps `applicationTaskExecutor` available to the notification SSE stream after WebSocket adds channel executors. Without it, application startup fails.
