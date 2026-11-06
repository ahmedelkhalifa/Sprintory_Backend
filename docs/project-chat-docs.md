# PMP-137 — Project chat backend

**Status:** backend implemented and tested, including a real WebSocket against PostgreSQL (Damir, merged 6 Nov 2026). Mention notifications, the sender's error queue and the delete-check fix were added in the refactoring branch `fix/refactoring` (6 Nov 2026). Frontend (PMP-139) open.

## Status and dependencies

Project chat is one persistent channel per project. Backend: Spring Boot 4.x, PostgreSQL, native WebSocket + STOMP. This feature belongs to **PMP-137**, and its frontend to **PMP-139**. Comments and task-description mentions belong to **PMP-136**; they share the canonical rich-text contract in [`mentions-docs.md` §2](./mentions-docs.md#2-rich-text-format).

- **Migration:** `V12__project_chat.sql` (**V11** belongs to task comments and mentions). Do not rename/rewrite an already-applied production migration.
- **Shared rich text:** `common/richtext/RichTextParser`, not `ChatContentSanitizer`.
- **Mention validation/expansion:** `mention/services/MentionService.newlyMentionedUserIds(project, text, previousText)`.
- **Chat mention notifications:** `notification/events/ProjectChatMentioned`, published inside the write transaction; the notification module turns it into `MENTIONED` (see [Notifications](#notifications)).
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
| Subscribe (errors) | `/user/queue/errors` | none; once per connection, right after `CONNECT` |
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
- **Refused send, edit or delete:** the server sends one frame to the sender's own `/user/queue/errors` and to no one else. The connection stays open, and nothing is broadcast on the topic. The frame is the REST error shape ([api-conventions.md §4](./api-conventions.md#4-standard-error-responses)), with the STOMP destination as `path`:

  ```json
  { "timestamp": "2026-11-06T12:00:00Z", "status": 400, "code": "INVALID_RICH_TEXT",
    "message": "Node type 'script' is not allowed.", "path": "/app/projects/42/chat.send" }
  ```

  | `code` | `status` | When |
  | --- | --- | --- |
  | `INVALID_RICH_TEXT` | 400 | not a valid TipTap document, empty, or over 10,000 characters |
  | `INVALID_MENTION` | 400 | a newly added mention of someone outside the project, or of a team with nobody in it |
  | `BAD_REQUEST` | 400 | the frame body is not valid JSON for the action |
  | `ACCESS_DENIED` | 403 | editing someone else's message, or deleting it without `MODERATE_COMMENTS` |
  | `RESOURCE_NOT_FOUND` | 404 | the message does not exist in this project, or editing a deleted message |
  | `WORKSPACE_ARCHIVED` | 409 | the workspace was archived after the connection opened |
  | `INTERNAL_ERROR` | 500 | anything unexpected (no details) |

  Show the message next to the composer and keep the draft. Branch on `code`, not on the message text. A send gives no success frame of its own: the sender sees their message when it arrives on the topic.
- **Refused frames** (`CONNECT` without a valid chat token, `SUBSCRIBE` to a project you are not in, `SEND` without `COMMENT`, an unknown destination) are stopped before they reach the chat and answered with a STOMP `ERROR` frame, which closes the connection. Fetch a new socket token and reconnect.

## REST history

`GET /api/v1/projects/{projectId}/chat/messages?limit=50&before={oldestReceivedMessageId}`

Requires the existing HttpOnly cookie. `limit` 1..100, default 50. `before` is an exclusive positive message ID. Response is `{ "messages": [...], "nextBefore": 123, "hasMore": true }`. Messages in each page are **oldest first** and include deleted tombstones. `nextBefore` is null when no older page exists. Message objects have the same shape as STOMP events, including `mentions`.

## Authorization and editing

- Subscribe/history: **current project members** only, Viewers included.
- Send: active-project member with `COMMENT` permission, Viewer denied.
- Edit: **author only**, with `COMMENT`, and only if the message exists and is not deleted.
- Delete: author with `COMMENT`, **or** member holding `MODERATE_COMMENTS`; no one can delete a message without active project access. Repeated deletes by the author or a moderator are idempotent; anyone else still gets `403`.
- Edit of an already deleted message: `404 RESOURCE_NOT_FOUND` (matching comments, not 400).
- STOMP destinations are allowlisted: the project topic and the session's own `/user/queue/errors`. Outbound topic frames recheck the subscriber's current membership **on every delivery**, so revoked members stop receiving further project events immediately.
- Archived workspaces/projects remain readable when backend access permits but cannot be written to.

## Rich-text and mention handling

Chat uses the **same** `RichTextParser` as comments and task descriptions. The canonical full list of supported nodes, marks, attribute behavior, and errors is [`mentions-docs.md` §2](./mentions-docs.md#2-rich-text-format). Key points:

- Supported nodes: `doc`, `paragraph`, `heading` (level 1–3), `bulletList`, `orderedList` (`start`), `listItem`, `blockquote`, `codeBlock` (`language`), `horizontalRule`, `text`, `hardBreak`, `mention`.
- Marks: `bold`, `italic`, `underline`, `strike`, `code`, `link` (`href` must use `http:`, `https:` or `mailto:`). Default editor extras such as `class`, `target`, `rel` and unknown attributes are **dropped**, not rejected. Unknown nodes/marks, unsafe URLs and invalid document structure are **rejected** with `INVALID_RICH_TEXT`.
- Mentions: `{"type":"mention","attrs":{"kind":"user","id":"31","label":"Damir"}}` or `kind:"team"` with the team's ID. Missing `kind` defaults to `user`; the ID is stable and the label is display fallback only.
- Newly added mentions are checked with `MentionService`, returning **`INVALID_MENTION`** for an outside-project user or a team with no members in this project. `@team` expands to **members of that team who are also current project members**. An old retained mention is allowed when editing after its user has left; compare the previous text before applying validation.
- On edit, only **newly added** user/team references generate `ProjectChatMentioned`. An unchanged body, a removed mention or editing unrelated text does **not** trigger another mention event. The event carries deduplicated IDs; the notification listener excludes the author.
- A message must not be blank and can contain at most **10,000 plain-text characters**. The shared parser imposes a depth limit of 20 and 50 distinct mentions. Chat does not maintain its old separate 400-node/15-depth rejection rules.
- HTML is never stored; literal `<script>` is harmless text. **Frontend must render TipTap JSON structurally and never use `dangerouslySetInnerHTML`.**

## Notifications

Implemented on 6 Nov 2026. **Decision: a separate project-level event, not an optional task on `Mentioned`.** A chat has no task, and a nullable task would weaken the existing task notification contract. `ChatService` publishes, inside the send or edit transaction:

```java
new ProjectChatMentioned(Project project, User actor, Long messageId,
                         Set<Long> userIds, String plainText)   // notification/events
```

`NotificationListener` turns it into a `MENTIONED` notification for each newly mentioned project member ([notifications-docs.md §5](./notifications-docs.md#5-event-catalogue)):

- The text is *Ada Lovelace mentioned you in the "Mobile App" chat*, with the usual excerpt of at most 200 characters; the email subject is `[Sprintory] You were mentioned in the Mobile App chat`.
- The notification is about the **project** (`target.kind = PROJECT`), so the bell and the email link open the project. There is no link to a single message.
- The author and anyone who is not a project member are dropped, and the `MENTION` preference applies to the bell and the email as for comments.
- It is written in the action's transaction, so a failed send leaves no notification.

## Tests and local verification

Run the full suite from the backend repository root, with Docker running (Testcontainers starts a disposable PostgreSQL):

```bash
./mvnw -B clean verify
```

`ChatStompIntegrationTest` uses two actual STOMP clients and PostgreSQL; verifies immediate delivery and removal revocation. `ChatServiceTest` verifies the shared parser and normalization, author-only edits, permission-based deletes, deleted-message 404, mention event emission and edit diff behavior. Chat mention notifications are covered by `NotificationListenerTest`, `NotificationEmailsTest` and `MentionIntegrationTest` (PostgreSQL).

**Spring Boot 4 executor setting:** `spring.task.execution.mode=force` in `application.properties` keeps `applicationTaskExecutor` available to the notification SSE stream after WebSocket adds channel executors. Without it, application startup fails.
