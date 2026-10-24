# Deployment

How the backend runs in production: one VPS, Docker Compose, and Nginx Proxy Manager in front.
The frontend is a separate repository with its own container; this file only covers what it needs
from the backend side.

## Overview

| Piece | What it is | Reachable from |
|---|---|---|
| `db` | `postgres:16`, data in the `db-data` volume | backend only |
| `backend` | the image built from this repo's `Dockerfile`, pulled from Docker Hub | the proxy only |
| `nginx` | `jc21/nginx-proxy-manager`, configured in its web UI | the internet (80, 443) and the admin UI (81) |

All three share the Docker network `sprintory`. The VPS needs only two files:
`deploy/docker-compose.yml` and a `deploy/.env` next to it. It never needs the source code.

**Why two subdomains of one domain:** the auth cookies are `SameSite=Lax` and CSRF is off (see
`docs/auth-endpoints.md`). The browser sends `Lax` cookies only between pages of the **same site**,
so the frontend and the API must be `app.<domain>` and `api.<domain>`. On two unrelated domains
login looks broken. Do not switch the cookies to `SameSite=None` to work around it.

## 1. Before the first deploy

1. **DNS**: create A records for `api.<domain>` and `app.<domain>`, both pointing at the VPS IP.
2. **Google Console** (OAuth client): add the authorized redirect URI
   `https://api.<domain>/login/oauth2/code/google`. The frontend's "Sign in with Google" button
   links to `https://api.<domain>/oauth2/authorization/google`.
3. **SMTP**: Mailpit is local only. Pick a real provider and note its host, port and credentials.
   Example for Gmail (needs an app password, not the account password): `MAIL_HOST=smtp.gmail.com`,
   `MAIL_PORT=587`, `MAIL_SMTP_AUTH=true`, `MAIL_SMTP_STARTTLS=true`. `MAIL_FROM` must be an address
   the provider lets you send from. Without working SMTP, registration answers 503.
4. **JWT secret**: `JwtService` uses the raw bytes of `JWT_SECRET` and needs at least 32 bytes.
   Generate one with `openssl rand -base64 48`. Never reuse the test or dev secret.
5. **The image**: until the CD workflow exists, build and push it by hand from the repo root:
   ```bash
   docker build -t <dockerhub-user>/sprintory-backend:latest .
   docker push <dockerhub-user>/sprintory-backend:latest
   ```

## 2. `deploy/.env` on the VPS

Start from `.env.example`. Give every key a value: an empty one defeats its default and can stop
startup.

- **Leave out** `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and `SPRING_PROFILES_ACTIVE`. The compose
  file sets them (`prod` profile, `db` as host, credentials from the `POSTGRES_*` keys) and would
  override them anyway.
- **Set** `BACKEND_IMAGE`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`.
- **Production values**: `COOKIE_SECURE=true`, `FRONTEND_URL=https://app.<domain>`, the real
  `MAIL_*` values, the real `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`, the new `JWT_SECRET`.
- `SEED_ADMIN_*` are not needed: the seeder runs only under the `dev` profile.

`POSTGRES_USER` and `POSTGRES_PASSWORD` take effect **only the first time** the `db-data` volume is
created. Changing them later does not change the existing database, and the backend then fails to
connect. To change the password, run `ALTER USER` inside the database, then update `.env`.

`deploy/.env` is gitignored. Do not commit it.

## 3. First start

In the folder with `docker-compose.yml` and `.env`:

```bash
docker compose pull
docker compose up -d
docker compose logs -f backend
```

The backend log should show Flyway applying the migrations from `db/migration` and then the
application starting. Prod runs `ddl-auto=validate`, so a missing migration stops startup here.

## 4. Nginx Proxy Manager (in the browser)

1. Open `http://<vps-ip>:81` and set your own admin email and password straight away.
2. **Proxy host `api.<domain>`**: scheme `http`, forward hostname `backend`, port `8080`. On the SSL
   tab request a Let's Encrypt certificate and turn on **Force SSL**.
3. **Proxy host `app.<domain>`**: forward to the frontend container (its name and port on the
   `sprintory` network), with a certificate and Force SSL as above.
4. The proxy sends `X-Forwarded-Proto` by default. `application-prod.properties` sets
   `server.forward-headers-strategy=framework` so Spring trusts it. Without it, the Google
   `redirect_uri` would start with `http://` and Google would reject it.
5. Close the admin UI to the public afterwards. Docker-published ports bypass `ufw`, so a firewall
   rule alone does not do it. Change the mapping in `docker-compose.yml` to `"127.0.0.1:81:81"`,
   run `docker compose up -d`, and reach the UI through an SSH tunnel:
   `ssh -L 8181:localhost:81 <user>@<vps-ip>`, then open `http://localhost:8181`.

## 5. Check that it works

1. `curl https://api.<domain>/api/v1/auth/me` returns the 401 JSON body.
2. Register with a real address, receive the email, follow the link, log in from
   `https://app.<domain>`. In the browser dev tools the `access_token` and `refresh_token` cookies
   are set on `api.<domain>` and sent on later API calls.
3. Sign in with Google.
4. `docker compose restart` and check that the data is still there.

## 6. Day to day

- **Update**: push a new image, then on the VPS `docker compose pull && docker compose up -d`.
- **Roll back**: set `BACKEND_IMAGE` to an earlier tag in `.env`, then `docker compose up -d`.
  This only works if images are pushed with a tag per version, not only `latest`. A rolled-back
  image cannot undo migrations a newer one already applied.
- **Logs**: `docker compose logs -f backend` (or `db`, `nginx`).
- **Backup**: `docker compose exec -T db pg_dump -U <POSTGRES_USER> <POSTGRES_DB> > backup.sql`.
  Restore into an empty database with
  `docker compose exec -T db psql -U <POSTGRES_USER> <POSTGRES_DB> < backup.sql`.
- **Never** run `docker compose down -v`: `-v` deletes the volumes, which means the database and the
  proxy's certificates.

## 7. What the frontend needs

- API base URL `https://api.<domain>`; CORS allows exactly `FRONTEND_URL`.
- Every request with `credentials: 'include'` (fetch) or `withCredentials: true` (axios), or the
  cookies are not sent.
- Its container joins the existing network in its own compose file:
  ```yaml
  networks:
    sprintory:
      external: true
  ```
- Its routes must match the emailed-link paths (`VERIFY_EMAIL_PATH`, `RESET_PASSWORD_PATH`,
  `INVITE_PATH`) and the Google redirect paths (`OAUTH2_SUCCESS_PATH`, `OAUTH2_FAILURE_PATH`).