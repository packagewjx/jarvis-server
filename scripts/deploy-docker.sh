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

do_build() {
  ensure_docker_ready
  log "Building image ${DOCKER_IMAGE}"
  docker build -f "${DOCKERFILE_PATH}" -t "${DOCKER_IMAGE}" "${DOCKER_CONTEXT}"
}

do_stop() {
  ensure_docker_ready
  if docker ps -a --format '{{.Names}}' | grep -Fxq "${DOCKER_CONTAINER}"; then
    log "Stopping container ${DOCKER_CONTAINER}"
    docker stop "${DOCKER_CONTAINER}" >/dev/null 2>&1 || true
    log "Removing container ${DOCKER_CONTAINER}"
    docker rm "${DOCKER_CONTAINER}" >/dev/null 2>&1 || true
  else
    log "Container ${DOCKER_CONTAINER} is not running"
  fi
}

do_start() {
  ensure_docker_ready
  ensure_tls_inputs
  do_stop

  log "Starting container ${DOCKER_CONTAINER}"
  docker run -d \
    --name "${DOCKER_CONTAINER}" \
    --restart unless-stopped \
    --env-file "${CONFIG_PATH}" \
    -e NGINX_LISTEN_PORT="${NGINX_LISTEN_PORT}" \
    -e NGINX_SERVER_NAME="${NGINX_SERVER_NAME}" \
    -e NGINX_TLS_CERT_PATH="${CONTAINER_TLS_CERT_PATH}" \
    -e NGINX_TLS_KEY_PATH="${CONTAINER_TLS_KEY_PATH}" \
    -e JARVIS_SERVER_HOST="${JARVIS_SERVER_HOST}" \
    -e JARVIS_SERVER_PORT="${JARVIS_SERVER_PORT}" \
    -p "${HOST_HTTPS_PORT}:${NGINX_LISTEN_PORT}" \
    -v "${TLS_CERT_PATH}:${CONTAINER_TLS_CERT_PATH}:ro" \
    -v "${TLS_KEY_PATH}:${CONTAINER_TLS_KEY_PATH}:ro" \
    "${DOCKER_IMAGE}" >/dev/null

  log "Container started: ${DOCKER_CONTAINER}"
  log "HTTPS endpoint: https://127.0.0.1:${HOST_HTTPS_PORT}/health"
}

do_status() {
  ensure_docker_ready
  if docker ps --format '{{.Names}}' | grep -Fxq "${DOCKER_CONTAINER}"; then
    log "Container ${DOCKER_CONTAINER} is running"
    docker ps --filter "name=^${DOCKER_CONTAINER}$"
  else
    log "Container ${DOCKER_CONTAINER} is stopped"
    docker ps -a --filter "name=^${DOCKER_CONTAINER}$"
  fi
}

do_logs() {
  ensure_docker_ready
  docker logs --tail 200 "${DOCKER_CONTAINER}"
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
