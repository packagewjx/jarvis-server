#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SELF_PATH="${SCRIPT_DIR}/$(basename "${BASH_SOURCE[0]}")"

ACTION="${1:-run}"
CONFIG_PATH_INPUT="${2:-}"
CONFIG_PATH=""

log() {
  echo "[certbot-aliyun] $*"
}

fail() {
  echo "[certbot-aliyun] ERROR: $*" >&2
  exit 1
}

need_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing command: $1"
}

looks_like_placeholder() {
  local value="$1"
  [[ "${value}" == replace-with-* ]]
}

resolve_path() {
  local path="$1"
  if [[ "${path}" = /* ]]; then
    echo "${path}"
  else
    echo "$(pwd)/${path}"
  fi
}

pick_default_config_path() {
  local repo_config="${REPO_ROOT}/scripts/certbot.aliyun.env"
  local system_config="/etc/jarvis/certbot.aliyun.env"
  local user_config="${HOME}/.config/jarvis/certbot.aliyun.env"

  if [[ -n "${JARVIS_CERTBOT_CONFIG_PATH:-}" ]]; then
    echo "${JARVIS_CERTBOT_CONFIG_PATH}"
    return
  fi

  if [[ -f "${system_config}" ]]; then
    echo "${system_config}"
    return
  fi

  if [[ -f "${user_config}" ]]; then
    echo "${user_config}"
    return
  fi

  echo "${repo_config}"
}

load_config() {
  if [[ -n "${CONFIG_PATH_INPUT}" ]]; then
    CONFIG_PATH="${CONFIG_PATH_INPUT}"
  else
    CONFIG_PATH="$(pick_default_config_path)"
  fi

  if [[ ! -f "${CONFIG_PATH}" ]]; then
    fail "Missing config file: ${CONFIG_PATH}. Create it from scripts/certbot.aliyun.env.example."
  fi

  # shellcheck disable=SC1090
  set -a
  source "${CONFIG_PATH}"
  set +a
}

validate_common_config() {
  DOCKER_IMAGE="${DOCKER_IMAGE:-certbot-dns-aliyun:local}"
  AUTO_BUILD_DOCKER_IMAGE="${AUTO_BUILD_DOCKER_IMAGE:-true}"
  CERTBOT_IMAGE_SOURCE="${CERTBOT_IMAGE_SOURCE:-https://github.com/justjavac/certbot-dns-aliyun.git#main}"
  CERT_DOMAIN="${CERT_DOMAIN:-api.jarvis.xense-ai.com}"
  LETSENCRYPT_STATE_DIR="${LETSENCRYPT_STATE_DIR:-/var/lib/jarvis-letsencrypt}"
  ARCH_CERT_DIR="${ARCH_CERT_DIR:-}"
  ARCH_CERTS_DIR="${ARCH_CERTS_DIR:-}"
  ARCH_PRIVATE_DIR="${ARCH_PRIVATE_DIR:-}"
  ARCH_CERT_CRT_NAME="${ARCH_CERT_CRT_NAME:-${CERT_DOMAIN}.crt}"
  ARCH_CERT_PEM_NAME="${ARCH_CERT_PEM_NAME:-${CERT_DOMAIN}.pem}"
  ARCH_KEY_NAME="${ARCH_KEY_NAME:-${CERT_DOMAIN}.key}"
  COPY_WITH_DOCKER="${COPY_WITH_DOCKER:-auto}"
  COPY_HELPER_IMAGE="${COPY_HELPER_IMAGE:-${DOCKER_IMAGE}}"
  CERTBOT_DRY_RUN="${CERTBOT_DRY_RUN:-false}"
  POST_DEPLOY_CMD="${POST_DEPLOY_CMD:-}"
  CRON_SCHEDULE="${CRON_SCHEDULE:-0 3 1 * *}"
  CRON_LOG_PATH="${CRON_LOG_PATH:-/var/log/jarvis-certbot.log}"
  LOCK_FILE="${LOCK_FILE:-/tmp/jarvis-certbot-aliyun.lock}"

  [[ -n "${ALIYUN_REGION:-}" ]] || fail "ALIYUN_REGION is required"
  [[ -n "${ALIYUN_ACCESS_KEY_ID:-}" ]] || fail "ALIYUN_ACCESS_KEY_ID is required"
  [[ -n "${ALIYUN_ACCESS_KEY_SECRET:-}" ]] || fail "ALIYUN_ACCESS_KEY_SECRET is required"
  [[ -n "${CERTBOT_EMAIL:-}" ]] || fail "CERTBOT_EMAIL is required"
  looks_like_placeholder "${ALIYUN_ACCESS_KEY_ID}" && fail "ALIYUN_ACCESS_KEY_ID is still placeholder"
  looks_like_placeholder "${ALIYUN_ACCESS_KEY_SECRET}" && fail "ALIYUN_ACCESS_KEY_SECRET is still placeholder"

  # Backward compatibility with old single directory config
  if [[ -n "${ARCH_CERT_DIR}" ]]; then
    if [[ -z "${ARCH_CERTS_DIR}" ]]; then
      ARCH_CERTS_DIR="${ARCH_CERT_DIR}"
    fi
    if [[ -z "${ARCH_PRIVATE_DIR}" ]]; then
      ARCH_PRIVATE_DIR="${ARCH_CERT_DIR}"
    fi
  fi

  [[ -n "${ARCH_CERTS_DIR}" ]] || fail "ARCH_CERTS_DIR is required (e.g. /etc/ssl/certs)"
  [[ -n "${ARCH_PRIVATE_DIR}" ]] || fail "ARCH_PRIVATE_DIR is required (e.g. /etc/ssl/private)"
}

acquire_lock() {
  if command -v flock >/dev/null 2>&1; then
    exec 9>"${LOCK_FILE}"
    flock -n 9 || fail "Another renewal process is running (lock: ${LOCK_FILE})"
  fi
}

ensure_docker_image() {
  if docker image inspect "${DOCKER_IMAGE}" >/dev/null 2>&1; then
    return
  fi

  if [[ "${AUTO_BUILD_DOCKER_IMAGE}" != "true" ]]; then
    fail "Docker image not found: ${DOCKER_IMAGE}. Set AUTO_BUILD_DOCKER_IMAGE=true or build it manually."
  fi

  log "Docker image not found, building ${DOCKER_IMAGE} from ${CERTBOT_IMAGE_SOURCE}"
  docker build -t "${DOCKER_IMAGE}" "${CERTBOT_IMAGE_SOURCE}"
}

run_certbot_in_docker() {
  need_command docker

  mkdir -p "${LETSENCRYPT_STATE_DIR}" "${ARCH_CERTS_DIR}" "${ARCH_PRIVATE_DIR}"
  ensure_docker_image

  log "Running certbot for domain ${CERT_DOMAIN} with image ${DOCKER_IMAGE}"
  docker run --rm \
    -e REGION="${ALIYUN_REGION}" \
    -e ACCESS_KEY_ID="${ALIYUN_ACCESS_KEY_ID}" \
    -e ACCESS_KEY_SECRET="${ALIYUN_ACCESS_KEY_SECRET}" \
    -e DOMAIN="${CERT_DOMAIN}" \
    -e EMAIL="${CERTBOT_EMAIL}" \
    -e CERTBOT_DRY_RUN="${CERTBOT_DRY_RUN}" \
    -v "${LETSENCRYPT_STATE_DIR}:/etc/letsencrypt" \
    --entrypoint /bin/bash \
    "${DOCKER_IMAGE}" \
    -lc '
      set -euo pipefail
      source /opt/venv/bin/activate

      aliyun configure set \
        --profile akProfile \
        --mode AK \
        --region "$REGION" \
        --access-key-id "$ACCESS_KEY_ID" \
        --access-key-secret "$ACCESS_KEY_SECRET" >/dev/null

      certbot_args=(
        certonly
        -d "$DOMAIN"
        --manual
        --preferred-challenges dns
        --manual-auth-hook "/usr/local/bin/alidns"
        --manual-cleanup-hook "/usr/local/bin/alidns clean"
        --agree-tos
        --email "$EMAIL"
        --non-interactive
        --keep-until-expiring
      )

      if [[ "${CERTBOT_DRY_RUN:-false}" == "true" ]]; then
        certbot_args+=(--dry-run)
      fi

      certbot "${certbot_args[@]}"
    '
}

copy_outputs_to_arch_dirs() {
  if [[ "${CERTBOT_DRY_RUN}" == "true" ]]; then
    log "CERTBOT_DRY_RUN=true, skipping copy to ARCH_CERTS_DIR/ARCH_PRIVATE_DIR"
    return
  fi

  local live_dir="${LETSENCRYPT_STATE_DIR}/live/${CERT_DOMAIN}"
  local src_fullchain="${live_dir}/fullchain.pem"
  local src_privkey="${live_dir}/privkey.pem"
  local dst_crt="${ARCH_CERTS_DIR}/${ARCH_CERT_CRT_NAME}"
  local dst_pem="${ARCH_CERTS_DIR}/${ARCH_CERT_PEM_NAME}"
  local dst_key="${ARCH_PRIVATE_DIR}/${ARCH_KEY_NAME}"
  local tmp_crt="${ARCH_CERTS_DIR}/.${ARCH_CERT_CRT_NAME}.tmp"
  local tmp_pem="${ARCH_CERTS_DIR}/.${ARCH_CERT_PEM_NAME}.tmp"
  local tmp_key="${ARCH_PRIVATE_DIR}/.${ARCH_KEY_NAME}.tmp"

  local use_docker_copy="false"
  case "${COPY_WITH_DOCKER}" in
    true)
      use_docker_copy="true"
      ;;
    false)
      use_docker_copy="false"
      ;;
    auto)
      if [[ ! -w "${ARCH_CERTS_DIR}" || ! -w "${ARCH_PRIVATE_DIR}" ]]; then
        use_docker_copy="true"
      fi
      ;;
    *)
      fail "Invalid COPY_WITH_DOCKER=${COPY_WITH_DOCKER}. Use auto|true|false"
      ;;
  esac

  if [[ "${use_docker_copy}" == "true" ]]; then
    need_command docker
    if ! docker image inspect "${COPY_HELPER_IMAGE}" >/dev/null 2>&1; then
      if docker image inspect "${DOCKER_IMAGE}" >/dev/null 2>&1; then
        COPY_HELPER_IMAGE="${DOCKER_IMAGE}"
      else
        fail "Copy helper image not found: ${COPY_HELPER_IMAGE}"
      fi
    fi
    log "Copying cert/key via docker helper image ${COPY_HELPER_IMAGE}"
    docker run --rm \
      -e CERT_DOMAIN="${CERT_DOMAIN}" \
      -e ARCH_CERT_CRT_NAME="${ARCH_CERT_CRT_NAME}" \
      -e ARCH_CERT_PEM_NAME="${ARCH_CERT_PEM_NAME}" \
      -e ARCH_KEY_NAME="${ARCH_KEY_NAME}" \
      -v "${LETSENCRYPT_STATE_DIR}:/src:ro" \
      -v "${ARCH_CERTS_DIR}:/dst-certs" \
      -v "${ARCH_PRIVATE_DIR}:/dst-private" \
      --entrypoint /bin/sh \
      "${COPY_HELPER_IMAGE}" \
      -c '
        set -eu
        src="/src/live/${CERT_DOMAIN}"
        [ -f "${src}/fullchain.pem" ] || { echo "missing ${src}/fullchain.pem" >&2; exit 1; }
        [ -f "${src}/privkey.pem" ] || { echo "missing ${src}/privkey.pem" >&2; exit 1; }
        install -m 0644 "${src}/fullchain.pem" "/dst-certs/.${ARCH_CERT_CRT_NAME}.tmp"
        install -m 0644 "${src}/fullchain.pem" "/dst-certs/.${ARCH_CERT_PEM_NAME}.tmp"
        install -m 0600 "${src}/privkey.pem" "/dst-private/.${ARCH_KEY_NAME}.tmp"
        mv -f "/dst-certs/.${ARCH_CERT_CRT_NAME}.tmp" "/dst-certs/${ARCH_CERT_CRT_NAME}"
        mv -f "/dst-certs/.${ARCH_CERT_PEM_NAME}.tmp" "/dst-certs/${ARCH_CERT_PEM_NAME}"
        mv -f "/dst-private/.${ARCH_KEY_NAME}.tmp" "/dst-private/${ARCH_KEY_NAME}"
      '
  else
  [[ -f "${src_fullchain}" ]] || fail "Certificate not found: ${src_fullchain}"
  [[ -f "${src_privkey}" ]] || fail "Private key not found: ${src_privkey}"
  # .crt and .pem both use fullchain to satisfy common HTTPS server expectations.
  install -m 0644 "${src_fullchain}" "${tmp_crt}"
  install -m 0644 "${src_fullchain}" "${tmp_pem}"
  install -m 0600 "${src_privkey}" "${tmp_key}"
  mv -f "${tmp_crt}" "${dst_crt}"
  mv -f "${tmp_pem}" "${dst_pem}"
  mv -f "${tmp_key}" "${dst_key}"
  fi

  log "Deployed certificate (.crt): ${dst_crt}"
  log "Deployed certificate (.pem): ${dst_pem}"
  log "Deployed private key (.key): ${dst_key}"
}

run_post_deploy_hook() {
  if [[ -z "${POST_DEPLOY_CMD}" ]]; then
    return
  fi
  log "Running POST_DEPLOY_CMD"
  bash -lc "${POST_DEPLOY_CMD}"
}

install_monthly_cron() {
  need_command crontab

  local config_abs
  config_abs="$(resolve_path "${CONFIG_PATH}")"
  local tag="# jarvis-certbot-aliyun"
  local cron_line="${CRON_SCHEDULE} ${SELF_PATH} run ${config_abs} >> ${CRON_LOG_PATH} 2>&1 ${tag}"

  {
    crontab -l 2>/dev/null | grep -Fv "${tag}" || true
    echo "${cron_line}"
  } | crontab -

  log "Installed cron job: ${cron_line}"
}

uninstall_monthly_cron() {
  need_command crontab

  local tag="# jarvis-certbot-aliyun"
  {
    crontab -l 2>/dev/null | grep -Fv "${tag}" || true
  } | crontab -

  log "Removed cron jobs with tag: ${tag}"
}

load_config
validate_common_config

case "${ACTION}" in
  build-image)
    need_command docker
    ensure_docker_image
    ;;
  run)
    acquire_lock
    run_certbot_in_docker
    copy_outputs_to_arch_dirs
    run_post_deploy_hook
    ;;
  install-cron)
    install_monthly_cron
    ;;
  uninstall-cron)
    uninstall_monthly_cron
    ;;
  *)
    fail "Unsupported action: ${ACTION}. Use build-image|run|install-cron|uninstall-cron"
    ;;
esac
