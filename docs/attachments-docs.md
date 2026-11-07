# Sprintory — Task attachments API contract (R25, PMP-30)

**Status:** contract agreed, backend not built yet (PMP-141, with the storage and validation of PMP-33, branch `features/attachments`, 7 Nov 2026). Frontend (PMP-142) and acceptance testing (PMP-143) can start against this file. See [§9 Implementation status](#9-implementation-status).
**Applies to:** backend `/api/v1/projects/{projectId}/tasks/{taskId}/attachments` and `/api/v1/attachments/{attachmentId}/**`.
**Related:** [Tasks](./tasks-docs.md) (subtasks, delete and restore), [Task history](./task-history-docs.md) ("attachment added / removed"), [Comments](./comments-docs.md) (same moderation rule), [Roles and permissions](./roles-docs.md), [Workspaces](./workspaces-docs.md) (archiving), [API and Git Conventions](./api-conventions.md) (error shape §4, private projects §5), [Deployment](./deployment.md) (the storage volume), [Requirements](./requirements.md) R25, R38.

This document is the single reference for task attachments. The backend implements it, the frontend (PMP-142, Mobeen) extends the task full view against it, and acceptance testing (PMP-143, Amir) checks it. If the code and this file disagree, fix one of them in the same PR.

---

## 1. Overview

The story: *"As a team member, I want to attach files to tasks, so that related documents are kept with the work."*

- **An attachment belongs to one task.** It has a file name, a type, a size, an uploader and a time.
- **Allowed types** (an allow-list, §3.1): PDF; Word, Excel and PowerPoint (`docx`, `xlsx`, `pptx`); text (`txt`, `md`, `csv`); images (`png`, `jpg`/`jpeg`, `gif`, `webp`); `zip`. Everything else is refused, in particular executables (`exe`, `sh`, `bat`, ...) and **HTML and SVG**, which can carry scripts.
- **The real type is read from the file's content**, not from its extension or the browser's `Content-Type`. An `.exe` renamed to `.pdf` is refused. The extension must also match the content: a real PDF named `report.docx` is refused.
- **Limits:** at most **20 MB per file** and **5 files per upload**.
- **Images are compressed.** PNG, JPEG and WebP images are re-encoded as WebP, scaled down to at most 2560 px on the long side, and the file name ends in `.webp` afterwards (`diagram.png` → `diagram.webp`). If the WebP would not be smaller, the original is kept. GIFs are never converted (that would lose animation). See §3.2.
- **Who uploads:** every project member except Viewers. **Who downloads:** every project member, Viewers included.
- **Who deletes:** the uploader, or the Project Manager (who can delete anyone's attachment).
- **Soft delete.** A deleted attachment disappears from the task and can no longer be downloaded. The task's history records "attachment removed" ([task-history-docs.md](./task-history-docs.md)).
- **Downloads only through the backend**, with a permission check on every request. There are no public or shareable links.
- **Projects are private** ([API conventions §5](./api-conventions.md#5-private-projects-and-access-control)). Someone who cannot see the project gets `404`, also when they guess an attachment id.
- **Archived workspaces are read-only.** Attachments can still be listed and downloaded; uploading and deleting get `409 WORKSPACE_ARCHIVED`.
- **A deleted task hides its attachments.** They cannot be listed or downloaded (`404`). Restoring the task (PMP-131) brings them back unchanged. Deleting a parent also hides its subtasks' attachments until it is restored.
- **Subtasks have attachments too.** Use the subtask's id as `{taskId}`, with the same rules. A subtask's attachments are its own; they do not appear on the parent.

### Who can do what

| Action | Not in the workspace, or not in the project | Workspace admin, not in the project | Viewer | Member/Developer, Scrum Master, Product Owner | Project Manager |
| --- | --- | --- | --- | --- | --- |
| List and download | 404 | 403 | allowed | allowed | allowed |
| Upload | 404 | 403 | 403 | allowed | allowed |
| Delete **own** attachment | 404 | 403 | 403 | allowed | allowed |
| Delete someone else's attachment | 404 | 403 | 403 | 403 | allowed |

- Listing and downloading need `VIEW_WORK`. Uploading and deleting one's own attachment need `EDIT_TASKS`. Deleting anyone's attachment needs `MODERATE_COMMENTS`, which only the Project Manager holds ([roles-docs.md §2](./roles-docs.md#2-permission-matrix)).
- Someone who became a Viewer after uploading can no longer delete their old attachments: they lost `EDIT_TASKS`.
- Someone who left the project keeps their attachments on the task, under their name. Only the Project Manager can delete them then.

---

## 2. Endpoints at a glance

Bodies are JSON in `camelCase`, except the upload, which is `multipart/form-data`.

| # | Method | Path | Request | Success | Permission |
| --- | --- | --- | --- | --- | --- |
| 3.1 | POST | `/api/v1/projects/{projectId}/tasks/{taskId}/attachments` | multipart, part `files` (1–5) | `201` + `AttachmentResponse[]` | `EDIT_TASKS` |
| 3.3 | GET | `/api/v1/projects/{projectId}/tasks/{taskId}/attachments` | none | `200` + `AttachmentResponse[]` | `VIEW_WORK` |
| 3.4 | GET | `/api/v1/attachments/{attachmentId}` | none | `200` + the file, as a download | `VIEW_WORK` |
| 3.5 | GET | `/api/v1/attachments/{attachmentId}/preview` | none | `200` + the image, shown inline | `VIEW_WORK`, images only |
| 3.6 | DELETE | `/api/v1/attachments/{attachmentId}` | none | `204` | `EDIT_TASKS` and uploader, or `MODERATE_COMMENTS` |

`{taskId}` is looked up inside `{projectId}`: an id from another project gives the same `404` as a missing one. The `/api/v1/attachments/{attachmentId}` paths find the project through the attachment's task, and check access there.

### Bodies

```jsonc
// AttachmentResponse
{
  "id": 91,
  "taskId": 230,
  "fileName": "login-flow.webp",                 // display only, never a path; was "login-flow.png" when uploaded
  "contentType": "image/webp",                   // the type detected from the content, as stored
  "sizeBytes": 184332,                           // the stored size, after compression
  "image": true,                                 // png, jpeg, gif or webp: a preview is available
  "uploader": { "id": 7, "name": "Ada Lovelace" },   // null if the user account no longer exists
  "uploadedAt": "2026-11-07T14:02:51.320Z",
  "downloadUrl": "/api/v1/attachments/91",
  "previewUrl": "/api/v1/attachments/91/preview",    // null unless "image" is true
  "canDelete": true                              // for the caller: own attachment with EDIT_TASKS, or MODERATE_COMMENTS
}

// A PDF
{ "id": 92, "taskId": 230, "fileName": "Requirements v2.pdf", "contentType": "application/pdf",
  "sizeBytes": 1048576, "image": false, "uploader": { "id": 9, "name": "Linus Torvalds" },
  "uploadedAt": "2026-11-07T14:05:10Z", "downloadUrl": "/api/v1/attachments/92",
  "previewUrl": null, "canDelete": false }
```

- `downloadUrl` and `previewUrl` are **paths**; put the API base URL in front of them (`https://api.<domain>` in production, `http://localhost:8080` locally).
- **`fileName` is user input.** Render it as text, never as HTML.
- `canDelete` shows or hides the delete button. The backend checks again on every request.

---

## 3. Endpoints in detail

### 3.1 Upload files to a task

`POST /api/v1/projects/{projectId}/tasks/{taskId}/attachments`

- **Who:** members with `EDIT_TASKS` (everyone but Viewers). The workspace must be active.
- **Request:** `multipart/form-data` with **one to five parts named `files`**, one per file. Nothing else.

  ```js
  const form = new FormData();
  for (const file of selectedFiles) form.append("files", file);
  await fetch(`${API}/api/v1/projects/${projectId}/tasks/${taskId}/attachments`, {
    method: "POST",
    body: form,
    credentials: "include",   // sends the auth cookies
  });                         // do NOT set Content-Type yourself: the browser adds the multipart boundary
  ```

- **Rules:**
  - Every file at most **20 MB** and not empty; at most **5 files** per request.
  - The type must be on the allow-list, and the extension must match the content:

    | Type | Extensions | Recognized by |
    | --- | --- | --- |
    | PDF | `.pdf` | starts with `%PDF-` |
    | Word / Excel / PowerPoint | `.docx` / `.xlsx` / `.pptx` | a zip holding the Office parts (`word/`, `xl/`, `ppt/`); files with macros are refused |
    | Zip | `.zip` | starts with `PK` (an Office file named `.zip` is accepted as a plain zip) |
    | PNG | `.png` | the PNG signature |
    | JPEG | `.jpg`, `.jpeg` | starts with `FF D8 FF` |
    | GIF | `.gif` | starts with `GIF87a` / `GIF89a` |
    | WebP | `.webp` | `RIFF....WEBP` |
    | Text | `.txt`, `.md`, `.csv` | no binary content (no NUL or control bytes besides tab and line breaks); any 8-bit encoding (UTF-8, Windows-1254, ...); refused if it starts like HTML, SVG, XML or a script |

  - Extensions are compared ignoring case (`.PDF` is fine). Old Office formats (`.doc`, `.xls`, `.ppt`) are not allowed.
  - **All or nothing:** if one file is refused, none is saved, and the error names that file.
  - The **file name** is kept for display only: everything up to the last `/` or `\` is dropped (`../../x.pdf` becomes `x.pdf`), control characters are removed, and it is cut to 255 characters. On disk the file is stored under a random id, never under its name.
- **Side effects, in the same transaction:** one `ATTACHMENT_ADDED` history entry per file ([task-history-docs.md §3.1](./task-history-docs.md#31-types)). Nobody is notified.
- **Response `201`:** the new attachments, as `AttachmentResponse[]`, in the order they were sent.
- **Errors:**

  | Status | Code | When |
  | --- | --- | --- |
  | 400 | `BAD_REQUEST` | no `files` part, or a file is empty |
  | 400 | `TOO_MANY_FILES` | more than 5 files |
  | 401 | `AUTHENTICATION_ERROR` | not logged in |
  | 403 | `ACCESS_DENIED` | a Viewer, or a workspace admin who is not in the project |
  | 404 | `RESOURCE_NOT_FOUND` | "Project not found." or "Task not found." (missing, deleted, or another project's task) |
  | 409 | `WORKSPACE_ARCHIVED` | the workspace is archived |
  | 413 | `FILE_TOO_LARGE` | a file over 20 MB (or the whole request over the server's limit) |
  | 415 | `FILE_TYPE_NOT_ALLOWED` | not on the allow-list, the content does not match the extension, or an Office file with macros |

### 3.2 Image compression

Done during the upload, before the file is stored:

1. Applies to **PNG, JPEG and WebP**. GIFs are stored as they are.
2. An image larger than **40 megapixels** is stored as it is, without decoding (protection against "decompression bombs").
3. A JPEG's camera rotation (EXIF orientation) is applied, so photos stay upright.
4. The image is scaled down so its long side is at most **2560 px**; smaller images keep their size.
5. It is encoded as **lossy WebP at quality 80**. Transparency in PNGs is kept.
6. **The WebP is kept only if it is smaller than the original.** Otherwise the original file is stored unchanged, with its own name and type.
7. When converted: `contentType` becomes `image/webp`, `fileName` ends in `.webp`, and `sizeBytes` is the compressed size.

Re-encoding drops the image's metadata, including GPS location from phone photos. Screenshots and diagrams with sharp text may look slightly softer; attach them as a `.zip` to keep them byte-for-byte.

### 3.3 List a task's attachments

`GET /api/v1/projects/{projectId}/tasks/{taskId}/attachments`

- **Who:** members with `VIEW_WORK` (every project role). Archived workspace: allowed.
- **Order:** newest first (`uploadedAt`, then `id`). Not paginated: the whole list is returned. Deleted attachments are **not** included.
- **Response `200`:** `AttachmentResponse[]` (empty array if there are none).
- **Errors:** `401`; `403` (a workspace admin not in the project); `404` "Project not found."; `404` "Task not found.".

### 3.4 Download an attachment

`GET /api/v1/attachments/{attachmentId}`

- **Who:** members with `VIEW_WORK` in the attachment's project. Archived workspace: allowed.
- **Response `200`:** the file's bytes, with these headers:

  | Header | Value |
  | --- | --- |
  | `Content-Type` | the stored type, e.g. `application/pdf` |
  | `Content-Length` | `sizeBytes` |
  | `Content-Disposition` | `attachment; filename="..."; filename*=UTF-8''...` (the browser saves it, never opens it in the page) |
  | `X-Content-Type-Options` | `nosniff` (the browser must not guess another type) |
  | `Content-Security-Policy` | `default-src 'none'; sandbox` |
  | `Cache-Control` | `no-store` |

- A plain link works: `<a href={API + downloadUrl}>`. The auth cookie goes with it because the frontend and the API are on the same site (`app.<domain>` / `api.<domain>`). `Content-Disposition` is exposed through CORS, so a `fetch` can read the file name too.
- **Errors:** `401`; `403` (a workspace admin not in the project); `404` "Attachment not found." when the attachment is missing or deleted, its task is deleted, or the caller cannot see the project. All four cases look the same.

### 3.5 Preview an image

`GET /api/v1/attachments/{attachmentId}/preview`

- **Who and errors:** as 3.4. An attachment that is not an image (`image: false`) gives `404` "Attachment not found.".
- **Response `200`:** the image, with `Content-Disposition: inline`, the same `nosniff` and CSP headers as 3.4, and `Cache-Control: private, max-age=300`.
- Use it as the `src` of an `<img>` for thumbnails and for the full-size preview. There is no separate small thumbnail file; the image is already compressed (3.2), so size it with CSS (`object-fit: cover`).

### 3.6 Delete an attachment

`DELETE /api/v1/attachments/{attachmentId}`

- **Who:** the uploader with `EDIT_TASKS`, or anyone with `MODERATE_COMMENTS` (the Project Manager). The workspace must be active.
- **Soft delete:** the attachment disappears from the list (3.3) and cannot be downloaded or previewed any more. There is no restore.
- **History:** one `ATTACHMENT_REMOVED` entry on the task, with the file name and who deleted it ([task-history-docs.md §3.1](./task-history-docs.md#31-types)).
- **Response:** `204`, no body. Deleting it again gives `404` "Attachment not found.".
- **Errors:** `401`; `403` (not the uploader and not the Project Manager, a Viewer, or a workspace admin not in the project); `404` as in 3.4; `409 WORKSPACE_ARCHIVED`.

---

## 4. Errors

Every error uses the shared shape from [API conventions §4](./api-conventions.md#4-standard-error-responses). Branch on `code`, not on `message`. Messages that are about one file start with its (cleaned) name; render them as text.

| Code | Status | Message | When |
| --- | --- | --- | --- |
| `BAD_REQUEST` | 400 | "Choose at least one file to upload." / "x.pdf is empty." | No `files` part; an empty file |
| `TOO_MANY_FILES` | 400 | "You can upload at most 5 files at once." | More than 5 files in one request |
| `AUTHENTICATION_ERROR` | 401 | Authentication required. Please log in. | No valid access cookie |
| `ACCESS_DENIED` | 403 | You do not have access to this resource. | A Viewer uploading or deleting; deleting someone else's attachment without being the Project Manager; a workspace admin not in the project |
| `RESOURCE_NOT_FOUND` | 404 | Project not found. / Task not found. / Attachment not found. | Not visible, missing, deleted, or in another project or task |
| `WORKSPACE_ARCHIVED` | 409 | This workspace is archived. Restore it to make changes. | Uploading or deleting in an archived workspace |
| `FILE_TOO_LARGE` | 413 | "File too large (max 20 MB)." | A file over 20 MB |
| `FILE_TYPE_NOT_ALLOWED` | 415 | "setup.exe: this file type is not allowed." / "report.pdf: the file's content does not match its extension." / "budget.docx: files with macros are not allowed." | §3.1 type rules |

---

## 5. Frontend flows

### 5.1 Attachments section of the task full view (PMP-142)

1. Load `GET …/tasks/{taskId}/attachments` with the task. Show each attachment with `fileName`, a human-readable `sizeBytes` (e.g. "1.2 MB"), `uploader.name` (or "Deleted user" when `null`) and `uploadedAt`.
2. **Images** (`image: true`): show a thumbnail, `<img src={API + previewUrl} alt={fileName}>`, sized with CSS. Clicking it opens a larger preview (a modal or lightbox with the same URL) with a download button.
3. **Other files:** an icon by type (PDF, Word, Excel, PowerPoint, text, zip) and the file name as a download link to `API + downloadUrl`.
4. Show the **upload control** only when the project's `permissions` contain `EDIT_TASKS` ([roles-docs.md §7](./roles-docs.md#7-frontend-notes)). Viewers see the list and can download, without upload or delete buttons.
5. Show **Delete** when `canDelete`. Ask for confirmation, call 3.6, then remove the item.
6. Hide the upload and delete buttons in an archived workspace, as for comments.

### 5.2 Uploading

1. Let the user pick or drop up to 5 files. **Check in the browser first**, to save a round trip: at most 5 files, each at most 20 MB, the extension in §3.1's list (`accept=".pdf,.docx,.xlsx,.pptx,.txt,.md,.csv,.png,.jpg,.jpeg,.gif,.webp,.zip"`). The backend checks again and is the one that decides.
2. Send them in one request (3.1). Show progress if you like (`XMLHttpRequest`/axios `onUploadProgress`).
3. On `201`, add the returned items to the top of the list. **Use the returned names and sizes**: images may now end in `.webp` and be smaller than what was picked.
4. On `413`, `415`, `400`: show the `message`. Nothing was saved, so the user can remove the bad file and send the rest again.

### 5.3 Expired login on images

The access cookie lives 15 minutes. A plain `<img>` or `<a>` cannot refresh it, so after a long idle time a thumbnail can fail with `401`. On the image's `error` event, call the normal refresh (`POST /api/v1/auth/refresh`) once and set the `src` again (e.g. with `?v=` + a counter).

### 5.4 History lines

The task history shows `ATTACHMENT_ADDED` as "**Ada** attached *login-flow.webp*" and `ATTACHMENT_REMOVED` as "**Ada** removed the attachment *login-flow.webp*". Do not turn them into links: the attachment may be gone.

---

## 6. Data model

| Table | Columns | Constraints |
| --- | --- | --- |
| `task_attachments` | `id`, `task_id`, `uploader_id` (nullable), `file_name` (display name, ≤ 255), `file_type` (the detected `AttachmentType`, e.g. `PDF`; `contentType` in the response is derived from it), `size_bytes` (stored size), `original_size_bytes` (size as uploaded), `storage_key` (random UUID), `deleted_at` (nullable), `deleted_by_id` (nullable), audit columns | `storage_key` unique; check constraints on `file_type` and the sizes; index on (`task_id`, `created_at`, `id`) for live rows |

- **Foreign keys:**
  - `task_id` → `tasks`, `ON DELETE CASCADE`. A soft-deleted task keeps its attachments; permanently deleting the workspace removes the rows.
  - `uploader_id` → `users`, `ON DELETE SET NULL`. The attachment survives a deleted account, and the uploader shows as `null`.
  - `deleted_by_id` has **no** foreign key, like the audit log's `actor_id`.
- **Files** live on disk under `app.attachments.dir` (env `ATTACHMENTS_DIR`), named by `storage_key` only and spread over sub-folders (`ab/cd/<uuid>`). In production that directory is a Docker volume, backed up with the database ([deployment.md](./deployment.md)).
- **Clean-up:** a nightly job deletes the files of attachments soft-deleted more than 30 days ago (`app.attachments.retention-days`), and files whose row no longer exists (left over after a workspace was deleted permanently).
- **Leaving a project does not touch attachments.** No cleanup is needed in `ProjectService.removeMember` or `WorkspaceService.removeMember`.
- **Migration:** `V14__task_attachments.sql`, which also adds `ATTACHMENT_ADDED` and `ATTACHMENT_REMOVED` to `ck_task_history_type`.
- **Code:** `attachment/{models,repositories,services,controllers,dto,exceptions,storage}`. `AttachmentService` holds the rules, `FileTypeDetector` the type checks, `ImageOptimizer` the WebP conversion, `AttachmentStorage` / `LocalAttachmentStorage` the files.

---

## 7. What the API reveals

| Endpoint | Reveals |
| --- | --- |
| Every attachment endpoint | Nothing about projects the caller cannot see: `404`, as for a missing project |
| `/api/v1/attachments/{attachmentId}` | Not whether an id exists: missing, deleted, on a deleted task, or in an invisible project all give the same `404`. A workspace admin of that workspace who is not in the project gets `403`, as on every task endpoint. |
| A deleted attachment | Only its file name, in the task's history |
| Upload errors | Only facts about the caller's own files |

---

## 8. Known limitations

- **No restoring a deleted attachment.** Upload it again.
- **No new versions of a file.** Uploading the same name again adds a second attachment.
- **No attachments in comments or chat.** Files are attached to the task.
- **No virus scanning.** Executables are refused by type, but a `.zip` can contain anything; it is only ever downloaded, never opened by the server.
- **No limit per task** besides 5 files per upload.
- **Text files in UTF-16** are refused (they contain NUL bytes). Old Office formats (`.doc`, `.xls`, `.ppt`) are not allowed.
- **No notifications** for new attachments.
- **Local disk only.** The storage sits behind an interface, so a cloud store can replace it later.

---

## 9. Implementation status

As of 7 Nov 2026, on branch `features/attachments`:

| Part | Status |
| --- | --- |
| This contract | Written, agreed with the backend owner |
| `task_attachments` table (`V14__task_attachments.sql`), `TaskAttachment`, `AttachmentType`, repository, the two history types | Done; validated against PostgreSQL by the context test |
| `FileTypeDetector` (allow-list, content check, macros) | Done; `FileTypeDetectorTest` (30) |
| `ImageOptimizer` (WebP conversion, EXIF orientation, size and pixel limits) | Done; `ImageOptimizerTest` (9) |
| `AttachmentStorage` / `LocalAttachmentStorage` (PMP-33) | Done; `LocalAttachmentStorageTest` (16) |
| Docker volume, deployment notes (PMP-33) | Not started |
| `AttachmentService`: upload and list (§3.1–§3.3), file names, rollback clean-up, `ATTACHMENT_ADDED` | Done; `AttachmentServiceTest` (16), `AttachmentFileNamesTest` (7), `TaskStatusHistoryServiceTest` |
| `AttachmentService`: download, preview, delete (§3.4–§3.6), `ATTACHMENT_REMOVED` | Not started (the history writer is done) |
| `AttachmentController`, the five endpoints, error mapping | Not started |
| Nightly clean-up job | Not started |
| Frontend (PMP-142) and acceptance test (PMP-143) | Not started |

---

## 10. Decisions

| Date | Decision |
| --- | --- |
| 7 Nov 2026 | **20 MB per file**, 5 files per upload. The ticket's acceptance criterion said 10 MB; the rule of 20 MB wins and the criterion is updated. |
| 7 Nov 2026 | Uploading and deleting one's own attachment need `EDIT_TASKS`; deleting anyone's needs `MODERATE_COMMENTS` (Project Manager), the same moderation permission as comments and chat. No new permission. |
| 7 Nov 2026 | The type is detected from the content by our own small detector (no Apache Tika), and the extension must match it. HTML, SVG, executables and Office files with macros are refused. |
| 7 Nov 2026 | PNG, JPEG and WebP images are compressed to WebP (long side ≤ 2560 px, quality 80), only kept if smaller. GIFs are not converted. |
| 7 Nov 2026 | Images get an inline `/preview` endpoint used for thumbnails; there is no separate thumbnail file. Downloads are always `Content-Disposition: attachment`. |
| 7 Nov 2026 | Files are stored on a local Docker volume under random UUIDs; the user's file name is display text only. |
| 7 Nov 2026 | Delete is soft and leaves an `ATTACHMENT_REMOVED` history entry; files of deleted attachments are removed from disk after 30 days. |
| 7 Nov 2026 | Upload is all-or-nothing: one refused file refuses the request. |