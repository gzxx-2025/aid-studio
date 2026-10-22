# GPU 调度器容器接入指导书

## 适用范围

用于 AID Studio、Hermes、千川、抖音剪辑以及其它需要使用 `10.190.0.222` GPU 的容器项目。接入后，业务容器不再获得 GPU 设备，也不直接访问 SSH、Docker socket、ComfyUI 或其它私有推理端口；它只向 GPU 资源管理 API 提交管理员预先批准的任务类型。

当前阶段一控制面仅监听 222 的 `127.0.0.1:18120`，Agent 处于观察模式，全部生产 profile 均为禁用状态。因此本手册可用于代码改造，但在管理员完成固定适配器、TLS 和防火墙验收前，外部容器不能投入生产调用。

## 正确架构

```text
业务容器
  └─ HTTPS + 项目专属 API Key
       └─ GPU Resource Manager（排队、优先级、ETA、租约）
            └─ 222 Node Agent（唯一 GPU 执行者）
                 └─ 管理员固定的启动/卸载适配器
                      └─ GPU / ComfyUI / 推理服务
```

业务请求只能包含 profile、稳定的幂等键和任务数据引用。客户端不能提交 Shell 命令；节点 Agent 也不会把 payload 拼接进命令行。

## 容器必须移除的权限

- `gpus: all`、`runtime: nvidia` 和 Compose 的 GPU device reservation。
- `/dev/nvidia*`、Docker socket、宿主机 systemd socket 的映射。
- 到 222 的 SSH 密钥以及对 8188、8191、8192、18080 等私有工作端口的直连。
- 在容器内执行 `nvidia-smi`、CUDA 推理、启停模型服务或杀进程的逻辑。

录制、上传、普通 CPU 转码和 Web API 可以继续在原容器中运行；只有真正的 GPU 阶段改为提交调度任务。

## Compose 改造

可复制 [docker-compose.scheduler.example.yml](../deploy/local/integration/docker-compose.scheduler.example.yml)。核心配置如下：

```yaml
services:
  project-worker:
    environment:
      AID_GPU_MANAGER_URL: https://gpu-manager.internal
      AID_GPU_MANAGER_API_KEY_FILE: /run/secrets/gpu_manager_api_key
      AID_GPU_MANAGER_CA_FILE: /etc/aid-gpu/ca.crt
    secrets:
      - gpu_manager_api_key
    # 不再声明 gpus、runtime:nvidia 或 /dev/nvidia* 映射

secrets:
  gpu_manager_api_key:
    file: ./secrets/gpu_manager_api_key.txt
```

每个项目使用独立 API Key。密钥由管理员注入 Docker secret、Kubernetes Secret 或主机密钥库，不写入镜像、Compose 仓库、日志或命令行。内部 CA 证书以只读 config 挂载，客户端必须验证证书，禁止设置 `verify=false`。生产 URL 必须使用 TLS；控制面同时通过 `GPU_MANAGER_ALLOWED_CIDRS` 限制来源网段，并由主机防火墙再做一层限制。不要把当前 loopback 18120 直接暴露出去。

## 应用接入

依赖最少的 Python 客户端位于 [gpu_scheduler_client.py](../deploy/local/integration/gpu_scheduler_client.py)。将该文件复制为项目内部模块，或在后续把它发布为内部包。

```python
from gpu_scheduler_client import GpuSchedulerClient

client = GpuSchedulerClient.from_environment()
profiles = client.profiles()
if "h3-single-fast" not in profiles:
    raise RuntimeError("管理员尚未开放 FastH3")

job = client.submit(
    profile="h3-single-fast",
    idempotency_key="project-26-shot-18-v1",
    payload={"projectId": 26, "shotId": 18, "requestRef": "aid://video/981"},
)
```

`202` 只表示任务已进入可靠队列，不表示已经启动。保存 `job["id"]`，用 `GET /v1/jobs/{id}` 查询，向前端展示 `queuePosition`、`estimatedStartSeconds` 和 `estimatedColdStartSeconds`。

Celery 项目参考 [celery_gpu_task_example.py](../deploy/local/integration/celery_gpu_task_example.py)：提交任务后退出当前 worker，通过延时任务轮询，避免一个 Celery worker 阻塞几十分钟。稳定幂等键必须来自项目任务 ID；网络超时后使用同一个键查询或重试，不能换键重复生成。

## 结果回传

调度器负责 GPU 排队和执行状态，不是媒体存储系统。固定节点适配器可在终态返回不超过 1 MiB 的结构化 `result`（例如对象存储引用），但不能把视频本体放入结果 JSON。适配器应把媒体写入原项目对象存储/数据库，再返回引用。业务表至少保存：

- `scheduler_job_id`
- `scheduler_profile`
- `scheduler_status`
- `idempotency_key`
- `result_ref`（由项目适配器写入）
- `last_error`

## 上线顺序

1. 代码接入开发环境，仍保留旧路径但默认不调用。
2. 管理员为目标服务编写固定适配器并完成输入校验、输出回传和释放测试。
3. 在观察模式用无副作用任务验证排队、ETA、失败和取消。
4. 管理员启用单个 profile，业务侧做影子流量或少量测试。
5. 移除业务容器 GPU 权限和私有端口访问。
6. 验证回退后再收紧 222 SSH 与设备 ACL。

不能倒置第 4、5 步。尚未验收的 profile 会返回 `PROFILE_DISABLED`，不能为了绕过该错误恢复直连 GPU。

## 运维约束

- 普通项目可降低自己排队任务的优先级，但不能超过管理员给该 profile 设置的上限。
- 运行中的不可抢占任务只记录取消请求，到安全边界才结束。
- H3 热驻留期间新 H3 会优先复用；主动降低或释放可能增加约 10 分钟冷启动成本，前端必须提示。
- 调度器不可用时任务保持未提交并告警，禁止自动退回 SSH 或私有端口。
- 对于 `legacy-video-generate` 和 `avcs-gpu`，先修复其 Redis 连接故障，再做接入测试。
