# Jarvis Chat Client Protocol v3（群组引导页版）

## 1. 变更历史

| 版本 | 日期 | 变更摘要 |
| --- | --- | --- |
| v3.4 | 2026-04-07 | 新增讯飞 Super Smart-TTS 签名接口 `GET /api/voice/super-tts-sign-url`，客户端可直接使用返回 `wsUrl` 建立语音合成连接。 |
| v3.3 | 2026-04-07 | 新增讯飞声纹识别签名接口 `GET /api/voice/isv-sign-url`，客户端可直接使用返回 `requestUrl` 调用 ISV HTTP API。 |
| v3.2 | 2026-04-06 | 明确 OpenClaw 会话隔离规则：服务端按 `group_id` 映射独立 OpenClaw session（`grp_<group_id>`），不同群组上下文不共享。 |
| v3.1 | 2026-04-06 | 服务端新增 `POST /api/groups/create`（创建群并自动加入，返回 `join_code`）；群组引导页从“创建占位”升级为“可创建 + 可邀请码入群”。客户端需接入创建群接口并更新首登流程。 |
| v3 | 2026-04-05 | 新增“首登群组引导页”规范；明确 `GET /api/groups/mine` 对新用户返回空列表；补充创建群入口的客户端占位策略与上线前兼容行为；新增 TTS 签名接口对齐说明。 |
| v2 | 2026-04-05 | 引入用户+群组消息隔离、`events/sync` 增量同步、命令模式 `input_mode=command`。 |

## 2. 目标

- 客户端与服务端通过 `WebSocket` 实时收发消息。
- 客户端进入群聊页面时，通过 `HTTP 增量同步` 自动补齐本地缺失消息。
- 服务端按“用户 + 群组”隔离消息，避免跨用户或跨群串消息。
- OpenClaw 上下文按群组隔离：每个 `group_id` 会映射到独立会话（`grp_<group_id>`）。
- 客户端在注册/登录后，必须先完成“群组选择或入群”流程再进入聊天。

## 3. 鉴权与用户体系

### 3.1 注册

`POST /api/auth/register`

请求体：

```json
{
  "username": "alice",
  "password": "P@ssw0rd123"
}
```

### 3.2 登录

`POST /api/auth/login`

请求体：

```json
{
  "username": "alice",
  "password": "P@ssw0rd123"
}
```

### 3.3 刷新令牌

`POST /api/auth/refresh`

请求体：

```json
{
  "refresh_token": "<jwt_refresh_token>"
}
```

### 3.4 统一请求头

所有 `HTTP` 和 `WebSocket` 请求都必须带：

- `Authorization: Bearer <access_token>`

## 4. 群组能力

### 4.1 我的群组列表

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
    }
  ]
}
```

新用户行为（重要）：

- 新注册用户默认**不会自动创建群组**。
- 新注册用户默认**不会自动加入任何群组**。
- 因此首次调用 `GET /api/groups/mine` 时，允许返回：

```json
{
  "items": []
}
```

### 4.2 邀请码入群

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
- 失败时常见返回 `404 INVALID_JOIN_CODE`。

### 4.3 创建群入口约定（客户端）

`POST /api/groups/create`

请求体：

```json
{
  "name": "产品讨论群"
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
  },
  "join_code": "INVITE-9X3A2"
}
```

说明：

- 创建成功后，创建者自动加入该群组。
- `join_code` 用于分享给其他用户入群。
- 建议客户端在 UI 层限制群名长度（建议 1..64）。

## 5. 首登群组引导页（新增）

### 5.1 触发时机

注册或登录成功后：

1. 调用 `GET /api/groups/mine`。
2. 当 `items.length > 0`，进入“群组列表/最近会话页”。
3. 当 `items.length == 0`，进入“群组引导页”。

### 5.2 页面结构

页面名建议：`群组引导页`。

页面至少包含：

- 区块 A：`创建群组`（输入群名 + 创建按钮）
- 区块 B：`输入邀请码加入`
- 输入框：`join_code`
- 按钮：`加入群组`

### 5.3 交互流程

创建群流程：

1. 用户输入群名称（建议 1..64 字符）。
2. 调用 `POST /api/groups/create`。
3. 成功后跳转到新群聊天页，并保存 `join_code` 供用户分享。

邀请码入群流程：

1. 用户输入 `join_code`。
2. 调用 `POST /api/groups/join`。
3. 成功后再次调用 `GET /api/groups/mine` 刷新列表。
4. 自动跳转到刚加入的群聊页面。

错误处理：

- `404 INVALID_JOIN_CODE`：提示“邀请码无效或已失效”。
- `400 INVALID_GROUP_NAME`：提示“群名称不合法”。
- `401 UNAUTHORIZED`：触发刷新 token 或重新登录。
- `429`：提示稍后重试。

## 6. WebSocket 协议

### 6.1 连接地址

- `wss://<host>/ws/chat`

### 6.2 Envelope（统一包体）

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

### 6.3 事件类型

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

### 6.4 `message_id` / `client_message_id` 语义

1. `message.ack.message_id` 是用户消息 ID。
2. `message.start/delta/complete/...` 的 `message_id` 是助手消息 ID。
3. 用户消息 ID 和助手消息 ID 不能相同。
4. 同一次发送回合，服务端下发事件中的 `client_message_id` 必须等于请求中的 `client_message_id`。

## 7. 发送消息

### 7.1 `message.send` 示例

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
    "created_at": 1710000000000,
    "input_mode": "text",
    "command": null
  }
}
```

### 7.2 命令模式

`payload.input_mode`：

- `text`：普通文本对话
- `command`：执行受控命令

当前命令白名单：

- `/help`
- `/status`
- `/new` 或 `/reset`
- `/think <off|minimal|low|medium|high|xhigh>`
- `/verbose <on|off>`

## 8. 页面进入自动补齐（HTTP 增量同步）

### 8.1 接口

`GET /api/chat/groups/{groupId}/events/sync?after_event_id=<long>&limit=<int>`

### 8.2 客户端同步算法

1. 读取本地 `last_event_id`（按 `user_id + group_id` 存储）。
2. 调用 `events/sync(after_event_id=last_event_id)`。
3. 按返回顺序应用事件。
4. 更新 `last_event_id = next_after_event_id`。
5. 若 `has_more=true` 继续翻页，直到 `false`。
6. 再建立 WS 并按 `event_id` 去重推进。

## 9. 错误处理

### 9.1 `message.error` payload

```json
{
  "code": "CHANNEL_BRIDGE_FAILED",
  "message": "Bridge request failed"
}
```

常见错误码：

- `FORBIDDEN_GROUP`：用户未加入目标群组。

### 9.2 HTTP 错误码

- `400`：参数错误
- `401`：token 缺失、无效或过期
- `403`：无权限
- `404`：资源不存在（如邀请码无效）
- `429`：频率限制
- `500`：服务端内部错误

## 10. 客户端最小改造清单（v3）

1. 登录后先调用 `GET /api/groups/mine`。
2. `mine` 为空时进入“群组引导页”。
3. 群组引导页接入 `POST /api/groups/create` 和 `POST /api/groups/join`。
4. 聊天页继续使用 `events/sync + ws` 的群组维度同步。
5. 保持 `message_id` 双 ID 语义和 `event_id` 去重策略。

## 11. 语音签名接口（IAT + TTS + Super Smart-TTS + ISV）

### 11.1 IAT（语音识别）

- `GET /api/voice/iat-sign-url`
- 可选参数：`sampleRate`、`domain`、`language`、`accent`
- 客户端应使用返回的 `data.config.appId/sampleRate/domain/language/accent/audioEncoding` 作为讯飞请求参数源

### 11.2 TTS（语音合成）

- `GET /api/voice/tts-sign-url`
- 可选参数：`vcn`（或 `voice`）、`speed`、`pitch`、`volume`、`sampleRate`、`audioEncoding`、`textEncoding`（或 `tte`）
- 客户端应使用返回的 `data.config.appId/vcn/speed/pitch/volume/aue/auf/tte` 作为讯飞请求参数源

### 11.3 Super Smart-TTS（超拟人语音合成）

- `GET /api/voice/super-tts-sign-url`
- 可选参数：`vcn`（或 `voice`）、`speed`、`pitch`、`volume`、`sampleRate`、`audioEncoding`（或 `aue`）、`reg`、`rdn`、`rhy`、`scn`
- 客户端应使用返回的 `data.config.appId/vcn/speed/pitch/volume/aue/auf/reg/rdn/rhy/scn` 作为讯飞请求参数源

### 11.4 ISV（声纹识别）

- `GET /api/voice/isv-sign-url`
- 无业务参数，服务端返回已签名 HTTP URL
- 客户端应：
  1. 直接使用返回的 `data.requestUrl` 发起讯飞 ISV HTTP 请求
  2. 使用 `data.config.appId` 填充请求体 `header.app_id`
  3. 业务字段（如 `func`、`groupId`、`featureId`、音频 `payload`）按讯飞 ISV 文档自行组装
