# Repository Guidelines

## Project Structure & Module Organization

- `server/` contains the Kotlin bridge server. Main code lives under `server/src/main/kotlin/jarvis/server`, grouped by `config`, `gateway`, `model`, `service`, and `util`.
- `server/src/test/kotlin` holds Kotlin tests.
- `openclaw-channel/` contains the TypeScript OpenClaw channel plugin and standalone HTTPS bridge. Source files are in `openclaw-channel/src`, compiled output goes to `openclaw-channel/dist`.
- `chat-server-protocol.md` is the client/server contract. Keep protocol DTOs and event mapping aligned with this file.
- `docs/client-chat-protocol-v3.md` is the client integration protocol source of truth. Whenever API/protocol fields, routes, auth flow, voice capabilities, or error codes change, you must update this file in the same change.
- For every update to `docs/client-chat-protocol-v3.md`, add a clear update note in Chapter 1 ("变更历史") describing what changed and what client actions are required.
- `README.md` documents runtime configuration, especially HTTPS-only service-to-service settings.

## Build, Test, and Development Commands

- `cd openclaw-channel && npm install` — install plugin dependencies.
- `cd openclaw-channel && npm run build` — compile the TypeScript plugin into `dist/`.
- `cd openclaw-channel && npm test` — run Vitest tests for bridge behavior.
- `cd openclaw-channel && npm run dev:bridge` — start the HTTPS plugin bridge locally.
- `gradle :server:run` — run the Kotlin Ktor server.
- `gradle :server:test` — run Kotlin tests once Java/Gradle are available locally.

## Coding Style & Naming Conventions

- Use 4 spaces in Kotlin and 2 spaces in TypeScript.
- Prefer descriptive names: `ChatBridgeService`, `HttpsSseChannelGateway`, `BridgeService`.
- Keep protocol field names consistent with the wire format; use Kotlin serialization annotations instead of renaming payload keys ad hoc.
- Follow existing file naming: Kotlin uses `PascalCase.kt`; TypeScript source uses `kebab-case.ts` or focused module names like `bridge-service.ts`.
- Avoid plaintext HTTP for bridge traffic; all new bridge code must preserve HTTPS-only behavior.

## Testing Guidelines

- TypeScript tests use Vitest; place tests next to the module as `*.test.ts`.
- Kotlin tests use JUnit 5 and `kotlin.test`; mirror production package paths under `server/src/test/kotlin`.
- Cover protocol ordering, idempotency, auth rejection, and HTTPS validation whenever bridge behavior changes.

## Commit & Pull Request Guidelines

- No Git history is available in this workspace, so use concise imperative commit messages such as `Add SSE bridge event mapping`.
- Keep commits focused by module when possible (`server:` or `openclaw-channel:` prefixes are helpful).
- PRs should include: purpose, key protocol or config changes, test evidence (`npm test`, `gradle :server:test`), and screenshots/log snippets if runtime behavior changes.

## Security & Configuration Tips

- Never commit real bearer tokens, OpenClaw credentials, or TLS private keys.
- For local development, use self-signed certs and set `JARVIS_CHANNEL_CA_CERT_PATH` so the Kotlin bridge trusts the plugin certificate.
