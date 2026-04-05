# Chat Client / Server Protocol

## Goals

This protocol is designed for a chat application that supports:

- real-time text streaming
- image messages
- audio output cards
- real-time ASR input upload
- future extensible card types
- offline persistence on the client

The client implementation is already prepared to consume the protocol described in this document.

## Transport

### 1. HTTP

Use HTTP for:

- login / refresh token
- conversation list
- history pagination
- media upload
- media pre-sign URL generation
- TTS / ASR credential bootstrap if needed

### HTTP API: XFYun IAT Sign URL

Route:

- `GET /api/voice/iat-sign-url`

Auth:

- `Authorization: Bearer <token>`
- unauthenticated request returns `40101`

Query (all optional):

- `sampleRate`: `16000` or `8000`, default `16000`
- `domain`: currently fixed to `slm`
- `language`: currently fixed to `zh_cn`
- `accent`: default `mulacc`

Success response example:

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "wsUrl": "wss://iat.cn-huabei-1.xf-yun.com/v1?authorization=...&date=...&host=iat.cn-huabei-1.xf-yun.com",
    "expireAt": 1775366400000,
    "ttlSec": 120,
    "config": {
      "appId": "64ff2388",
      "sampleRate": 16000,
      "domain": "slm",
      "language": "zh_cn",
      "accent": "mulacc",
      "audioEncoding": "lame"
    }
  },
  "traceId": "req_xxx"
}
```

Error codes:

- `40001`: invalid query params
- `40101`: unauthorized
- `42901`: rate limited
- `50001`: sign generation failed
- `50002`: missing server config

### 2. WebSocket

Use a single authenticated WebSocket connection for:

- sending chat messages
- receiving ack
- receiving streamed text deltas
- receiving card replacement events
- receiving streamed audio chunks
- receiving completion / error events
- heartbeat ping/pong

## Authentication

Recommended handshake:

- client gets access token over HTTP
- client connects to WebSocket with `Authorization: Bearer <token>` header
- server validates token and binds connection to user id

## Envelope Format

All WebSocket frames should use one unified envelope.

```json
{
  "event": "message.delta",
  "trace_id": "evt_1710000000_12345",
  "group_id": "g_001",
  "message_id": "msg_srv_001",
  "client_message_id": "msg_local_001",
  "card_id": "card_text_main",
  "seq": 3,
  "timestamp": 1710000000123,
  "event_id": 1024,
  "payload": {}
}
```

## Envelope Fields

- `event`: event type string
- `trace_id`: request / event trace id
- `group_id`: group id
- `message_id`: server-side message entity id
- `client_message_id`: client request turn id used for event correlation
- `card_id`: target card id when updating a specific card
- `seq`: sequence number for ordering deltas or chunks
- `timestamp`: server event time in ms
- `event_id`: server-assigned monotonic event id for sync and dedupe
- `payload`: event-specific body

## Message ID Semantics

- `message_id` identifies one message entity.
- `client_message_id` identifies one client send turn.
- For one normal request-reply turn, apply these rules:
- `message.ack.message_id` must be user message id (for example `msg_srv_user_xxx`).
- `message.start/message.delta/card.replace/audio.output.chunk/audio.output.complete/message.complete.message_id` must be assistant message id (for example `msg_srv_assistant_xxx`).
- `message.ack.message_id` and assistant response `message_id` must not be equal.
- For events tied to `message.send`, keep the same `client_message_id` from request.
- For proactive server messages not tied to a user send, use `client_message_id: ""` and do not emit `message.ack`.

## Server Event ID Contract

| Event | `message_id` meaning | `client_message_id` |
|---|---|---|
| `message.ack` | user message id | required, copied from `message.send` |
| `message.start` | assistant message id | required if tied to `message.send`, else `""` |
| `message.delta` | same assistant id as `message.start` | same as `message.start` |
| `card.replace` | target message entity id, usually assistant id | same as message context |
| `audio.output.chunk` | same assistant id as `message.start` | same as `message.start` |
| `audio.output.complete` | same assistant id as `message.start` | same as `message.start` |
| `message.complete` | same assistant id as `message.start` | same as `message.start` |
| `message.error` | failed message entity id (assistant preferred; user on send-phase fail) | include when available |

## Event Types

### Client -> Server

#### `message.send`

Client sends one user message.

```json
{
  "event": "message.send",
  "trace_id": "evt_xxx",
  "group_id": "g_001",
  "client_message_id": "local_001",
  "message_id": "",
  "card_id": "",
  "seq": 0,
  "timestamp": 1710000000123,
  "payload": {
    "role": "user",
    "cards": [
      {
        "id": "card_text_1",
        "cardType": "text",
        "text": "你好",
        "imageUrl": "",
        "audioUrl": "",
        "audioMime": "",
        "durationMs": 0,
        "extra": null
      }
    ],
    "created_at": 1710000000123
  }
}
```

#### `ping`

Used for heartbeat.

```json
{
  "event": "ping",
  "trace_id": "ping_1710000000",
  "group_id": "g_001",
  "message_id": "",
  "client_message_id": "",
  "card_id": "",
  "seq": 0,
  "timestamp": 1710000000123,
  "payload": null
}
```

#### `audio.input.chunk`

Optional. If you want the app to upload ASR input to your own server rather than directly to a third-party ASR service.

```json
{
  "event": "audio.input.chunk",
  "trace_id": "evt_xxx",
  "group_id": "g_001",
  "message_id": "msg_voice_001",
  "client_message_id": "local_voice_001",
  "card_id": "card_audio_in_1",
  "seq": 12,
  "timestamp": 1710000000123,
  "payload": {
    "codec": "pcm_s16le",
    "sample_rate": 16000,
    "channels": 1,
    "base64": "..."
  }
}
```

#### `audio.input.final`

Marks ASR input end.

## Server -> Client

### `session.welcome`

Send after successful connection.

```json
{
  "event": "session.welcome",
  "trace_id": "evt_xxx",
  "group_id": "g_001",
  "message_id": "",
  "client_message_id": "",
  "card_id": "",
  "seq": 0,
  "timestamp": 1710000000123,
  "payload": {
    "user_id": "u_001",
    "server_time": 1710000000123
  }
}
```

### `message.ack`

Server confirms receipt of the client message.

```json
{
  "event": "message.ack",
  "trace_id": "evt_xxx",
  "group_id": "g_001",
  "message_id": "msg_srv_user_001",
  "client_message_id": "local_001",
  "card_id": "",
  "seq": 0,
  "timestamp": 1710000000123,
  "payload": {
    "accepted": true
  }
}
```

### `message.start`

Server starts one assistant response.

```json
{
  "event": "message.start",
  "trace_id": "evt_xxx",
  "group_id": "g_001",
  "message_id": "msg_srv_assistant_001",
  "client_message_id": "local_001",
  "card_id": "",
  "seq": 0,
  "timestamp": 1710000000123,
  "payload": {
    "role": "assistant"
  }
}
```

### `message.delta`

Append streamed text to one text card.

```json
{
  "event": "message.delta",
  "trace_id": "evt_xxx",
  "group_id": "g_001",
  "message_id": "msg_srv_assistant_001",
  "client_message_id": "local_001",
  "card_id": "card_text_main",
  "seq": 1,
  "timestamp": 1710000000123,
  "payload": {
    "delta": "你"
  }
}
```

### `card.replace`

Replace or create a non-streaming card.

```json
{
  "event": "card.replace",
  "trace_id": "evt_xxx",
  "group_id": "g_001",
  "message_id": "msg_srv_assistant_001",
  "client_message_id": "local_001",
  "card_id": "card_image_1",
  "seq": 0,
  "timestamp": 1710000000123,
  "payload": {
    "card_type": "image",
    "url": "https://cdn.example.com/demo.png",
    "caption": "识图结果"
  }
}
```

Supported `card_type` values for current client:

- `text`
- `image`
- `audio`

### `audio.output.chunk`

Push one streamed audio chunk.

```json
{
  "event": "audio.output.chunk",
  "trace_id": "evt_xxx",
  "group_id": "g_001",
  "message_id": "msg_srv_assistant_001",
  "client_message_id": "local_001",
  "card_id": "card_audio_out_1",
  "seq": 8,
  "timestamp": 1710000000123,
  "payload": {
    "mime": "audio/mpeg",
    "duration_ms": 320,
    "base64": "..."
  }
}
```

### `audio.output.complete`

Marks audio output end for the message.

### `message.complete`

Marks the assistant message complete.

```json
{
  "event": "message.complete",
  "trace_id": "evt_xxx",
  "group_id": "g_001",
  "message_id": "msg_srv_assistant_001",
  "client_message_id": "local_001",
  "card_id": "",
  "seq": 999,
  "timestamp": 1710000000123,
  "payload": {
    "finish_reason": "stop"
  }
}
```

### `message.error`

Failure for either send or generation.

```json
{
  "event": "message.error",
  "trace_id": "evt_xxx",
  "group_id": "g_001",
  "message_id": "msg_srv_assistant_001",
  "client_message_id": "local_001",
  "card_id": "",
  "seq": 0,
  "timestamp": 1710000000123,
  "payload": {
    "code": "GENERATION_FAILED",
    "message": "downstream llm timeout"
  }
}
```

### `pong`

Server reply for heartbeat.

## Ordering Rules

- `message.start` must arrive before `message.delta`
- `message.delta.seq` must be strictly increasing
- `audio.output.chunk.seq` must be strictly increasing per `card_id`
- `message.complete` arrives after all text deltas and after `audio.output.complete`
- client should tolerate duplicate events and ignore already applied `seq`

## Message Lifecycle

1. client sends `message.send`
2. server replies `message.ack` with user message id
3. server emits `message.start`
4. server emits zero or more `message.delta`
5. server may emit `card.replace` for image/audio/tool cards
6. server may emit zero or more `audio.output.chunk`
7. server emits `audio.output.complete`
8. server emits `message.complete`

Rule: step 2 uses user message id and steps 3-8 use assistant message id. These ids must be different.

## Media Handling

### Images

Recommended flow:

1. client uploads image over HTTP to object storage
2. server receives only metadata in `message.send`
3. image card contains CDN URL, width, height, caption if needed

### Audio Output

Current client can play audio cards when `audioUrl` becomes a playable URL or data URI.

Recommended streaming strategy for the first server version:

- use `audio/mpeg` or `audio/aac` small playable chunks
- send them as `audio.output.chunk`
- keep `card_id` stable for the full assistant turn

If you later want lower latency:

- switch to PCM or Opus
- add a native low-latency playback module on the client

## Idempotency

Server should treat `client_message_id` as idempotency key per group.

If the same `client_message_id` is received again:

- do not create a duplicate user message
- return the same `message.ack` with the same user message id
- keep the same assistant message id for the replayed stream/events
- optionally resume the existing generation stream

## Incremental Sync API

HTTP endpoint:

- `GET /api/chat/groups/{groupId}/events/sync?after_event_id=0&limit=100`

Required headers:

- `Authorization: Bearer <token>`

Response:

```json
{
  "items": [
    {
      "event": "message.ack",
      "trace_id": "trace_1",
      "group_id": "g_001",
      "message_id": "msg_srv_user_001",
      "client_message_id": "local_001",
      "card_id": "",
      "seq": 0,
      "timestamp": 1710000000123,
      "event_id": 1024,
      "payload": { "accepted": true }
    }
  ],
  "next_after_event_id": 1024,
  "has_more": false
}
```

Client sync sequence:

1. Persist `last_event_id` by `user_id + group_id`.
2. On page enter, call sync API with `after_event_id=last_event_id`.
3. Apply returned envelopes in order, dedupe by `event_id`.
4. Continue while `has_more=true`.
5. Start websocket and keep advancing `last_event_id`.

## Server State Recommendation

You should persist at least:

- groups
- messages
- message_cards
- outbound event log or stream sequence cursor

Suggested logical tables:

- `chat_groups`
- `group_memberships`
- `messages`
- `message_cards`
- `message_events`
- `media_assets`

## Error Handling

- always include machine code in `payload.code`
- always include human-readable message in `payload.message`
- send `message.error` for business failures
- close socket only for auth / unrecoverable failures

## Client Already Implemented

The current client at `pages/template/ai-chat` already supports:

- WebSocket connection management
- unified envelope parsing
- local ack mapping by `client_message_id`
- streamed text merge via `message.delta`
- card replacement via `card.replace`
- streamed audio card updates via `audio.output.chunk`
- message completion handling

## Minimum Viable Server Checklist

- [ ] authenticate WebSocket by bearer token
- [ ] support `message.send`
- [ ] emit `message.ack`
- [ ] emit `message.start`
- [ ] emit `message.delta`
- [ ] emit `message.complete`
- [ ] support `ping` / `pong`
- [ ] keep `seq` ordered per message
- [ ] persist `client_message_id` for idempotency

## Recommended Next Step

Implement the server in this exact order:

1. `message.send` -> `message.ack`
2. `message.start` -> repeated `message.delta` -> `message.complete`
3. image card support via `card.replace`
4. audio output support via `audio.output.chunk`
5. incremental events sync API
