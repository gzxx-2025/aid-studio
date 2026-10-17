# GPU 项目迁移修改手册

## 目标

把“业务程序直接获得 GPU”改为“业务程序提交允许的 GPU 作业，由 222 的节点 Agent 执行”。现有 HTTP 页面、上传、数据库和 CPU 流程不必整体搬迁，只修改 GPU 调用边界。

## 通用代码修改

1. 找到真正创建 CUDA 上下文的位置，而不是只搜索 `nvidia-smi`。
2. 将一次 GPU 调用封装成有确定输入、输出和最大运行时间的节点适配器。
3. 业务侧用调度客户端提交 `profile + payload + idempotencyKey`。
4. 在业务任务表保存调度任务 ID，并将 `QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/LOST` 映射到现有状态。
5. 前端显示排队名次、预计开始时间和冷启动代价；提交成功不能显示为“生成中”。
6. 输出继续写回原项目存储。调度器不保存视频、图片或模型返回正文。
7. 删除业务容器的 GPU device、SSH、Docker socket 和私有工作端口权限。

适配器只能使用管理员固定命令，payload 必须通过 JSON/数据库/API 读取并按字段校验。禁止 `shell=True`、字符串拼命令、客户端指定可执行文件或任意本地路径。

## 各服务迁移方案

| 服务 | 推荐 profile | 改造方式 | 验收重点 |
|---|---|---|---|
| FastH3 | `h3-single-fast` | 业务提交镜头任务；节点适配器调用固定 FastH3 worker；完成后保温 | 参考图确实装配、5 秒输出、热启动、失败回传 |
| H3 标准质量 | `h3-standard-quality` | 与 FastH3 分开适配器和 warmKey | 画质参数不被快速档污染、超时和显存释放 |
| H3 双卡 | `h3-dual-dense` | 原子申请 GPU0+GPU1，运行中不抢占 | 两卡同时获得/释放，Qwen 释放需管理员确认 |
| Veda | `h3-veda-fast` | 独立固定 workflow | 低分辨率 FAST 范围和 768P 压测门槛 |
| Qwen 3.8 | `qwen38-local-llm` | 作为受管常驻服务，不把 GPU 交给调用者 | 空闲释放策略、重新加载代价、GPU1 独占 |
| 千川 ASR | `qianchuan-asr` | 优先评估调用 203；保留 222 时按作业加载 | 音频结果一致、203 失败回退不绕过调度器 |
| 千川重混 | `qianchuan-render` | Celery CPU 编排，GPU 步骤提交统一队列 | worker 不再挂 GPU，任务断点续跑 |
| 抖音录制/剪辑 | `douyin-gpu-processing` | 录制常驻，只有 GPU 后处理排队 | 录制不中断、后处理可延迟执行 |
| 字幕/修复/替换 | 对应现有 profile | API 常驻，推理子步骤排队 | CPU 阶段继续运行，GPU 阶段无直连 |
| TTS | `omnivoice-tts-local` | 默认调用 203 API；222 只作管理员启用的回退档 | 可回退测试、222 不常驻显存 |

FastH3 当前只完成固定适配器空跑，约束为 480P、1–5 秒、文生视频。参考图 FastH3 必须使用另一个经过画质与一致性验收的 profile，不能把参考素材静默丢弃后按文生视频执行。

`legacy-video-generate` 当前无法解析 `redis:6379`，`avcs-gpu` 当前无法连接 `host.docker.internal:6379`；这两个服务在故障修复前保持禁用，不能用调度接入掩盖原有队列故障。

## 优先级修改

业务调用通常不传 `priority`，使用管理员默认值。若允许用户“稍后执行”，只可把优先级下调。222 的生产 profile 已配置 `maxClientPriority = defaultPriority`，因此普通项目不能通过传入 1000 抢占其它项目；只有后台管理员接口能越过上限。

遇到 `PRIORITY_NOT_ALLOWED` 时，前端显示管理员允许的范围，不要循环重试。

## 数据库建议

```sql
ALTER TABLE project_task ADD COLUMN scheduler_job_id BIGINT NULL;
ALTER TABLE project_task ADD COLUMN scheduler_profile VARCHAR(64) NULL;
ALTER TABLE project_task ADD COLUMN scheduler_status VARCHAR(24) NULL;
ALTER TABLE project_task ADD COLUMN scheduler_idempotency_key VARCHAR(160) NULL;
CREATE UNIQUE INDEX uk_project_scheduler_key
  ON project_task(scheduler_idempotency_key);
```

字段名按各项目规范调整。不要把 API Key 或完整提示词写入调度事件。

## 回退方案

应用部署时保留“旧调用实现”的代码开关，但生产开关只能由管理员控制。回退顺序是：停止新提交、等待运行任务到安全边界、关闭 profile、恢复原服务和网络权限。调度异常时不得由应用自动启用旧直连路径。

## 单项目验收清单

- 相同业务任务重试两次只产生一个调度作业。
- 禁用 profile 时明确失败，没有静默结束或直连回退。
- 排队、运行、成功、失败、取消、租约丢失均能映射到业务状态。
- 前端显示真实 ETA，并区分“已排队”和“正在生成”。
- 运行任务不会被另一个项目停止；双卡作业不会只占一张卡。
- 适配器不会接受命令、任意路径、SSH 地址或服务控制参数。
- 容器检查结果中没有 GPU device、Docker socket 和 222 SSH 凭据。
- 输出与迁移前基线一致；H3 参考图、分辨率、帧数、步数和音频选项逐项核对。
- 禁止路径测试会产生管理员告警，但观察阶段不会自动杀生产进程。
