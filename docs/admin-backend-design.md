# 微博助手后台管理系统 — 方案与接口设计

> 状态：设计完成，已按客户端实现复核  
> 日期：2026-07-25  
> 关联客户端：`Android-Auto-Api`（微博助手）

## 1. 背景与目标

当前 App 是端侧闭环的微博超话自动化工具：

- Room 保存账号、任务记录、模板文案、执行日志
- 无障碍操作微博，不收集密码
- 远程仅依赖静态文件：`comment.json` / `version.json` / APK（七牛）
- **没有业务后端、设备上报、用户体系**

### 1.1 管理目标

| 能力 | 说明 |
|------|------|
| 使用人数 | 以设备为维度统计安装/活跃 |
| 设备信息 | 型号、系统版本、App 版本、脱敏安装标识 |
| 活跃时间 | 进程启动、进入前台、任务执行等事件时间线 |
| 任务执行 | 批次结果、明细回放 |
| 关联账号与日任务 | 当前关联微博账号，以及每个账号当日签到、浏览、评论、水贴和超 like 状态 |
| 文案管理 | 发帖/评论模板 CRUD、发布、回滚 |

### 1.2 已确认约束

| 项 | 决策 |
|----|------|
| 技术栈 | NestJS + Vue 3 + Element Plus |
| App 鉴权 | **无需登录**；上报安装标识和运行信息，不采集序列号 |
| 使用权限 | **仅统计，不限制** App 使用 |
| 部署 | 自建 VPS / 云主机 |
| 本期交付 | 架构、数据模型、API、页面、分阶段路径（不写业务代码） |

---

## 2. 总体架构

```text
┌─────────────────────┐     HTTPS      ┌──────────────────────────────┐
│  Android App        │ ──────────────►│  Nginx                       │
│  - 启动心跳上报      │                │         │                    │
│  - 任务结果上报      │                │         ▼                    │
│  - 拉取文案 JSON     │◄───────────────│  NestJS API                  │
└─────────────────────┘                │  - /app/*   设备侧（无登录）  │
                                       │  - /admin/* 管理台（JWT）     │
┌─────────────────────┐                │         │                    │
│  管理后台            │ ──────────────►│         ▼                    │
│  Vue3 + Element Plus│                │  PostgreSQL                  │
└─────────────────────┘                └──────────────────────────────┘
```

### 2.1 实现阶段仓库结构（建议独立 monorepo）

```text
weibo-auto-admin/
  apps/
    api/          # NestJS + Prisma
    web/          # Vue3 + Element Plus
  docs/
    openapi.yaml
  deploy/
    docker-compose.yml
    nginx.conf
```

第一期**不改 Android 业务**；先落服务端与管理台，App 对接放在后续阶段。

### 2.2 技术选型

| 层 | 选型 | 理由 |
|----|------|------|
| API | NestJS + Prisma | 模块清晰，TS 全栈一致 |
| DB | PostgreSQL 16 | 报表 / JSON 明细友好 |
| 管理台 | Vue 3 + Vite + Element Plus + Pinia | 中文后台效率高 |
| 部署 | Docker Compose + Nginx | VPS 一键部署 |
| 管理端鉴权 | JWT（管理员账号密码） | 只保护管理台 |
| App 安装键 | `installationId` | 首次安装生成随机 UUID；服务端只存加盐哈希 |

> App 上报接口不等于可信设备认证。部署时应启用 `X-App-Key`、IP/安装键哈希限流、请求体大小限制和异常流量监控；APK 内的 Key 可被提取，只能降低滥用，**不用于限制正版用户**。

---

## 3. 领域模型

### 3.1 `devices`（使用人数统计主体）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | uuid PK | 内部 ID |
| installation_key_hash | string unique | 服务端对安装 UUID 加盐后的哈希，不保存原值 |
| model | string? | `Build.MODEL` |
| brand | string? | `Build.BRAND` |
| manufacturer | string? | 厂商 |
| android_version | string? | 系统版本 |
| sdk_int | int? | API Level |
| app_version | string? | versionName |
| app_version_code | int? | versionCode |
| first_seen_at | timestamptz | 首次上报 |
| last_seen_at | timestamptz | 最近一次有效事件的服务端接收时间 |
| last_ip_prefix | string? | 脱敏 IP 网段；原始 IP 不入业务表 |
| account_count | int | 最近一次完整账号快照中的当前关联账号数 |
| extra | jsonb | 扩展 |
| created_at / updated_at | timestamptz | |

- **总使用人数** = `devices` 去重计数，口径为“至少成功上报一次的安装实例”，不是自然人数量。  
- **今日活跃 / 7 日活跃** = 按有效事件的服务端接收时间过滤；时区统一为 `Asia/Shanghai`。  

### 3.2 `device_heartbeats`（启动时间线）

| 字段 | 说明 |
|------|------|
| id | PK |
| device_id | FK → devices |
| event_type | `PROCESS_START` / `APP_FOREGROUND` / `TASK_RUN` / `HEARTBEAT` |
| occurred_at | 客户端发生时间，仅供展示 |
| received_at | 服务端接收时间，统计以此为准 |
| app_version / model… | 快照字段 |
| payload | 原始 JSON |

建议保留 30–90 天；原始 payload 需字段白名单、大小限制，并与设备记录一同按留存策略清理。

### 3.3 `task_runs`（对齐端侧 `TaskExecutionLog`）

| 字段 | 说明 |
|------|------|
| id | uuid PK |
| device_id | FK |
| client_run_id | 端侧生成并持久化的 UUID，幂等用 |
| started_at / completed_at | 起止 |
| accounts_summary | 账号名拼接 |
| tasks_summary | 任务 label 拼接 |
| result | RUNNING / SUCCESS / PARTIAL / FAILED / CANCELLED |
| detail | 执行明细全文 |
| account_count | 参与账号数 |
| failed_account_count | 失败账号数 |
| app_version | 当时 App 版本 |
| created_at | |

唯一约束：`(device_id, client_run_id)`。重复请求必须返回首次写入的资源，不得重复计数或重复创建明细；同一键但不可变字段不一致时返回 `409 CONFLICT`。

### 3.4 `task_records`（对齐端侧 `TaskRecord`，二期）

| 字段 | 说明 |
|------|------|
| id | PK |
| task_run_id | FK |
| device_id | FK |
| account_name | 昵称（端侧伪 uid 不稳定，优先 name） |
| task_type | BROWSE / POST / SUPER_LIKE / COMMENT / CHECK_IN / ACCOUNT |
| status | SUCCESS / FAILED / … |
| message | |
| occurred_at | |

当前 Android `TaskRecord` 没有批次关联字段，不能安全地从“最近记录”反推某次 `task_run`。因此一期只上传批次汇总和 `detail`；二期先给端侧记录增加 `client_run_id`、账号名快照和发生时间，再开放明细写入接口。

### 3.5 `device_accounts` + `account_daily_statuses`（关联账号与当日状态）

账号列表和每日完成情况采用**全量状态快照**，而不是从 `task_records` 反推。这样可以在一期直接上报“该设备当前关联的全部微博账号”和本地已知的日任务状态，逐条任务明细仍保持二期。

**`device_accounts`（设备当前或历史关联账号）**

| 字段 | 说明 |
|------|------|
| id | uuid PK |
| device_id | FK → devices |
| client_account_key | 客户端账号键；当前为本地 `uid`，仅在同一设备内稳定 |
| account_name | 当前解析到的微博昵称 |
| first_seen_at / last_seen_at | 首次和最近一次出现在成功快照中的时间 |
| present | 是否在最近一次完整快照中仍存在 |
| absent_at | 从完整快照中消失的时间；不立即物理删除 |
| created_at / updated_at | |

唯一约束：`(device_id, client_account_key)`。昵称不是跨设备身份，`client_account_key` 也不用于跨设备聚合。

**`account_daily_statuses`（账号本地自然日状态快照）**

| 字段 | 说明 |
|------|------|
| id | uuid PK |
| device_account_id | FK → device_accounts |
| local_date | 客户端 `Asia/Shanghai` 自然日，`YYYY-MM-DD` |
| check_in_status | `COMPLETED` / `INCOMPLETE` / `UNKNOWN` |
| browse_completed / browse_required | 看帖完成次数和目标；未知为 `null` |
| comment_completed / comment_required | 评论完成次数和目标；未知为 `null` |
| water_post_completed | 当日水贴成功数 |
| super_like_lit / super_like_exp | 最近一次检测结果；未检测为 `null` |
| observed_at | 客户端生成快照时间 |
| received_at | 服务端接收时间 |

唯一约束：`(device_account_id, local_date)`。重复快照按 `observed_at` 较新的值覆盖；`UNKNOWN` / `null` 表示客户端未检测，绝不能统计为“未完成”。账号快照和昵称属于敏感运营数据，仅 `admin` 可查看，查看和导出必须审计；账号消失后保留 90 天，日状态默认保留 180 天。

### 3.6 `templates` + `template_versions`（文案中心）

替代静态 `https://file.qingzhou.link/yaozechuan/comment.json`。

**templates（编辑草稿 / 当前条目）**

| 字段 | 说明 |
|------|------|
| id | |
| type | `post` / `comment`（对应 fatie / pinglun） |
| content | 正文 |
| enabled | bool |
| sort_order | int |
| updated_at | |

**template_versions（发布快照）**

| 字段 | 说明 |
|------|------|
| id | |
| version | 递增整数 |
| fatie | string[] |
| pinglun | string[] |
| note | 发布说明 |
| published_by | 管理员 id |
| published_at | |
| is_current | bool |

App 拉取兼容格式：

```json
{
  "fatie": ["发帖1"],
  "pinglun": ["评论1"],
  "version": 12,
  "updatedAt": "2026-07-20T10:00:00.000Z"
}
```

`version` / `updatedAt` 为新增可选字段，旧客户端可忽略。

当前客户端由用户点击“更新文案”后才拉取并覆盖本地模板，因此第一期保持手动更新。接口应返回 `ETag`，客户端使用 `If-None-Match`，未变更时返回 `304`；不依赖短 `max-age` 触发自动刷新。

### 3.7 `admins`（仅管理台）

| 字段 | 说明 |
|------|------|
| id | |
| username | unique |
| password_hash | bcrypt / argon2 |
| role | `admin` / `viewer`（预留） |
| last_login_at | |
| created_at | |

> 「使用权限」本期**不做设备侧拦截**。若未来要做，可在 `devices` 增加 `enabled` / `expires_at`，并新增 `GET /app/v1/access`。

### 3.8 端侧可靠上报状态（Android 实现阶段）

为避免离线、进程被杀或服务端短暂失败造成数据丢失，端侧 `TaskExecutionLog` 和账号快照都通过持久化 outbox 上传。任务日志需新增：

| 字段 | 说明 |
|------|------|
| client_run_id | 创建批次时生成的 UUID，永久不变 |
| upload_state | `PENDING` / `UPLOADING` / `UPLOADED` / `RETRYABLE_FAILED` |
| upload_attempts | 已尝试次数 |
| last_upload_error | 最近一次错误摘要 |
| uploaded_at | 成功确认时间 |

账号快照也需有 `snapshot_id`、上传状态、尝试次数和最近错误；快照写入必须在本地账号状态保存成功后进行。所有 outbox 项由带网络约束的 WorkManager 上传。只有收到服务端成功或幂等成功响应才标记 `UPLOADED`；上传任务不得阻塞自动化流程。

---

## 4. API 设计

Base URL 示例：`https://api.example.com`

### 4.1 App 侧（无登录）

约定：

- `Content-Type: application/json`
- `X-App-Key: <deploy_secret>`：生产环境必填；它只用于防滥用，不代表用户登录
- 请求体 JSON，拒绝未知顶层字段；心跳最大 16 KiB、任务批次最大 128 KiB、账号快照最大 64 KiB
- 客户端时间戳使用毫秒 Unix 时间；服务端拒绝早于 2020-01-01 或晚于接收时间 24 小时的数据
- 幂等：以安装键哈希 + 业务 id 去重

#### `POST /app/v1/heartbeat`

由明确事件触发上报：进程创建为 `PROCESS_START`，主界面进入前台为 `APP_FOREGROUND`，任务结束为 `TASK_RUN`；可选 `HEARTBEAT` 仅在应用处于前台时发送。不得将保活服务启动等同于一次用户打开 App。

**Request**

```json
{
  "installationId": "5e52fdb4-e9d1-4bea-81b5-2107fcab8fa5",
  "eventType": "APP_FOREGROUND",
  "occurredAt": 1720000000000,
  "model": "M2007J17C",
  "brand": "Xiaomi",
  "manufacturer": "Xiaomi",
  "androidVersion": "12",
  "sdkInt": 31,
  "appVersion": "1.4.4",
  "appVersionCode": 2,
  "accountCount": 3
}
```

**Response**

```json
{
  "ok": true,
  "serverTime": "2026-07-20T12:00:00.000Z",
  "deviceId": "uuid..."
}
```

服务端：哈希 `installationId` 后 upsert `devices`，插入 `device_heartbeats`，以 `received_at` 更新 `last_seen_at`。请求中的 `accountCount` 仅作诊断，不覆盖 `devices.account_count`；该字段只由成功完整账号快照更新。不接收或保存序列号、IMEI、MAC 等硬件标识。

#### `POST /app/v1/task-runs`

任务批次结束时上报（对齐 `TaskExecutionLogRepository.finish`）。

**Request**

```json
{
  "installationId": "5e52fdb4-e9d1-4bea-81b5-2107fcab8fa5",
  "clientRunId": "cf7c589a-3f6e-443d-95f4-0ace0c07f70e",
  "startedAt": 1720000000000,
  "completedAt": 1720003600000,
  "accountsSummary": "账号A, 账号B",
  "tasksSummary": "浏览, 发帖",
  "result": "SUCCESS",
  "detail": "12:00:01 检查是否在首页…\n...",
  "accountCount": 2,
  "failedAccountCount": 0,
  "appVersion": "1.4.4"
}
```

**Response**：`{ "ok": true, "id": "...", "idempotent": false }`  
幂等键：`(installationKeyHash, clientRunId)`。重复成功请求返回相同 `id` 和 `idempotent: true`；不可变字段冲突返回 `409`。一期不接收 `records`，二期在端侧具备批次关联后另行定义明细接口。

#### `POST /app/v1/account-snapshots`

在以下时机创建并入队全量快照：账号刷新成功后、任务批次完成并落本地状态后；应用进入前台仅上传已有待发送快照，不凭空生成“当前账号列表”。客户端只有在**成功完整解析**账号管理页后才可声明 `isComplete: true`，避免解析失败时将服务端账号错误标记为已移除。

**Request**

```json
{
  "installationId": "5e52fdb4-e9d1-4bea-81b5-2107fcab8fa5",
  "snapshotId": "3a455802-f2d8-46eb-b590-eb3f4d32257c",
  "observedAt": 1720003600000,
  "localDate": "2026-07-25",
  "isComplete": true,
  "accounts": [
    {
      "clientAccountKey": "name_123456789",
      "accountName": "账号A",
      "checkInStatus": "COMPLETED",
      "browseCompleted": 4,
      "browseRequired": 4,
      "commentCompleted": 2,
      "commentRequired": 2,
      "waterPostCompleted": 1,
      "superLikeLit": true,
      "superLikeExp": 88
    },
    {
      "clientAccountKey": "name_987654321",
      "accountName": "账号B",
      "checkInStatus": "UNKNOWN",
      "browseCompleted": null,
      "browseRequired": null,
      "commentCompleted": null,
      "commentRequired": null,
      "waterPostCompleted": 0,
      "superLikeLit": null,
      "superLikeExp": null
    }
  ]
}
```

限制：最多 100 个账号；`accountName` 最多 64 个 Unicode 字符；同一 `snapshotId` 重复提交返回幂等成功。服务端在一个事务内 upsert 当前账号和当日状态；仅当 `isComplete: true` 时，才将该设备未出现在本次快照中的账号标记为 `present=false`。`localDate` 必须与 `observedAt` 的 `Asia/Shanghai` 日期一致。

**Response**：`{ "ok": true, "idempotent": false, "accountCount": 2 }`

#### `GET /app/v1/templates`

替代 `TemplateTextUpdater` 静态 URL。

**Response**

```json
{
  "fatie": ["发帖1", "发帖2"],
  "pinglun": ["评论1"],
  "version": 12,
  "updatedAt": "2026-07-20T10:00:00.000Z"
}
```

建议：`Cache-Control: no-cache` + `ETag`；管理台发布或回滚后递增 version。客户端每次手动更新可条件请求，未变更时服务端返回 `304`。

#### （二期可选）`GET /app/v1/config`

下发 `AutomationSettings` 默认参数。

---

### 4.2 管理台（JWT）

#### 认证

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/admin/v1/auth/login` | `{ username, password }` → `{ accessToken, expiresIn }` |
| GET | `/admin/v1/auth/me` | 当前管理员 |

Header：`Authorization: Bearer <token>`

登录接口须按账号和 IP 限流；密码采用 Argon2id 或 bcrypt，管理员创建、密码修改、文案发布和回滚写入审计日志。JWT 使用短有效期，管理台登出时由前端清除令牌；如需服务端强制失效，再引入 token version 或会话表。

#### 仪表盘

`GET /admin/v1/dashboard/summary`

```json
{
  "totalDevices": 128,
  "activeToday": 36,
  "active7d": 90,
  "taskRunsToday": 52,
  "taskSuccessRateToday": 0.86,
  "templateVersion": 12
}
```

#### 设备

| 方法 | 路径 |
|------|------|
| GET | `/admin/v1/devices?q=&model=&activeWithin=7d&page=&pageSize=` |
| GET | `/admin/v1/devices/:id` |
| GET | `/admin/v1/devices/:id/heartbeats?page=` |
| GET | `/admin/v1/devices/:id/accounts?date=YYYY-MM-DD` |
| GET | `/admin/v1/devices/:id/task-runs?page=` |

列表字段：脱敏安装标识、model、brand、appVersion、accountCount、firstSeenAt、lastSeenAt。设备详情中的“关联账号”页展示当前账号及指定日期的状态快照；账号昵称仅 `admin` 可见。原始安装 UUID、完整 IP 和硬件序列号均不在管理台展示。

`GET /admin/v1/devices/:id/accounts` 返回 `date`、`snapshotReceivedAt` 和账号数组；每项包含 `present`、`accountName`（仅 admin）、签到状态、浏览/评论完成与目标、水贴数、超 like 状态。没有该日期快照时返回空状态，不以历史日期补齐。

#### 任务

| 方法 | 路径 |
|------|------|
| GET | `/admin/v1/task-runs?deviceId=&result=&from=&to=&page=` |
| GET | `/admin/v1/task-runs/:id` | 含 detail；二期具备明细后再含 records |

#### 文案

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/admin/v1/templates` | 当前条目，可按 type 筛 |
| POST | `/admin/v1/templates` | 单条新增 |
| PUT | `/admin/v1/templates/:id` | 更新 |
| DELETE | `/admin/v1/templates/:id` | 删除 |
| POST | `/admin/v1/templates/import` | 从 comment.json 批量导入 |
| POST | `/admin/v1/templates/publish` | 打版本并设为 current |
| GET | `/admin/v1/template-versions` | 版本列表 |
| POST | `/admin/v1/template-versions/:id/rollback` | 回滚 |
| GET | `/admin/v1/templates/export` | 导出 `{fatie,pinglun}` |

#### 管理员（最小）

| 方法 | 路径 |
|------|------|
| POST | `/admin/v1/admins` |
| PUT | `/admin/v1/admins/:id/password` |

---

## 5. 管理台页面

| 菜单 | 功能 |
|------|------|
| 仪表盘 | 使用人数、今日/7 日活跃、任务成功率、最近启动 |
| 设备管理 | 列表筛选、详情、启动时间线、关联任务、关联账号及当日完成状态 |
| 任务监控 | 批次列表、结果筛选、明细回放 |
| 文案管理 | 发帖/评论 CRUD、导入导出、发布版本、回滚 |
| 系统设置 | 管理员、App-Key、心跳/日志保留天数、审计日志 |

交互要点：

- 表格分页 + 关键词（脱敏安装标识 / 型号）
- 设备详情的账号页按日期查看签到、浏览、评论、水贴和超 like；`UNKNOWN` 显示为“未检测”，不显示为失败
- 任务 detail 用等宽日志查看器
- 文案多行编辑；发布前展示条数变化

---

## 6. 与现有 Android 的衔接（实现阶段）

| 触点 | 现有代码 | 改造点 |
|------|----------|--------|
| 事件上报 | `WeiboApp.onCreate`、`MainActivity` 生命周期 | 分别上报进程创建和进入前台，避免把保活服务视为用户启动 |
| 安装标识 | 无 | 首次生成随机 UUID，保存在不随备份迁移的本地存储；服务端仅保存哈希 |
| 账号与日任务快照 | `AccountRepository.refreshAccounts`、`WeiboAccount` | 账号刷新成功、任务结束后生成全量快照并写入 outbox；保留 `UNKNOWN` 语义 |
| 文案拉取 | `TemplateTextUpdater.TEMPLATE_TEXT_URL` | 改为 `GET /app/v1/templates` |
| 任务上报 | `WeiboTaskRunner` finally / `TaskExecutionLogRepository.finish` | 先落本地批次 UUID 与待上传状态，再由 WorkManager 异步上报 |
| 网络层 | `HttpURLConnection` | 一期可沿用；异步重试使用 WorkManager，二期可迁移 OkHttp |

兼容策略：响应保持 `fatie` / `pinglun`，旧版可继续走静态文件，新版切 API。

### 客户端关键参考

- `app/src/main/java/cn/vove7/weibo/auto/WeiboApp.kt`
- `app/src/main/java/cn/vove7/weibo/auto/domain/task/TaskRunner.kt`
- `app/src/main/java/cn/vove7/weibo/auto/data/entity/TaskExecutionLog.kt`
- `app/src/main/java/cn/vove7/weibo/auto/data/update/TemplateTextUpdater.kt`
- `app/src/main/java/cn/vove7/weibo/auto/data/entity/PostTemplate.kt`
- `app/src/main/java/cn/vove7/weibo/auto/data/entity/CommentTemplate.kt`
- `comment.json`
- `README.md`

---

## 7. 部署（VPS）

### 7.1 最小 Compose

```yaml
services:
  db:
    image: postgres:16
    volumes: [pgdata:/var/lib/postgresql/data]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"]
      interval: 10s
      timeout: 5s
      retries: 5
  api:
    build: ../apps/api
    env_file: .env
    depends_on:
      db:
        condition: service_healthy
  web:
    build: ../apps/web
  nginx:
    image: nginx:alpine
    ports: ["80:80", "443:443"]
volumes:
  pgdata:
```

### 7.2 路由

- `admin.example.com` → 管理台静态资源  
- `api.example.com` → NestJS  

### 7.3 环境变量

| 变量 | 说明 |
|------|------|
| `DATABASE_URL` | Postgres 连接串 |
| `JWT_SECRET` | 管理台 JWT |
| `APP_REPORT_KEY` | App 上报防滥用密钥；生产环境必填 |
| `ADMIN_BOOTSTRAP_USER` / `PASSWORD` | 初始管理员 |
| `HEARTBEAT_RETENTION_DAYS` | 默认 90 |
| `CORS_ORIGIN` | 管理台域名 |

### 7.4 安全基线

- 管理台 HTTPS、强密码、登录限流和管理员审计  
- App 接口按 IP / 安装键哈希限流，限制请求体和日志字段  
- 定期 `pg_dump` 备份，并定期在隔离环境完成恢复演练  
- Nginx 自动续期 TLS 证书；API 配置健康检查和结构化错误日志  

---

## 8. 分阶段落地

### 阶段 0 — 本期（已完成）

- [x] 需求与约束确认  
- [x] 架构 / 模型 / API / 页面 / 部署方案  
- [x] 本文档落地到仓库  

### 阶段 1 — 服务端骨架（约 3–5 天）

1. NestJS + Prisma + PostgreSQL  
2. 建表  
3. App 四接口：事件上报 / task-runs / account-snapshots / templates；定义 DTO 校验、错误码、ETag 与幂等语义  
4. Admin 登录 + 设备/关联账号日状态/任务/文案  
5. `comment.json` 导入脚本  

### 阶段 2 — 管理台（约 3–5 天）

1. Vue3 + Element Plus  
2. 登录、仪表盘、设备、关联账号日状态、任务、文案  
3. Docker Compose 部署 VPS  
4. 假数据验收  

### 阶段 3 — App 对接（约 2–4 天）

1. 安装 UUID、进程/前台事件上报与隐私提示  
2. 文案 URL 切换，支持 ETag 的手动更新  
3. 为 `TaskExecutionLog` 和账号快照增加 ID、上传状态与 Room 迁移  
4. 账号刷新成功、任务结束后写入全量账号日状态快照；用 WorkManager 失败重试  
5. 真机验证离线、重复上传、账号解析失败、进程被杀和服务端 409 场景  

### 阶段 4 — 增强（可选）

- 为 `TaskRecord` 增加批次关联后，再上报 records 与失败率图表  
- 远程配置下发  
- 设备启用/禁用（硬限制权限）  
- APK 发布可视化  
- 多超话配置（解耦 `WeiboConsts`）  

---

## 9. 验收标准

### 设计阶段（本期）

1. 覆盖：使用人数、型号、启动时间、任务、文案  
2. 明确无 App 登录 + 仅统计不限制  
3. 接口与现有 `TaskExecutionLog` / `comment.json` 可映射  
4. 有可执行技术栈与分期路径  

### 实现阶段（未来）

1. 进程启动或进入前台后管理台出现设备事件，且 `last_seen_at` 按服务端时间更新  
2. 账号刷新成功后，设备详情可见该设备的全部当前关联账号；账号解析失败不会清空既有账号  
3. 任务结束后，设备详情可见每个账号当天签到、浏览、评论、水贴和超 like 的已知状态，`UNKNOWN` 不计为失败  
4. 离线完成任务后恢复网络，任务和账号快照各只出现一次；重复上传不重复计数  
5. 管理台发布文案后 App「更新文案」收到新内容；未变更时 ETag 返回 304  
6. 设备详情和导出中不出现原始安装 UUID、完整 IP 或硬件序列号；账号昵称仅管理员可见且查询有审计  

---

## 10. 风险

1. **安装标识**：Android 10+ 对 `Build.getSerial()` 限制严，且硬件序列号无统计必要；仅使用随机安装 UUID，并在服务端哈希。  
2. **无登录上报**：公开接口需 Key、限流、大小限制和监控；静态 Key 不能防伪造。  
3. **离线可靠性**：若没有持久化 outbox，任务完成后网络失败会永久丢失统计。  
4. **伪微博 uid**：端侧 `uid = name_hash`，跨设备不可靠；二期任务明细关联用批次 UUID + accountName 快照。  
5. **账号快照完整性**：账号解析可能失败或不完整，只有成功完整快照可以标记账号已移除；未知日任务状态不能当作失败。  
6. **隐私**：账号昵称和日任务状态属于敏感运营数据，明确告知用途和留存期限，支持按安装实例删除，且仅管理员可查询或导出。  

---

## 11. 建议的下一步

1. 新建 `weibo-auto-admin` 仓库（NestJS + Vue3 + docker-compose）  
2. 实现 DB + App 四接口 + 管理台登录/设备/关联账号状态/文案  
3. 再改 Android 做事件、账号快照、任务上报与文案切换  

**本期到此为止：只交付设计，不写业务代码。**
