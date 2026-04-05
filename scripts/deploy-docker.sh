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
OPENCLAW_BRIDGE_CONTAINER="${OPENCLAW_BRIDGE_CONTAINER:-jarvis-openclaw-bridge}"
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

OPENCLAW_BRIDGE_ENABLED="${OPENCLAW_BRIDGE_ENABLED:-true}"
OPENCLAW_BRIDGE_IMAGE="${OPENCLAW_BRIDGE_IMAGE:-node:24-bookworm}"
OPENCLAW_BRIDGE_AUTH_TOKEN="${OPENCLAW_BRIDGE_AUTH_TOKEN:-${JARVIS_CHANNEL_AUTH_TOKEN:-}}"
OPENCLAW_BRIDGE_CHANNEL_HOST="${OPENCLAW_BRIDGE_CHANNEL_HOST:-0.0.0.0}"
OPENCLAW_BRIDGE_CHANNEL_PORT="${OPENCLAW_BRIDGE_CHANNEL_PORT:-}"
OPENCLAW_BRIDGE_NETWORK_ALIAS="${OPENCLAW_BRIDGE_NETWORK_ALIAS:-}"
OPENCLAW_BRIDGE_COMMAND_ALLOWLIST="${OPENCLAW_BRIDGE_COMMAND_ALLOWLIST:-help,status,new,reset,think,verbose}"
OPENCLAW_BRIDGE_COMMAND_RATE_LIMIT_PER_MIN="${OPENCLAW_BRIDGE_COMMAND_RATE_LIMIT_PER_MIN:-30}"
OPENCLAW_BRIDGE_COMMAND_AUTO_DETECT_SLASH="${OPENCLAW_BRIDGE_COMMAND_AUTO_DETECT_SLASH:-true}"
OPENCLAW_BRIDGE_AGENT_LOCAL="${OPENCLAW_BRIDGE_AGENT_LOCAL:-true}"
OPENCLAW_BRIDGE_AGENT_TIMEOUT_MS="${OPENCLAW_BRIDGE_AGENT_TIMEOUT_MS:-180000}"
OPENCLAW_BRIDGE_AGENT_WORKDIR="${OPENCLAW_BRIDGE_AGENT_WORKDIR:-/workspace/openclaw-channel}"
OPENCLAW_BRIDGE_OPENCLAW_CONFIG_PATH="${OPENCLAW_BRIDGE_OPENCLAW_CONFIG_PATH:-${HOME}/.openclaw/openclaw.json}"
OPENCLAW_BRIDGE_GENERATED_CONFIG_PATH="${OPENCLAW_BRIDGE_GENERATED_CONFIG_PATH:-${REPO_ROOT}/.run/openclaw-bridge.json}"
OPENCLAW_BRIDGE_CONTAINER_CONFIG_PATH="${OPENCLAW_BRIDGE_CONTAINER_CONFIG_PATH:-/workspace/.run/openclaw-bridge.json}"
OPENCLAW_BRIDGE_PROVIDER_ID="${OPENCLAW_BRIDGE_PROVIDER_ID:-}"
OPENCLAW_BRIDGE_MODEL_ID="${OPENCLAW_BRIDGE_MODEL_ID:-}"
OPENCLAW_BRIDGE_TLS_CERT_PATH="${OPENCLAW_BRIDGE_TLS_CERT_PATH:-${TLS_CERT_PATH}}"
OPENCLAW_BRIDGE_TLS_KEY_PATH="${OPENCLAW_BRIDGE_TLS_KEY_PATH:-${TLS_KEY_PATH}}"
OPENCLAW_BRIDGE_CONTAINER_TLS_CERT_PATH="${OPENCLAW_BRIDGE_CONTAINER_TLS_CERT_PATH:-/workspace/.run/bridge.crt}"
OPENCLAW_BRIDGE_CONTAINER_TLS_KEY_PATH="${OPENCLAW_BRIDGE_CONTAINER_TLS_KEY_PATH:-/workspace/.run/bridge.key}"

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

is_true() {
  local value="${1:-false}"
  case "${value,,}" in
    1|true|yes|on)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

expand_path() {
  local value="$1"
  if [[ "${value}" == "~/"* ]]; then
    echo "${HOME}/${value#~/}"
    return
  fi
  echo "${value}"
}

resolve_path() {
  local value="$1"
  value="$(expand_path "${value}")"
  if [[ "${value}" = /* ]]; then
    echo "${value}"
    return
  fi
  echo "${REPO_ROOT}/${value}"
}

extract_base_url_host() {
  python3 - "$1" <<'PY'
import sys
from urllib.parse import urlparse
url = (sys.argv[1] or "").strip()
if not url:
    print("")
    raise SystemExit(0)
parsed = urlparse(url)
print(parsed.hostname or "")
PY
}

extract_base_url_port() {
  python3 - "$1" <<'PY'
import sys
from urllib.parse import urlparse
url = (sys.argv[1] or "").strip()
if not url:
    print("")
    raise SystemExit(0)
parsed = urlparse(url)
if parsed.port:
    print(parsed.port)
elif parsed.scheme == "https":
    print(443)
elif parsed.scheme == "http":
    print(80)
else:
    print("")
PY
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

ensure_bridge_inputs() {
  if ! is_true "${OPENCLAW_BRIDGE_ENABLED}"; then
    return
  fi

  need_command python3

  local source_config
  source_config="$(resolve_path "${OPENCLAW_BRIDGE_OPENCLAW_CONFIG_PATH}")"
  [[ -f "${source_config}" ]] || fail "OPENCLAW_BRIDGE_OPENCLAW_CONFIG_PATH does not exist: ${source_config}"

  [[ -n "${OPENCLAW_BRIDGE_AUTH_TOKEN}" ]] || fail "OPENCLAW_BRIDGE_AUTH_TOKEN or JARVIS_CHANNEL_AUTH_TOKEN is required"
  [[ -n "${OPENCLAW_BRIDGE_TLS_CERT_PATH}" ]] || fail "OPENCLAW_BRIDGE_TLS_CERT_PATH is required"
  [[ -n "${OPENCLAW_BRIDGE_TLS_KEY_PATH}" ]] || fail "OPENCLAW_BRIDGE_TLS_KEY_PATH is required"
  local bridge_tls_cert bridge_tls_key
  bridge_tls_cert="$(resolve_path "${OPENCLAW_BRIDGE_TLS_CERT_PATH}")"
  bridge_tls_key="$(resolve_path "${OPENCLAW_BRIDGE_TLS_KEY_PATH}")"
  [[ -f "${bridge_tls_cert}" ]] || fail "OPENCLAW_BRIDGE_TLS_CERT_PATH file does not exist: ${bridge_tls_cert}"
  [[ -f "${bridge_tls_key}" ]] || fail "OPENCLAW_BRIDGE_TLS_KEY_PATH file does not exist: ${bridge_tls_key}"
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

generate_bridge_openclaw_config() {
  if ! is_true "${OPENCLAW_BRIDGE_ENABLED}"; then
    return
  fi

  local source_config generated_config generated_dir
  source_config="$(resolve_path "${OPENCLAW_BRIDGE_OPENCLAW_CONFIG_PATH}")"
  generated_config="$(resolve_path "${OPENCLAW_BRIDGE_GENERATED_CONFIG_PATH}")"
  generated_dir="$(dirname "${generated_config}")"
  mkdir -p "${generated_dir}"

  python3 - "${source_config}" "${generated_config}" "${OPENCLAW_BRIDGE_PROVIDER_ID}" "${OPENCLAW_BRIDGE_MODEL_ID}" <<'PY'
import json
import sys
from pathlib import Path

source_path = Path(sys.argv[1])
target_path = Path(sys.argv[2])
provider_override = sys.argv[3].strip()
model_override = sys.argv[4].strip()

with source_path.open("r", encoding="utf-8") as f:
    source = json.load(f)

providers = (((source.get("models") or {}).get("providers")) or {})
if not isinstance(providers, dict) or len(providers) == 0:
    raise SystemExit(f"models.providers not found in {source_path}")

if provider_override:
    if provider_override not in providers:
        raise SystemExit(
            f"OPENCLAW_BRIDGE_PROVIDER_ID={provider_override} not found in models.providers"
        )
    provider_id = provider_override
else:
    provider_id = next(iter(providers.keys()))

provider_cfg = providers.get(provider_id) or {}
provider_models = provider_cfg.get("models") or []
if not isinstance(provider_models, list) or len(provider_models) == 0:
    raise SystemExit(f"Provider {provider_id} has no models list")

available_model_ids = [str(item.get("id") or "") for item in provider_models]
available_model_ids = [model_id for model_id in available_model_ids if model_id]
if len(available_model_ids) == 0:
    raise SystemExit(f"Provider {provider_id} models have no id field")

if model_override:
    if model_override not in available_model_ids:
        raise SystemExit(
            "OPENCLAW_BRIDGE_MODEL_ID="
            + model_override
            + f" not found under provider {provider_id}"
        )
    model_id = model_override
else:
    model_id = available_model_ids[0]

minimal = {
    "models": {
        "mode": "replace",
        "providers": {
            provider_id: provider_cfg
        },
    },
    "agents": {
        "defaults": {
            "model": {
                "primary": f"{provider_id}/{model_id}",
                "fallbacks": [],
            },
        },
    },
}

target_path.write_text(json.dumps(minimal, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"[deploy-docker] Generated OpenClaw bridge config: {target_path}")
print(f"[deploy-docker] OpenClaw bridge model: {provider_id}/{model_id}")
PY

  chmod 600 "${generated_config}"
}

start_openclaw_bridge() {
  if ! is_true "${OPENCLAW_BRIDGE_ENABLED}"; then
    log "Skipping OpenClaw bridge startup (OPENCLAW_BRIDGE_ENABLED=${OPENCLAW_BRIDGE_ENABLED})"
    return
  fi

  local bridge_channel_port bridge_network_alias generated_config bridge_tls_cert bridge_tls_key
  local -a docker_args
  bridge_channel_port="${OPENCLAW_BRIDGE_CHANNEL_PORT}"
  if [[ -z "${bridge_channel_port}" ]]; then
    bridge_channel_port="$(extract_base_url_port "${JARVIS_CHANNEL_BASE_URL:-}")"
  fi
  [[ -n "${bridge_channel_port}" ]] || bridge_channel_port="9443"

  bridge_network_alias="${OPENCLAW_BRIDGE_NETWORK_ALIAS}"
  if [[ -z "${bridge_network_alias}" ]]; then
    bridge_network_alias="$(extract_base_url_host "${JARVIS_CHANNEL_BASE_URL:-}")"
  fi

  generated_config="$(resolve_path "${OPENCLAW_BRIDGE_GENERATED_CONFIG_PATH}")"
  bridge_tls_cert="$(resolve_path "${OPENCLAW_BRIDGE_TLS_CERT_PATH}")"
  bridge_tls_key="$(resolve_path "${OPENCLAW_BRIDGE_TLS_KEY_PATH}")"

  stop_container_if_exists "${OPENCLAW_BRIDGE_CONTAINER}"
  log "Starting OpenClaw bridge container ${OPENCLAW_BRIDGE_CONTAINER}"
  docker_args=(
    run -d
    --name "${OPENCLAW_BRIDGE_CONTAINER}"
    --restart unless-stopped
    --network "${DOCKER_NETWORK}"
    -e OPENCLAW_CHANNEL_HOST="${OPENCLAW_BRIDGE_CHANNEL_HOST}"
    -e OPENCLAW_CHANNEL_PORT="${bridge_channel_port}"
    -e OPENCLAW_CHANNEL_AUTH_TOKEN="${OPENCLAW_BRIDGE_AUTH_TOKEN}"
    -e OPENCLAW_CHANNEL_TLS_CERT_PATH="${OPENCLAW_BRIDGE_CONTAINER_TLS_CERT_PATH}"
    -e OPENCLAW_CHANNEL_TLS_KEY_PATH="${OPENCLAW_BRIDGE_CONTAINER_TLS_KEY_PATH}"
    -e OPENCLAW_COMMAND_ALLOWLIST="${OPENCLAW_BRIDGE_COMMAND_ALLOWLIST}"
    -e OPENCLAW_COMMAND_RATE_LIMIT_PER_MIN="${OPENCLAW_BRIDGE_COMMAND_RATE_LIMIT_PER_MIN}"
    -e OPENCLAW_COMMAND_AUTO_DETECT_SLASH="${OPENCLAW_BRIDGE_COMMAND_AUTO_DETECT_SLASH}"
    -e OPENCLAW_AGENT_LOCAL="${OPENCLAW_BRIDGE_AGENT_LOCAL}"
    -e OPENCLAW_AGENT_TIMEOUT_MS="${OPENCLAW_BRIDGE_AGENT_TIMEOUT_MS}"
    -e OPENCLAW_AGENT_WORKDIR="${OPENCLAW_BRIDGE_AGENT_WORKDIR}"
    -e OPENCLAW_CONFIG_PATH="${OPENCLAW_BRIDGE_CONTAINER_CONFIG_PATH}"
    -v "${REPO_ROOT}:/workspace"
    -v "${generated_config}:${OPENCLAW_BRIDGE_CONTAINER_CONFIG_PATH}:ro"
    -v "${bridge_tls_cert}:${OPENCLAW_BRIDGE_CONTAINER_TLS_CERT_PATH}:ro"
    -v "${bridge_tls_key}:${OPENCLAW_BRIDGE_CONTAINER_TLS_KEY_PATH}:ro"
  )

  if [[ -n "${bridge_network_alias}" && "${bridge_network_alias}" != "localhost" && "${bridge_network_alias}" != "127.0.0.1" ]]; then
    docker_args+=(--network-alias "${bridge_network_alias}")
  fi

  docker_args+=(
    "${OPENCLAW_BRIDGE_IMAGE}"
    sh -lc "cd /workspace/openclaw-channel && [ -d node_modules ] || npm install --no-fund --no-audit; npm run dev:bridge"
  )
  docker "${docker_args[@]}" >/dev/null
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
  log "Stopping OpenClaw bridge container ${OPENCLAW_BRIDGE_CONTAINER}"
  stop_container_if_exists "${OPENCLAW_BRIDGE_CONTAINER}"
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
  ensure_bridge_inputs

  start_postgres
  generate_bridge_openclaw_config
  start_openclaw_bridge
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
  if is_true "${OPENCLAW_BRIDGE_ENABLED}"; then
    log "OpenClaw bridge container: ${OPENCLAW_BRIDGE_CONTAINER}"
  fi
  log "Database container: ${POSTGRES_CONTAINER}"
  log "HTTPS endpoint: https://127.0.0.1:${HOST_HTTPS_PORT}/health"
}

do_status() {
  ensure_docker_ready
  log "Application container status"
  docker ps -a --filter "name=^${DOCKER_CONTAINER}$"
  log "OpenClaw bridge container status"
  docker ps -a --filter "name=^${OPENCLAW_BRIDGE_CONTAINER}$"
  log "Database container status"
  docker ps -a --filter "name=^${POSTGRES_CONTAINER}$"
}

do_logs() {
  ensure_docker_ready
  log "--- ${DOCKER_CONTAINER} logs ---"
  docker logs --tail 200 "${DOCKER_CONTAINER}" || true
  log "--- ${OPENCLAW_BRIDGE_CONTAINER} logs ---"
  docker logs --tail 200 "${OPENCLAW_BRIDGE_CONTAINER}" || true
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
