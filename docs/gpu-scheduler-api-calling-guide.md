# GPU 资源管理 API 调用说明

## 接入状态

阶段一已在 `10.190.0.222` 以用户服务运行，但仅监听 `127.0.0.1:18120`，Agent 为观察模式，生产 profile 尚未启用。其它主机或容器必须等待管理员配置 TLS 反向代理、项目专属密钥、固定节点适配器和防火墙后才能调用。

外部启用时必须配置明确的 `GPU_MANAGER_ALLOWED_CIDRS`；不接受为了省事设置 `0.0.0.0/0`。来源不在允许网段时返回 `SOURCE_NOT_ALLOWED`。

生产调用使用管理员下发的地址，不要在代码中写死 222 IP 或 18120：

```text
AID_GPU_MANAGER_URL=https://gpu-manager.internal
AID_GPU_MANAGER_API_KEY_FILE=/run/secrets/gpu_manager_api_key
AID_GPU_MANAGER_CA_FILE=/etc/aid-gpu/ca.crt
```

除 `/health` 外，所有请求都带：

```http
Authorization: Bearer <项目专属密钥>
Content-Type: application/json
```

## 1. 查看可用任务类型

```bash
curl -sS --cacert "$AID_GPU_MANAGER_CA_FILE" "$AID_GPU_MANAGER_URL/v1/profiles" \
  -H "Authorization: Bearer $AID_GPU_MANAGER_API_KEY"
```

只返回管理员已启用的 profile。客户端必须从此结果选择，不能猜测或硬绕过禁用状态。

## 2. 提交任务

```bash
curl -sS --cacert "$AID_GPU_MANAGER_CA_FILE" -X POST "$AID_GPU_MANAGER_URL/v1/jobs" \
  -H "Authorization: Bearer $AID_GPU_MANAGER_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{
    "profile": "h3-single-fast",
    "idempotencyKey": "project-26-shot-18-v1",
    "payload": {
      "projectId": 26,
      "shotId": 18,
      "requestRef": "aid://video/981"
    }
  }'
```

优先级通常省略。普通项目只能在管理员给 profile 设置的范围内调整，不能自行抢占更高优先级。

典型 `202` 响应：

```json
{
  "id": 42,
  "source": "aid",
  "profile": "h3-single-fast",
  "status": "QUEUED",
  "priority": 90,
  "queuePosition": 1,
  "estimatedStartSeconds": 0,
  "estimatedColdStartSeconds": 600
}
```

`202` 仅表示持久化排队成功，不能据此显示“正在生成”。网络超时后使用同一个 `idempotencyKey` 重试，服务会返回原任务。

## 3. 查询任务与队列

```bash
curl -sS --cacert "$AID_GPU_MANAGER_CA_FILE" "$AID_GPU_MANAGER_URL/v1/jobs/42" \
  -H "Authorization: Bearer $AID_GPU_MANAGER_API_KEY"

curl -sS --cacert "$AID_GPU_MANAGER_CA_FILE" "$AID_GPU_MANAGER_URL/v1/queue" \
  -H "Authorization: Bearer $AID_GPU_MANAGER_API_KEY"

curl -sS --cacert "$AID_GPU_MANAGER_CA_FILE" "$AID_GPU_MANAGER_URL/v1/snapshot" \
  -H "Authorization: Bearer $AID_GPU_MANAGER_API_KEY"
```

项目密钥只能查看自己的任务。普通 snapshot 会隐藏其它项目的 payload，但保留 GPU 状态、队列和 ETA。

任务状态：

| 状态 | 含义 | 客户端动作 |
|---|---|---|
| `QUEUED` | 已排队 | 显示名次、ETA、冷启动代价 |
| `RUNNING` | Agent 已取得租约并执行 | 显示运行中，避免重复提交 |
| `SUCCEEDED` | 节点适配器成功 | 读取 `result.resultRef` 等结构化引用，再访问项目自己的结果存储 |
| `FAILED` | 适配器失败 | 显示 `error_message`，按业务规则重试 |
| `CANCELLED` | 排队任务已取消或安全结束 | 停止轮询 |
| `LOST` | 租约心跳丢失，GPU 已隔离 | 通知管理员，不自动重新提交 |

建议轮询间隔 5 秒；不要每秒高频查询。

## 4. 降低优先级

```bash
curl -sS --cacert "$AID_GPU_MANAGER_CA_FILE" -X PATCH "$AID_GPU_MANAGER_URL/v1/jobs/42/priority" \
  -H "Authorization: Bearer $AID_GPU_MANAGER_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"priority": 40}'
```

只有 `QUEUED` 状态允许修改。降低热驻留 H3 任务前，前端应显示 `estimatedColdStartSeconds` 并提醒可能重新加载模型。普通项目不能超过管理员设定上限。

## 5. 取消任务

```bash
curl -sS --cacert "$AID_GPU_MANAGER_CA_FILE" -X POST "$AID_GPU_MANAGER_URL/v1/jobs/42/cancel" \
  -H "Authorization: Bearer $AID_GPU_MANAGER_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{}'
```

排队任务立即取消。运行中的不可抢占任务只设置 `cancel_requested`，在适配器安全边界结束；客户端不能杀节点进程。

## API 清单

| 方法 | 路径 | 权限 |
|---|---|---|
| `GET` | `/health` | 无鉴权，仅健康状态 |
| `GET` | `/v1/profiles` | 项目密钥 |
| `POST` | `/v1/jobs` | 项目密钥 |
| `GET` | `/v1/jobs/{id}` | 任务所属项目 |
| `GET` | `/v1/queue` | 当前项目队列 |
| `GET` | `/v1/snapshot` | 脱敏全局资源视图 |
| `PATCH` | `/v1/jobs/{id}/priority` | 任务所属项目、仅排队中 |
| `POST` | `/v1/jobs/{id}/cancel` | 任务所属项目 |

`/v1/agent/*` 和 `/v1/admin/*` 不对业务项目开放。不要把项目密钥用于模拟 Agent，也不要在项目中保存管理员密钥。

## 错误处理

错误格式：

```json
{"error":{"code":"PROFILE_DISABLED","message":"GPU profile is disabled by an administrator"}}
```

| code | 处理方式 |
|---|---|
| `UNAUTHORIZED` | 检查密钥注入，不把密钥输出到日志 |
| `PROFILE_NOT_ALLOWED` | 刷新 `/v1/profiles`，不要猜 profile |
| `PROFILE_DISABLED` | 等待管理员完成适配器验收并启用 |
| `PRIORITY_NOT_ALLOWED` | 使用默认值或降低到管理员范围 |
| `JOB_FORBIDDEN` | 当前项目无权操作该任务 |
| `JOB_ALREADY_STARTED` | 不再改优先级；取消只能等待安全边界 |
| `JOB_NOT_FOUND` | 检查持久化的任务 ID 和环境 |
| `SCHEDULER_UNAVAILABLE` | 保持业务任务待提交并告警，禁止直连回退 |
| `SOURCE_NOT_ALLOWED` | 由管理员核对调用方网段，应用不得自行扩大白名单 |

Python 项目优先复用 [gpu_scheduler_client.py](../deploy/local/integration/gpu_scheduler_client.py)，避免每个项目重复实现鉴权、错误解析和幂等规则。
