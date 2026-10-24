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
if docker info >/dev/null 2>&1; then
  compose=(docker compose)
elif sudo -n docker info >/dev/null 2>&1; then
  compose=(sudo -n docker compose)
else
  echo 'Cannot reach Docker (neither direct access nor passwordless sudo).' >&2
  exit 1
fi

# An explicit SHA tag guarantees this run deploys the image it just published.
# Override BACKEND_IMAGE only for these commands; NEVER rewrite Ahmed's .env.
BACKEND_IMAGE="$image" "${compose[@]}" --env-file .env config --quiet
BACKEND_IMAGE="$image" "${compose[@]}" --env-file .env pull db backend
BACKEND_IMAGE="$image" "${compose[@]}" --env-file .env up -d --no-build --wait db backend
BACKEND_IMAGE="$image" "${compose[@]}" --env-file .env ps db backend
printf '\nDeployed backend image: %s\n' "$image"
