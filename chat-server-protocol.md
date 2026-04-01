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
  "conversation_id": "conv_001",
  "message_id": "msg_srv_001",
  "client_message_id": "msg_local_001",
  "card_id": "card_text_main",
  "seq": 3,
  "timestamp": 1710000000123,
  "payload": {}
}
```

## Envelope Fields

- `event`: event type string
- `trace_id`: request / event trace id
- `conversation_id`: conversation id
- `message_id`: server-side message id
- `client_message_id`: client local message id, mainly used for ack mapping
- `card_id`: target card id when updating a specific card
- `seq`: sequence number for ordering deltas or chunks
- `timestamp`: server event time in ms
- `payload`: event-specific body

## Event Types

### Client -> Server

#### `message.send`

Client sends one user message.

```json
{
  "event": "message.send",
  "trace_id": "evt_xxx",
  "conversation_id": "conv_001",
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
  "conversation_id": "conv_001",
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
  "conversation_id": "conv_001",
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
  "conversation_id": "conv_001",
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
  "conversation_id": "conv_001",
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
  "conversation_id": "conv_001",
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
  "conversation_id": "conv_001",
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
  "conversation_id": "conv_001",
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
  "conversation_id": "conv_001",
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
  "conversation_id": "conv_001",
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
  "conversation_id": "conv_001",
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
2. server replies `message.ack`
3. server emits `message.start`
4. server emits zero or more `message.delta`
5. server may emit `card.replace` for image/audio/tool cards
6. server may emit zero or more `audio.output.chunk`
7. server emits `audio.output.complete`
8. server emits `message.complete`

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

Server should treat `client_message_id` as idempotency key per conversation.

If the same `client_message_id` is received again:

- do not create a duplicate user message
- return the same `message.ack`
- optionally resume the existing generation stream

## History API Recommendation

HTTP endpoint example:

- `GET /api/chat/conversations/:id/messages?cursor=...`

Return normalized messages with cards:

```json
{
  "items": [
    {
      "id": "msg_001",
      "conversation_id": "conv_001",
      "role": "assistant",
      "created_at": 1710000000123,
      "status": "completed",
      "cards": [
        {
          "id": "card_text_main",
          "cardType": "text",
          "text": "你好",
          "imageUrl": "",
          "audioUrl": "",
          "audioMime": "",
          "durationMs": 0,
          "extra": null
        }
      ]
    }
  ],
  "next_cursor": "abc"
}
```

## Server State Recommendation

You should persist at least:

- conversations
- messages
- message_cards
- outbound event log or stream sequence cursor

Suggested logical tables:

- `conversations`
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
5. history HTTP API
