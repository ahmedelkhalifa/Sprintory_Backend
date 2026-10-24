# Backend continuous deployment — Sprintory

## What the CD workflow does

`.github/workflows/cd.yml` runs after **Backend CI** has succeeded for a **push to main**, or by manual dispatch from `main`. It is dormant until the repository variable `SPRINTORY_CD_ENABLED` is set to `true`.

It builds `DOCKERHUB_IMAGE:sha-<40-character-commit-SHA>` and `DOCKERHUB_IMAGE:latest`, pushes both tags to the **existing** Docker Hub repository, and SSHes to the VPS to pull and run `db` + `backend` with `docker compose`. It does **not** start the `nginx` service: the existing host Nginx and Nginx Proxy Manager both require TCP 80/443, so choose the reverse proxy architecture separately. It never edits or uploads `.env`.

## One-time VPS setup (run from the local laptop)

Make sure the backend repository is on its merged `main` and the working tree is clean. Use the actual EC2 IP and your private SSH key:

```bash
cd ~/Desktop/coding_no_venv/python/real_projects/0-advanced/sprintory/Sprintory_Backend
ssh -i ~/.ssh/server_key_fixed.pem ubuntu@18.192.119.158 'mkdir -p ~/deploy && chmod 700 ~/deploy'
scp -i ~/.ssh/server_key_fixed.pem deploy/docker-compose.yml ubuntu@18.192.119.158:deploy/docker-compose.yml
# Run ONLY if deploy/.env does not exist (never overwrite filled-in values):
ssh -i ~/.ssh/server_key_fixed.pem ubuntu@18.192.119.158 'test ! -e ~/deploy/.env' \
  && scp -i ~/.ssh/server_key_fixed.pem .env.example ubuntu@18.192.119.158:deploy/.env
ssh -i ~/.ssh/server_key_fixed.pem ubuntu@18.192.119.158 'chmod 600 ~/deploy/.env && ls -la ~/deploy'
```

Ahmed fills in `.env` later. The file is initially a **copy of the template with empty values**. Note that `deploy/docker-compose.yml` overrides `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and `SPRING_PROFILES_ACTIVE` for the backend, despite those being listed in `.env.example`.

**Do not run** `docker compose up -d` while the variables are still empty.

### VPS diagnostics

```bash
sudo docker ps -a
sudo docker image ls
sudo ss -ltnp | grep -E ':(80|81|443)\\b' || true
sudo systemctl is-active nginx
sudo -n docker info >/dev/null && echo 'Passwordless sudo to Docker works'
```

On the GitHub Actions SSH connection, the `ubuntu` user needs access to Docker directly **or passwordless sudo** for Docker; the deploy script detects either. Docker group access is root-equivalent; grant it only if needed and accepted by the team.

## GitHub repository configuration

Create **Repository variables** (Settings → Secrets and variables → Actions → Variables):

| Variable | Content |
|---|---|
| `DOCKERHUB_IMAGE` | Existing Docker Hub repo **without** tag, e.g. `existing-namespace/sprintory-backend` |
| `DOCKERHUB_USERNAME` | Docker Hub login authorized to push to that repo |
| `VPS_HOST` | EC2 public IP/DNS |
| `VPS_USER` | `ubuntu` |
| `SPRINTORY_CD_ENABLED` | Initially unset; set to `true` only after the VPS `.env` is configured and SSH secrets have been tested |

Create **Repository secrets**:

| Secret | Content |
|---|---|
| `DOCKERHUB_TOKEN` | Docker Hub access token with push permission (not the account password) |
| `VPS_SSH_KEY` | A **dedicated** deployment SSH private key whose public key has been added to `ubuntu`'s `~/.ssh/authorized_keys` |
| `VPS_SSH_KNOWN_HOSTS` | A verified OpenSSH `known_hosts` line for the VPS host |

Use a separate deployment SSH key; do not upload the personal EC2 key unless the owner explicitly accepts the additional exposure. On the VPS, check the host-key fingerprint with `sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`; compare it with the fingerprint from `ssh-keyscan -t ed25519 <VPS_HOST> > /tmp/sprintory_ssh_host && ssh-keygen -lf /tmp/sprintory_ssh_host` before storing that line in the GitHub secret. Do not trust a freshly scanned key without matching fingerprints.

If the Docker Hub repository is **private**, the VPS also needs permission to `docker pull` it; initialize Docker login for whichever user runs Compose (which may be root when sudo is used).

## First rollout

1. Verify that the image namespace/repository is exactly the existing Docker Hub image; check `sudo docker image ls` on the VPS or ask the image owner.
2. Ask Ahmed to fill in `~/deploy/.env`, including `BACKEND_IMAGE`, PostgreSQL variables, JWT, mail, OAuth and frontend URL.
3. Set GitHub variable `SPRINTORY_CD_ENABLED=true`.
4. Use **Actions → Backend CD → Run workflow** on `main`, or merge the next PR to main after Backend CI passes.
5. Check the action log, then on VPS: `sudo docker ps`, `sudo docker compose --env-file ~/deploy/.env -f ~/deploy/docker-compose.yml ps`, `sudo docker logs <backend-container>`.
6. Configure the reverse proxy separately. The CD script deliberately leaves `nginx` untouched.

To roll back, temporarily disable CD, choose a previously published `sha-...` image tag, then run Compose with `BACKEND_IMAGE=<existing-repo>:sha-...`; check DB migration compatibility before reverting.

## Important limitations

- The workflow is a **backend-only** deploy; it does not build the frontend.
- The initial `.env` template is intentionally incomplete; not a working production configuration.
- Postgres images may run at a different native architecture on ARM; this workflow builds `linux/amd64`, matching typical x86 EC2 instances (verify `uname -m`).
- There is no backend HTTP healthcheck in the supplied compose. `up --wait` waits until the backend container reaches a running state, **not** until the application answers real HTTP requests. Perform application smoke tests separately.
- Do not use `docker compose down -v` against production: it deletes data volumes.
