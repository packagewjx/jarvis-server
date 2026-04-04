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
- `server/`: Kotlin Ktor app
- `openclaw-channel/`: OpenClaw SDK plugin and standalone HTTPS bridge process

## Environment

### Kotlin bridge

- `JARVIS_SERVER_HOST` default `0.0.0.0`
- `JARVIS_SERVER_PORT` default `8080`
- `JARVIS_SERVER_AUTH_TOKEN` default `dev-client-token`
- `JARVIS_SERVER_USER_ID` default `dev-user`
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

By default the script runs OpenClaw CLI via `npx openclaw` inside `openclaw-channel/`.
If you want to use your system-installed OpenClaw binary, set:

```bash
USE_SYSTEM_OPENCLAW=true
```

in `scripts/deploy.local.env`.

## Notes

- The Kotlin bridge is the source of truth for client-visible `message_id` values and sequence ordering.
- Idempotency is enforced per `conversation_id + client_message_id` in memory.
- The TypeScript module includes a demo OpenClaw event source so the bridge can be exercised before wiring a real OpenClaw account.
- Replace the demo event source in `openclaw-channel/src/bridge-service.ts` with real OpenClaw event subscriptions when the target channel account is ready.
