# 公开模型 Provider 接入指南

面向外部贡献者：如何把一个**公开供应商协议**接到 AID 现有的文本、图片、视频和语音编排上。英文版见 [Public provider integration guide](public-provider-integration.md)。

接入前先读 [贡献指南](../CONTRIBUTING.md)。大范围新协议先开 Issue 说明用户场景、官方文档链接和兼容影响。

## 范围与禁止项

本指南只使用公开仓源码和供应商官方协议文档。示例里的协议名、模型名和价格页都是公开资料。

不要在贡献里写入：

- 内部网关、代理地址、真实 API 密钥或账号
- 私有提示词、运营侧加价或站点内部价
- 平行任务表、单独扣费逻辑，或绕过现有 Provider 编排直接打上游

官方目录里没有的能力，不要靠猜测补上。核验口径见 [公开模型官方能力核验台账](../deliverables/official-model-capability-evidence.md)。

## 先判断要不要写新 Client

多数公开模型不需要新类：

| 官方协议 | 复用 |
| --- | --- |
| OpenAI Chat Completions 兼容文本 | `GenericOpenAiCompatibleTextProviderClient`，协议 `openai-compatible-text` |
| 提交后返回 `task_id` / `id`、再按任务查询的异步图片或视频 | `ConfigurableAsyncImageProviderClient` / `ConfigurableAsyncVideoProviderClient` |
| 已有厂商协议（如 Dashscope、Vidu、可灵、MiniMax、Gemini、DeepSeek） | 对应 `impl/` 下的 Client，只补能力字段或官方文档证据 |

只有官方请求字段、鉴权或状态机对不上现有适配器时，才新增 `*ProviderClient`。新实现必须是 Spring `@Component`，并实现对应接口。编排层注入 `List<TextProviderClient>` / `ImageProviderClient` / `VideoProviderClient` / `AudioProviderClient`，按协议或 `provider_code` 路由。

## 代码入口与职责

统一编排在 `MediaGenerationServiceImpl`：报价与能力校验 → 写入 `aid_media_task` → 预冻结 → 提交上游 → 调度轮询或同步收口。前端不直接轮询厂商。

| 类型 | 接口 | 目录 | 职责 |
| --- | --- | --- | --- |
| 文本 | `TextProviderClient` | `aid-interface/.../media/provider/` | `protocol()`；`validateRequest`（无网络）；`validateProviderConfiguration`（密钥与地址，报价路径不得调用）；`streamChat` / `submit` 聚合流式结果 |
| 图片 | `ImageProviderClient` | 同上，`impl/` 为各协议 | `supportsProviderCode` 是强路由；`submit` 返回直出 URL 或 `providerTaskId`；`query` 归一化状态 |
| 视频 | `VideoProviderClient` | 同上 | 与图片相同，另可声明参考视频上限；提交前 `normalizeRequest` 只填协议默认值，不访问上游 |
| 语音合成 | `AudioProviderClient` | 同上 | `submit` / `query`；状态只允许 `PROCESSING` / `SUCCEEDED` / `FAILED` |
| 语音识别 | `SpeechRecognitionClient` | 同上 | 自动字幕用的同步识别，不是 TTS 编排，不要混进音频生成 Client |

图片和视频：先按 `aid_ai_provider.provider_code` 唯一命中 `supportsProviderCode`，再按 `protocol()`。多个 Client 抢同一个 `provider_code` 会直接失败。文本：先按 `protocol`，再按真实上游模型名弱匹配，最后落到 `openai-compatible-text`。

实现类只做协议适配。不要在 Client 里建任务、改余额、写业务表或自己起轮询线程。

## 声明模型能力

能力写在模型配置上，由公共校验链在建任务和预冻结前拒绝超限输入，不得截断后继续调用上游。

**表字段（`AiModelConfigVo`）** 描述输入类型和开关，例如 `supportsTextInput`、`supportsImageInput`、`supportsMultiImageInput`、`supportsAspectRatio`、`supportsSizePreset`、`supportsDuration`、`supportsFirstFrame`、`supportsLastFrame`、`supportsCallback`。

**`capability_json`** 描述白名单和数量。公共键包括：

| 键 | 含义 |
| --- | --- |
| `sizeOptions` / `defaultSize` | 清晰度档位，图片常用 `1K`/`2K`/`4K`，视频常用 `720P`/`1080P` |
| `aspectRatioOptions` / `defaultAspectRatio` | 画面比例 |
| `durationOptions` | 视频时长秒数白名单 |
| `maxReferenceImages` / `minReferenceImages` | 参考图上限 / 下限；`0` 表示禁止参考图 |
| `maxPromptCharacters` | 提示词字符上限 |
| `allowedInputs` / `requiredInputs` / `requiredAnyOf` | 允许或必填的输入组合 |
| `allowedScenes` | 场景白名单（文生、首帧、首尾帧等） |

数量限制走 `ReferenceImageLimiter`（及对应的参考视频 / 参考音频限制器），不要在单个 Client 里再写一套。枚举兜底见 `AiModelCapabilityConstants`。官方未写明的档位不要编进白名单。

同一真实模型的多能力用 `aid_ai_model_capability` / `ModelCapabilityDefinition`（`capabilityCode`、`generateMode`、`bindings`）。请求里的 `capabilityCode` 由编排选择对应协议绑定。

示例（通用公开字段，不是某个内部模型）：

```json
{
  "sizeOptions": ["1K", "2K"],
  "defaultSize": "2K",
  "aspectRatioOptions": ["1:1", "16:9", "9:16"],
  "defaultAspectRatio": "1:1",
  "durationOptions": [5, 10],
  "maxReferenceImages": 1,
  "minReferenceImages": 0,
  "maxPromptCharacters": 2000,
  "allowedInputs": ["text", "image"],
  "requiredInputs": ["text"]
}
```

## 复用统一任务、计费、回调、重试和失败补偿

不要为一家供应商另起任务表或余额表。

1. **任务**：`aid_media_task`。异步提交后由 `TaskDispatchService.initDispatchSchedule` 写入调度快照和 `nextPollTime`。主循环是 `mediaTask.dispatch()`：到期轮询、回调超时转轮询、存活对账、未提交僵尸收口、排队拉起。
2. **计费**：`IMediaBillingService` 三阶段——`prepareBilling` 预冻结，成功 `settleBilling`，失败 `refundBilling`。文本用量走 `ProviderSubmitResult.usage`（`input_tokens` / `output_tokens`）。计费模式是模型上的 `FIXED` / `SKU`，计量类型是 `TOKEN` / `PER_IMAGE` / `PER_SECOND` / `SKU_PACKAGE`。价格规则写在模型计费配置里，不要在 Client 里算账。
3. **回调**：厂商若支持 webhook，复用已有回调支持类和 `supportsCallback`。回调分两类，不要一律写成只唤醒轮询：
   - **仅唤醒轮询**（通用 `CallbackController`、MiniMax H3）：回调不是可信的官方状态契约，只调度轮询（`scheduleImmediatePoll` / `scheduleImmediatePollIfWaitingCallback`），终态仍来自 `query()`。
   - **校验后终态收口**（`ViduCallbackServiceImpl`、`KlingCallbackServiceImpl`）：验签并确认官方终态后，可构造 `ProviderTaskResult` 并直接调用 `taskCompletionService.completeTask(...)`。中间态只登记进展。成功但缺少结果 URL 等不完整载荷仍交轮询。
4. **重试与补偿**：处理中任务由 `compensateProcessingTasks` 按 5/10/20/30 秒退避对账；排队任务由 `drainQueuedCompensate` 在重启或完成事件丢失后拉起；计费中间态由 `mediaTask.billingCompensate()` 重试结算或退回；成功但未落存储由 `ossCompensate` 补齐。查询网络失败或未知状态必须把 `ProviderTaskResult.querySuccessful` 设为 `false`，继续对账，**不能**当成生成失败。

文本失败是否扣费由 `TextFailureBillingPolicy` 根据是否真正发请求、HTTP 是否最终拒绝、是否观察到 token 用量决定。本地校验失败用 `notSent`，不要先扣再退。

## 按官方文档记录协议、能力和公开价格

每个新协议或新能力在 PR 里给出官方文档 URL（模型页、API 参考、公开价格页）。建议同步更新 [公开模型官方能力核验台账](../deliverables/official-model-capability-evidence.md)：

- **已表达**：现有字段能完整表达官方约束
- **部分表达**：官方约束已核对，但公共契约还表达不了，模型不得据此自动启用
- **待核验**：官方资料不够，保持原配置，不猜上限

公开价格只记录供应商官方标价和计费单位（按 token、按张、按秒、按次），并链到官方价格页。不要写入平台加价、内部倍率或私有套餐。新协议或新计费语义还要看 [远程模型目录](../model-catalog/README.md) 和 [兼容性策略](../model-catalog/compatibility-policy.md)：先发支持该契约的程序，再发目录。

## 错误处理

- 用户可见文案走已有 `TaskErrorCode` / `TaskErrorPresentation`，不要把厂商原文堆到前台。
- 日志用 `ProviderErrorSanitizer`，去掉密钥、完整提示词和签名 URL。
- `query()` 分清「查询异常」和「生成失败」。只有官方文档定义的终态才能把 `terminalConfirmed` 设为 true。
- 成功结果必须先落到本系统存储 URL，再回填业务表。不要把厂商临时签名 URL 写入业务字段。

## 本地验证

按 [贡献指南](../CONTRIBUTING.md) 的验证表如实写结果。Provider 相关变更至少包括：

1. 在默认分支用官方文档里的请求/响应样例证明现有 Client **不能**表达该协议或能力（若已经能表达，不要再开平行实现）。
2. 给 `validateRequest`、状态映射、参考图上限或计费估算补**会在未修复代码上失败**的单元测试。
3. Java：相关测试 + `mvn clean package -DskipTests` 不能单独当行为证据。
4. 文档：检查本指南、README、CONTRIBUTING 的相对链接和锚点。
5. 不提交真实密钥、本地环境文件、构建产物或临时日志。连通性测试若只读（例如列模型），在 PR 里写明不会发起计费生成。

## 提交检查清单

- [ ] 复用了现有接口、`aid_media_task`、预冻结/结算/退回和调度补偿，没有平行任务表或单独扣费
- [ ] `protocol()` / `supportsProviderCode` 唯一，不和其他 Client 抢路由
- [ ] 能力字段与官方文档一致；超限在校验链拒绝，不截断后调用
- [ ] PR 中有官方协议、能力和公开价格页链接；无密钥、网关、代理、运营价、私有提示词
- [ ] 中英文说明、限制和链接一致
- [ ] `git diff --check` 通过，PR 写明已做和未做的检查
