# GPU资源管理调度系统

## 目标

所有本地GPU任务先提交到统一API。调用方只选择管理员允许的任务模板（profile），不能提交Shell命令、访问GPU设备、直接调用ComfyUI或停止GPU服务。GPU节点Agent是唯一执行者。

第一阶段只部署控制面和只读/空跑Agent。确认排队、租约、保温、释放和故障恢复无误后，再收紧222上的SSH、设备与服务权限。

## 当前部署状态（2026-10-17）

- 222 用户级 Manager 与 Agent 已启用，控制面仍只监听 `127.0.0.1:18120`。
- 222 Agent 已切换为 `enforce`，只领取已通过固定适配器验收的 GPU 作业；203 Agent 仍保持 `observe`。
- 调度器会在 GPU 存在未解决安全事件时拒绝发放新租约，避免遗留进程与新任务争抢显存。
- `h3-single-fast` 与 `h3-standard-quality` 已启用；`h3-veda-fast` 因480P吞吐低于FastH3且显存占用接近满载，于2026-10-17关闭生产入口与热驻留，仅保留模型和固定适配器供回归测试。尚未验收的双卡 profile 保持禁用，普通客户端不能绕过适配器启用它。
- FastH3 已从遗留会话迁入 `comfyui-fasth3.service`，固定适配器已通过 832×480、5 秒、Fast VSA 8 步真实生成与调度 API 端到端验收。首次冷启动任务耗时约 611 秒，成功后 GPU0 保温 20 分钟。
- AID Studio 的 `fast_vsa` 业务请求已改为经本地 H3 适配器提交 `h3-single-fast` profile；任务状态、结果地址和失败状态均从调度器回传。当前 Manager 仍只监听 222 的 loopback，本机通过只承载 Manager API 的 SSH 端口转发访问 `127.0.0.1:19120`，不会直连 ComfyUI 或执行远程 GPU 命令。
- 本机调度密钥只保存在 `.runtime/secrets/gpu_manager_api_key`，不进入仓库。调度器不可达或 profile/节点不可用时 FastH3 直接失败，不回退到直连 GPU。
- H3 Standard/Turbo/参考图固定 Worker 已通过调度任务 #2 的真实验收：Turbo、2 张参考图、480P、4 秒，冷启动总耗时约 663 秒；结果正确回传 `inputImageCount=2`。本地业务入口使用同一幂等键复查时直接得到任务 #2 的成功结果，没有创建重复任务。
- H3 Veda 固定 Worker 曾通过调度任务 #4/#5 的真实验收：480P、4 秒纯文生视频冷启动约753秒、热启动约140秒、峰值显存约23.2 GiB。与FastH3对照后确认没有生产效率优势；当前 profile 已禁用、Worker不自启且不热驻留，模型和适配器保留，不支持参考图。
- `h3-dual-dense` 继续禁用：222 的两张 3090 为跨 NUMA `SYS` 链路且无 NVLink，当前也没有经过验证的双卡 H3 Worker。生产优化优先采用两个单卡 Worker 并发或组件级流水线，不把单个 Transformer 强行拆到两卡。
- AID 本地 H3 适配器已启用 fail-closed：尚未通过固定 Worker 验收的双卡模式会返回明确的“尚未完成 GPU 调度器验收”，不会触发远程 ComfyUI、模型卸载或显存抢占。
- Qwen3.8 已确认无活动槽位后停止并取消开机自启，GPU1 已释放；如需恢复，应先作为独立固定 profile 完成适配器验收。
- HTTPS/内部 CA 的程序支持已完成，但没有开放外部监听；需先确定受信 CIDR 并完成项目密钥分发。

## 安全边界

- 客户端密钥只能提交、查询、取消或调整自己尚未运行的任务。
- Agent密钥只能注册节点、领取租约、续租和报告终态。
- 管理员密钥才能提前结束模型保温。
- Agent每5秒上报真实GPU计算进程；控制面用当前租约、作业进程组和节点本地白名单判定越权，事件按节点、GPU、PID和进程启动标识去重。
- 管理员可以查看进程用户、命令行、cgroup、显存、发现时间和判定原因，并发起人工终止。Agent复核PID与启动标识后先发SIGTERM，超时才发SIGKILL；所有操作写入审计表。
- Agent只执行节点本地JSON中固定的命令数组；客户端payload不会拼接到命令行。
- 双GPU任务在同一数据库事务内一次获取两张卡，不允许部分占用。
- 租约心跳丢失后GPU进入 `QUARANTINED`，不会自动分配给下一任务。
- 运行中的任务不可抢占；取消请求只记录，具体任务在安全边界结束。

## 状态

GPU状态：`FREE`、`RUNNING`、`WARM`、`RELEASING`、`QUARANTINED`、`OFFLINE`。

任务状态：`QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`LOST`。

H3成功完成后，两张GPU进入20分钟 `WARM`。同一 `warmKey` 的H3任务获得亲和加成并复用热模型；其它任务等待保温结束或管理员请求释放。Agent完成卸载并确认后，GPU才回到 `FREE`。

## API

所有受保护请求使用：

```http
Authorization: Bearer <token>
Content-Type: application/json
```

### 客户端

- `GET /health`
- `GET /v1/profiles`
- `POST /v1/jobs`
- `GET /v1/jobs/{id}`
- `GET /v1/queue`
- `GET /v1/snapshot`
- `PATCH /v1/jobs/{id}/priority`
- `POST /v1/jobs/{id}/cancel`

提交示例：

```json
{
  "profile": "h3-dual-dense",
  "priority": 80,
  "idempotencyKey": "project-26-shot-18-v1",
  "payload": {
    "projectId": 26,
    "shotId": 18,
    "requestUrl": "https://aid.internal/jobs/h3/18"
  }
}
```

返回 `202`，包括任务ID、排队位置、预计等待和预计冷启动时间。

### 节点Agent

- `POST /v1/agent/register`
- `POST /v1/agent/heartbeat`
- `POST /v1/agent/claim`
- `POST /v1/agent/jobs/{id}/heartbeat`
- `POST /v1/agent/jobs/{id}/finish`
- `POST /v1/agent/warm/released`
- `POST /v1/agent/security/processes`
- `POST /v1/agent/security/events/{id}/termination-result`

### 管理员

- `POST /v1/admin/warm/release`
- `POST /v1/admin/gpus/reconcile`
- `PATCH /v1/admin/jobs/{id}/priority`
- `POST /v1/admin/jobs/{id}/cancel`
- `GET /v1/admin/security-events`
- `POST /v1/admin/security-events/{id}/acknowledge`
- `POST /v1/admin/security-events/{id}/terminate`
- `GET /v1/admin/services`
- `PATCH /v1/admin/services/{id}/policy`

后台通过 `/aid/gpu-security/events` 代理上述管理员接口，浏览器永远拿不到控制面的管理员Token。顶部铃铛每5秒读取活动告警；终止按钮需要 `aid:modelmonitor:manage` 权限。

节点Agent配置中的 `allowedProcessPatterns` 只适合无法继承作业进程组的热驻留服务（例如systemd启动的ComfyUI）。规则必须尽量绑定服务名和启动参数，不能使用 `python`、`cuda` 之类的宽泛表达式。

当前探针识别的是已经成功创建CUDA上下文、出现在 `nvidia-smi` 计算进程列表中的绕过行为。以后收紧 `/dev/nvidia*` 设备权限后，如需显示“被系统拒绝但尚未占用GPU”的尝试，还要再接入 auditd/eBPF 设备访问审计；这类拒绝事件不能仅靠 `nvidia-smi` 观察到。

222部署前实机扫描结果、建议优先级和阻断项见 [gpu-222-predeployment-inventory.md](gpu-222-predeployment-inventory.md)。服务清单中的适配器未验收时 `adapterReady=false`，即使管理员有管理权限也不能启用，只能预设优先级。

生产部署必须使用 `gpu_deployment_preflight.py` 生成5分钟有效的只读报告，并按 [gpu-resource-deployment-approval.md](gpu-resource-deployment-approval.md) 向管理员请求确认。预检返回退出码3表示存在阻断项，不能继续部署。

`gpu_scheduler_deploy.py` 默认只输出计划；只有同时提供 `--apply`、有效预检文件和匹配的approval token才会安装阶段一控制面。阶段一绑定loopback、Agent为observe模式、不会停止现有GPU服务。使用安装器生成的manifest可由 `gpu_scheduler_rollback.py` 恢复覆盖前文件。

普通客户端只能读取和修改自己的任务。普通快照保留GPU占用、排队与ETA，但隐藏各项目的任务payload；管理员Token才能读取完整全局任务内容。

## 启动控制面

必需环境变量：

```text
GPU_MANAGER_CLIENT_KEYS=aid=<random>,hermes=<random>,qianchuan=<random>,douyin=<random>
GPU_MANAGER_AGENT_TOKEN=<random>
GPU_MANAGER_ADMIN_TOKEN=<random>
```

密钥不要写入仓库。正式部署由反向代理提供TLS，并只允许受信网段访问。

## 权限收紧顺序

1. 控制面本地测试。
2. Agent `--dry-run` 注册真实GPU并模拟领取任务。
3. 白名单执行脚本逐个接入H3、千川和抖音。
4. ComfyUI迁入私有网络或Unix socket，取消对开发网段公开端口。
5. Hermes改用独立账号，移除 `video`、`render`、`docker`、sudo和共享用户权限。
6. GPU生产服务从 `systemctl --user` 迁移为独立系统服务。
7. 验证回退通道后，关闭Hermes到222的普通SSH。

不要在第2步以前封锁现有入口，否则调度器故障时会失去生产回退通道。

## 其它项目接入资料

- [GPU 调度器容器接入指导书](gpu-scheduler-container-integration-guide.md)
- [GPU 项目迁移修改手册](gpu-scheduler-project-migration-manual.md)
- [GPU 资源管理 API 调用说明](gpu-scheduler-api-calling-guide.md)
- [无第三方依赖 Python 客户端](../deploy/local/integration/gpu_scheduler_client.py)
