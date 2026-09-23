import type { UserModelListItem } from '~/types/business-api'
import { allowedParameterOptions, modelParameterConstraints } from '~/utils/modelParameterConstraints'

export interface ModelCapabilitySnapshot {
  aspectRatioOptions: string[]
  sizeOptions: string[]
  maxOutputCount: number
  defaultAspectRatio: string
  defaultSize: string
  defaultOutputCount: number
  durationOptions: number[]
  defaultDurationSeconds: number
  supportsDuration: boolean
  /** 是否支持音画同出；仅 capability.supportsAudio === true 时为 true */
  supportsAudio: boolean
  countOptions?: number[]
  audioOptions?: string[]
}

export interface VideoGenerationSettingsState {
  aspectRatio: string
  count: number
  quality: string
  duration: string
  audio: string
}

export interface SelectOption<T extends string | number = string> {
  value: T
  label: string
}

const DEFAULT_ASPECT_RATIOS = ['16:9', '9:16', '1:1']
const DEFAULT_SIZE_OPTIONS = ['1k', '2k', '4k']
const DEFAULT_VIDEO_SIZE_OPTIONS = ['720p', '1080p']
const DEFAULT_DURATION_OPTIONS = [5, 10]
const DEFAULT_MAX_COUNT = 4
const MAX_STORYBOARD_VIDEO_COUNT = 4

const CJK_SCRIPT_RE = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]/u

const DEFAULT_AUDIO_OPTIONS: SelectOption<string>[] = [
  { value: 'silent', label: '无声视频' },
  { value: 'with_audio', label: '带音频' }
]

function normalizeSizeCode(raw: string): string {
  const t = String(raw || '').trim()
  if (!t) return ''
  if (/^\d+k$/i.test(t)) return t.toLowerCase()
  return t.toLowerCase()
}

/** capability 可能是对象，少数网关/缓存场景仍可能是 JSON 字符串 */
export function resolveCapabilityRecord(
  item?: UserModelListItem | null
): Record<string, unknown> {
  const raw = item?.capability as unknown
  if (!raw) return {}
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw) as unknown
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
        ? (parsed as Record<string, unknown>)
        : {}
    } catch {
      return {}
    }
  }
  if (typeof raw === 'object' && !Array.isArray(raw)) {
    return raw as Record<string, unknown>
  }
  return {}
}

function readPromptCharacterLimit(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value)) return null
  const limit = Math.floor(value)
  return limit >= 0 ? limit : null
}

/**
 * 与服务端 ModelCapabilityValidator.validatePrompt 保持同一字段优先级：
 * 内容含中日韩文字时优先 maxPromptCharactersCjk，否则读取 maxPromptCharacters。
 */
export function resolveModelPromptCharacterLimit(
  item: UserModelListItem | null | undefined,
  prompt: string,
  fallback: number
): number {
  const capability = resolveCapabilityRecord(item)
  if (CJK_SCRIPT_RE.test(String(prompt ?? ''))) {
    const cjkLimit = readPromptCharacterLimit(capability.maxPromptCharactersCjk)
    if (cjkLimit != null) return cjkLimit
  }
  return readPromptCharacterLimit(capability.maxPromptCharacters) ?? fallback
}

function readStringOptions(raw: unknown): string[] {
  if (!Array.isArray(raw)) return []
  return [...new Set(raw.map((v) => String(v ?? '').trim()).filter(Boolean))]
}

/**
 * 清晰度下拉：以模型 capability.sizeOptions 为准（接口文档要求勿写死）。
 * 兼容个别响应对 sizeOptions 的顶层冗余字段。
 */
export function resolveModelSizeOptions(item?: UserModelListItem | null): string[] {
  const cap = resolveCapabilityRecord(item)
  const fromCap = readStringOptions(cap.sizeOptions)
  if (fromCap.length) return fromCap
  const root = item as (UserModelListItem & { sizeOptions?: unknown }) | null | undefined
  return readStringOptions(root?.sizeOptions)
}

/** 比例下拉：以模型 capability.aspectRatioOptions 为准 */
export function resolveModelAspectRatioOptions(item?: UserModelListItem | null): string[] {
  const cap = resolveCapabilityRecord(item)
  return readStringOptions(cap.aspectRatioOptions)
}

/** 在选项中按忽略大小写匹配；未命中则回退 default → 首项 */
export function pickMatchedOption(
  current: string | null | undefined,
  options: string[],
  fallback?: string | null
): string {
  // 无选项列表时优先用模型默认值（如 defaultAspectRatio），不保留非法旧值
  if (!options.length) return String(fallback || current || '').trim()
  const cur = String(current || '').trim()
  if (cur) {
    const hit = options.find((o) => o.toLowerCase() === cur.toLowerCase())
    if (hit) return hit
  }
  const fb = String(fallback || '').trim()
  if (fb) {
    const hit = options.find((o) => o.toLowerCase() === fb.toLowerCase())
    if (hit) return hit
  }
  return options[0] || ''
}

/**
 * 按模型 capability 解析清晰度 / 比例。
 * - 默认：保留仍合法的旧值（加载已保存配置）
 * - forceModelDefaults：忽略旧值，强制回落模型 defaultSize / defaultAspectRatio（切换模型）
 */
export function resolveImageGenParamsForModel(
  current: { resolution?: string | null; aspectRatio?: string | null },
  item?: UserModelListItem | null,
  opts?: { forceModelDefaults?: boolean }
): { resolution: string; aspectRatio: string } {
  const cap = resolveCapabilityRecord(item)
  const sizeOpts = resolveModelSizeOptions(item)
  const ratioOpts = resolveModelAspectRatioOptions(item)
  const defaultSize = String(cap.defaultSize || item?.defaultSizeCode || '').trim()
  const defaultRatio = String(cap.defaultAspectRatio || item?.defaultAspectRatio || '').trim()
  const force = Boolean(opts?.forceModelDefaults)
  return {
    resolution: pickMatchedOption(force ? '' : current.resolution, sizeOpts, defaultSize),
    aspectRatio: pickMatchedOption(force ? '' : current.aspectRatio, ratioOpts, defaultRatio)
  }
}

/**
 * 生成配置 GET 的智能体级默认值优先于模型 capability 默认值。
 * 下拉选项仍由模型 capability 提供；这里只负责弹窗首次回显，不能因为
 * 智能体配置的值不等于模型 defaultSize/defaultAspectRatio 就将其覆盖。
 */
export function resolveImageGenParamsFromAgentDefaults(
  current: { resolution?: string | null; aspectRatio?: string | null },
  item?: UserModelListItem | null
): { resolution: string; aspectRatio: string } {
  const modelDefaults = resolveImageGenParamsForModel({}, item, {
    forceModelDefaults: true
  })
  return {
    resolution: String(current.resolution || '').trim() || modelDefaults.resolution,
    aspectRatio: String(current.aspectRatio || '').trim() || modelDefaults.aspectRatio
  }
}

/** 图片清晰度档位排序（1k < 2k < 4k < 8k） */
export function rankSizeCode(raw: string): number {
  const n = normalizeSizeCode(raw)
  if (!n) return -1
  const mk = n.match(/^(\d+)k$/)
  if (mk) return Number(mk[1])
  if (n === '720p') return 720
  if (n === '1080p') return 1080
  return 0
}

/** 变清晰：按接口文档 priority 解析模型 resolution（优先 sizeOptions 最高档，否则 defaultSizeCode） */
export function resolveUpscaleResolutionFromModel(
  item?: UserModelListItem | null,
  format: 'lower' | 'upper' = 'lower'
): string {
  const cap = resolveCapabilityRecord(item)
  const sizeOptions = resolveModelSizeOptions(item)

  let bestRaw = ''
  let bestRank = -1
  for (const raw of sizeOptions) {
    const rank = rankSizeCode(raw)
    if (rank > bestRank) {
      bestRank = rank
      bestRaw = raw
    }
  }
  if (!bestRaw) {
    bestRaw = String(item?.defaultSizeCode || cap.defaultSize || '').trim()
  }

  const fallback = format === 'upper' ? '2K' : '2k'
  if (!bestRaw) return fallback
  const normalized = normalizeSizeCode(bestRaw)
  if (format === 'upper') {
    return /^\d+k$/.test(normalized) ? normalized.toUpperCase() : bestRaw
  }
  return normalized || fallback
}

/** 从 image_upscale 模型池取各模型最高 resolution 的最大值 */
export function resolveMaxDefaultSizeCodeFromModels(
  models: UserModelListItem[],
  format: 'lower' | 'upper' = 'lower'
): string {
  let bestRaw = ''
  let bestRank = -1
  for (const item of models) {
    const code = resolveUpscaleResolutionFromModel(item, format)
    const rank = rankSizeCode(code)
    if (rank > bestRank) {
      bestRank = rank
      bestRaw = code
    }
  }
  const fallback = format === 'upper' ? '2K' : '2k'
  if (!bestRaw) return fallback
  const normalized = normalizeSizeCode(bestRaw)
  if (format === 'upper') {
    return /^\d+k$/.test(normalized) ? normalized.toUpperCase() : bestRaw
  }
  return normalized || fallback
}

function formatSizeLabel(code: string): string {
  const n = normalizeSizeCode(code)
  if (/^\d+k$/.test(n)) return n.toUpperCase()
  return code
}

/** 从 listByFunc / model/list 单项解析生成配置下拉数据源 */
export function parseModelCapability(item?: UserModelListItem | null, parameters?: Record<string, unknown>): ModelCapabilitySnapshot {
  const cap = resolveCapabilityRecord(item)
  const aspectFromCap = resolveModelAspectRatioOptions(item)
  const sizeFromCap = resolveModelSizeOptions(item).map(normalizeSizeCode).filter(Boolean)

  const aspectRatioOptions =
    aspectFromCap.length > 0
      ? aspectFromCap
      : item?.supportsAspectRatio === false
        ? []
        : [...DEFAULT_ASPECT_RATIOS]

  const rawSizes =
    sizeFromCap.length > 0
      ? sizeFromCap
      : item?.defaultSizeCode
        ? [normalizeSizeCode(String(item.defaultSizeCode))]
        : [...DEFAULT_SIZE_OPTIONS]

  const sizeOptions = [...new Set(rawSizes.filter(Boolean))]
  const maxFromItem = Number(item?.maxOutputCount)
  const maxOutputCount =
    Number.isFinite(maxFromItem) && maxFromItem > 0
      ? Math.min(Math.floor(maxFromItem), 9)
      : DEFAULT_MAX_COUNT

  const defaultAspectRatio =
    String(cap.defaultAspectRatio ?? item?.defaultAspectRatio ?? aspectRatioOptions[0] ?? '16:9').trim() ||
    '16:9'

  const defaultSizeRaw = String(cap.defaultSize ?? item?.defaultSizeCode ?? sizeOptions[0] ?? '2k')
  const defaultSize = normalizeSizeCode(defaultSizeRaw) || sizeOptions[0] || '2k'

  const defaultOut = Number(item?.defaultOutputCount ?? cap.defaultOutputCount)
  const defaultOutputCount =
    Number.isFinite(defaultOut) && defaultOut > 0 ? Math.floor(defaultOut) : 1

  const durationFromCap = Array.isArray(cap.durationOptions)
    ? (cap.durationOptions as number[])
        .map((n) => Number(n))
        .filter((n) => Number.isFinite(n) && n > 0)
    : []
  const durationOptions =
    durationFromCap.length > 0
      ? [...new Set(durationFromCap)]
      : item?.supportsDuration === false
        ? []
        : [...DEFAULT_DURATION_OPTIONS]

  const defaultDur = Number(cap.defaultDurationSeconds ?? item?.defaultDurationSeconds)
  const defaultDurationSeconds =
    Number.isFinite(defaultDur) && defaultDur > 0 && durationOptions.includes(defaultDur)
      ? Math.floor(defaultDur)
      : durationOptions[0] ?? 5

  const supportsDuration = item?.supportsDuration !== false && durationOptions.length > 0
  const supportsAudio = resolveModelSupportsAudio(item)

  const snapshot: ModelCapabilitySnapshot = {
    aspectRatioOptions,
    sizeOptions: sizeOptions.length ? sizeOptions : [...DEFAULT_SIZE_OPTIONS],
    maxOutputCount,
    defaultAspectRatio,
    defaultSize,
    defaultOutputCount,
    durationOptions,
    defaultDurationSeconds,
    supportsDuration,
    supportsAudio
  }
  const constraints = modelParameterConstraints(item, parameters)
  snapshot.aspectRatioOptions = allowedParameterOptions(snapshot.aspectRatioOptions, constraints.get('aspectRatio') ?? constraints.get('options.aspectRatio'))
  snapshot.sizeOptions = allowedParameterOptions(snapshot.sizeOptions, constraints.get('size') ?? constraints.get('options.size') ?? constraints.get('options.resolution'))
  snapshot.durationOptions = allowedParameterOptions(snapshot.durationOptions, constraints.get('durationSeconds'))
  const countConstraint = constraints.get('expectedImageCount')
  if (countConstraint) snapshot.countOptions = allowedParameterOptions(Array.from({ length: snapshot.maxOutputCount }, (_, index) => index + 1), countConstraint)
  const audioConstraint = constraints.get('audio')
  if (audioConstraint) {
    snapshot.audioOptions = allowedParameterOptions([false, true], audioConstraint).map((value) => value ? 'with_audio' : 'silent')
    if (audioConstraint.forbidden) snapshot.supportsAudio = false
  }
  return snapshot
}

/** 视频模型音画同出：仅 capability.supportsAudio === true 视为支持（未配置按 false） */
export function resolveModelSupportsAudio(item?: UserModelListItem | null): boolean {
  const cap = resolveCapabilityRecord(item)
  return cap.supportsAudio === true
}

/** 视频画质：优先 capability.sizeOptions（720P/1080P），否则回退默认 */
export function parseVideoQualityOptions(item?: UserModelListItem | null): string[] {
  const snapshot = parseModelCapability(item)
  const sizes = snapshot.sizeOptions
    .map((s) => String(s || '').trim())
    .filter(Boolean)
  if (sizes.length) return sizes
  return [...DEFAULT_VIDEO_SIZE_OPTIONS]
}

export function buildAspectRatioSelectOptions(
  snapshot: ModelCapabilitySnapshot
): SelectOption<string>[] {
  return snapshot.aspectRatioOptions.map((value) => ({ value, label: value }))
}

export function buildCountSelectOptions(snapshot: ModelCapabilitySnapshot): SelectOption<number>[] {
  const max = Math.max(1, snapshot.maxOutputCount)
  return (snapshot.countOptions ?? Array.from({ length: max }, (_, i) => i + 1)).map((value) => ({ value, label: `${value}张` }))
}

export function buildQualitySelectOptions(
  snapshot: ModelCapabilitySnapshot,
  opts?: { include3k?: boolean }
): SelectOption<string>[] {
  // 默认保留接口返回的全部 sizeOptions（含 3K）；仅显式 include3k=false 时过滤
  const include3k = opts?.include3k ?? true
  return snapshot.sizeOptions
    .filter((code) => include3k || code !== '3k')
    .map((value) => ({ value, label: formatSizeLabel(value) }))
}

export function buildVideoQualitySelectOptions(
  snapshot: ModelCapabilitySnapshot
): SelectOption<string>[] {
  const sizes = snapshot.sizeOptions
    .map((s) => String(s || '').trim())
    .filter(Boolean)
  return sizes.map((value) => ({
    value: value.toLowerCase(),
    label: /^\d+p$/i.test(value) ? value.toUpperCase() : formatSizeLabel(value)
  }))
}

export function buildDurationSelectOptions(
  snapshot: ModelCapabilitySnapshot
): SelectOption<string>[] {
  return snapshot.durationOptions.map((sec) => ({
    value: String(sec),
    label: `${sec} s`
  }))
}

/** 分镜图生视频数量：接口上限 4 */
export function buildVideoCountSelectOptions(snapshot: ModelCapabilitySnapshot): SelectOption<number>[] {
  const max = Math.min(
    MAX_STORYBOARD_VIDEO_COUNT,
    Math.max(1, snapshot.maxOutputCount || MAX_STORYBOARD_VIDEO_COUNT)
  )
  return Array.from({ length: max }, (_, i) => {
    const n = i + 1
    return { value: n, label: `${n}个` }
  })
}

/** 不支持音画同出时返回空列表，由 UI 隐藏音频下拉 */
export function buildAudioSelectOptions(supportsAudio = true, allowed?: string[]): SelectOption<string>[] {
  if (!supportsAudio) return []
  return DEFAULT_AUDIO_OPTIONS.filter((option) => allowed == null || allowed.includes(option.value))
}

/** 是否向生成接口传 generateAudio=true */
export function resolveGenerateAudioFlag(
  wantAudio: boolean,
  supportsAudio: boolean
): boolean {
  return wantAudio && supportsAudio
}

/** 用枚举字典为比例 value 补充中文 label */
export function mergeAspectRatioLabels(
  values: string[],
  enumLabels: { value: string; label: string }[]
): SelectOption<string>[] {
  const labelMap = new Map(enumLabels.map((o) => [o.value, o.label]))
  return values.map((value) => ({
    value,
    label: labelMap.get(value) ? `${value}（${labelMap.get(value)}）` : value
  }))
}

export function coerceVideoGenerationSettings(
  current: VideoGenerationSettingsState,
  snapshot: ModelCapabilitySnapshot
): VideoGenerationSettingsState {
  const ratios = buildAspectRatioSelectOptions(snapshot)
  const counts = buildVideoCountSelectOptions(snapshot)
  const qualities = buildVideoQualitySelectOptions(snapshot)
  const durations = buildDurationSelectOptions(snapshot)
  const audios = buildAudioSelectOptions(snapshot.supportsAudio, snapshot.audioOptions)

  let aspectRatio = current.aspectRatio
  if (!ratios.some((o) => o.value === aspectRatio)) {
    aspectRatio = ratios.some((o) => o.value === snapshot.defaultAspectRatio)
      ? snapshot.defaultAspectRatio
      : ratios[0]?.value ?? '16:9'
  }

  let count = current.count
  if (!counts.some((o) => o.value === count)) {
    count = counts.some((o) => o.value === snapshot.defaultOutputCount)
      ? Math.min(snapshot.defaultOutputCount, MAX_STORYBOARD_VIDEO_COUNT)
      : counts[0]?.value ?? 1
  }

  let quality = normalizeSizeCode(current.quality)
  if (!qualities.some((o) => o.value === quality)) {
    const def = normalizeSizeCode(snapshot.defaultSize)
    quality = qualities.some((o) => o.value === def) ? def : qualities[0]?.value ?? '1080p'
  }

  let duration = current.duration
  if (!durations.length) {
    duration = String(snapshot.defaultDurationSeconds)
  } else if (!durations.some((o) => o.value === duration)) {
    const def = String(snapshot.defaultDurationSeconds)
    duration = durations.some((o) => o.value === def) ? def : durations[0]?.value ?? '5'
  }

  let audio = current.audio
  if (!snapshot.supportsAudio) {
    audio = 'silent'
  } else if (!audios.some((o) => o.value === audio)) {
    audio = audios.some((option) => option.value === 'with_audio') ? 'with_audio' : audios[0]?.value ?? 'silent'
  }

  return { aspectRatio, count, quality, duration, audio }
}

/** 切换模型时校正当前选中值 */
export function coerceGenerationSettings(
  current: { aspectRatio: string; count: number; quality: string },
  snapshot: ModelCapabilitySnapshot,
  opts?: { include3k?: boolean }
): { aspectRatio: string; count: number; quality: string } {
  const ratios = buildAspectRatioSelectOptions(snapshot)
  const counts = buildCountSelectOptions(snapshot)
  const qualities = buildQualitySelectOptions(snapshot, opts)

  let aspectRatio = current.aspectRatio
  if (!ratios.some((o) => o.value === aspectRatio)) {
    aspectRatio = ratios.some((o) => o.value === snapshot.defaultAspectRatio)
      ? snapshot.defaultAspectRatio
      : ratios[0]?.value ?? '16:9'
  }

  let count = current.count
  if (!counts.some((o) => o.value === count)) {
    count = counts.some((o) => o.value === snapshot.defaultOutputCount)
      ? snapshot.defaultOutputCount
      : counts[0]?.value ?? 1
  }

  let quality = normalizeSizeCode(current.quality)
  if (!qualities.some((o) => o.value === quality)) {
    quality = qualities.some((o) => o.value === snapshot.defaultSize)
      ? snapshot.defaultSize
      : qualities[0]?.value ?? '2k'
  }

  return { aspectRatio, count, quality }
}
