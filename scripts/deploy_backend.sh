#!/usr/bin/env bash
# Invoked by GitHub Actions over SSH. Runs only db + backend, deliberately
# leaving reverse-proxy management to the VPS owner.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo 'Usage: deploy_backend.sh <dockerhub-namespace/image> <image-tag>' >&2
  exit 2
fi
image="$1:$2"
deploy_dir="$HOME/deploy"

if [[ ! -f "$deploy_dir/docker-compose.yml" || ! -f "$deploy_dir/.env" ]]; then
  echo "Expected $deploy_dir/docker-compose.yml and $deploy_dir/.env" >&2
  exit 1
fi

cd "$deploy_dir"
# Do not allow CD to deploy against an unconfigured database or JWT secret.
for key in POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD JWT_SECRET FRONTEND_URL; do
  if ! grep -Eq "^${key}=.+" .env; then
    echo "Missing nonempty ${key} in deploy/.env. Deployment stopped." >&2
    exit 1
  fi
done

# Ubuntu images commonly require sudo for Docker; support either access mode.
# Supply the SHA image override *inside* sudo's command. Ubuntu sudo normally
# resets the environment, so setting BACKEND_IMAGE before sudo is insufficient.
if docker info >/dev/null 2>&1; then
  docker_cmd=(docker)
  compose=(env "BACKEND_IMAGE=$image" docker compose)
elif sudo -n docker info >/dev/null 2>&1; then
  docker_cmd=(sudo -n docker)
  compose=(sudo -n env "BACKEND_IMAGE=$image" docker compose)
else
  echo 'Cannot reach Docker (neither direct access nor passwordless sudo).' >&2
  exit 1
fi

# An explicit SHA tag guarantees this run deploys the image it just published.
# Override BACKEND_IMAGE only for these commands; NEVER rewrite Ahmed's .env.
"${compose[@]}" --env-file .env config --quiet
"${compose[@]}" --env-file .env pull db backend
"${compose[@]}" --env-file .env up -d --no-build --wait db backend
"${compose[@]}" --env-file .env ps db backend

# Point the VPS's local :latest at the image that is now running. Compose never
# re-pulls a tag it already has, so without this a manual `docker compose up -d`
# with BACKEND_IMAGE=<repo> (or <repo>:latest) silently starts an older image.
# Done only after `up --wait` succeeded, so :latest is never a failed deploy.
"${docker_cmd[@]}" tag "$image" "$1:latest"
printf '\nDeployed backend image: %s (also tagged %s:latest on this host)\n' "$image" "$1"
