# Jarvis Chat Client Protocol v2（群组版）

## 1. 目标

- 客户端与服务端通过 `WebSocket` 实时收发消息。
- 客户端进入群聊页面时，通过 `HTTP 增量同步` 自动补齐本地缺失消息。
- 服务端按“用户 + 群组”隔离消息，避免跨用户或跨群串消息。
- 服务端仅保证最近 `retentionDays`（默认 7 天）内历史可同步。

## 2. 鉴权与用户体系

### 2.1 注册

`POST /api/auth/register`

请求体：

```json
{
  "username": "alice",
  "password": "P@ssw0rd123"
}
```

成功响应示例：

```json
{
  "access_token": "<jwt_access_token>",
  "refresh_token": "<jwt_refresh_token>",
  "expires_in": 7200,
  "user": {
    "user_id": "u_1001",
    "username": "alice"
  }
}
```

### 2.2 登录

`POST /api/auth/login`

请求体：

```json
{
  "username": "alice",
  "password": "P@ssw0rd123"
}
```

成功响应结构与注册一致。

### 2.3 刷新令牌

`POST /api/auth/refresh`

请求体：

```json
{
  "refresh_token": "<jwt_refresh_token>"
}
```

成功响应示例：

```json
{
  "access_token": "<new_jwt_access_token>",
  "refresh_token": "<new_jwt_refresh_token>",
  "expires_in": 7200
}
```

### 2.4 统一请求头

所有 `HTTP` 和 `WebSocket` 请求都必须带：

- `Authorization: Bearer <access_token>`

说明：

- `access_token` 过期后，先调用 `/api/auth/refresh`，刷新失败再走登录。
- 客户端不再传用户标识头，用户身份以 JWT 为准。

## 3. 群组能力（仅入群，不含群管理）

### 3.1 我的群组列表

`GET /api/groups/mine`

请求头：

- `Authorization: Bearer <access_token>`

成功响应示例：

```json
{
  "items": [
    {
      "group_id": "g_001",
      "name": "产品讨论群"
    },
    {
      "group_id": "g_002",
      "name": "研发测试群"
    }
  ]
}
```

### 3.2 邀请码入群

`POST /api/groups/join`

请求体：

```json
{
  "join_code": "INVITE-9X3A2"
}
```

成功响应示例：

```json
{
  "group": {
    "group_id": "g_001",
    "name": "产品讨论群"
  },
  "membership": {
    "joined_at": 1710000000000
  }
}
```

说明：

- 重复入群按幂等成功处理。
- 本协议不包含创建群、解散群、踢人、改名等管理接口。

## 4. WebSocket 协议

### 4.1 连接地址

- `wss://<host>/ws/chat`

### 4.2 Envelope（统一包体）

服务端和客户端都使用同一结构：

```json
{
  "event": "message.delta",
  "trace_id": "trace_001",
  "group_id": "g_001",
  "message_id": "msg_srv_assistant_001",
  "client_message_id": "local_001",
  "card_id": "card_text_main",
  "seq": 3,
  "timestamp": 1710000000123,
  "event_id": 1024,
  "payload": {
    "delta": "你好"
  }
}
```

字段说明：

- `event`: 事件类型
- `trace_id`: 请求链路 ID
- `group_id`: 群组 ID
- `message_id`: 消息实体 ID
- `client_message_id`: 客户端本地发送 ID（一次发送回合关联键）
- `card_id`: 卡片 ID（文本/图片/音频卡）
- `seq`: 单消息内序号（delta/chunk）
- `timestamp`: 服务端事件时间戳（ms）
- `event_id`: 群组事件游标（用于增量同步和去重）
- `payload`: 事件内容

### 4.3 事件类型

Server -> Client：

- `session.welcome`
- `message.ack`
- `message.start`
- `message.delta`
- `card.replace`
- `audio.output.chunk`
- `audio.output.complete`
- `message.complete`
- `message.error`
- `pong`

Client -> Server：

- `message.send`
- `ping`

### 4.4 `message_id` / `client_message_id` 语义（必须遵守）

1. `message.ack.message_id` 是用户消息 ID。
2. `message.start/delta/complete/...` 的 `message_id` 是助手消息 ID。
3. 用户消息 ID 和助手消息 ID 不能相同。
4. 同一次发送回合，服务端下发事件中的 `client_message_id` 必须等于请求中的 `client_message_id`。

## 5. 发送消息（Client -> Server）

### 5.1 `message.send` 示例

```json
{
  "event": "message.send",
  "trace_id": "trace_local_001",
  "group_id": "g_001",
  "message_id": "",
  "client_message_id": "local_001",
  "card_id": "",
  "seq": 0,
  "timestamp": 1710000000000,
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
    "created_at": 1710000000000
  }
}
```

### 5.2 正常回包顺序（典型）

1. `message.ack`
2. `message.start`
3. `message.delta`（0..n）
4. `card.replace` / `audio.output.chunk`（可选）
5. `audio.output.complete`（可选）
6. `message.complete`

说明：若用户不在该群，服务端返回权限错误（见错误处理章节）。

## 6. 页面进入自动补齐（HTTP 增量同步）

### 6.1 接口

`GET /api/chat/groups/{groupId}/events/sync?after_event_id=<long>&limit=<int>`

请求头：

- `Authorization: Bearer <access_token>`

参数：

- `after_event_id`: 客户端已处理的最后一个 `event_id`，首次传 `0`
- `limit`: 每页数量，建议 `100`（服务端可限制最大值）

### 6.2 响应示例

```json
{
  "items": [
    {
      "event": "message.ack",
      "trace_id": "trace_001",
      "group_id": "g_001",
      "message_id": "msg_srv_user_001",
      "client_message_id": "local_001",
      "card_id": "",
      "seq": 0,
      "timestamp": 1710000000100,
      "event_id": 2001,
      "payload": {
        "accepted": true
      }
    },
    {
      "event": "message.start",
      "trace_id": "trace_001",
      "group_id": "g_001",
      "message_id": "msg_srv_assistant_001",
      "client_message_id": "local_001",
      "card_id": "",
      "seq": 0,
      "timestamp": 1710000000200,
      "event_id": 2002,
      "payload": {
        "role": "assistant"
      }
    }
  ],
  "next_after_event_id": 2002,
  "has_more": false
}
```

### 6.3 客户端同步算法（必须）

进入群聊页面时：

1. 读取本地 `last_event_id`（按 `user_id + group_id` 维度存储）。
2. 调 `events/sync(after_event_id=last_event_id)`。
3. 按 `items` 顺序逐条应用到现有消息装配器。
4. 更新 `last_event_id = next_after_event_id`。
5. 若 `has_more=true`，继续下一页直到 `false`。
6. 然后建立或恢复 WS。
7. WS 收到事件后，若 `event_id <= last_event_id` 则忽略；否则应用并推进游标。

## 7. 幂等与去重规则

- 同一群组 `group_id` 下，`client_message_id` 作为一次发送回合幂等键。
- 客户端渲染去重以 `event_id` 为主。
- 若极端情况下缺少 `event_id`（理论不应发生），回退 `(event, message_id, seq, timestamp)` 组合去重。

## 8. 错误处理

### 8.1 `message.error` payload

```json
{
  "code": "CHANNEL_BRIDGE_FAILED",
  "message": "Bridge request failed"
}
```

常见错误码补充：

- `FORBIDDEN_GROUP`: 用户未加入目标群组或无访问权限。

客户端处理建议：

- 若能匹配到 assistant message，标记 assistant 消息失败。
- 否则回退到 `client_message_id` 关联的用户消息，标记发送失败。

### 8.2 HTTP 错误码

- `400`: 参数错误
- `401`: token 缺失、无效或过期
- `403`: 用户无权访问该群组
- `404`: 群组不存在或不可见
- `429`: 频率限制
- `500`: 服务端内部错误

## 9. 客户端本地存储建议

按 `user_id + group_id` 维度保存：

- `last_event_id: number`
- `messages: ChatMessage[]`
- 可选 `pendingClientMessageIds`（断线重连时辅助状态恢复）

## 10. 最小接入清单（给客户端开发）

1. 接入 `register/login/refresh` 三个认证接口，并统一使用 Bearer token。
2. 进入聊天前调用 `groups/mine` 和 `groups/join`（邀请码入群）。
3. WS 和消息发送请求改为携带 `group_id`。
4. 新增页面进入时 `events/sync` 增量拉取（群组维度）。
5. 本地持久化 `last_event_id`，在 WS/HTTP 共用同一套去重推进逻辑。
6. 保持 `message_id` 双 ID 语义（ack=用户消息 ID，stream=助手消息 ID）。
