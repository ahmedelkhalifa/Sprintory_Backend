# Sprintory — Authentication API contract (R01)

**Status:** contract agreed, implementation in progress. See [§10 Implementation status](#10-implementation-status) for which parts are live.
**Applies to:** backend `/api/v1/auth/**`, Google sign-in, and every frontend call that depends on being logged in.
**Related:** [API and Git Conventions](./api-conventions.md) (error shape §4), [Email service](./email-service.md), [Requirements](./requirements.md) R01.

This document is the single reference for how authentication works end to end. If the code and this file disagree, fix one of them in the same PR.

---

## 1. Overview

- **Stateless.** The server keeps no HTTP session. Who you are comes from a cookie on every request.
- **Two tokens, both in HttpOnly cookies.** JavaScript can never read them. They are never put in JSON bodies or URLs.
- **Two ways to sign in:**
  - email and password, which requires email verification first
  - Google
- **Emailed one-time links** for email verification and password reset.
- **Every state-changing endpoint is `POST`.** CSRF protection is disabled on purpose. `SameSite=Lax` on the cookies is the protection, and it only works if state changes never happen on `GET`.

### Tokens

| | Access token | Refresh token | Link token (verify / reset) |
| --- | --- | --- | --- |
| Form | Signed JWT (HS256), `sub` = email | Random 32 bytes, URL-safe | Random 32 bytes, URL-safe |
| Lifetime | 15 minutes | ~30 days (`2629800000` ms) | 24 hours |
| Where the client holds it | `access_token` cookie | `refresh_token` cookie | `?token=` in the emailed link |
| Server storage | none | `refresh_tokens` row (SHA-256 hash only) | `one_time_tokens` row (SHA-256 hash only) |
| Single use | no | yes, rotated on every refresh | yes, deleted when used |
| Can be revoked | no, expires on its own | yes, by deleting the row | yes, a newer link replaces older ones |

### Cookies

| Cookie | Path | HttpOnly | SameSite | Secure | Max-Age |
| --- | --- | --- | --- | --- | --- |
| `access_token` | `/` | yes | `Lax` | `COOKIE_SECURE` (`true` in production) | 900 s with remember-me; none (session cookie) without it |
| `refresh_token` | `/api/v1/auth` | yes | `Lax` | `COOKIE_SECURE` | 2 629 800 s with remember-me; none without it |
| `oauth2_auth_request` | `/` | yes | `Lax` | `COOKIE_SECURE` | 180 s, used only during Google sign-in |

- The refresh cookie's path means the browser sends it **only** to `/api/v1/auth/**`, never to the rest of the API.
- Logout sends both auth cookies back empty with `Max-Age=0`.

### Frontend requirements

1. Send every API request with credentials: `fetch(..., { credentials: "include" })` or axios `withCredentials: true`. Without this the browser neither stores nor sends the cookies.
2. The frontend origin must equal `FRONTEND_URL` exactly (scheme, host and port). CORS allows only that origin, with credentials.
3. Never try to read the tokens. To know whether the user is logged in, call `GET /api/v1/auth/me`.

---

## 2. Endpoints

All paths are under `/api/v1/auth`. Request and response bodies are JSON in `camelCase`. Every endpoint here is reachable without being logged in (`permitAll`). `/me` checks for a user itself.

| Method | Path | Request | Success | Cookies set |
| --- | --- | --- | --- | --- |
| POST | `/register` | `RegisterRequest` | `201` + `AuthUserResponse` | none |
| POST | `/verify-email` | `VerifyEmailRequest` | `204` | none |
| POST | `/login` | `LoginRequest` | `200` + `AuthUserResponse` | access + refresh |
| GET | `/me` | access cookie | `200` + `AuthUserResponse` | none |
| POST | `/refresh` | refresh cookie | `200` + `AuthUserResponse` | new access + new refresh |
| POST | `/logout` | refresh cookie (optional) | `204` | both cleared |
| POST | `/forgot-password` | `ForgotPasswordRequest` | `202` + `MessageResponse` | none |
| POST | `/reset-password` | `ResetPasswordRequest` | `204` | none |

Google sign-in uses two non-JSON URLs handled by Spring Security (see [§4.6](#46-google-sign-in)):

| Method | Path | Who calls it |
| --- | --- | --- |
| GET | `/oauth2/authorization/google` | The browser, as a full-page navigation (not `fetch`) |
| GET | `/login/oauth2/code/google` | Google's redirect back; never called by the frontend |

### Request bodies

```jsonc
// RegisterRequest
{ "firstName": "Ada", "lastName": "Lovelace", "email": "ada@example.com", "password": "at-least-8-chars" }

// LoginRequest
{ "email": "ada@example.com", "password": "at-least-8-chars", "rememberMe": true }

// VerifyEmailRequest
{ "token": "<value of ?token= from the email link>" }

// ForgotPasswordRequest
{ "email": "ada@example.com" }

// ResetPasswordRequest
{ "token": "<value of ?token= from the email link>", "newPassword": "at-least-8-chars" }
```

| Field | Rules |
| --- | --- |
| `firstName`, `lastName` | required, not blank |
| `email` | required, valid email format. The server trims it and lower-cases it, so `Ada@Example.com ` and `ada@example.com` are the same account. |
| `password`, `newPassword` | required, at least 8 characters. Stored only as a BCrypt hash. |
| `rememberMe` | optional boolean, default `false`. See [§4.2](#42-login). |
| `token` | required, not blank |

### Response bodies

```jsonc
// AuthUserResponse: the only user data the API returns
{ "id": 7, "firstName": "Ada", "lastName": "Lovelace", "email": "ada@example.com" }

// MessageResponse
{ "message": "If the account exists, password reset instructions will be sent." }
```

Passwords, password hashes, roles, provider, `enabled` and tokens are never part of a response body.

---

## 3. Errors

Every error uses the shared shape from [API conventions §4](./api-conventions.md#4-standard-error-responses):

```json
{
  "timestamp": "2026-10-22T12:30:00Z",
  "status": 401,
  "code": "AUTHENTICATION_ERROR",
  "message": "Invalid email or password.",
  "path": "/api/v1/auth/login"
}
```

`fieldErrors` appears only with `VALIDATION_ERROR`, for example `{ "password": "size must be between 8 and 2147483647" }`. Each field gets one message.

**The frontend should branch on `code`, not on `message`.** Messages are safe to show, but they may be reworded.

### Error codes used by authentication

| Code | Status | Message | When |
| --- | --- | --- | --- |
| `VALIDATION_ERROR` | 400 | Request validation failed. | A request body breaks the rules in §2 |
| `BAD_REQUEST` | 400 | Malformed request body. | The body is not valid JSON, or a value has the wrong type (e.g. `"rememberMe": "yes"`) |
| `AUTHENTICATION_ERROR` | 401 | Invalid email or password. | Login with an unknown email **or** a wrong password. The response is identical in both cases on purpose. |
| `AUTHENTICATION_ERROR` | 401 | Authentication required. | `GET /me` with no valid access cookie |
| `AUTHENTICATION_ERROR` | 401 | Authentication required. Please log in. | Any other protected endpoint with no valid access cookie |
| `INVALID_TOKEN` | 401 | This token is no longer valid. Please sign in again. | Refresh cookie missing, unknown, expired or already used; verification or reset link unknown, expired, already used or for the wrong purpose |
| `ACCOUNT_NOT_ACTIVATED` | 403 | This account is not activated. Use the activation link we emailed you. | Login with the **correct** password on an account whose email is not verified yet |
| `ACCESS_DENIED` | 403 | You do not have access to this resource. | Logged in, but the role does not allow the action (not used by the auth endpoints themselves) |
| `PROVIDER_MISMATCH` | 409 | This account uses Google sign-in. Continue with Google. | Email/password login or registration for an email that belongs to a Google account |
| `CONFLICT` | 409 | An account with this email already exists. | Registration for an email that already has a verified password account |
| `SERVICE_UNAVAILABLE` | 503 | We couldn't send the email. Please try again later. | Registration could not send the verification email. Nothing was saved, so the user can simply retry. |
| `INTERNAL_ERROR` | 500 | Something went wrong. Please try again. | Unexpected server error; details go to the server log only |

The `INVALID_TOKEN` message mentions signing in because it was written for refresh tokens. On the verify-email and reset-password pages, show your own text, such as "This link has expired or was already used."

### What each endpoint can return

| Endpoint | Possible errors |
| --- | --- |
| `POST /register` | 400 `VALIDATION_ERROR`, 409 `PROVIDER_MISMATCH`, 409 `CONFLICT`, 503 `SERVICE_UNAVAILABLE` |
| `POST /verify-email` | 400 `VALIDATION_ERROR`, 401 `INVALID_TOKEN` |
| `POST /login` | 400 `VALIDATION_ERROR`, 401 `AUTHENTICATION_ERROR`, 403 `ACCOUNT_NOT_ACTIVATED`, 409 `PROVIDER_MISMATCH` |
| `GET /me` | 401 `AUTHENTICATION_ERROR` |
| `POST /refresh` | 401 `INVALID_TOKEN` |
| `POST /logout` | none; always `204` |
| `POST /forgot-password` | 400 `VALIDATION_ERROR` only; otherwise always `202`, even if the email is unknown or sending fails |
| `POST /reset-password` | 400 `VALIDATION_ERROR`, 401 `INVALID_TOKEN` |

Any endpoint with a JSON body can also return 400 `BAD_REQUEST` for a malformed body, and any endpoint can return 500 `INTERNAL_ERROR`.

### Google sign-in errors

Google sign-in is a browser redirect, so errors cannot be JSON. On failure the browser is sent to `FRONTEND_URL + OAUTH2_FAILURE_PATH` (default `/login`) with `?error=<code>`:

| `error` | Meaning |
| --- | --- |
| `USE_PASSWORD_LOGIN` | This email is registered with a password. Sign in with the password instead. |
| `EMAIL_NOT_VERIFIED` | The Google account has no verified email address. |
| `EMAIL_MISSING` | Google returned no email address. |
| `access_denied` | The user cancelled on Google's consent screen (code from Google/Spring). |
| `OAUTH2_FAILED` | Any other failure; details are in the server log. |

---

## 4. Flows

### 4.1 Registration and email verification

1. The frontend sends `POST /register`.
2. The backend:
   1. normalizes the email (trim, lower-case)
   2. if the email belongs to a **Google** account → `409 PROVIDER_MISMATCH`
   3. if the email belongs to a **verified** password account → `409 CONFLICT`
   4. if the email belongs to an **unverified** password account → updates that account's name and password and sends a fresh link. This is how a user whose link expired starts over: they register again.
   5. otherwise creates the user: provider `LOCAL`, role `WORKSPACE_MEMBER`, `enabled = false`, password hashed with BCrypt
   6. issues a `VERIFY_EMAIL` link token. Older verification links for this user stop working.
   7. emails `FRONTEND_URL + VERIFY_EMAIL_PATH + "?token=" + token`, which is `/verify-email?token=...` by default
3. If the email cannot be sent, the whole registration is rolled back and the response is `503 SERVICE_UNAVAILABLE`. No half-created account is left behind.
4. On success the response is `201` with the user. **No cookies are set and the user is not logged in.**
5. The frontend shows "Check your inbox".
6. The user clicks the link, which opens the frontend's **verify-email page**. That page reads `token` from the URL and sends `POST /verify-email { token }`.
7. The backend consumes the token (it can't be used again) and sets `enabled = true`. Response: `204`.
8. The frontend shows "Email verified" and sends the user to the login page. Verifying does **not** log the user in.

### 4.2 Login

1. The frontend sends `POST /login { email, password, rememberMe }`.
2. The backend:
   1. if the email belongs to a **Google** account → `409 PROVIDER_MISMATCH`
   2. checks the password
      - unknown email or wrong password → `401 AUTHENTICATION_ERROR` "Invalid email or password."
   3. only **after** the password matches, checks that the account is verified
      - not verified → `403 ACCOUNT_NOT_ACTIVATED`
      - because of this order, a wrong password never reveals whether an unverified account exists
   4. issues an access token and a refresh token
3. Response: `200` with the user, plus both cookies.
4. `rememberMe`:
   - `true`: both cookies are persistent (`Max-Age` set) and survive a browser restart.
   - `false`: both are session cookies (no `Max-Age`). Closing the browser logs the user out.
   - The choice is stored with the refresh token, so every later refresh keeps the same kind of cookie.

### 4.3 Being logged in, and checking it

- On app start, and after login, the frontend calls `GET /me`.
  - `200` means logged in. Use the returned user.
  - `401` means not logged in, or the access token has expired. Try a refresh (§4.4) before sending the user to the login page.
- Every other API request carries the `access_token` cookie automatically.
- The server accepts the access token only if:
  - its signature and expiry are valid, and
  - the user still exists and is enabled.

### 4.4 Refreshing the session (silent re-login)

The access token lasts only 15 minutes. The frontend keeps the user logged in like this:

1. Any API call returns `401` with `AUTHENTICATION_ERROR`.
2. The frontend calls `POST /refresh` once. The browser sends the `refresh_token` cookie automatically.
3. The backend consumes the presented refresh token (it is deleted) and issues a **new** access token and a **new** refresh token with the same remember-me setting.
4. `200`: retry the original request once.
5. `401 INVALID_TOKEN`: the session is over. Go to the login page.

Notes:
- **Single-flight.** If several requests fail at once, call `/refresh` **once** and let the others wait for it. Each refresh token works only once, so two parallel refreshes with the same cookie: one succeeds and the other gets `401`.
- Never retry `/refresh` itself in a loop.

### 4.5 Logout

1. The frontend sends `POST /logout`. The browser sends the refresh cookie automatically.
2. The backend finds whose refresh token it is and **deletes all of that user's refresh tokens on every device**. If the cookie is missing, unknown or expired, it does nothing.
3. The response is always `204`, and both cookies are cleared in this browser.
4. The frontend goes to the login page.

On **other** devices, the access token keeps working for up to 15 minutes. Their next `/refresh` fails, so they are logged out from then on. Access tokens cannot be revoked; that is the cost of being stateless.

### 4.6 Google sign-in

1. The frontend sends the browser to `GET {backend}/oauth2/authorization/google` with a link or `window.location`, **not** `fetch`.
2. The backend stores the in-flight request in the short-lived `oauth2_auth_request` cookie and redirects to Google.
3. Google redirects back to `/login/oauth2/code/google`. The backend then:
   1. requires a **verified** email from Google
   2. if the email belongs to a password account → failure `USE_PASSWORD_LOGIN`. Accounts are never linked silently.
   3. if the email is new → creates the user: provider `GOOGLE`, role `WORKSPACE_MEMBER`, already enabled, no password
   4. issues an access token and a refresh token as persistent cookies. Google sign-in has no remember-me choice.
4. Success: redirect to `FRONTEND_URL + OAUTH2_SUCCESS_PATH` (default `/`). The frontend calls `GET /me`. Tokens are never put in the redirect URL.
5. Failure: redirect to `FRONTEND_URL + OAUTH2_FAILURE_PATH?error=<code>` (see §3).

### 4.7 Forgot and reset password

1. The frontend sends `POST /forgot-password { email }`.
2. The backend always answers `202` with the same message. If, and only if, the email belongs to a **verified password account**:
   1. it issues a `RESET_PASSWORD` link token. Older reset links for this user stop working.
   2. it emails `FRONTEND_URL + RESET_PASSWORD_PATH + "?token=" + token`, which is `/reset-password?token=...` by default. The email says the link expires in 24 hours.
3. Unknown emails, Google accounts and unverified accounts get no email and the same `202`. If sending fails, the error is logged and the response is still `202`. A different response would reveal which emails are registered.
4. The user opens the link on the frontend's **reset-password page**. That page reads `token`, asks for a new password, and sends `POST /reset-password { token, newPassword }`.
5. The backend:
   1. consumes the token (it can't be used again)
   2. overwrites the old password hash
   3. **revokes every refresh token of the user**, logging out all devices
6. The response is `204`. The frontend sends the user to the login page.
7. The old password stops working immediately. Access tokens issued before the reset stay valid for up to 15 minutes.

---

## 5. Accounts

| | Password sign-up | Google sign-up |
| --- | --- | --- |
| `provider` | `LOCAL` | `GOOGLE` |
| `role` | `WORKSPACE_MEMBER` | `WORKSPACE_MEMBER` |
| `enabled` at creation | `false` until the email is verified | `true` (Google already verified the email) |
| `password` | BCrypt hash | none |
| Can use the other sign-in method | no → `PROVIDER_MISMATCH` | no → `USE_PASSWORD_LOGIN` |

- Emails are unique and stored trimmed and lower-cased.
- Only the dev seeder (`DevDataSeeder`, `dev` profile only) creates a `WORKSPACE_ADMIN`.
- Authorities are `ROLE_` plus the role name, for example `ROLE_WORKSPACE_MEMBER`.

---

## 6. What the API reveals about registered emails

| Endpoint | Reveals whether an email is registered? |
| --- | --- |
| `POST /login` | **Password accounts: no.** Unknown email and wrong password give the identical 401. `ACCOUNT_NOT_ACTIVATED` appears only after the correct password. **Google accounts: yes**, through `PROVIDER_MISMATCH`, because the acceptance criteria require telling the user to continue with Google. |
| `POST /register` | Yes, through `CONFLICT` / `PROVIDER_MISMATCH`. This is unavoidable when emails must be unique. |
| `POST /forgot-password` | No. Always the same `202`. |

---

## 7. Token storage and cleanup

- **`refresh_tokens`:** `token_hash` (SHA-256, unique), `user_id`, `expires_at`, `remember_me`, plus the audit columns. The row's existence is what makes a refresh token valid.
- **`one_time_tokens`:** `token_hash` (SHA-256, unique), `user_id`, `purpose` (`VERIFY_EMAIL` or `RESET_PASSWORD`), `expires_at`, plus the audit columns.
- Raw tokens are never stored, so a database leak does not hand over working tokens.
- Deleting a user deletes their tokens (`ON DELETE CASCADE`).
- Expired rows are removed by nightly jobs: refresh tokens at 03:15, link tokens at 03:20, server time.

---

## 8. Emails

Sent through `EmailService` (see [email-service.md](./email-service.md)). Delivery is synchronous SMTP.

| Email | Sent by | Link |
| --- | --- | --- |
| Verify your email | `POST /register` | `FRONTEND_URL + VERIFY_EMAIL_PATH?token=...` |
| Reset your password (expires in 24 h) | `POST /forgot-password` | `FRONTEND_URL + RESET_PASSWORD_PATH?token=...` |

For local development, run Mailpit with `docker run -p 1025:1025 -p 8025:8025 axllent/mailpit` and read the emails at http://localhost:8025. The mail defaults point to it.

---

## 9. Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `JWT_SECRET` | none, required | HS256 key, at least 32 bytes |
| `COOKIE_SECURE` | `false` | Must be `true` in production (HTTPS) |
| `FRONTEND_URL` | `http://localhost:5173` | CORS origin and the base of every redirect and emailed link |
| `VERIFY_EMAIL_PATH` | `/verify-email` | Frontend route that handles the verification link |
| `RESET_PASSWORD_PATH` | `/reset-password` | Frontend route that handles the reset link |
| `OAUTH2_SUCCESS_PATH` | `/` | Frontend route after Google sign-in succeeds |
| `OAUTH2_FAILURE_PATH` | `/login` | Frontend route after Google sign-in fails (`?error=`) |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | `dummy` | Google sign-in fails at Google until real values are set |
| `MAIL_HOST`, `MAIL_PORT` | `localhost`, `1025` | SMTP server (Mailpit defaults) |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | empty | SMTP credentials (for Gmail: the address and an App Password) |
| `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS` | `false`, `false` | `true`, `true` for Gmail |
| `MAIL_FROM` | `no-reply@sprintory.local` | Sender address |

Fixed in `application.properties`:
- access token lifetime: `app.jwt.access-expiration-ms=900000`
- refresh token lifetime: `app.jwt.refresh-expiration-ms=2629800000`
- link lifetime: `app.auth.link-expiration-ms=86400000`

Give every variable in `.env` a value. An empty value defeats its default.

---

## 10. Implementation status

As of 22 Oct 2026, on branch `features/auth_config`:

| Part | Status |
| --- | --- |
| Cookies, remember-me cookie kinds, `AuthCookieFactory` | Implemented |
| Access-token filter, `GET /me` | Implemented |
| Google sign-in (§4.6) | Implemented |
| Refresh-token storage, rotation, logout-all support in `RefreshTokenService` | Implemented |
| Link-token storage (`OneTimeTokenService`) | Implemented |
| Password check before the "not activated" check (§4.2) | Implemented |
| `PROVIDER_MISMATCH` / `CONFLICT` / `SERVICE_UNAVAILABLE` / malformed-body error mapping | Implemented |
| Mail and frontend-path configuration (§9) | Implemented |
| `AuthService`: register, verify-email, login, refresh, logout, forgot/reset password | Implemented |
| Unit tests: `AuthServiceTest` (15), `OneTimeTokenServiceTest` (5), `AuthControllerTest` (11), `AuthRequestValidationTest` (4) | Implemented, all passing |
| End-to-end check against a running app with Mailpit | Pending |

### Known limitations

- **Timing on `/forgot-password`.** The body and status are identical for every email, but a registered email takes longer to answer because the reset email is actually sent. An attacker who measures response times could tell registered emails apart. The fix is to send emails asynchronously (a queue or outbox, see [email-service.md](./email-service.md)). This is deferred.
- **Access tokens cannot be revoked.** After logout or a password reset, access tokens already issued on other devices keep working for up to 15 minutes.
## 11. Decisions

| Date | Decision |
| --- | --- |
| 20 Oct 2026 | Stateless JWT in HttpOnly cookies; no server-side sessions. |
| 21 Oct 2026 | Opaque, hashed, rotating refresh tokens (not JWTs). |
| 21 Oct 2026 | CSRF disabled; `SameSite=Lax` is the CSRF protection. Never move a cookie to `SameSite=None` without re-enabling CSRF. |
| 21 Oct 2026 | Password sign-up gets `WORKSPACE_MEMBER`, the same as Google. |
| 21 Oct 2026 | Logout revokes the user's refresh tokens on all devices. |
| 21 Oct 2026 | Verification and reset links use hashed, single-use DB tokens, valid for 24 h. |
| 21 Oct 2026 | `rememberMe = false` gives session cookies; `true` gives persistent cookies. |