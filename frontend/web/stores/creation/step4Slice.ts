import type { TaskPartialFailedData } from '~/utils/taskPartialFailed'
import {
EMPTY_COUNT_PROGRESS,
mergeCountProgressFromSse,
normalizeCountProgress,
type TaskSseProgressInput
} from '~/utils/taskSseProgressText'
import {
collectModalOwnedTaskIds,
emptyStep4PlusLiveGenSnapshot,
sanitizeLegacyModalPanelGenerating
} from './liveGenMigrate'
import type { CreationGet,CreationSet } from './state'
import type { SceneGenerationStatus,Step4PlusLiveGenSnapshot } from './types'

export interface Step4Actions {
  persistStep4PlusLiveGenForScopeKey: (key: string) => void
  restoreStep4PlusLiveGenForScopeKey: (key: string) => void
  /** 异步任务已脱离当前作品上下文时，只更新对应 scope 桶，避免污染当前扁平状态 */
  mergeStep4PlusLiveGenForScopeKey: (
    scopeKey: string,
    partial: Partial<Step4PlusLiveGenSnapshot>
  ) => void
  syncStep4PlusLiveGenToCurrentScope: () => void
  setDubbingBatchGeneratingIndices: (indices: number[]) => void
  /** 异步批量中已切换作品时，仅从原 scope 桶移除某下标，避免脏数据一直占着「生成中」 */
  removeDubbingBatchIndexFromScope: (scopeKey: string, index: number) => void
  /** 分镜脚本生成设置 */
  setStoryboardGenerateSettings: (settings: {
    agentId?: string
    shotDensity?: string
    modelCode?: string
  }) => void
  /** 分镜脚本：选择智能体后同步名称/描述/缩略图与 agentId */
  updateStoryboardAgent: (agent: {
    id: string
    name: string
    desc: string
    thumbnail?: string
  }) => void
  setStoryboardStylistGenerateSettings: (settings: { agentId?: string; modelCode?: string }) => void
  updateStoryboardStylistAgent: (agent: {
    id: string
    name: string
    desc: string
    thumbnail?: string
  }) => void
  /** 分镜视频生成设置 */
  setStoryboardVideoGenerateSettings: (settings: {
    agentId?: string
    videoModel?: string
    videoPromptModelCode?: string
    aspectRatio?: string
    resolution?: string
    durationSeconds?: number | null
    soundEffects?: 'none' | 'with-sound'
  }) => void
  /** 分镜视频：选择智能体后同步展示信息与 agentId */
  updateStoryboardVideoAgent: (agent: {
    id: string
    name: string
    desc: string
    thumbnail?: string
  }) => void
  setStoryboardGenerating: (flag: boolean) => void
  clearStoryboardScriptGenerationOutcome: () => void
  setStoryboardPartialFailedOutcome: (
    message: string,
    taskId: number,
    data: TaskPartialFailedData | null
  ) => void
  setStoryboardScriptActiveTaskId: (taskId: number | null) => void
  setStoryboardScriptPartialFailedData: (data: TaskPartialFailedData | null) => void
  setStoryboardProgress: (completed: number, total: number) => void
  applyStoryboardScriptSseProgress: (p: TaskSseProgressInput) => void
  clearStoryboardScriptProgress: () => void
  setStoryboardError: (msg: string | null) => void
  stopStoryboardGeneration: () => void
  setStoryboardImageBatchGenerating: (flag: boolean) => void
  setStoryboardImageBatchActiveTaskId: (taskId: number | null) => void
  setStoryboardImageBatchActiveImageTaskId: (taskId: number | null) => void
  setStoryboardImageBatchProgress: (completed: number, total: number) => void
  applyStoryboardImageBatchSseProgress: (p: TaskSseProgressInput) => void
  clearStoryboardImageBatchProgress: () => void
  setStoryboardImageBatchError: (msg: string | null) => void
  setStoryboardImageBatchTargetStoryboardIds: (storyboardIds: number[]) => void
  clearStoryboardImageBatchTargetStoryboardIds: () => void
  isStoryboardImageBatchTarget: (storyboardId: number) => boolean
  setStoryboardPanelImageGenStatus: (storyboardId: number, status: SceneGenerationStatus) => void
  clearStoryboardPanelImageGenStatus: (storyboardId: number) => void
  stopStoryboardImageBatchGeneration: () => void
}

export function createStep4Slice(set: CreationSet, get: CreationGet): Step4Actions {
  return {
    persistStep4PlusLiveGenForScopeKey(key: string) {
      if (!key) return
      const prev = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      set({
        step4PlusLiveGenByScope: {
          ...get().step4PlusLiveGenByScope,
          [key]: {
            isGeneratingStoryboard: get().isGeneratingStoryboard,
            storyboardGenerationProgress: { ...get().storyboardGenerationProgress },
            storyboardGenerationError: get().storyboardGenerationError,
            isGeneratingStoryboardImageBatch: get().isGeneratingStoryboardImageBatch,
            storyboardImageBatchProgress: { ...get().storyboardImageBatchProgress },
            storyboardImageBatchError: get().storyboardImageBatchError,
            storyboardImageBatchActiveTaskId: get().storyboardImageBatchActiveTaskId,
            storyboardImageBatchActiveImageTaskId: get().storyboardImageBatchActiveImageTaskId,
            storyboardPanelImageGenStatusByStoryboardId: {
              ...get().storyboardPanelImageGenStatusByStoryboardId
            },
            storyboardImageBatchTargetStoryboardIds: [
              ...get().storyboardImageBatchTargetStoryboardIds
            ],
            isGeneratingStoryboardVideo: get().isGeneratingStoryboardVideo,
            storyboardVideoBatchProgress: { ...get().storyboardVideoBatchProgress },
            storyboardVideoBatchError: get().storyboardVideoBatchError,
            storyboardVideoBatchActivePromptTaskId: get().storyboardVideoBatchActivePromptTaskId,
            storyboardVideoBatchActiveVideoTaskId: get().storyboardVideoBatchActiveVideoTaskId,
            storyboardPanelVideoGenStatusByStoryboardId: {
              ...get().storyboardPanelVideoGenStatusByStoryboardId
            },
            storyboardPanelVideoGenErrorByStoryboardId: {
              ...get().storyboardPanelVideoGenErrorByStoryboardId
            },
            storyboardVideoBatchTargetStoryboardIds: [
              ...get().storyboardVideoBatchTargetStoryboardIds
            ],
            dubbingBatchGeneratingIndices: [...get().dubbingBatchGeneratingIndices],
            storyboardScriptActiveTaskId: get().storyboardScriptActiveTaskId,
            storyboardScriptPartialFailedData: get().storyboardScriptPartialFailedData,
            storyboardImageGenTasksByStoryboardId: {
              ...(prev.storyboardImageGenTasksByStoryboardId || {})
            },
            storyboardImagePromptGenTasksByStoryboardId: {
              ...(prev.storyboardImagePromptGenTasksByStoryboardId || {})
            },
            storyboardVideoGenTasksByStoryboardId: {
              ...(prev.storyboardVideoGenTasksByStoryboardId || {})
            },
            storyboardVideoPromptGenTasksByStoryboardId: {
              ...(prev.storyboardVideoPromptGenTasksByStoryboardId || {})
            },
            storyboardDubbingGenTasksByStoryboardId: {
              ...(prev.storyboardDubbingGenTasksByStoryboardId || {})
            },
            episodeExportTaskId: prev.episodeExportTaskId ?? null,
            episodeExportEditorId: prev.episodeExportEditorId ?? null
          }
        }
      })
    },

    restoreStep4PlusLiveGenForScopeKey(key: string) {
      const s = get().step4PlusLiveGenByScope[key] ?? emptyStep4PlusLiveGenSnapshot()
      const modalImageTaskIds = collectModalOwnedTaskIds(s.storyboardImageGenTasksByStoryboardId)
      const imageGenBatchTid = Number(s.storyboardImageBatchActiveImageTaskId)
      const restoredImageGenBatchTid =
        Number.isFinite(imageGenBatchTid) &&
        imageGenBatchTid > 0 &&
        !modalImageTaskIds.has(imageGenBatchTid)
          ? imageGenBatchTid
          : null
      const restoredImagePanelStatus = sanitizeLegacyModalPanelGenerating(
        s.storyboardPanelImageGenStatusByStoryboardId,
        s.storyboardImageGenTasksByStoryboardId
      )
      const imageBatchTid = Number(s.storyboardImageBatchActiveTaskId)
      const modalVideoTaskIds = collectModalOwnedTaskIds(s.storyboardVideoGenTasksByStoryboardId)
      const videoBatchTid = Number(s.storyboardVideoBatchActiveVideoTaskId)
      const restoredVideoBatchTid =
        Number.isFinite(videoBatchTid) &&
        videoBatchTid > 0 &&
        !modalVideoTaskIds.has(videoBatchTid)
          ? videoBatchTid
          : null
      const restoredVideoPanelStatus = sanitizeLegacyModalPanelGenerating(
        s.storyboardPanelVideoGenStatusByStoryboardId,
        s.storyboardVideoGenTasksByStoryboardId
      )
      const videoPromptBatchTid = Number(s.storyboardVideoBatchActivePromptTaskId)
      const tid = Number(s.storyboardScriptActiveTaskId)
      set({
        isGeneratingStoryboard: s.isGeneratingStoryboard,
        storyboardGenerationProgress: { ...s.storyboardGenerationProgress },
        storyboardGenerationError: s.storyboardGenerationError,
        isGeneratingStoryboardImageBatch: Boolean(
          s.isGeneratingStoryboardImageBatch &&
            (Number(s.storyboardImageBatchActiveTaskId) > 0 ||
              restoredImageGenBatchTid != null ||
              (s.storyboardImageBatchTargetStoryboardIds?.length ?? 0) > 0 ||
              Object.values(restoredImagePanelStatus).some((status) => status === 'generating'))
        ),
        storyboardImageBatchProgress: { ...s.storyboardImageBatchProgress },
        storyboardImageBatchError: s.storyboardImageBatchError,
        storyboardImageBatchActiveTaskId:
          Number.isFinite(imageBatchTid) && imageBatchTid > 0 ? imageBatchTid : null,
        storyboardImageBatchActiveImageTaskId: restoredImageGenBatchTid,
        storyboardPanelImageGenStatusByStoryboardId: restoredImagePanelStatus,
        storyboardImageBatchTargetStoryboardIds: Array.isArray(
          s.storyboardImageBatchTargetStoryboardIds
        )
          ? s.storyboardImageBatchTargetStoryboardIds
              .map((id) => Number(id))
              .filter((id) => Number.isFinite(id) && id > 0)
          : [],
        isGeneratingStoryboardVideo: Boolean(
          s.isGeneratingStoryboardVideo &&
            (Number(s.storyboardVideoBatchActivePromptTaskId) > 0 ||
              restoredVideoBatchTid != null ||
              (s.storyboardVideoBatchTargetStoryboardIds?.length ?? 0) > 0 ||
              Object.values(restoredVideoPanelStatus).some((status) => status === 'generating'))
        ),
        storyboardVideoBatchProgress: { ...s.storyboardVideoBatchProgress },
        storyboardVideoBatchError: s.storyboardVideoBatchError,
        storyboardVideoBatchActivePromptTaskId:
          Number.isFinite(videoPromptBatchTid) && videoPromptBatchTid > 0
            ? videoPromptBatchTid
            : null,
        storyboardVideoBatchActiveVideoTaskId: restoredVideoBatchTid,
        storyboardPanelVideoGenStatusByStoryboardId: restoredVideoPanelStatus,
        storyboardPanelVideoGenErrorByStoryboardId: {
          ...(s.storyboardPanelVideoGenErrorByStoryboardId || {})
        },
        storyboardVideoBatchTargetStoryboardIds: Array.isArray(
          s.storyboardVideoBatchTargetStoryboardIds
        )
          ? s.storyboardVideoBatchTargetStoryboardIds
              .map((id) => Number(id))
              .filter((id) => Number.isFinite(id) && id > 0)
          : [],
        dubbingBatchGeneratingIndices: [...s.dubbingBatchGeneratingIndices],
        storyboardScriptActiveTaskId: Number.isFinite(tid) && tid > 0 ? tid : null,
        storyboardScriptPartialFailedData: s.storyboardScriptPartialFailedData ?? null
      })
    },

    /** 异步任务已脱离当前作品上下文时，只更新对应 scope 桶，避免污染当前扁平状态 */
    mergeStep4PlusLiveGenForScopeKey(scopeKey: string, partial: Partial<Step4PlusLiveGenSnapshot>) {
      const base = get().step4PlusLiveGenByScope[scopeKey] ?? emptyStep4PlusLiveGenSnapshot()
      set({
        step4PlusLiveGenByScope: {
          ...get().step4PlusLiveGenByScope,
          [scopeKey]: {
            ...base,
            ...partial,
            storyboardGenerationProgress:
              partial.storyboardGenerationProgress != null
                ? normalizeCountProgress({
                    ...base.storyboardGenerationProgress,
                    ...partial.storyboardGenerationProgress
                  })
                : base.storyboardGenerationProgress,
            storyboardImageBatchProgress:
              partial.storyboardImageBatchProgress != null
                ? normalizeCountProgress({
                    ...base.storyboardImageBatchProgress,
                    ...partial.storyboardImageBatchProgress
                  })
                : base.storyboardImageBatchProgress,
            storyboardVideoBatchProgress:
              partial.storyboardVideoBatchProgress != null
                ? normalizeCountProgress({
                    ...base.storyboardVideoBatchProgress,
                    ...partial.storyboardVideoBatchProgress
                  })
                : base.storyboardVideoBatchProgress,
            storyboardPanelImageGenStatusByStoryboardId:
              partial.storyboardPanelImageGenStatusByStoryboardId != null
                ? { ...partial.storyboardPanelImageGenStatusByStoryboardId }
                : { ...(base.storyboardPanelImageGenStatusByStoryboardId || {}) },
            storyboardImageBatchTargetStoryboardIds:
              partial.storyboardImageBatchTargetStoryboardIds != null
                ? partial.storyboardImageBatchTargetStoryboardIds
                    .map((id) => Number(id))
                    .filter((id) => Number.isFinite(id) && id > 0)
                : [...(base.storyboardImageBatchTargetStoryboardIds || [])],
            storyboardPanelVideoGenStatusByStoryboardId:
              partial.storyboardPanelVideoGenStatusByStoryboardId != null
                ? { ...partial.storyboardPanelVideoGenStatusByStoryboardId }
                : { ...(base.storyboardPanelVideoGenStatusByStoryboardId || {}) },
            storyboardPanelVideoGenErrorByStoryboardId:
              partial.storyboardPanelVideoGenErrorByStoryboardId != null
                ? { ...partial.storyboardPanelVideoGenErrorByStoryboardId }
                : { ...(base.storyboardPanelVideoGenErrorByStoryboardId || {}) },
            storyboardVideoBatchTargetStoryboardIds:
              partial.storyboardVideoBatchTargetStoryboardIds != null
                ? partial.storyboardVideoBatchTargetStoryboardIds
                    .map((id) => Number(id))
                    .filter((id) => Number.isFinite(id) && id > 0)
                : [...(base.storyboardVideoBatchTargetStoryboardIds || [])],
            dubbingBatchGeneratingIndices:
              partial.dubbingBatchGeneratingIndices != null
                ? [...partial.dubbingBatchGeneratingIndices]
                : base.dubbingBatchGeneratingIndices,
            storyboardImageGenTasksByStoryboardId:
              partial.storyboardImageGenTasksByStoryboardId != null
                ? { ...partial.storyboardImageGenTasksByStoryboardId }
                : { ...(base.storyboardImageGenTasksByStoryboardId || {}) },
            storyboardImagePromptGenTasksByStoryboardId:
              partial.storyboardImagePromptGenTasksByStoryboardId != null
                ? { ...partial.storyboardImagePromptGenTasksByStoryboardId }
                : { ...(base.storyboardImagePromptGenTasksByStoryboardId || {}) },
            storyboardVideoGenTasksByStoryboardId:
              partial.storyboardVideoGenTasksByStoryboardId != null
                ? { ...partial.storyboardVideoGenTasksByStoryboardId }
                : { ...(base.storyboardVideoGenTasksByStoryboardId || {}) },
            storyboardVideoPromptGenTasksByStoryboardId:
              partial.storyboardVideoPromptGenTasksByStoryboardId != null
                ? { ...partial.storyboardVideoPromptGenTasksByStoryboardId }
                : { ...(base.storyboardVideoPromptGenTasksByStoryboardId || {}) },
            storyboardDubbingGenTasksByStoryboardId:
              partial.storyboardDubbingGenTasksByStoryboardId != null
                ? { ...partial.storyboardDubbingGenTasksByStoryboardId }
                : { ...(base.storyboardDubbingGenTasksByStoryboardId || {}) }
          }
        }
      })
    },

    syncStep4PlusLiveGenToCurrentScope() {
      get().persistStep4PlusLiveGenForScopeKey(get().step3GenVisualScopeKey())
    },

    setDubbingBatchGeneratingIndices(indices: number[]) {
      set({ dubbingBatchGeneratingIndices: [...indices] })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    /** 异步批量中已切换作品时，仅从原 scope 桶移除某下标，避免脏数据一直占着「生成中」 */
    removeDubbingBatchIndexFromScope(scopeKey: string, index: number) {
      const base = get().step4PlusLiveGenByScope[scopeKey] ?? emptyStep4PlusLiveGenSnapshot()
      const next = (base.dubbingBatchGeneratingIndices || []).filter((x) => x !== index)
      get().mergeStep4PlusLiveGenForScopeKey(scopeKey, { dubbingBatchGeneratingIndices: next })
    },

    // 分镜脚本生成设置
    setStoryboardGenerateSettings(settings: {
      agentId?: string
      shotDensity?: string
      modelCode?: string
    }) {
      const next = { ...get().storyboardGenerateSettings }
      if (settings.agentId !== undefined) next.agentId = settings.agentId
      if (settings.shotDensity !== undefined) next.shotDensity = settings.shotDensity
      if (settings.modelCode !== undefined) {
        next.modelCode = String(settings.modelCode || '').trim()
      }
      set({ storyboardGenerateSettings: next })
      get().syncOptionalModelCodesToCurrentScope()
    },

    /** 分镜脚本：选择智能体后同步名称/描述/缩略图与 agentId */
    updateStoryboardAgent(agent: { id: string; name: string; desc: string; thumbnail?: string }) {
      set({
        storyboardAgent: {
          id: agent.id,
          name: agent.name,
          desc: agent.desc,
          thumbnail: agent.thumbnail || ''
        },
        storyboardGenerateSettings: { ...get().storyboardGenerateSettings, agentId: agent.id }
      })
      get().syncOptionalModelCodesToCurrentScope()
    },

    setStoryboardStylistGenerateSettings(settings: { agentId?: string; modelCode?: string }) {
      const next = { ...get().storyboardStylistGenerateSettings }
      if (settings.agentId !== undefined) {
        next.agentId = String(settings.agentId || '').trim()
      }
      if (settings.modelCode !== undefined) {
        next.modelCode = String(settings.modelCode || '').trim()
      }
      set({ storyboardStylistGenerateSettings: next })
      get().syncOptionalModelCodesToCurrentScope()
    },

    updateStoryboardStylistAgent(agent: {
      id: string
      name: string
      desc: string
      thumbnail?: string
    }) {
      set({
        storyboardStylistAgent: {
          id: agent.id,
          name: agent.name,
          desc: agent.desc,
          thumbnail: agent.thumbnail || ''
        },
        storyboardStylistGenerateSettings: {
          ...get().storyboardStylistGenerateSettings,
          agentId: agent.id
        }
      })
    },

    // 分镜视频生成设置
    setStoryboardVideoGenerateSettings(settings: {
      agentId?: string
      videoModel?: string
      videoPromptModelCode?: string
      aspectRatio?: string
      resolution?: string
      durationSeconds?: number | null
      soundEffects?: 'none' | 'with-sound'
    }) {
      const next = { ...get().storyboardVideoGenerateSettings }
      if (settings.agentId !== undefined) next.agentId = settings.agentId
      if (settings.videoModel !== undefined) next.videoModel = settings.videoModel
      if (settings.videoPromptModelCode !== undefined) {
        next.videoPromptModelCode = settings.videoPromptModelCode
      }
      if (settings.aspectRatio !== undefined) next.aspectRatio = settings.aspectRatio
      if (settings.resolution !== undefined) {
        next.resolution = String(settings.resolution || '')
          .trim()
          .toLowerCase()
      }
      if (settings.durationSeconds !== undefined) {
        const n = Number(settings.durationSeconds)
        next.durationSeconds = Number.isFinite(n) && n > 0 ? Math.floor(n) : undefined
      }
      if (settings.soundEffects !== undefined) next.soundEffects = settings.soundEffects
      set({ storyboardVideoGenerateSettings: next })
      get().syncStoryboardVideoSettingsToCurrentScope()
    },

    /** 分镜视频：选择智能体后同步展示信息与 agentId */
    updateStoryboardVideoAgent(agent: {
      id: string
      name: string
      desc: string
      thumbnail?: string
    }) {
      set({
        storyboardVideoAgent: {
          id: agent.id,
          name: agent.name,
          desc: agent.desc,
          thumbnail: agent.thumbnail || ''
        },
        storyboardVideoGenerateSettings: {
          ...get().storyboardVideoGenerateSettings,
          agentId: agent.id
        }
      })
      get().syncStoryboardVideoSettingsToCurrentScope()
    },

    setStoryboardGenerating(flag: boolean) {
      set({ isGeneratingStoryboard: flag })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    clearStoryboardScriptGenerationOutcome() {
      set({
        storyboardGenerationError: null,
        storyboardScriptActiveTaskId: null,
        storyboardScriptPartialFailedData: null
      })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    setStoryboardPartialFailedOutcome(
      message: string,
      taskId: number,
      data: TaskPartialFailedData | null
    ) {
      set({
        storyboardGenerationError: message,
        storyboardScriptActiveTaskId: taskId,
        storyboardScriptPartialFailedData: data,
        isGeneratingStoryboard: false
      })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    setStoryboardScriptActiveTaskId(taskId: number | null) {
      const n = Number(taskId)
      set({ storyboardScriptActiveTaskId: Number.isFinite(n) && n > 0 ? n : null })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    setStoryboardScriptPartialFailedData(data: TaskPartialFailedData | null) {
      set({ storyboardScriptPartialFailedData: data })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    setStoryboardProgress(completed: number, total: number) {
      set({
        storyboardGenerationProgress: {
          ...get().storyboardGenerationProgress,
          completed,
          total
        }
      })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    applyStoryboardScriptSseProgress(p: TaskSseProgressInput) {
      set({
        storyboardGenerationProgress: mergeCountProgressFromSse(
          get().storyboardGenerationProgress,
          p
        )
      })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    clearStoryboardScriptProgress() {
      set({ storyboardGenerationProgress: { ...EMPTY_COUNT_PROGRESS } })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    setStoryboardError(msg: string | null) {
      set({ storyboardGenerationError: msg })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    stopStoryboardGeneration() {
      set({ isGeneratingStoryboard: false })
      get().clearStoryboardScriptGenerationOutcome()
    },

    setStoryboardImageBatchGenerating(flag: boolean) {
      set({ isGeneratingStoryboardImageBatch: flag })
      if (!flag) {
        set({
          storyboardImageBatchError: null,
          storyboardImageBatchActiveTaskId: null,
          storyboardImageBatchActiveImageTaskId: null
        })
      }
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    setStoryboardImageBatchActiveTaskId(taskId: number | null) {
      const n = Number(taskId)
      set({ storyboardImageBatchActiveTaskId: Number.isFinite(n) && n > 0 ? n : null })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    setStoryboardImageBatchActiveImageTaskId(taskId: number | null) {
      const n = Number(taskId)
      set({ storyboardImageBatchActiveImageTaskId: Number.isFinite(n) && n > 0 ? n : null })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    setStoryboardImageBatchProgress(completed: number, total: number) {
      set({
        storyboardImageBatchProgress: {
          ...get().storyboardImageBatchProgress,
          completed,
          total
        }
      })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    applyStoryboardImageBatchSseProgress(p: TaskSseProgressInput) {
      set({
        storyboardImageBatchProgress: mergeCountProgressFromSse(
          get().storyboardImageBatchProgress,
          p
        )
      })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    clearStoryboardImageBatchProgress() {
      set({ storyboardImageBatchProgress: { ...EMPTY_COUNT_PROGRESS } })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    setStoryboardImageBatchError(msg: string | null) {
      set({ storyboardImageBatchError: msg })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    setStoryboardImageBatchTargetStoryboardIds(storyboardIds: number[]) {
      set({
        storyboardImageBatchTargetStoryboardIds: (storyboardIds ?? [])
          .map((id) => Number(id))
          .filter((id) => Number.isFinite(id) && id > 0)
      })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    clearStoryboardImageBatchTargetStoryboardIds() {
      set({ storyboardImageBatchTargetStoryboardIds: [] })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    isStoryboardImageBatchTarget(storyboardId: number): boolean {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return false
      return get().storyboardImageBatchTargetStoryboardIds.includes(sid)
    },

    setStoryboardPanelImageGenStatus(storyboardId: number, status: SceneGenerationStatus) {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return
      const key = String(sid)
      if (get().storyboardPanelImageGenStatusByStoryboardId[key] === status) return
      set({
        storyboardPanelImageGenStatusByStoryboardId: {
          ...get().storyboardPanelImageGenStatusByStoryboardId,
          [key]: status
        }
      })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    clearStoryboardPanelImageGenStatus(storyboardId: number) {
      const sid = Number(storyboardId)
      if (!Number.isFinite(sid) || sid <= 0) return
      const key = String(sid)
      if (!(key in get().storyboardPanelImageGenStatusByStoryboardId)) return
      const next = { ...get().storyboardPanelImageGenStatusByStoryboardId }
      delete next[key]
      set({ storyboardPanelImageGenStatusByStoryboardId: next })
      get().syncStep4PlusLiveGenToCurrentScope()
    },

    stopStoryboardImageBatchGeneration() {
      set({
        isGeneratingStoryboardImageBatch: false,
        storyboardImageBatchError: null,
        storyboardImageBatchActiveTaskId: null,
        storyboardImageBatchActiveImageTaskId: null,
        storyboardImageBatchTargetStoryboardIds: [],
        storyboardPanelImageGenStatusByStoryboardId: {}
      })
      get().syncStep4PlusLiveGenToCurrentScope()
    }
  }
}
