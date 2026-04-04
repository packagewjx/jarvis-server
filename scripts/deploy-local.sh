#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ACTION="${1:-start}"
CONFIG_PATH="${2:-${REPO_ROOT}/scripts/deploy.local.env}"

if [[ ! -f "${CONFIG_PATH}" ]]; then
  echo "[deploy] Missing config file: ${CONFIG_PATH}" >&2
  echo "[deploy] Copy ${REPO_ROOT}/scripts/deploy.local.env.example and edit it first." >&2
  exit 1
fi

# shellcheck disable=SC1090
set -a
source "${CONFIG_PATH}"
set +a

OPENCLAW_CHANNEL_DIR="${OPENCLAW_CHANNEL_DIR:-${REPO_ROOT}/openclaw-channel}"
JARVIS_REPO_ROOT="${JARVIS_REPO_ROOT:-${REPO_ROOT}}"
if [[ -x "${JARVIS_REPO_ROOT}/gradlew" ]]; then
  GRADLE_CMD="${GRADLE_CMD:-${JARVIS_REPO_ROOT}/gradlew}"
else
  GRADLE_CMD="${GRADLE_CMD:-gradle}"
fi

RUN_DIR="${RUN_DIR:-${REPO_ROOT}/.run}"
LOG_DIR="${LOG_DIR:-${REPO_ROOT}/.logs}"
CERT_DIR="${CERT_DIR:-${REPO_ROOT}/.certs}"

OPENCLAW_CONFIG_PATH="${OPENCLAW_CONFIG_PATH:-${HOME}/.openclaw/openclaw.json}"
OPENCLAW_STATE_DIR="${OPENCLAW_STATE_DIR:-}"

OPENCLAW_CHANNEL_HOST="${OPENCLAW_CHANNEL_HOST:-127.0.0.1}"
OPENCLAW_CHANNEL_PORT="${OPENCLAW_CHANNEL_PORT:-9443}"
JARVIS_SERVER_HOST="${JARVIS_SERVER_HOST:-127.0.0.1}"
JARVIS_SERVER_PORT="${JARVIS_SERVER_PORT:-8080}"

JARVIS_SERVER_AUTH_TOKEN="${JARVIS_SERVER_AUTH_TOKEN:-dev-client-token}"
JARVIS_SERVER_USER_ID="${JARVIS_SERVER_USER_ID:-dev-user}"
JARVIS_CHANNEL_BASE_URL="${JARVIS_CHANNEL_BASE_URL:-https://${OPENCLAW_CHANNEL_HOST}:${OPENCLAW_CHANNEL_PORT}}"
JARVIS_CHANNEL_CONNECT_TIMEOUT_MS="${JARVIS_CHANNEL_CONNECT_TIMEOUT_MS:-10000}"
JARVIS_CHANNEL_READ_TIMEOUT_MS="${JARVIS_CHANNEL_READ_TIMEOUT_MS:-60000}"
JARVIS_CHANNEL_HOSTNAME_VERIFICATION="${JARVIS_CHANNEL_HOSTNAME_VERIFICATION:-false}"

OPENCLAW_CHANNEL_TLS_KEY_PATH="${OPENCLAW_CHANNEL_TLS_KEY_PATH:-${CERT_DIR}/openclaw-channel-key.pem}"
OPENCLAW_CHANNEL_TLS_CERT_PATH="${OPENCLAW_CHANNEL_TLS_CERT_PATH:-${CERT_DIR}/openclaw-channel-cert.pem}"
JARVIS_CHANNEL_CA_CERT_PATH="${JARVIS_CHANNEL_CA_CERT_PATH:-${OPENCLAW_CHANNEL_TLS_CERT_PATH}}"

OPENCLAW_CHANNEL_AUTH_TOKEN="${OPENCLAW_CHANNEL_AUTH_TOKEN:-${JARVIS_CHANNEL_AUTH_TOKEN:-}}"
OPENCLAW_CHANNEL_DM_SECURITY="${OPENCLAW_CHANNEL_DM_SECURITY:-allowlist}"
OPENCLAW_CHANNEL_ALLOW_FROM="${OPENCLAW_CHANNEL_ALLOW_FROM:-}"

OPENCLAW_BRIDGE_PID_FILE="${RUN_DIR}/openclaw-bridge.pid"
JARVIS_SERVER_PID_FILE="${RUN_DIR}/jarvis-server.pid"
OPENCLAW_BRIDGE_LOG="${LOG_DIR}/openclaw-bridge.log"
JARVIS_SERVER_LOG="${LOG_DIR}/jarvis-server.log"

OPENCLAW_CHANNEL_ID="jarvis-openclaw"

log() {
  echo "[deploy] $*"
}

fail() {
  echo "[deploy] ERROR: $*" >&2
  exit 1
}

need_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing command: $1"
}

require_start_config() {
  [[ -n "${JARVIS_CHANNEL_AUTH_TOKEN:-}" ]] || fail "JARVIS_CHANNEL_AUTH_TOKEN is required"
  [[ -n "${OPENCLAW_CHANNEL_AUTH_TOKEN:-}" ]] || fail "OPENCLAW_CHANNEL_AUTH_TOKEN is required"
  [[ -n "${OPENCLAW_CHANNEL_TOKEN:-}" ]] || fail "OPENCLAW_CHANNEL_TOKEN is required"
}

ensure_dirs() {
  mkdir -p "${RUN_DIR}" "${LOG_DIR}" "${CERT_DIR}"
}

is_pid_running() {
  local pid="$1"
  kill -0 "${pid}" >/dev/null 2>&1
}

read_pid() {
  local file="$1"
  if [[ -f "${file}" ]]; then
    tr -d '[:space:]' <"${file}"
  fi
}

run_openclaw() {
  local -a env_vars
  env_vars=("OPENCLAW_CONFIG_PATH=${OPENCLAW_CONFIG_PATH}")
  if [[ -n "${OPENCLAW_STATE_DIR}" ]]; then
    env_vars+=("OPENCLAW_STATE_DIR=${OPENCLAW_STATE_DIR}")
  fi

  (
    cd "${OPENCLAW_CHANNEL_DIR}"
    env "${env_vars[@]}" npx openclaw "$@"
  )
}

ensure_tls_materials() {
  if [[ -f "${OPENCLAW_CHANNEL_TLS_KEY_PATH}" && -f "${OPENCLAW_CHANNEL_TLS_CERT_PATH}" ]]; then
    return
  fi

  log "Generating self-signed cert for local HTTPS channel bridge"
  local san="${TLS_CERT_SAN:-DNS:localhost,IP:127.0.0.1}"
  local cn="${TLS_CERT_CN:-localhost}"
  openssl req \
    -x509 \
    -newkey rsa:2048 \
    -sha256 \
    -nodes \
    -days 365 \
    -subj "/CN=${cn}" \
    -addext "subjectAltName=${san}" \
    -keyout "${OPENCLAW_CHANNEL_TLS_KEY_PATH}" \
    -out "${OPENCLAW_CHANNEL_TLS_CERT_PATH}" >/dev/null 2>&1
}

ensure_node_modules() {
  if [[ -d "${OPENCLAW_CHANNEL_DIR}/node_modules" ]]; then
    return
  fi
  log "Installing openclaw-channel dependencies"
  (cd "${OPENCLAW_CHANNEL_DIR}" && npm install)
}

configure_openclaw_channel() {
  local existing_paths_json="[]"
  if existing_paths_json="$(run_openclaw config get plugins.load.paths --json 2>/dev/null)"; then
    :
  else
    existing_paths_json="[]"
  fi

  local merged_paths_json
  merged_paths_json="$(node -e '
    const current = JSON.parse(process.argv[1] || "[]");
    const pluginPath = process.argv[2];
    const list = Array.isArray(current) ? current.slice() : [];
    if (!list.includes(pluginPath)) list.push(pluginPath);
    process.stdout.write(JSON.stringify(list));
  ' "${existing_paths_json}" "${OPENCLAW_CHANNEL_DIR}")"

  run_openclaw config set plugins.load.paths "${merged_paths_json}" >/dev/null
  run_openclaw config set "plugins.entries.${OPENCLAW_CHANNEL_ID}.enabled" true >/dev/null
  run_openclaw config set "channels.${OPENCLAW_CHANNEL_ID}.token" "${OPENCLAW_CHANNEL_TOKEN}" >/dev/null
  run_openclaw config set "channels.${OPENCLAW_CHANNEL_ID}.dmSecurity" "${OPENCLAW_CHANNEL_DM_SECURITY}" >/dev/null

  if [[ -n "${OPENCLAW_CHANNEL_ALLOW_FROM}" ]]; then
    local allow_from_json
    allow_from_json="$(node -e '
      const values = process.argv[1]
        .split(",")
        .map((item) => item.trim())
        .filter(Boolean);
      process.stdout.write(JSON.stringify(values));
    ' "${OPENCLAW_CHANNEL_ALLOW_FROM}")"
    run_openclaw config set "channels.${OPENCLAW_CHANNEL_ID}.allowFrom" "${allow_from_json}" >/dev/null
  fi

  log "OpenClaw channel configured in ${OPENCLAW_CONFIG_PATH}"
}

start_openclaw_bridge() {
  local pid
  pid="$(read_pid "${OPENCLAW_BRIDGE_PID_FILE}")"
  if [[ -n "${pid}" ]] && is_pid_running "${pid}"; then
    log "openclaw bridge already running (pid=${pid})"
    return
  fi

  log "Starting openclaw-channel HTTPS bridge"
  (
    cd "${OPENCLAW_CHANNEL_DIR}"
    nohup env \
      OPENCLAW_CHANNEL_HOST="${OPENCLAW_CHANNEL_HOST}" \
      OPENCLAW_CHANNEL_PORT="${OPENCLAW_CHANNEL_PORT}" \
      OPENCLAW_CHANNEL_AUTH_TOKEN="${OPENCLAW_CHANNEL_AUTH_TOKEN}" \
      OPENCLAW_CHANNEL_TLS_KEY_PATH="${OPENCLAW_CHANNEL_TLS_KEY_PATH}" \
      OPENCLAW_CHANNEL_TLS_CERT_PATH="${OPENCLAW_CHANNEL_TLS_CERT_PATH}" \
      npm run dev:bridge >"${OPENCLAW_BRIDGE_LOG}" 2>&1 &
    echo $! >"${OPENCLAW_BRIDGE_PID_FILE}"
  )
}

start_jarvis_server() {
  local pid
  pid="$(read_pid "${JARVIS_SERVER_PID_FILE}")"
  if [[ -n "${pid}" ]] && is_pid_running "${pid}"; then
    log "jarvis server already running (pid=${pid})"
    return
  fi

  log "Starting Kotlin jarvis server"
  (
    cd "${JARVIS_REPO_ROOT}"
    nohup env \
      JARVIS_SERVER_HOST="${JARVIS_SERVER_HOST}" \
      JARVIS_SERVER_PORT="${JARVIS_SERVER_PORT}" \
      JARVIS_SERVER_AUTH_TOKEN="${JARVIS_SERVER_AUTH_TOKEN}" \
      JARVIS_SERVER_USER_ID="${JARVIS_SERVER_USER_ID}" \
      JARVIS_CHANNEL_BASE_URL="${JARVIS_CHANNEL_BASE_URL}" \
      JARVIS_CHANNEL_AUTH_TOKEN="${JARVIS_CHANNEL_AUTH_TOKEN}" \
      JARVIS_CHANNEL_CONNECT_TIMEOUT_MS="${JARVIS_CHANNEL_CONNECT_TIMEOUT_MS}" \
      JARVIS_CHANNEL_READ_TIMEOUT_MS="${JARVIS_CHANNEL_READ_TIMEOUT_MS}" \
      JARVIS_CHANNEL_CA_CERT_PATH="${JARVIS_CHANNEL_CA_CERT_PATH}" \
      JARVIS_CHANNEL_HOSTNAME_VERIFICATION="${JARVIS_CHANNEL_HOSTNAME_VERIFICATION}" \
      "${GRADLE_CMD}" :server:run >"${JARVIS_SERVER_LOG}" 2>&1 &
    echo $! >"${JARVIS_SERVER_PID_FILE}"
  )
}

wait_jarvis_health() {
  local url="http://${JARVIS_SERVER_HOST}:${JARVIS_SERVER_PORT}/health"
  for _ in $(seq 1 40); do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      log "Jarvis server is healthy at ${url}"
      return 0
    fi
    sleep 1
  done
  log "Jarvis server health check timed out (see ${JARVIS_SERVER_LOG})"
  return 1
}

stop_pid_file() {
  local file="$1"
  local name="$2"
  local pid
  pid="$(read_pid "${file}")"
  if [[ -z "${pid}" ]]; then
    log "${name} is not running"
    return
  fi

  if ! is_pid_running "${pid}"; then
    log "${name} is not running (stale pid ${pid})"
    rm -f "${file}"
    return
  fi

  log "Stopping ${name} (pid=${pid})"
  kill "${pid}" >/dev/null 2>&1 || true
  for _ in $(seq 1 15); do
    if ! is_pid_running "${pid}"; then
      rm -f "${file}"
      log "${name} stopped"
      return
    fi
    sleep 1
  done

  log "${name} did not stop in time, sending SIGKILL"
  kill -9 "${pid}" >/dev/null 2>&1 || true
  rm -f "${file}"
}

status_one() {
  local file="$1"
  local name="$2"
  local pid
  pid="$(read_pid "${file}")"
  if [[ -n "${pid}" ]] && is_pid_running "${pid}"; then
    log "${name}: running (pid=${pid})"
  else
    log "${name}: stopped"
  fi
}

ensure_common_prerequisites() {
  need_command node
  need_command npm
  need_command npx
  need_command openssl
  need_command curl
}

ensure_runtime_prerequisites() {
  ensure_common_prerequisites
  if [[ "${GRADLE_CMD}" == */* ]]; then
    [[ -x "${GRADLE_CMD}" ]] || fail "Gradle command not executable: ${GRADLE_CMD}"
  else
    need_command "${GRADLE_CMD}"
  fi
}

usage() {
  cat <<EOF
Usage: $(basename "$0") <start|stop|restart|status|configure> [config-file]

Examples:
  $(basename "$0") start
  $(basename "$0") configure ./scripts/deploy.local.env
  $(basename "$0") stop
EOF
}

case "${ACTION}" in
  start)
    require_start_config
    ensure_runtime_prerequisites
    ensure_dirs
    ensure_node_modules
    ensure_tls_materials
    configure_openclaw_channel
    start_openclaw_bridge
    start_jarvis_server
    wait_jarvis_health || true
    status_one "${OPENCLAW_BRIDGE_PID_FILE}" "openclaw bridge"
    status_one "${JARVIS_SERVER_PID_FILE}" "jarvis server"
    log "Logs: ${OPENCLAW_BRIDGE_LOG}, ${JARVIS_SERVER_LOG}"
    ;;
  configure)
    require_start_config
    ensure_common_prerequisites
    ensure_dirs
    ensure_node_modules
    ensure_tls_materials
    configure_openclaw_channel
    ;;
  stop)
    ensure_dirs
    stop_pid_file "${JARVIS_SERVER_PID_FILE}" "jarvis server"
    stop_pid_file "${OPENCLAW_BRIDGE_PID_FILE}" "openclaw bridge"
    ;;
  restart)
    ensure_dirs
    stop_pid_file "${JARVIS_SERVER_PID_FILE}" "jarvis server"
    stop_pid_file "${OPENCLAW_BRIDGE_PID_FILE}" "openclaw bridge"
    require_start_config
    ensure_runtime_prerequisites
    ensure_node_modules
    ensure_tls_materials
    configure_openclaw_channel
    start_openclaw_bridge
    start_jarvis_server
    wait_jarvis_health || true
    status_one "${OPENCLAW_BRIDGE_PID_FILE}" "openclaw bridge"
    status_one "${JARVIS_SERVER_PID_FILE}" "jarvis server"
    log "Logs: ${OPENCLAW_BRIDGE_LOG}, ${JARVIS_SERVER_LOG}"
    ;;
  status)
    ensure_dirs
    status_one "${OPENCLAW_BRIDGE_PID_FILE}" "openclaw bridge"
    status_one "${JARVIS_SERVER_PID_FILE}" "jarvis server"
    ;;
  *)
    usage
    exit 1
    ;;
esac
