import type { TaskPartialFailedData } from '~/utils/taskPartialFailed'
import type { CountProgressSnapshot } from '~/utils/taskSseProgressText'

/**
 * 提取智能体选项（原类型定义在 components/steps/AgentPickerModal.vue，
 * 该组件尚未迁移到本项目，此处按原结构平移，组件迁移后应保持一致）。
 */
export interface ExtractAgentOption {
  /** 智能体 agentCode，用于形态/分镜等需传 agentCode 的接口 */
  id: string
  name: string
  desc?: string
  thumbnail?: string
  /** 业务分类编码（列表分组用；parallel 提交用 id/agentCode） */
  bizCategoryCode?: string
  /** 智能体默认 modelCode（切换智能体时优先选中） */
  defaultModelCode?: string
}

/**
 * 场景/角色/道具三类提取智能体（原类型定义在 components/steps/ExtractAgentModal.vue，
 * 该组件尚未迁移到本项目，此处按原结构平移）。
 */
export interface ExtractAgents {
  scene: ExtractAgentOption
  character: ExtractAgentOption
  prop: ExtractAgentOption
}

/** 批量提取完成后：主表已入库但尚无形态，需用户点击「生成形态」的资产 */
export type PendingExtractFormAssetItem = {
  assetId: number
  assetType: 'scene' | 'character' | 'prop'
  title: string
}

// 场景图片数据
export interface SceneImage {
  id: string
  title: string
  url: string
  thumbnail?: string
  source: string
  importDate: string
}

// 角色/道具图片数据
export interface CharacterImage {
  id: string
  title: string
  url: string
  thumbnail?: string
  source: string
  importDate: string
}

// 场景生成状态
export type SceneGenerationStatus = 'idle' | 'generating' | 'success' | 'failed'

/** 第三步智能提取 loading 按「作品 + 剧集」隔离，切作品后回到原作品可立即恢复遮罩 */
export type ExtractUiScopeSnapshot = {
  isExtractingAssets: boolean
  extractingStage: 'scene' | 'character' | 'prop'
  extractingStages: { scene: boolean; character: boolean; prop: boolean }
  extractingTaskProgress: {
    percent: number
    stepTitle: string
    message: string
    stepIndex: number | null
    stepTotal: number | null
  }
}

/** 第三步形态图生成 UI 状态按「作品 + 剧集」隔离，避免多 Tab/多作品互相覆盖 */
export type Step3GenVisualScopeMaps = {
  scene: Record<number, SceneGenerationStatus>
  character: Record<string, SceneGenerationStatus>
  prop: Record<string, SceneGenerationStatus>
  /** 场景/角色/道具编辑弹窗内 SSE 任务，key 为 editorScopeKey（形态须带类型前缀：character-0-1 / prop-0-1） */
  modalSseTasks?: Record<string, SceneModalSseTaskSnapshot>
}

export type SceneModalSseTaskKind =
  | 'edit-image'
  | 'dialogue'
  | 'upscale'
  | 'multi-view'
  | 'setting-card'
  | 'form-image'

/** 第三步编辑弹窗 SSE 任务（按 editorScopeKey 隔离，刷新后恢复 loading） */
export type SceneModalSseTaskSnapshot = {
  taskId: number
  taskKind: SceneModalSseTaskKind
  sceneIdx: number
  imageIdx: number
  formId?: number | null
  imageId?: number | null
  editorScopeKey: string
  /** SSE 最新进度文案（刷新后恢复 loading 展示） */
  message?: string
  stepTitle?: string
}

/** 分镜图弹窗单镜生图任务类型 */
export type StoryboardModalImageGenKind =
  | 'storyboard'
  | 'dialogue'
  | 'upscale'
  | 'multiangle'
  | 'ninegrid'

/** 分镜图单镜生图任务（按 storyboardId 隔离，刷新后恢复弹窗 loading） */
export type StoryboardImageGenTaskSnapshot = {
  taskId: number
  sceneIdx: number
  /** 默认 storyboard（生成分镜图） */
  kind?: StoryboardModalImageGenKind
  /** 对话作图时的图片索引 */
  imageIdx?: number
  /** SSE 最新进度文案（刷新后恢复 loading 展示） */
  message?: string
  stepTitle?: string
}

/** 分镜视频单镜生成任务（按 storyboardId 隔离） */
export type StoryboardVideoGenTaskSnapshot = {
  taskId: number
  sceneIdx: number
  taskKind: 'i2v' | 'multi' | 'edge' | 'grid'
  /** SSE 最新进度文案（刷新后恢复 loading 展示） */
  message?: string
  stepTitle?: string
}

/** 分镜配音弹窗生成任务（按 storyboardId 隔离，刷新后 compose/status 轮询恢复） */
export type StoryboardDubbingGenTaskSnapshot = {
  taskId?: number
  composeBatchId?: string
  audioRecordId?: number
  sceneIdx: number
  lipSync?: boolean
  message?: string
  stepTitle?: string
}

/** 分镜视频弹窗单条提示词生成任务（图生 / 宫格 / 多参，刷新后恢复 SSE） */
export type StoryboardVideoPromptGenTaskKind =
  | 'video-prompt-gen'
  | 'grid-video-prompt-gen'
  | 'multi-video-prompt-gen'

export type StoryboardVideoPromptGenTaskSnapshot = {
  taskId: number
  sceneIdx: number
  taskKind: StoryboardVideoPromptGenTaskKind
}

/** 分镜脚本 / 分镜视频 / 配音批量 loading 等，与 step3 使用同一 scope key */
export type Step4PlusLiveGenSnapshot = {
  isGeneratingStoryboard: boolean
  storyboardGenerationProgress: CountProgressSnapshot
  storyboardGenerationError: string | null
  /** 分镜图批量生成（提示词 + 出图），与分镜脚本批量隔离 */
  isGeneratingStoryboardImageBatch: boolean
  storyboardImageBatchProgress: CountProgressSnapshot
  storyboardImageBatchError: string | null
  /** 分镜图批量父任务 id（storyboard_image_prompt_batch，刷新后恢复 SSE） */
  storyboardImageBatchActiveTaskId: number | null
  /** 分镜图出图父任务 id（storyboard_image_generate） */
  storyboardImageBatchActiveImageTaskId: number | null
  /** 分镜列表单镜出图 loading，key 为 storyboardId 字符串 */
  storyboardPanelImageGenStatusByStoryboardId: Record<string, SceneGenerationStatus>
  /** 当前批量出图任务包含的分镜 id（刷新后恢复卡片 loading；手动新增分镜不在此列表） */
  storyboardImageBatchTargetStoryboardIds: number[]
  isGeneratingStoryboardVideo: boolean
  /** 分镜视频批量生成进度（提示词 + 出片） */
  storyboardVideoBatchProgress: CountProgressSnapshot
  storyboardVideoBatchError: string | null
  /** 分镜视频提示词批量父任务 id（storyboard_video_prompt_batch） */
  storyboardVideoBatchActivePromptTaskId: number | null
  /** 分镜视频出片父任务 id（storyboard_video_generate） */
  storyboardVideoBatchActiveVideoTaskId: number | null
  /** 分镜列表单镜出视频 loading，key 为 storyboardId 字符串 */
  storyboardPanelVideoGenStatusByStoryboardId: Record<string, SceneGenerationStatus>
  /** 分镜列表单镜出视频失败文案（按作品:剧集隔离，刷新后恢复「生成失败」卡片） */
  storyboardPanelVideoGenErrorByStoryboardId: Record<string, string>
  /** 当前批量出片任务包含的分镜 id（刷新后恢复卡片 loading；手动新增分镜不在此列表） */
  storyboardVideoBatchTargetStoryboardIds: number[]
  dubbingBatchGeneratingIndices: number[]
  /** 分镜脚本批量生成任务 id（按作品隔离，刷新后用于恢复 SSE） */
  storyboardScriptActiveTaskId: number | null
  /** 分镜脚本批量 PARTIAL_FAILED 时的 SSE 失败明细（供续生 UI 展示） */
  storyboardScriptPartialFailedData: TaskPartialFailedData | null
  /** 分镜图弹窗生图任务，key 为 storyboardId 字符串 */
  storyboardImageGenTasksByStoryboardId: Record<string, StoryboardImageGenTaskSnapshot>
  /** 分镜图弹窗提示词生成任务，key 为 storyboardId 字符串 */
  storyboardImagePromptGenTasksByStoryboardId: Record<string, StoryboardImageGenTaskSnapshot>
  /** 分镜视频弹窗任务，key 为 storyboardId 字符串 */
  storyboardVideoGenTasksByStoryboardId: Record<string, StoryboardVideoGenTaskSnapshot>
  /** 分镜视频弹窗单条提示词生成任务，key 为 storyboardId 字符串 */
  storyboardVideoPromptGenTasksByStoryboardId: Record<string, StoryboardVideoPromptGenTaskSnapshot>
  /** 分镜配音弹窗任务，key 为 storyboardId 字符串 */
  storyboardDubbingGenTasksByStoryboardId: Record<string, StoryboardDubbingGenTaskSnapshot>
  /**
   * 成品预览导出进行中标记（>0 表示需 resume 轮询 export/status）。
   * 注意：不是 /api/user/task 的 taskId；成片进度只查 export/status。
   */
  episodeExportTaskId: number | null
  /** 成品预览导出对应的剪辑记录 id（轮询 export/status 优先用） */
  episodeExportEditorId: number | null
}

export function liveGenScopeKeyFromIds(projectId: number | null, episodeId: number | null): string {
  const pid = projectId != null && Number.isFinite(Number(projectId)) ? Number(projectId) : 0
  // 电影/未写入剧集：persist 常为 null，路由/API 常为 0，统一为 0 避免刷新后 scope 错位
  if (episodeId === null || episodeId === undefined) {
    return `${pid}:0`
  }
  const n = Number(episodeId)
  const ep = Number.isFinite(n) && n >= 0 ? n : 0
  return `${pid}:${ep}`
}

/** 内部工具：scope key 的 null/0 历史别名（原文件私有函数，拆分后跨文件共用） */
export function scopeKeyLegacyAliases(key: string): string[] {
  const keys = [key]
  const m = /^(\d+):(.+)$/.exec(String(key || '').trim())
  if (!m) return keys
  const pid = m[1]
  const ep = m[2]
  if (ep === 'null') keys.push(`${pid}:0`)
  if (ep === '0') keys.push(`${pid}:null`)
  return keys
}

/** 第三步提取/批量生图 + 分镜可选 modelCode，按「作品:剧集」隔离，避免跨作品污染 */
export type ExtractModelCodesMap = {
  scene: string
  character: string
  prop: string
}

export type OptionalModelCodesScopeSnapshot = {
  /** 智能提取 / 形态文案（LLM） */
  extractText: ExtractModelCodesMap
  /** 形态图 generate-image / 批量生图 */
  extractImage: ExtractModelCodesMap
  storyboardScriptModelCode: string
  storyboardStylistModelCode: string
  /** 分镜脚本「生成设置」智能体（与 modelCode 同 scope 隔离） */
  storyboardScriptAgentId: string
  storyboardScriptAgentName: string
  storyboardScriptAgentDesc: string
  storyboardScriptAgentThumbnail: string
  /** @deprecated 旧版合并字段，仅可落到 extractText，禁止用于生图 */
  extract?: ExtractModelCodesMap
}

/** 分镜视频「生成设置」按作品:剧集隔离，避免 A 作品智能体/模型污染 B 作品 */
export type StoryboardVideoSettingsScopeSnapshot = {
  agentId: string
  agentName: string
  agentDesc: string
  agentThumbnail: string
  /** 图生视频模型（与 generate/video-prompt 的文本 modelCode 无关） */
  videoModel: string
  /** 生成视频提示词用的文本 modelCode；未选则不传接口 */
  videoPromptModelCode: string
  aspectRatio: string
  /** 清晰度档（如 720p / 1080p / 1k），与模型 capability.sizeOptions 对齐 */
  resolution: string
  /** 出片时长（秒），批量/编辑弹窗共用 */
  durationSeconds?: number
  soundEffects: 'none' | 'with-sound'
}
