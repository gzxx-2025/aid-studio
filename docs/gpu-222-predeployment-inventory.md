# 10.190.0.222 GPU服务部署前清单

扫描时间：2026-10-16 13:20 MDT（19:20 UTC）
扫描方式：只读 SSH、`nvidia-smi`、systemd、Docker设备授权、监听端口和代码依赖特征；没有停止或重启服务。

## 当前资源事实

| GPU | 当前显存 | 连续30秒利用率 | 当前CUDA进程 |
|---|---:|---:|---|
| RTX 3090 GPU0 | 3292 / 24576 MiB | 0% | ComfyUI 8188、FastH3 8191、Veda 8192、千川ASR 7873 |
| RTX 3090 GPU1 | 24017 / 24576 MiB | 0% | Qwen3.8-27B llama-server 18080 |

GPU1虽然计算利用率为0%，但Qwen已经占用约97.7%显存。双卡H3开始前必须有计划地卸载Qwen和GPU0上的所有常驻模型，不能直接抢占。

## 管理员队列清单

所有条目目前均为“待适配、默认禁用”。管理员可以预先修改默认优先级；固定执行适配器验收后才允许启用。

| 默认优先级 | 服务/工作负载 | GPU | 当前状态 | 控制入口 | 建议 |
|---:|---|---|---|---|---|
| 95 | MiniMax H3 双卡密集模式 | 0+1 | 需释放现有服务 | 调度任务 | 最高优先级，运行中不可抢占，结束后保温20分钟 |
| 90 | FastH3 快速预览 | 0 | 运行但未受管 | 端口8191手工进程 | 首批迁入systemd和调度器 |
| 85 | H3标准质量 | 0 | 运行 | `comfyui.service` / 8188 | 保温20分钟 |
| 80 | H3 Veda FAST | 0 | 运行 | `comfyui-veda.service` / 8192 | 低分辨率FAST档 |
| 70 | Qwen3.8-27B本地推理 | 1 | 常驻，24010 MiB | `qwen38-gpu1.service` / 18080 | 双卡H3前明确提示卸载和冷启动代价 |
| 65 | 千川剧情/语音分析ASR | 0 | 常驻，2438 MiB | `qianchuan-story-analyzer.service` / 7873 | 改为按任务租约；继续评估迁移203 |
| 60 | 千川视频分析与重混 | 0 | 容器有GPU权限、当前空闲 | `/home/neo/wm-build/docker-compose.deploy.yml` | Celery任务改走统一API |
| 55 | 字幕清理与修复 | 0 | API常驻、GPU当前空闲 | `subtitle-cleaner.service` / 7872 | 仅GPU步骤入队 |
| 50 | 视频商品替换 | 0 | GPU能力待任务验证 | `video-product-replacer-poc.service` | 推理步骤入队 |
| 50 | 视频重混Worker | 0 | GPU能力待任务验证 | `video-remix-studio-worker.service` | Celery GPU步骤入队 |
| 45 | 抖音录像/GPU后处理 | 0 | systemd和容器均有GPU权限 | 8877、8899 | 录制API常驻，GPU后处理入队 |
| 45 | 旧版视频生成GPU队列 | 0 | 两个容器拥有全部GPU | `/mnt/disk3/video-generate/docker-compose.yml` | 停用独立gpu_queue_daemon后接入统一队列 |
| 40 | AVCS GPU Worker | 0 | 容器有GPU0权限 | `/mnt/disk3/AVCS/docker-compose.yml` | `gpu_queue_0`改走统一API |
| 35 | OmniVoice本地TTS | 0 | 当前停用 | 7861、7862 | 222档保持禁用，优先使用203 API |
| 30 | 电商视觉工坊 | 0 | GPU代码存在，实际调用未确认 | `ecom-studio.service` / 8077 | 完成真实任务追踪后再开放 |
| 30 | 短剧推广工作室 | 0 | GPU代码存在，实际调用未确认 | `short-drama-promo-studio.service` | 完成真实任务追踪后再开放 |
| 20 | 播客闪视频 | 0 | 启动失败并循环重启 | `podcast-flash-video.service` / 8092 | 先修复服务，保持禁用 |

## 部署前阻断项

1. FastH3 8191并非由当前显示的systemd unit管理，而是遗留会话进程；其父进程命令行包含明文凭据。部署前必须迁入固定服务、移除明文启动方式并轮换相关凭据。
2. 六个运行中容器拥有NVIDIA设备权限，能绕过调度器直接创建CUDA上下文；需要先把GPU任务入口改为调度API，再收紧容器设备授权。
3. `/usr/local/libexec/aid-gpu` 固定执行适配器和 `/etc/aid-gpu-agent.json` 尚未安装。因此本清单只允许配置优先级，暂不允许启用。
4. 使用 `restart=unless-stopped` 或systemd自动重启的越权服务，被“终止进程”后可能自动拉起；最终处置需要服务级隔离/停止适配器，而不只是杀PID。
5. Ubuntu自带的 `gpu-manager.service` 是显卡硬件探测oneshot服务，不是本项目GPU调度器；部署时使用 `gpu-resource-manager.service`，避免混淆。
6. 旧版视频生成Celery持续报错，容器网络中已经没有名为 `redis` 的服务；AVCS Celery持续连接被拒绝。两者当前不能接收任务，也不能通过 `inspect active` 证明队列为空；正式切换前必须修复检查链路或明确排除在首批部署之外。

## 实际部署批准门禁

正式部署前在222本机运行 `gpu_deployment_preflight.py`。报告有效期5分钟，并满足以下条件才进入人工确认：

- ComfyUI 8188、8191、8192均无运行和排队任务；
- 纳入首批切换的Celery队列明确返回空，状态未知也视为阻断；
- 连续GPU采样峰值低于10%；
- 输出中的 `changesApplied` 必须为 `false`；
- 把报告、计划停止/重启的服务及回滚步骤展示给管理员，得到明确确认后才执行部署。

预检只产生报告，不会修改222。用户确认不能复用过期报告；超过5分钟必须重新检查。

## 明确排除

千川EmbeddingGemma当前显式禁用CUDA；千川指标服务只读监控；数据库、Redis、MinIO、前端、Nginx、AID Java后端及未请求NVIDIA设备的短视频容器不加入GPU队列。
