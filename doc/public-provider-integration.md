# Public provider integration guide

How to attach a **public vendor protocol** to AID's existing text, image, video and audio orchestration. Chinese version: [公开模型 Provider 接入指南](公开Provider接入指南.md).

Read [CONTRIBUTING](../CONTRIBUTING.md) first. For a large new protocol, open an issue with the user need, official doc links and compatibility impact.

## Scope

Use only this public repository and the vendor's official protocol docs. Example protocol names, model names and pricing pages below are public.

Do not include internal gateways, proxy addresses, real API keys, private prompts, or site-specific markups. Do not add a parallel task table, a separate balance path, or a client that calls the vendor outside the existing provider orchestration.

Do not invent capabilities that are missing from the official docs. Evidence status is defined in [official model capability evidence](../deliverables/official-model-capability-evidence.md).

## Prefer an existing client

| Official protocol | Reuse |
| --- | --- |
| OpenAI Chat Completions compatible text | `GenericOpenAiCompatibleTextProviderClient`, protocol `openai-compatible-text` |
| Async image or video that returns `task_id` / `id` then query | `ConfigurableAsyncImageProviderClient` / `ConfigurableAsyncVideoProviderClient` |
| A vendor already in `impl/` (Dashscope, Vidu, Kling, MiniMax, Gemini, DeepSeek, …) | That client; add capability fields or official evidence only |

Add a new `*ProviderClient` only when request fields, auth or the status machine do not fit. Register it as a Spring `@Component`. The orchestrator injects `List<TextProviderClient>` / `ImageProviderClient` / `VideoProviderClient` / `AudioProviderClient` and routes by protocol or `provider_code`.

## Entry points

`MediaGenerationServiceImpl` owns the flow: quote and capability checks → insert `aid_media_task` → freeze billing → submit upstream → dispatch poll or sync completion. The UI does not poll the vendor.

| Kind | Interface | Role |
| --- | --- | --- |
| Text | `TextProviderClient` | `protocol()`; `validateRequest` (no I/O); `validateProviderConfiguration` (credentials/URL, not on the quote path); `streamChat` / `submit` |
| Image | `ImageProviderClient` | `supportsProviderCode` is the strong route; `submit` returns a URL or `providerTaskId`; `query` normalizes status |
| Video | `VideoProviderClient` | Same as image, plus optional reference-video limits; `normalizeRequest` fills protocol defaults only |
| Speech synthesis | `AudioProviderClient` | `submit` / `query`; status is `PROCESSING` / `SUCCEEDED` / `FAILED` |
| Speech recognition | `SpeechRecognitionClient` | Sync captions, not TTS — do not fold it into the audio generate client |

Image and video route by unique `provider_code` via `supportsProviderCode`, then `protocol()`. Two clients claiming the same code fail. Text routes by `protocol`, then the real upstream model name, then `openai-compatible-text`.

A client only adapts the protocol. It must not create tasks, move balances, write business tables or start its own poller.

## Declaring capabilities

Capabilities live on the model config. The shared validators reject over-limit input before task insert and freeze. Do not truncate and call upstream anyway.

Column flags on `AiModelConfigVo` cover input types and switches: `supportsTextInput`, `supportsImageInput`, `supportsMultiImageInput`, `supportsAspectRatio`, `supportsSizePreset`, `supportsDuration`, `supportsFirstFrame`, `supportsLastFrame`, `supportsCallback`.

`capability_json` holds allow-lists and counts:

| Key | Meaning |
| --- | --- |
| `sizeOptions` / `defaultSize` | Resolution presets (`1K`/`2K`/`4K` for images, `720P`/`1080P` for video) |
| `aspectRatioOptions` / `defaultAspectRatio` | Aspect ratios |
| `durationOptions` | Allowed video durations in seconds |
| `maxReferenceImages` / `minReferenceImages` | Reference-image cap / floor; `0` forbids references |
| `maxPromptCharacters` | Prompt length cap |
| `allowedInputs` / `requiredInputs` / `requiredAnyOf` | Input combinations |
| `allowedScenes` | Scene allow-list (text-to-image, first frame, first-and-last frame, …) |

Use `ReferenceImageLimiter` (and the video/audio siblings). Fallback enums are in `AiModelCapabilityConstants`. Multiple capabilities on one model use `aid_ai_model_capability` / `ModelCapabilityDefinition`.

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

## Tasks, billing, callbacks, retries, compensation

1. **Tasks** stay on `aid_media_task`. After async submit, `TaskDispatchService.initDispatchSchedule` stores the dispatch snapshot. `mediaTask.dispatch()` polls due tasks, moves expired callbacks to polling, reconciles stalled work, closes unsubmitted zombies and drains the queue.
2. **Billing** is `IMediaBillingService`: `prepareBilling` → `settleBilling` or `refundBilling`. Text usage is `ProviderSubmitResult.usage`. Meter types are `TOKEN` / `PER_IMAGE` / `PER_SECOND` / `SKU_PACKAGE`. Do not compute charges inside the client.
3. **Callbacks** reuse existing webhook helpers and `supportsCallback`. Not every webhook is wake-only:
   - **Wake-only** (generic `CallbackController`, MiniMax H3): the payload is not a trusted official status contract, so the handler only schedules a poll (`scheduleImmediatePoll` / `scheduleImmediatePollIfWaitingCallback`). Terminal state then comes from `query()`.
   - **Validated terminal** (`ViduCallbackServiceImpl`, `KlingCallbackServiceImpl`): after signature and payload checks, a known terminal status may build a `ProviderTaskResult` and call `taskCompletionService.completeTask(...)` directly. Non-terminal callbacks only mark upstream progress. Incomplete success payloads (for example no result URL) stay on polling.
4. **Compensation**: `compensateProcessingTasks` (5/10/20/30s backoff), `drainQueuedCompensate` after restart, `mediaTask.billingCompensate()` for stuck freeze/settle/refund, `ossCompensate` when success has no stored URL. If `querySuccessful` is false, keep reconciling — do not fail the generation.

`TextFailureBillingPolicy` decides text refund vs settle from whether the request was sent, whether HTTP was a final rejection, and whether token usage was observed.

## Record protocol, capabilities and public prices

Link the vendor's model page, API reference and public price page in the PR. Update [official model capability evidence](../deliverables/official-model-capability-evidence.md) with expressed / partially expressed / unverified. Record only the vendor's public list price and unit. Do not paste platform markups.

New protocols or billing semantics also follow the [model catalog](../model-catalog/README.md) and [compatibility policy](../model-catalog/compatibility-policy.md): ship program support first, then the catalog.

## Errors

Use `TaskErrorCode` / `TaskErrorPresentation` for users. Sanitize logs with `ProviderErrorSanitizer`. Only an official terminal status may set `terminalConfirmed`. Persist to this system's storage URL before filling business tables — never store a vendor signed URL there.

## Local checks

1. On the default branch, show that the official sample does not fit an existing client (otherwise do not add a parallel one).
2. Add unit tests that fail on unfixed code for validation, status mapping, reference limits or billing estimates.
3. Java: relevant tests; `mvn clean package -DskipTests` is not behavior evidence.
4. Docs: relative links in this guide, README and CONTRIBUTING.
5. No secrets, local env files or build output. If a connectivity check is read-only, say so.

## PR checklist

- [ ] Reused existing interfaces, `aid_media_task`, freeze/settle/refund and dispatch compensation
- [ ] Unique `protocol()` / `supportsProviderCode`
- [ ] Capabilities match official docs; over-limit input is rejected
- [ ] Official protocol, capability and public price links; no keys, gateways, proxies, operational prices or private prompts
- [ ] Chinese and English limits and links match
- [ ] `git diff --check` and an honest validation section
