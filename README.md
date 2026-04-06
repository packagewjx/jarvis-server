# Jarvis Chat Bridge

This repository contains a two-module bridge between a chat client and OpenClaw:

- `server/`: Kotlin Ktor bridge that exposes the client-facing HTTP/WebSocket protocol from `chat-server-protocol.md`
- `openclaw-channel/`: TypeScript OpenClaw channel plugin plus an HTTPS bridge API that the Kotlin server calls

## Architecture

1. The client connects to `server` over WebSocket and sends `message.send` envelopes.
2. The Kotlin bridge validates the bearer token, keeps idempotency state in memory, and calls the Channel Plugin over HTTPS.
3. The Channel Plugin normalizes OpenClaw events into a small internal event model and streams them back over SSE.
4. The Kotlin bridge maps those events into `message.ack`, `message.start`, `message.delta`, `card.replace`, `audio.output.chunk`, `audio.output.complete`, and `message.complete`.

## Project layout

- `chat-server-protocol.md`: client/server protocol contract
- `docs/client-chat-protocol-v3.md`: latest client integration guide (group onboarding flow + event sync + `event_id` cursoring)
- `server/`: Kotlin Ktor app
- `openclaw-channel/`: OpenClaw SDK plugin and standalone HTTPS bridge process

## Environment

### Kotlin bridge

- `JARVIS_SERVER_HOST` default `0.0.0.0`
- `JARVIS_SERVER_PORT` default `8080`
- `JARVIS_CHAT_RETENTION_DAYS` default `7`
- `JARVIS_JWT_SECRET` default `dev-jwt-secret-change-me`
- `JARVIS_JWT_ISSUER` default `jarvis-server`
- `JARVIS_JWT_ACCESS_TTL_SEC` default `7200`
- `JARVIS_JWT_REFRESH_TTL_SEC` default `2592000`
- `JARVIS_DB_JDBC_URL` default `jdbc:postgresql://127.0.0.1:5432/jarvis`
- `JARVIS_DB_USER` default `jarvis`
- `JARVIS_DB_PASSWORD` default `jarvis`
- `JARVIS_DB_MAX_POOL_SIZE` default `10`
- `JARVIS_XFYUN_IAT_APP_ID` optional; required to run actual IAT recognition request frames
- `JARVIS_XFYUN_IAT_API_KEY` optional; required to enable `GET /api/voice/iat-sign-url`
- `JARVIS_XFYUN_IAT_API_SECRET` optional; required to enable `GET /api/voice/iat-sign-url`
- `JARVIS_XFYUN_IAT_HOST` default `iat.cn-huabei-1.xf-yun.com`
- `JARVIS_XFYUN_IAT_PATH` default `/v1`
- `JARVIS_XFYUN_IAT_TTL_SEC` default `120`
- `JARVIS_XFYUN_IAT_RATE_LIMIT_PER_MIN` default `30`
- `JARVIS_XFYUN_IAT_DEFAULT_SAMPLE_RATE` default `16000` (allowed: `16000`/`8000`)
- `JARVIS_XFYUN_IAT_DEFAULT_DOMAIN` default `slm`
- `JARVIS_XFYUN_IAT_DEFAULT_LANGUAGE` default `zh_cn`
- `JARVIS_XFYUN_IAT_DEFAULT_ACCENT` default `mulacc`
- `JARVIS_XFYUN_IAT_AUDIO_ENCODING` default `lame`
- `JARVIS_XFYUN_TTS_APP_ID` optional; defaults to `JARVIS_XFYUN_IAT_APP_ID` when omitted
- `JARVIS_XFYUN_TTS_API_KEY` optional; defaults to `JARVIS_XFYUN_IAT_API_KEY` when omitted
- `JARVIS_XFYUN_TTS_API_SECRET` optional; defaults to `JARVIS_XFYUN_IAT_API_SECRET` when omitted
- `JARVIS_XFYUN_TTS_HOST` default `tts-api.xfyun.cn`
- `JARVIS_XFYUN_TTS_PATH` default `/v2/tts`
- `JARVIS_XFYUN_TTS_TTL_SEC` default `120`
- `JARVIS_XFYUN_TTS_RATE_LIMIT_PER_MIN` default `30`
- `JARVIS_XFYUN_TTS_DEFAULT_VCN` default `xiaoyan`
- `JARVIS_XFYUN_TTS_DEFAULT_SPEED` default `50`
- `JARVIS_XFYUN_TTS_DEFAULT_PITCH` default `50`
- `JARVIS_XFYUN_TTS_DEFAULT_VOLUME` default `50`
- `JARVIS_XFYUN_TTS_DEFAULT_SAMPLE_RATE` default `16000` (allowed: `16000`/`8000`)
- `JARVIS_XFYUN_TTS_DEFAULT_AUDIO_ENCODING` default `lame` (allowed: `lame`/`raw`/`speex`)
- `JARVIS_XFYUN_TTS_DEFAULT_TEXT_ENCODING` default `utf8` (allowed: `utf8`/`unicode`)
- `JARVIS_CHANNEL_BASE_URL` required, must start with `https://`
- `JARVIS_CHANNEL_AUTH_TOKEN` required
- `JARVIS_CHANNEL_CONNECT_TIMEOUT_MS` default `10000`
- `JARVIS_CHANNEL_READ_TIMEOUT_MS` default `60000`
- `JARVIS_CHANNEL_CA_CERT_PATH` optional PEM certificate bundle for the remote plugin
- `JARVIS_CHANNEL_HOSTNAME_VERIFICATION` default `true`

### OpenClaw channel bridge

- `OPENCLAW_CHANNEL_PORT` default `9443`
- `OPENCLAW_CHANNEL_HOST` default `0.0.0.0`
- `OPENCLAW_CHANNEL_AUTH_TOKEN` required
- `OPENCLAW_CHANNEL_TLS_KEY_PATH` required PEM private key path
- `OPENCLAW_CHANNEL_TLS_CERT_PATH` required PEM certificate path
- `OPENCLAW_CHANNEL_ID` default `jarvis-openclaw`
- `OPENCLAW_CHANNEL_LABEL` default `Jarvis OpenClaw Bridge`

## Local HTTPS development

The implementation intentionally does not support plaintext HTTP between the Kotlin bridge and the channel plugin. For local development:

1. Create a self-signed certificate for the channel plugin.
2. Point `OPENCLAW_CHANNEL_TLS_KEY_PATH` and `OPENCLAW_CHANNEL_TLS_CERT_PATH` to those PEM files.
3. Export the certificate path in `JARVIS_CHANNEL_CA_CERT_PATH` so the Kotlin bridge trusts the plugin.
4. Set `JARVIS_CHANNEL_BASE_URL=https://localhost:9443`.

## Running

### OpenClaw channel bridge

```bash
cd openclaw-channel
npm install
npm run dev:bridge
```

### Kotlin bridge

The repo does not include a Gradle wrapper yet. Use a local Gradle installation or add the wrapper once Java/Gradle are available.
Kotlin server build requires JDK 17.
The Gradle settings are configured to auto-download a matching JDK toolchain when available.
If your environment blocks toolchain download, install JDK 17 locally and ensure `java`/`javac` resolve to version 17.

```bash
gradle :server:run
```

### One-shot local deploy script

The repository now includes a local deployment script that:

1. Reads env-style config from a file
2. Ensures TLS materials exist for the HTTPS channel bridge
3. Configures OpenClaw channel `jarvis-openclaw` in `~/.openclaw/openclaw.json` (or your override)
4. Starts both `openclaw-channel` and Kotlin `server` in background

```bash
cp scripts/deploy.local.env.example scripts/deploy.local.env
# edit scripts/deploy.local.env

chmod +x scripts/deploy-local.sh
./scripts/deploy-local.sh start
```

Other actions:

```bash
./scripts/deploy-local.sh configure
./scripts/deploy-local.sh status
./scripts/deploy-local.sh stop
./scripts/deploy-local.sh restart
```

### IAT sign-url API

`server` now exposes:

`GET /api/voice/iat-sign-url`

Use an authenticated access token:

`Authorization: Bearer <access_token>`

You can obtain tokens from:

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/refresh`

Optional query parameters:

- `sampleRate` (`16000` or `8000`)
- `domain` (currently `slm`)
- `language` (currently `zh_cn`)
- `accent` (default `mulacc`)

Success response includes `data.wsUrl`, `expireAt`, `ttlSec`, and the effective session config.
`data.config.appId` is returned for client request-frame `header.app_id`.

### TTS sign-url API

`server` now exposes:

`GET /api/voice/tts-sign-url`

Use an authenticated access token:

`Authorization: Bearer <access_token>`

Optional query parameters:

- `vcn` (or alias `voice`)
- `speed` (`0..100`)
- `pitch` (`0..100`)
- `volume` (`0..100`)
- `sampleRate` (`16000` or `8000`)
- `audioEncoding` (`lame` / `raw` / `speex`)
- `textEncoding` (or alias `tte`, `utf8` / `unicode`)

Success response includes `data.wsUrl`, `expireAt`, `ttlSec`, and the effective TTS session config.
Client should use returned `data.config.appId/aue/auf/vcn/speed/pitch/volume/tte` directly in XFYun WS request frames.

### Chat events sync API

`GET /api/chat/groups/{groupId}/events/sync?after_event_id=<id>&limit=<n>`

Required headers:

- `Authorization: Bearer <access_token>`

Response fields:

- `items`: ordered chat envelopes with `event_id`
- `next_after_event_id`: newest event id in this page
- `has_more`: whether more pages are available

Client recommendation:

1. Persist `last_event_id` by `user_id + group_id`.
2. On page enter, call sync API until `has_more=false`.
3. Then connect WebSocket and deduplicate by `event_id`.

### Auth and group APIs

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `GET /api/groups/mine`
- `POST /api/groups/create` (body: `name`, creator auto-joins and receives `join_code`)
- `POST /api/groups/join` (body: `join_code`)

By default the script runs OpenClaw CLI via `npx openclaw` inside `openclaw-channel/`.
If you want to use your system-installed OpenClaw binary, set:

```bash
USE_SYSTEM_OPENCLAW=true
```

in `scripts/deploy.local.env`.

### Docker daemon mirror (China mainland)

If your Docker daemon is managed by systemd (Linux), configure mirrors in:

`/etc/docker/daemon.json`

Example:

```json
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://dockerproxy.com",
    "https://dockerhub.timeweb.cloud"
  ]
}
```

Then restart Docker daemon (`sudo systemctl restart docker`) and verify with:

```bash
docker info | rg -n "Registry Mirrors" -A 5
```

### Docker deploy (server + nginx + PostgreSQL + OpenClaw bridge)

This repo includes a container deployment path:

- Kotlin `server` process (app container)
- Nginx TLS termination (inside app container)
- PostgreSQL container for message persistence
- OpenClaw bridge container (`jarvis-openclaw-bridge`) for real model responses

Files:

- `Dockerfile`
- `docker/entrypoint.sh`
- `docker/nginx.conf.template`
- `scripts/deploy-docker.sh`
- `scripts/deploy.docker.env.example`

Setup:

```bash
cp scripts/deploy.docker.env.example scripts/deploy.docker.env
# edit scripts/deploy.docker.env
chmod +x scripts/deploy-docker.sh
```

Required envs for `start` in `deploy.docker.env`:

- `TLS_CERT_PATH` (host path to cert pem/crt)
- `TLS_KEY_PATH` (host path to key pem)
- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`
- `JARVIS_DB_JDBC_URL`, `JARVIS_DB_USER`, `JARVIS_DB_PASSWORD`
- `OPENCLAW_BRIDGE_OPENCLAW_CONFIG_PATH` (defaults to `~/.openclaw/openclaw.json`, used to generate bridge-only minimal model config)

Commands:

```bash
./scripts/deploy-docker.sh build scripts/deploy.docker.env
./scripts/deploy-docker.sh start scripts/deploy.docker.env
./scripts/deploy-docker.sh stop scripts/deploy.docker.env
```

Optional:

```bash
./scripts/deploy-docker.sh status scripts/deploy.docker.env
./scripts/deploy-docker.sh logs scripts/deploy.docker.env
```

After `start`, HTTPS endpoint is:

`https://127.0.0.1:${HOST_HTTPS_PORT}/health`

Database container defaults:

- Image: `postgres:16-alpine`
- Port mapping: `${POSTGRES_HOST_PORT}:5432`
- Data volume: `${POSTGRES_VOLUME}`

OpenClaw bridge defaults:

- Container: `jarvis-openclaw-bridge`
- Image: `node:24-bookworm`
- Generated config: `${REPO_ROOT}/.run/openclaw-bridge.json` (from `OPENCLAW_BRIDGE_OPENCLAW_CONFIG_PATH`)
- Command allowlist: `help,status,new,reset,think,verbose`

## Notes

- The Kotlin bridge is the source of truth for client-visible `message_id` values and sequence ordering.
- `message.ack.message_id` is the user message id, while `message.start/delta/complete` use assistant message id. The two ids are always different.
- `group_message_events` are persisted in PostgreSQL and replayed per `sender_user_id + group_id + client_message_id`.
- Every websocket and sync request must include `Authorization: Bearer <access_token>`.
- Clients should store and advance `event_id` to sync missing events.
- The TypeScript channel bridge now calls `openclaw agent --json` to fetch real model replies.
- You can tune bridge runtime behavior with optional env vars such as `OPENCLAW_AGENT_WORKDIR`, `OPENCLAW_AGENT_ID`, `OPENCLAW_AGENT_LOCAL`, `OPENCLAW_AGENT_TIMEOUT_MS`, `OPENCLAW_AGENT_THINKING`, and `OPENCLAW_AGENT_VERBOSE`.
- Command mode is supported via `message.send.payload.input_mode=command` and `payload.command`.
- For command safety, configure `OPENCLAW_COMMAND_ALLOWLIST` and `OPENCLAW_COMMAND_RATE_LIMIT_PER_MIN` (slash auto-detect can be controlled via `OPENCLAW_COMMAND_AUTO_DETECT_SLASH`).
