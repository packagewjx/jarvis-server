#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ACTION="${1:-build}"
CONFIG_PATH="${2:-${REPO_ROOT}/scripts/deploy.docker.env}"

if [[ ! -f "${CONFIG_PATH}" ]]; then
  echo "[deploy-docker] Missing config file: ${CONFIG_PATH}" >&2
  echo "[deploy-docker] Copy ${REPO_ROOT}/scripts/deploy.docker.env.example and edit it first." >&2
  exit 1
fi

# shellcheck disable=SC1090
set -a
source "${CONFIG_PATH}"
set +a

DOCKER_IMAGE="${DOCKER_IMAGE:-jarvis-server:docker}"
DOCKER_CONTAINER="${DOCKER_CONTAINER:-jarvis-server}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-jarvis-postgres}"
DOCKER_NETWORK="${DOCKER_NETWORK:-jarvis-net}"
POSTGRES_VOLUME="${POSTGRES_VOLUME:-jarvis-postgres-data}"

DOCKERFILE_PATH="${DOCKERFILE_PATH:-${REPO_ROOT}/Dockerfile}"
DOCKER_CONTEXT="${DOCKER_CONTEXT:-${REPO_ROOT}}"

HOST_HTTPS_PORT="${HOST_HTTPS_PORT:-8443}"
NGINX_LISTEN_PORT="${NGINX_LISTEN_PORT:-443}"
NGINX_SERVER_NAME="${NGINX_SERVER_NAME:-_}"

TLS_CERT_PATH="${TLS_CERT_PATH:-}"
TLS_KEY_PATH="${TLS_KEY_PATH:-}"

CONTAINER_TLS_CERT_PATH="${CONTAINER_TLS_CERT_PATH:-/etc/nginx/tls/server.crt}"
CONTAINER_TLS_KEY_PATH="${CONTAINER_TLS_KEY_PATH:-/etc/nginx/tls/server.key}"

JARVIS_SERVER_PORT="${JARVIS_SERVER_PORT:-18080}"
JARVIS_SERVER_HOST="${JARVIS_SERVER_HOST:-127.0.0.1}"

POSTGRES_DB="${POSTGRES_DB:-jarvis}"
POSTGRES_USER="${POSTGRES_USER:-jarvis}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-jarvis}"
POSTGRES_HOST_PORT="${POSTGRES_HOST_PORT:-5432}"

JARVIS_DB_JDBC_URL="${JARVIS_DB_JDBC_URL:-jdbc:postgresql://${POSTGRES_CONTAINER}:5432/${POSTGRES_DB}}"
JARVIS_DB_USER="${JARVIS_DB_USER:-${POSTGRES_USER}}"
JARVIS_DB_PASSWORD="${JARVIS_DB_PASSWORD:-${POSTGRES_PASSWORD}}"

log() {
  echo "[deploy-docker] $*"
}

fail() {
  echo "[deploy-docker] ERROR: $*" >&2
  exit 1
}

need_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing command: $1"
}

ensure_docker_ready() {
  need_command docker
  docker info >/dev/null 2>&1 || fail "Docker daemon is not running or not reachable"
}

ensure_tls_inputs() {
  [[ -n "${TLS_CERT_PATH}" ]] || fail "TLS_CERT_PATH is required for start"
  [[ -n "${TLS_KEY_PATH}" ]] || fail "TLS_KEY_PATH is required for start"
  [[ -f "${TLS_CERT_PATH}" ]] || fail "TLS_CERT_PATH file does not exist: ${TLS_CERT_PATH}"
  [[ -f "${TLS_KEY_PATH}" ]] || fail "TLS_KEY_PATH file does not exist: ${TLS_KEY_PATH}"
}

ensure_database_inputs() {
  [[ -n "${POSTGRES_DB}" ]] || fail "POSTGRES_DB is required"
  [[ -n "${POSTGRES_USER}" ]] || fail "POSTGRES_USER is required"
  [[ -n "${POSTGRES_PASSWORD}" ]] || fail "POSTGRES_PASSWORD is required"
  [[ -n "${JARVIS_DB_JDBC_URL}" ]] || fail "JARVIS_DB_JDBC_URL is required"
  [[ -n "${JARVIS_DB_USER}" ]] || fail "JARVIS_DB_USER is required"
  [[ -n "${JARVIS_DB_PASSWORD}" ]] || fail "JARVIS_DB_PASSWORD is required"
}

ensure_network() {
  if ! docker network ls --format '{{.Name}}' | grep -Fxq "${DOCKER_NETWORK}"; then
    log "Creating network ${DOCKER_NETWORK}"
    docker network create "${DOCKER_NETWORK}" >/dev/null
  fi
}

stop_container_if_exists() {
  local container="$1"
  if docker ps -a --format '{{.Names}}' | grep -Fxq "${container}"; then
    docker stop "${container}" >/dev/null 2>&1 || true
    docker rm "${container}" >/dev/null 2>&1 || true
  fi
}

wait_for_postgres() {
  local retries=30
  while (( retries > 0 )); do
    if docker exec "${POSTGRES_CONTAINER}" pg_isready -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" >/dev/null 2>&1; then
      return 0
    fi
    retries=$((retries - 1))
    sleep 1
  done
  return 1
}

do_build() {
  ensure_docker_ready
  log "Building image ${DOCKER_IMAGE}"
  docker build -f "${DOCKERFILE_PATH}" -t "${DOCKER_IMAGE}" "${DOCKER_CONTEXT}"
}

do_stop() {
  ensure_docker_ready
  log "Stopping application container ${DOCKER_CONTAINER}"
  stop_container_if_exists "${DOCKER_CONTAINER}"
  log "Stopping database container ${POSTGRES_CONTAINER}"
  stop_container_if_exists "${POSTGRES_CONTAINER}"
}

start_postgres() {
  ensure_network
  stop_container_if_exists "${POSTGRES_CONTAINER}"

  log "Starting PostgreSQL container ${POSTGRES_CONTAINER}"
  docker run -d \
    --name "${POSTGRES_CONTAINER}" \
    --restart unless-stopped \
    --network "${DOCKER_NETWORK}" \
    -e POSTGRES_DB="${POSTGRES_DB}" \
    -e POSTGRES_USER="${POSTGRES_USER}" \
    -e POSTGRES_PASSWORD="${POSTGRES_PASSWORD}" \
    -p "${POSTGRES_HOST_PORT}:5432" \
    -v "${POSTGRES_VOLUME}:/var/lib/postgresql/data" \
    postgres:16-alpine >/dev/null

  if ! wait_for_postgres; then
    fail "PostgreSQL did not become ready in time"
  fi
}

do_start() {
  ensure_docker_ready
  ensure_tls_inputs
  ensure_database_inputs

  start_postgres
  stop_container_if_exists "${DOCKER_CONTAINER}"

  log "Starting application container ${DOCKER_CONTAINER}"
  docker run -d \
    --name "${DOCKER_CONTAINER}" \
    --restart unless-stopped \
    --network "${DOCKER_NETWORK}" \
    --env-file "${CONFIG_PATH}" \
    -e NGINX_LISTEN_PORT="${NGINX_LISTEN_PORT}" \
    -e NGINX_SERVER_NAME="${NGINX_SERVER_NAME}" \
    -e NGINX_TLS_CERT_PATH="${CONTAINER_TLS_CERT_PATH}" \
    -e NGINX_TLS_KEY_PATH="${CONTAINER_TLS_KEY_PATH}" \
    -e JARVIS_SERVER_HOST="${JARVIS_SERVER_HOST}" \
    -e JARVIS_SERVER_PORT="${JARVIS_SERVER_PORT}" \
    -e JARVIS_DB_JDBC_URL="${JARVIS_DB_JDBC_URL}" \
    -e JARVIS_DB_USER="${JARVIS_DB_USER}" \
    -e JARVIS_DB_PASSWORD="${JARVIS_DB_PASSWORD}" \
    -p "${HOST_HTTPS_PORT}:${NGINX_LISTEN_PORT}" \
    -v "${TLS_CERT_PATH}:${CONTAINER_TLS_CERT_PATH}:ro" \
    -v "${TLS_KEY_PATH}:${CONTAINER_TLS_KEY_PATH}:ro" \
    "${DOCKER_IMAGE}" >/dev/null

  log "Container started: ${DOCKER_CONTAINER}"
  log "Database container: ${POSTGRES_CONTAINER}"
  log "HTTPS endpoint: https://127.0.0.1:${HOST_HTTPS_PORT}/health"
}

do_status() {
  ensure_docker_ready
  log "Application container status"
  docker ps -a --filter "name=^${DOCKER_CONTAINER}$"
  log "Database container status"
  docker ps -a --filter "name=^${POSTGRES_CONTAINER}$"
}

do_logs() {
  ensure_docker_ready
  log "--- ${DOCKER_CONTAINER} logs ---"
  docker logs --tail 200 "${DOCKER_CONTAINER}" || true
  log "--- ${POSTGRES_CONTAINER} logs ---"
  docker logs --tail 100 "${POSTGRES_CONTAINER}" || true
}

case "${ACTION}" in
  build)
    do_build
    ;;
  start)
    do_start
    ;;
  stop)
    do_stop
    ;;
  restart)
    do_stop
    do_start
    ;;
  status)
    do_status
    ;;
  logs)
    do_logs
    ;;
  *)
    fail "Unsupported action: ${ACTION}. Use build|start|stop|restart|status|logs"
    ;;
esac
