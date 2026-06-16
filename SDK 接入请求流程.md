# Android SDK 接入请求流程

本文档说明宿主 App 接入 `partnersdkcore-release.aar` 时，应该如何自行发起 HTTP 请求、按什么顺序请求，以及每个接口必须携带哪些参数。新版 SDK 不负责请求业务后端或媒体网关，只负责拿到媒体连接凭证后的媒体房间连接、房间状态、成员事件和本地麦克风控制。

## 1. 接入边界

宿主 App 需要负责：

- 向业务后端或媒体网关换取 WebRTC 访问令牌。
- 调用媒体网关加入房间接口，拿到媒体服务器连接地址和媒体服务器连接令牌。
- 在加入房间返回 `404` 时，按业务策略创建媒体房间并重试加入。
- 调用媒体网关命令接口完成成员列表刷新、静音、踢出、角色设置。

SDK 负责：

- 使用 `MediaCredentials.url` 和 `MediaCredentials.token` 连接媒体房间。
- 维护 `state`、`participants` 和 `events`。
- 执行本地麦克风开关：`setMicrophoneMuted(muted)`。

## 2. 总体请求顺序

推荐接入顺序如下：

1. 宿主完成自身登录，拿到业务登录态。
2. 宿主向业务后端或媒体网关换取 WebRTC 访问令牌。
3. 宿主初始化 SDK 客户端：`RtcClient.create(context, RtcConfig())`。
4. 用户点击连接房间时，宿主调用媒体网关 `join` 接口。
5. 如果 `join` 返回 `404`，宿主调用创建媒体房间接口，然后重试 `join`。
6. 宿主从 `join` 响应中解析媒体服务器连接地址和媒体服务器连接令牌。
7. 宿主构造 `MediaCredentials`，通过 `MediaCredentialsProvider` 交给 SDK。
8. SDK 连接媒体房间，宿主订阅 SDK 状态和成员事件刷新 UI。
9. 主持端管理成员时，宿主自行调用媒体网关命令接口。
10. 页面退出时调用 `client.disconnect()` 和 `client.close()`。

## 3. 初始化 SDK

### 3.1 依赖 AAR

将 AAR 放入宿主工程：

```text
app/libs/partnersdkcore-release.aar
```

`app/build.gradle.kts` 至少需要包含：

```kotlin
dependencies {
    implementation(files("libs/partnersdkcore-release.aar"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.rtc.client)
    implementation(libs.timber)
}
```

如果宿主示例代码直接使用 OkHttp 发起请求，还需要：

```kotlin
implementation(libs.okhttp)
```

### 3.2 创建客户端

```kotlin
val client = RtcClient.create(
    context = applicationContext,
    config = RtcConfig(),
)
```

如需强制覆盖媒体服务器地址，可设置：

```kotlin
val client = RtcClient.create(
    context = applicationContext,
    config = RtcConfig(
        webrtcServiceUrl = EndpointUrl.parse("wss://media.example.com"),
    ),
)
```

一般情况下不要设置 `webrtcServiceUrl`，直接使用加入房间接口返回的媒体服务器地址。

## 4. 获取 WebRTC 访问令牌

该请求通常由业务后端封装，路径由业务方自行决定。如果直接对接媒体网关，可使用媒体网关提供的 token 接口。

### 4.1 服务端凭证换 token

- Method: `POST`
- Path: `/api/v1/auth/token`
- Header: `Content-Type: application/json`

必须参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `clientId` | `string` | 是 | 租户或应用的客户端 ID |
| `clientSecret` | `string` | 是 | 租户或应用的客户端密钥，必须放在服务端使用 |
| `sub` | `string` | 是 | 当前用户唯一标识 |

可选参数：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | `string` | 当前用户展示名 |
| `scopes` | `string[]` | 授权范围；按业务接口权限配置 |
| `ttlSeconds` | `number` | token 有效期，建议与入房有效期一致 |

请求示例：

```json
{
  "clientId": "your-client-id",
  "clientSecret": "your-client-secret",
  "sub": "user-001",
  "name": "Alice",
  "scopes": ["rooms:join", "rooms:read", "rooms:write", "rooms:moderate"],
  "ttlSeconds": 3600
}
```

响应关键字段：

```json
{
  "requestId": "request-id",
  "data": {
    "token": "access-token",
    "expiresIn": 3600,
    "tid": "tenant-id",
    "sub": "user-001",
    "scopes": ["rooms:join"]
  }
}
```

宿主必须保存 `data.token`，后续媒体网关请求统一携带：

```text
Authorization: Bearer <data.token>
```

### 4.2 用户 token 接口

- Method: `POST`
- Path: `/api/v1/auth/user/token`
- Header: `Content-Type: application/json`

必须参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `clientId` | `string` | 是 | 租户或应用的客户端 ID |
| `clientSecret` | `string` | 是 | 租户或应用的客户端密钥，必须放在服务端使用 |
| `userId` | `string` | 是 | 当前用户唯一标识 |

可选参数：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | `string` | 当前用户展示名 |
| `ttlSeconds` | `number` | token 有效期 |

响应同样读取 `data.token` 作为 WebRTC 访问令牌。

## 5. 加入媒体房间

连接 SDK 前，宿主必须先调用加入媒体房间接口，拿到媒体连接凭证。

- Method: `POST`
- Path: `/api/v1/rooms/{roomId}/join`
- Header:
  - `Authorization: Bearer <accessToken>`
  - `Content-Type: application/json`

路径参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `roomId` | `string` | 是 | 业务房间 ID，必须和 SDK `ConnectParams.roomId` 一致 |

请求 Body：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | `string` | 否 | 当前用户展示名，对应 `ConnectParams.displayName` |
| `role` | `string` | 否 | 当前用户角色：`HOST`、`SPEAKER`、`LISTENER` |
| `ttlSeconds` | `number` | 否 | 媒体连接令牌有效期，demo 默认 `3600` |
| `metadata` | `object` | 否 | 业务自定义元数据 |

请求示例：

```json
{
  "name": "Alice",
  "role": "SPEAKER",
  "ttlSeconds": 3600
}
```

响应关键字段：

```json
{
  "requestId": "request-id",
  "data": {
    "identity": "user-001",
    "role": "SPEAKER",
    "permissions": {
      "canPublish": true,
      "canSubscribe": true,
      "canPublishData": true,
      "canPublishSources": ["microphone"]
    },
    "livekit": {
      "url": "wss://media.example.com",
      "token": "media-room-token"
    }
  }
}
```

必须读取：

- `data.livekit.url`：媒体服务器连接地址。
- `data.livekit.token`：媒体服务器连接令牌。

虽然响应字段名中包含 `livekit`，但宿主业务代码中建议封装成通用媒体连接模型：

```kotlin
MediaCredentials(
    url = response.data.livekit.url,
    token = response.data.livekit.token,
)
```

## 6. 房间不存在时创建媒体房间

如果加入媒体房间接口返回 `404`，表示房间不存在或已不可加入。demo 的处理方式是先创建媒体房间，再重试加入。

- Method: `POST`
- Path: `/api/v1/rooms`
- Header:
  - `Authorization: Bearer <accessToken>`
  - `Content-Type: application/json`

请求 Body：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `roomId` | `string` | 否 | 指定业务房间 ID；如果不传由服务端生成 |
| `maxParticipants` | `number` | 否 | 最大成员数 |
| `durationSeconds` | `number` | 否 | 房间最长持续时间 |
| `expiresAt` | `string` | 否 | 房间过期时间，ISO 时间字符串 |
| `emptyTimeoutSeconds` | `number` | 否 | 空房间保留时间 |
| `metadata` | `object` | 否 | 业务自定义元数据 |

demo 最小请求：

```json
{
  "roomId": "demo-room"
}
```

创建成功后，宿主必须再次调用 `/api/v1/rooms/{roomId}/join` 获取媒体连接凭证。

## 7. 将媒体凭证交给 SDK

推荐使用 `MediaCredentialsProvider`，让 SDK 在连接流程中回调宿主的凭证获取逻辑：

```kotlin
val params = ConnectParams(
    roomId = roomId,
    displayName = userName,
    role = role,
)

client.connect(
    params = params,
    credentialsProvider = MediaCredentialsProvider { requestParams ->
        backend.joinRoom(
            accessToken = accessToken,
            roomId = requestParams.roomId,
            displayName = requestParams.displayName,
            role = requestParams.role,
            ttlSeconds = 3600,
        )
    },
)
```

也可以先请求再连接：

```kotlin
val credentials = backend.joinRoom(
    accessToken = accessToken,
    roomId = roomId,
    displayName = userName,
    role = role,
    ttlSeconds = 3600,
)

client.connect(params, credentials)
```

## 8. 刷新成员列表

成员列表接口是宿主自行请求的媒体网关命令接口，不是 SDK 方法。

- Method: `GET`
- Path: `/api/v1/commands/rooms/{roomId}/participants`
- Header: `Authorization: Bearer <accessToken>`

路径参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `roomId` | `string` | 是 | 媒体房间 ID |

Query 参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `operatorUserId` | `string` | 否 | 操作者用户 ID，用于审计或权限判断 |

响应关键字段：

```json
{
  "requestId": "request-id",
  "data": [
    {
      "identity": "user-001",
      "name": "Alice",
      "metadata": "{}",
      "joinedAt": "2026-06-16T08:00:00.000Z",
      "onlineSeconds": 120
    }
  ]
}
```

demo 会将 `data` 转换为 `List<RtcParticipant>` 刷新 UI。

## 9. 主持端命令接口

以下接口都需要主持端或具备管理权限的 token。SDK 不会代替宿主调用这些接口。

### 9.1 静音或取消静音成员

- Method: `POST`
- Path: `/api/v1/commands/rooms/{roomId}/participants/{identity}/mute`
- Header:
  - `Authorization: Bearer <accessToken>`
  - `Content-Type: application/json`

路径参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `roomId` | `string` | 是 | 媒体房间 ID |
| `identity` | `string` | 是 | 目标成员唯一标识 |

请求 Body：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `muted` | `boolean` | 是 | `true` 表示静音，`false` 表示取消静音 |
| `operatorUserId` | `string` | 否 | 操作者用户 ID |

响应关键字段：

```json
{
  "data": {
    "muted": true
  }
}
```

### 9.2 踢出成员

- Method: `POST`
- Path: `/api/v1/commands/rooms/{roomId}/participants/{identity}/kick`
- Header:
  - `Authorization: Bearer <accessToken>`
  - `Content-Type: application/json`

路径参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `roomId` | `string` | 是 | 媒体房间 ID |
| `identity` | `string` | 是 | 目标成员唯一标识 |

请求 Body：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `operatorUserId` | `string` | 否 | 操作者用户 ID |

demo 当前发送空 JSON：

```json
{}
```

响应关键字段：

```json
{
  "data": {
    "kicked": true
  }
}
```

### 9.3 设置成员角色

- Method: `POST`
- Path: `/api/v1/commands/rooms/{roomId}/participants/{identity}/setRole`
- Header:
  - `Authorization: Bearer <accessToken>`
  - `Content-Type: application/json`

路径参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `roomId` | `string` | 是 | 媒体房间 ID |
| `identity` | `string` | 是 | 目标成员唯一标识 |

请求 Body：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `role` | `string` | 是 | 目标角色：`HOST`、`SPEAKER`、`LISTENER` |
| `operatorUserId` | `string` | 否 | 操作者用户 ID |

响应关键字段：

```json
{
  "data": {
    "role": "HOST",
    "enforcedByKick": true
  }
}
```

## 10. 错误处理要求

宿主至少需要处理以下错误：

| 场景 | 建议处理 |
| --- | --- |
| token 接口返回 `401` 或 `403` | 重新登录或重新换取 WebRTC 访问令牌 |
| 加入媒体房间返回 `404` | 创建媒体房间后重试加入 |
| 加入媒体房间返回 `403` | 检查 token 权限是否包含加入房间权限 |
| 响应缺少 `data.livekit.url` | 终止连接并提示协议字段缺失 |
| 响应缺少 `data.livekit.token` | 终止连接并提示协议字段缺失 |
| SDK 状态变为 `ERROR` | 展示错误提示，并允许用户重新连接 |

## 11. 最小可用流程示例

```kotlin
val gateway = DemoRtcBackend(EndpointUrl.parse("https://webrtc.example.com"))
val accessToken = "Bearer <access-token>"
val roomId = "demo-room"
val userName = "Alice"
val role = RoomRole.SPEAKER

val client = RtcClient.create(
    context = applicationContext,
    config = RtcConfig(),
)

client.connect(
    params = ConnectParams(
        roomId = roomId,
        displayName = userName,
        role = role,
    ),
    credentialsProvider = MediaCredentialsProvider { params ->
        gateway.joinRoom(
            accessToken = accessToken,
            roomId = params.roomId,
            displayName = params.displayName,
            role = params.role,
            ttlSeconds = 3600,
        )
    },
)
```

## 12. 退出与资源释放

页面退出或用户离开房间时：

```kotlin
client.disconnect()
client.close()
```

`disconnect()` 用于离开当前媒体房间；`close()` 用于释放 SDK 内部协程资源，通常在 Activity 或页面销毁时调用。
