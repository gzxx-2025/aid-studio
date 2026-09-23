import type { RefObject } from 'react'
import type { AsyncPromptApplyTicket } from '~/utils/asyncPromptApplyGuard'
import type { HorizontalScrollTabBarHandle } from '~/components/common/HorizontalScrollTabBar'
import type { ModelOption } from '~/components/steps/ModelSelectDropdown'
import type { ParamSettingsConfirmPayload } from '~/components/steps/StoryboardParamSettingsModal'
import type { CreationLiveGenScopeCtx } from '~/composables/useCreationLiveGenScopeGuard'
import type { CreationStoreState } from '~/stores/creation'
import type { StoryboardPanel } from '~/types'
import type { StoryboardRecordRow,UserModelListItem } from '~/types/business-api'
import type { RouteLikeLocation } from '~/types/routeLike'
import type { StoryboardVideoModalTabKey } from '~/utils/creationModeUiRules'
import type { ModalGenSessionScope } from '~/utils/modalGenSessionScope'
import type { SelectOption } from '~/utils/modelCapability'
import type { ReferenceMediaItem } from '~/utils/referenceMediaItem'
import type { PromptAssetItem } from '~/utils/storyboardPromptAssetRef'
import type { StoryboardGeneratePanelHandle } from '../StoryboardGeneratePanel'
import type { Mirrored } from './useMirrored'

export interface EditStoryboardVideoModalScene {
  name: string
  videos?: any[]
  scriptContent?: string
  scriptPanelTitle?: string
  storyboardId?: number | string
  /** 第四步对应分镜图，用于导入弹窗第二 Tab */
  storyboardImages?: any[]
}

/** 原 defineProps / defineEmits 的 React 契约 */
export interface EditStoryboardVideoModalProps {
  open: boolean
  sceneIndex: number
  scenes: EditStoryboardVideoModalScene[]
  /** 弹窗实例作用域，配合 storyboardId 隔离生视频 loading */
  editorScopeKey?: string
  /** 原 emit('update:open') */
  onOpenChange: (value: boolean) => void
  /** 原 emit('jump-to-storyboard-script', sceneIndex) */
  onJumpToStoryboardScript?: (sceneIndex: number) => void
  /** 原 emit('update', sceneIndex, data) */
  onUpdate: (
    sceneIndex: number,
    data: { name?: string; videos?: any[]; scriptContent?: string; scriptTitle?: string; title?: string }
  ) => void
}

/** withDefaults 之后的 props（默认值已填充） */
export type ResolvedEditStoryboardVideoModalProps = EditStoryboardVideoModalProps & {
  editorScopeKey: string
}

export type VideoTaskKind = 'i2v' | 'multi' | 'edge' | 'grid'

export type VideoPromptGenTaskKind =
  | 'video-prompt-gen'
  | 'grid-video-prompt-gen'
  | 'multi-video-prompt-gen'

export type ImageToVideoSettingKey = 'cameraMovement' | 'shootingTechnique'

export type EdgeFrameImage = {
  id?: string | number
  url?: string
  thumbnail?: string
  title?: string
  name?: string
  _fromServer?: boolean
  _serverRow?: { id?: number }
}

export type SelectAssetModalType =
  | 'scene'
  | 'character'
  | 'prop'
  | 'pose'
  | 'expression'
  | 'effect'
  | 'draft'
  | 'other'

export type ReferenceImageItem = {
  id?: string
  url?: string
  thumbnail?: string
  title?: string
  name?: string
}

/**
 * 原 Vue setup() 单闭包按 UI 区块 / 逻辑内聚拆分后共享的运行时上下文。
 * 主组件创建 base 部分，各子 hook 把自己的 API Object.assign 进来（延迟绑定解环）。
 */
export interface VideoModalBaseCtx {
  /** 事件回调 / 异步流程内读最新 props（默认值已填充） */
  props: () => ResolvedEditStoryboardVideoModalProps
  route: () => RouteLikeLocation
  store: () => CreationStoreState
  emitOpenChange: (value: boolean) => void
  emitUpdate: ResolvedEditStoryboardVideoModalProps['onUpdate']
  emitJumpToStoryboardScript: (sceneIndex: number) => void

  // —— 顶层可变状态（原 ref）——
  currentSceneIndex: Mirrored<number>
  viewMode: Mirrored<'list' | 'card'>
  leftActiveTab: Mirrored<StoryboardVideoModalTabKey>
  leftPanelLoading: Mirrored<boolean>
  rightPanelLoading: Mirrored<boolean>
  videoGenerateTargetKey: Mirrored<string>
  videoGenerateProgressText: Mirrored<string>
  selectedVideoIdx: Mirrored<number>
  showStoryboardScriptModal: Mirrored<boolean>
  scriptEditorKey: Mirrored<number>
  stepPanelImagesCache: Mirrored<Record<number, any[]>>
  selectReferenceModalOpen: Mirrored<boolean>

  // 提示词区
  imageToVideoPrompt: Mirrored<string>
  resolvedVideoPromptAssets: Mirrored<PromptAssetItem[]>
  /** 接口回填提示词时暂停面板内 prompt/参数联动，避免 Quill 与 watcher 递归更新 */
  videoPromptProgrammaticSyncDepth: Mirrored<number>
  isGeneratingVideoPrompt: Mirrored<boolean>
  isSavingVideoPrompt: Mirrored<boolean>
  videoPromptGenerateTargetKey: Mirrored<string>
  multiParamPrompt: Mirrored<string>
  resolvedMultiParamPromptAssets: Mirrored<PromptAssetItem[]>
  isGeneratingMultiParamPrompt: Mirrored<boolean>
  multiParamPromptGenerateTargetKey: Mirrored<string>
  edgeVideoPrompt: Mirrored<string>
  /** 首尾帧提示词按分镜本地缓存，与多参 videoPrompt 完全隔离（不从接口回落 videoPrompt） */
  edgeVideoPromptByStoryboardId: Mirrored<Record<string, string>>

  // 图生视频 tab：九宫格 + 参考图 + 镜头运动 + 特殊拍摄手法
  referenceImages: Mirrored<ReferenceImageItem[]>
  nineGridEnabled: Mirrored<boolean>
  isImageToVideoSettingExpanded: Mirrored<boolean>
  activeImageToVideoSettingKey: Mirrored<ImageToVideoSettingKey | null>
  selectedCameraMovement: Mirrored<{ key: string; value: string } | null>
  cameraMovementDesc: Mirrored<string>
  selectedImageToVideoShootingTechnique: Mirrored<{ key: string; value: string } | null>

  // 多参生视频 tab
  isMultiParamSettingExpanded: Mirrored<boolean>
  activeMultiParamSettingKey: Mirrored<string | null>
  multiParamShootingTechnique: Mirrored<{ key: string; value: string } | null>

  // 首尾帧生视频 tab
  isEdgeVideoSettingExpanded: Mirrored<boolean>
  firstFrameImage: Mirrored<EdgeFrameImage | null>
  lastFrameImage: Mirrored<EdgeFrameImage | null>
  edgeFrameImagesByStoryboardId: Mirrored<
    Record<string, { first: EdgeFrameImage | null; last: EdgeFrameImage | null }>
  >
  selectEdgeFrameModalOpen: Mirrored<boolean>
  edgeFramePickTarget: Mirrored<'first' | 'last'>

  // 多参素材桶
  sceneImages: Mirrored<any[]>
  characterImages: Mirrored<any[]>
  propImages: Mirrored<any[]>
  otherImages: Mirrored<any[]>

  // 选择弹窗
  selectAssetModalOpen: Mirrored<boolean>
  selectMultiParamReferenceModalOpen: Mirrored<boolean>
  selectAssetModalType: Mirrored<SelectAssetModalType>
  showMaterialFromLibraryModal: Mirrored<boolean>
  materialLibraryCategoryKey: Mirrored<string>
  showVideoLibraryModal: Mirrored<boolean>

  // 模型选择
  imageToVideoModel: Mirrored<string>
  multiParamVideoModel: Mirrored<string>
  edgeVideoModel: Mirrored<string>
  gridVideoModel: Mirrored<string>
  imageToVideoModelDropdownExpanded: Mirrored<boolean>
  multiParamVideoModelDropdownExpanded: Mirrored<boolean>
  edgeVideoModelDropdownExpanded: Mirrored<boolean>
  gridVideoModelDropdownExpanded: Mirrored<boolean>
  cachedImageToVideoAgentModelCodes: { current: string[] }
  cachedMultiParamAgentModelCodes: { current: string[] }
  cachedGridVideoAgentModelCodes: { current: string[] }
  initVideoModelGen: { current: number }

  // 参考音频 / 视频参数
  referenceAudios: Mirrored<ReferenceMediaItem[]>
  /** 多参生视频引用的视频记录；与参考图、参考音频使用独立协议字段 */
  referenceVideos: Mirrored<ReferenceMediaItem[]>
  videoAspectRatio: Mirrored<string>
  videoDuration: Mirrored<string>
  videoCount: Mirrored<number>
  videoQuality: Mirrored<string>
  videoAudio: Mirrored<string>
  /** detail.recommendedDurationSeconds 原始值；无效时由模型默认兜底 */
  recommendedDurationSecondsRaw: Mirrored<number | null>

  // 记录操作
  isSettingFinalVideo: Mirrored<boolean>
  isDeletingRecord: Mirrored<boolean>

  // 视频预览播放
  playingVideoIdx: Mirrored<number>
  videoPreviewMediaReady: Mirrored<Record<number, boolean>>

  // —— 非渲染可变量 ——
  resumeStoryboardVideoFollowGen: { current: number }
  resumeStoryboardVideoPromptFollowGen: { current: number }
  /** 提示词 SSE 跟进中的 taskId / 分镜，避免 restore 在 SSE 期间打 task/detail */
  activeStoryboardPromptFollowTaskIds: Set<number>
  activeStoryboardPromptFollowStoryboardIds: Set<number>
  /** 弹窗内跟进已完成并 refresh 过的分镜，避免 global-tasks-updated 再打 list */
  modalOwnedVideoRecordsRefreshedIds: Set<number>

  // DOM / 组件句柄
  videoCanvasBodyRef: RefObject<HTMLDivElement | null>
  sceneTabBarRef: RefObject<HorizontalScrollTabBarHandle | null>
  imageToVideoPanelRef: RefObject<StoryboardGeneratePanelHandle | null>
  multiParamPanelRef: RefObject<StoryboardGeneratePanelHandle | null>
  edgeVideoPanelRef: RefObject<StoryboardGeneratePanelHandle | null>

  // —— 基础 helpers（主组件内实现）——
  refreshHeaderTabs: (force?: boolean) => Promise<void>
  scrollActiveSceneTabIntoView: () => void
  syncSceneDetailAndRestore: (sceneIdx: number) => Promise<void>
  scriptPanels: () => StoryboardPanel[]
  resolveScriptPanelForSceneIndex: (sceneIdx: number) => StoryboardPanel | undefined
  currentStoryboardId: () => number | null
  scriptRowLabel: () => string
  currentSceneVideos: () => any[]
  projectCreationMode: () => string
  getActiveStoryboardPanel: () => StoryboardGeneratePanelHandle | null
  /** 分镜视频内点击脚本标题：关闭当前弹窗并跳转第 4 步分镜脚本列表 */
  openStoryboardScriptEditor: () => void
}

/** 会话 / loading 恢复（useVideoModalSession） */
export interface VideoModalSessionApi {
  resolveStoryboardIdForSceneIndex: (sceneIdx: number) => string
  storyboardVideoModalSessionScope: () => ModalGenSessionScope | null
  suspendLateModalVideoFollowIfScopeChanged: (taskId: number, taskScope: CreationLiveGenScopeCtx) => void
  overlayKeyParts: (
    sceneIdx: number,
    taskKind: string
  ) => { editorScopeKey: string; sceneIdx: number; entityId: string; itemIdx: number; taskKind: string }
  sceneStoryboardIdNum: (sceneIdx: number) => number | null
  defaultVideoProgressTextForTaskKind: (taskKind?: string) => string
  normalizeModalVideoGenTaskKind: (raw: unknown) => VideoTaskKind
  readSessionForScene: (sceneIdx: number) => ReturnType<
    typeof import('~/utils/storyboardVideoModalGenSession').readStoryboardVideoModalGenSession
  >
  resolveModalVideoGenOwnerSceneIdx: (storyboardId: number) => number | null
  isModalVideoGenOwnerScene: (sceneIdx: number) => boolean
  shouldRestoreStoryboardVideoGenerate: (sceneIdx: number) => boolean
  resolveVideoGenTaskSnapshotForStoryboard: (
    storyboardId: number,
    sceneIdx: number
  ) => { persisted: any; taskId: number | null; taskKind: VideoTaskKind }
  hasStoryboardVideoPendingState: (storyboardId: number) => boolean
  isStoryboardVideoGenerationInProgress: (storyboardId: number | null | undefined) => boolean
  removeLocalGeneratingPlaceholders: (videos: any[]) => any[]
  ensureGeneratingPlaceholderVideo: (sceneIdx: number) => void
  clearLocalGeneratingPlaceholdersForScene: (sceneIdx: number) => void
  finalizeMappedVideosWhileGenerating: (sceneIdx: number, mapped: any[]) => any[]
  clearModalStoryboardVideoLoadingUi: (storyboardId: number, sceneIdx: number, taskKind?: VideoTaskKind) => void
  primeStoryboardVideoLoadingUi: (sceneIdx: number) => void
  ensurePendingStoryboardVideoLoadingPlaceholders: (focusSceneIdx: number) => void
  ensureModalVideoLoadingRestored: (sceneIdx: number) => Promise<void>
  isSceneVideoGenerating: (sceneIdx: number) => boolean
  clearVideoGenerateOverlayForScene: (sceneIdx: number, taskKind?: VideoTaskKind) => void
  isVideoCanvasItemGenerating: (videoIndex: number) => boolean
  resolveGeneratingVideoIndex: () => number
  scrollVideoCanvasToIndex: (sceneIdx: number, index: number) => void
  selectHistoryVideo: (idx: number) => void
  isHistoryVideoMain: (videoIndex: number) => boolean
  canSetMainFromHistory: (videoIndex: number) => boolean
  isHistoryVideoItemGenerating: (videoIndex: number) => boolean
  suspendOtherStoryboardVideoModalFollows: (keepStoryboardId: number | null) => void
  storyboardVideoBizErr: (e: unknown) => string
  /** 原 showXxxGenerateLoading computed 的同步读取版（渲染与回调共用） */
  showImageToVideoGenerateLoadingGet: () => boolean
  showMultiParamGenerateLoadingGet: () => boolean
  showEdgeVideoGenerateLoadingGet: () => boolean
  showGridVideoGenerateLoadingGet: () => boolean
}

/** 生成记录列表与记录级操作（useVideoModalRecords） */
export interface VideoModalRecordsApi {
  mapRecordRowToVideoItem: (r: StoryboardRecordRow) => any
  fetchProjectRecordsForStoryboard: (
    storyboardId: number,
    type: 'image' | 'video',
    options?: { force?: boolean }
  ) => Promise<StoryboardRecordRow[]>
  refreshVideoRecords: (sceneIdx: number, options?: { focusLatest?: boolean; force?: boolean }) => Promise<void>
  refreshVideoRecordsFresh: (sceneIdx: number, options?: { focusLatest?: boolean }) => Promise<void>
  applyTerminalVideoItemsToScene: (sceneIdx: number, data: unknown) => void
  refreshStepPanelImagesForReference: (sceneIdx?: number) => Promise<void>
  localStoryboardImagesForScene: (sceneIdx: number) => any[]
  setAsStoryboardVideo: (idx: number) => Promise<void>
  unsetAsStoryboardVideo: (idx: number) => Promise<void>
  handleSetMainFromHistory: (videoIndex: number) => Promise<void>
  canDeleteHistoryVideo: (video: any) => boolean
  handleDeleteVideo: (videoIndex: number) => void
  handleUploadLocalVideo: () => void
  handleOpenVideoLibrary: () => void
  handleVideoLibraryImport: (asset: any) => void
  formatDate: (dateString: string) => string
}

/** 出片任务提交 / SSE 跟随 / 恢复（useVideoModalGenerate） */
export interface VideoModalGenerateApi {
  runStoryboardVideoGenerateForScene: (
    sceneIdx: number,
    opts: {
      taskKind: VideoTaskKind
      submitImageVideoBody?: any
      submitMultiBody?: any
      submitEdgeBody?: any
      submitGridBody?: any
      resumeTaskId?: number
      progressSubmit?: string
      progressRunning?: string
      silentComplete?: boolean
    }
  ) => Promise<void>
  restoreStoryboardVideoGenerateIfNeeded: (sceneIdx: number) => Promise<void>
  syncStoryboardVideoGenerateUiAfterSettled: (
    sceneIdx: number,
    options?: { forceRefresh?: boolean }
  ) => Promise<void>
  handleStoryboardVideoGenSettledEvent: (event: Event) => void
  handleGlobalTasksUpdatedForVideoModal: () => void
  shouldSkipStoryboardVideoRestore: (storyboardId: number, taskId?: number | null) => boolean
}

/** 各出片方向 body 组装与开始生成入口（useVideoModalGenerateActions） */
export interface VideoModalGenerateActionsApi {
  handleImageToVideoStartGenerate: () => Promise<void>
  handleGridVideoStartGenerate: () => Promise<void>
  handleMultiParamStartGenerate: () => Promise<void>
  handleEdgeVideoStartGenerate: () => Promise<void>
  persistVideoGenerateSettings: (modelName: string) => void
}

/** 提示词生成 / 回填（useVideoModalPrompt） */
export interface VideoModalPromptApi {
  imageToVideoPromptPlain: () => string
  multiParamPromptPlain: () => string
  edgeVideoPromptPlain: () => string
  videoPromptParamGroups: () => any[]
  multiParamPromptParamGroups: () => any[]
  ensureDictLoaded: () => Promise<void>
  cameraMovementOptions: () => any[]
  shootingTechniqueOptions: () => any[]
  aspectRatioEnumOptions: () => { value: string; label: string }[]
  showGeneratingVideoPromptForScene: () => boolean
  showGeneratingMultiParamPromptForScene: () => boolean
  isStoryboardVideoPromptGeneratingForScene: (sceneIdx?: number) => boolean
  applyVideoPromptFromApi: (
    plain: string,
    ticket?: AsyncPromptApplyTicket
  ) => Promise<boolean>
  applyMultiParamPromptFromApi: (
    plain: string,
    ticket?: AsyncPromptApplyTicket
  ) => Promise<boolean>
  beginVideoPromptApply: (
    channel: 'imageToVideo' | 'multiParam',
    storyboardId: number
  ) => AsyncPromptApplyTicket
  invalidateVideoPromptApply: (channel: 'imageToVideo' | 'multiParam') => void
  handleImageToVideoPromptEditorChange: (value: string) => void
  handleMultiParamPromptEditorChange: (value: string) => void
  handleEdgeVideoPromptEditorChange: (value: string) => void
  loadStoryboardVideoPromptForScene: () => Promise<void>
  loadStoryboardMultiVideoPromptForScene: () => Promise<void>
  loadStoryboardEdgeVideoPromptForScene: () => void
  saveEdgeVideoPromptToCache: (storyboardId: string | number | null | undefined) => void
  handleSaveVideoPrompt: () => Promise<void>
  copyImageToVideoPrompt: () => void
  copyMultiParamPrompt: () => void
  copyEdgeVideoPrompt: () => void
  copyCameraDesc: () => void
  writePromptPlainToActiveEditor: (plain: string) => void
  applyImportedReferenceAudios: (audios: ReferenceMediaItem[]) => void
  removeReferenceAudioAt: (index: number) => Promise<void>
  applyImportedReferenceVideos: (videos: ReferenceMediaItem[]) => void
  removeReferenceVideoAt: (index: number) => void
  applyVideoParamSelectionsFromPlain: (plain: string) => void
}

/** 提示词生成任务流（useVideoModalPromptFlows） */
export interface VideoModalPromptFlowsApi {
  restoreStoryboardVideoPromptGenerateIfNeeded: (sceneIdx: number) => Promise<void>
  handleImageToVideoGeneratePrompt: () => Promise<void>
  handleMultiParamGeneratePrompt: () => Promise<void>
}

/** 模型池与视频参数（useVideoModalModels） */
export interface VideoModalModelsApi {
  imageToVideoModelOptions: () => ModelOption[]
  multiParamVideoModelOptions: () => ModelOption[]
  edgeVideoModelOptions: () => ModelOption[]
  gridVideoModelOptions: () => ModelOption[]
  selectedImageToVideoModel: () => ModelOption
  selectedMultiParamVideoModel: () => ModelOption
  selectedEdgeVideoModel: () => ModelOption
  selectedGridVideoModel: () => ModelOption
  activeVideoModelGet: () => string
  videoRawModelList: () => UserModelListItem[]
  activeVideoRawModel: () => UserModelListItem | null
  activeVideoPromptMaxLength: (prompt: string) => number
  handleSelectImageToVideoModel: (model: ModelOption) => void
  handleSelectMultiParamVideoModel: (model: ModelOption) => void
  handleSelectEdgeVideoModel: (model: ModelOption) => void
  handleSelectGridVideoModel: (model: ModelOption) => void
  initVideoModelOptions: () => Promise<void>
  reapplyVideoModelDefaultIfEmpty: () => void
  applySavedVideoGenerateSettings: () => void
  resolveCurrentGenerateAudio: () => boolean
  syncVideoSettingsToModel: () => void
  applyRecommendedVideoDuration: () => void
  loadRecommendedDurationForScene: (options?: { force?: boolean }) => Promise<void>
  refreshRecommendedDurationAfterPromptGenerate: (storyboardId: number) => Promise<void>
  videoConfigShowDuration: () => boolean
  videoConfigShowAudio: () => boolean
  videoDurationTip: () => string
  videoAspectRatioOptions: () => SelectOption<string>[]
  videoDurationOptions: () => SelectOption<string>[]
  videoCountOptions: () => SelectOption<number>[]
  videoQualityOptions: () => SelectOption<string>[]
  videoAudioOptions: () => SelectOption<string>[]
  resolveImageVideoPromptAgentCode: () => string
  resolveGridVideoPromptAgentCode: () => string
  resolveMultiVideoPromptAgentCode: () => string
}

/** 参考图 / 素材 / 首尾帧（useVideoModalReferences） */
export interface VideoModalReferencesApi {
  referenceImageGet: () => ReferenceImageItem | null
  setReferenceImage: (v: ReferenceImageItem | null) => void
  resetStoryboardReferenceState: () => void
  applyDefaultStoryboardReferenceImages: (sceneIdx: number) => void
  resolveDefaultStoryboardReferenceImage: (sceneIdx: number) => ReferenceImageItem | null
  syncResolvedPromptAssetsToImportReferences: (
    assets: PromptAssetItem[],
    mode: 'imageToVideo' | 'multiParam'
  ) => void
  collectReferenceImageUrls: () => string[]
  validateImageToVideoReferenceImages: (images: string[]) => boolean
  normalizeImageToVideoReferenceItems: <T extends { url?: string; thumbnail?: string }>(items: T[]) => T[]
  resolveBaseImageRecordId: () => number | undefined
  handleImportReference: () => void
  handleMultiParamImportReference: () => void
  onSelectReferenceConfirm: (items: any[]) => void
  onSelectMultiParamReferenceConfirm: (items: any[]) => void
  onSelectAssetConfirm: (items: any[]) => void
  onSelectEdgeFrameConfirm: (items: any[]) => void
  openSelectAssetModal: (type: SelectAssetModalType) => void
  handleMaterialLibraryOtherImport: (assets: any[]) => void
  removeMultiParamAssetReference: (index: number) => void
  removeReferenceImageAt: (index: number) => void
  clearReferenceImage: () => void
  previewReferenceImage: (ref: { url?: string; thumbnail?: string }) => void
  onPreviewReferenceImage: () => void
  previewAssetImage: (img: any) => void
  removeOtherImage: (index: number) => void
  inferMultiParamAssetType: (item: any) => 'scene' | 'character' | 'prop' | 'other'
  onEdgeFrameCardClick: (target: 'first' | 'last') => void
  clearEdgeFrame: (target: 'first' | 'last') => void
  swapEdgeFrames: () => void
  buildEdgeFrameApiFields: (
    frame: EdgeFrameImage | null,
    role: 'first' | 'last'
  ) => { firstImageUrl?: string; lastImageUrl?: string; firstImageRecordId?: number; lastImageRecordId?: number }
  validateEdgeFrameImages: () => boolean
  showEdgeFrameSwap: () => boolean
  applyImageToVideoParamSettingsConfirm: (payload: ParamSettingsConfirmPayload) => void
  applyMultiParamSettingsConfirm: (payload: ParamSettingsConfirmPayload) => void
  referenceStepTabName: () => string
  currentPanelStoryboardImages: () => any[]
  storyboardScriptAssetGroups: () => { label: string; images: any[] }[]
  resolveSceneCoverImageUrl: (sceneIdx: number) => string
  cleanStoryboardScriptTabLabel: (raw: string, fallbackIndex: number) => string
}

/** 视频预览播放（useVideoPreviewPlayback） */
export interface VideoPreviewPlaybackApi {
  setVideoPreviewRef: (el: unknown, idx: number) => void
  getVideoPreviewEl: (idx: number) => HTMLVideoElement | null
  markVideoPreviewMediaReady: (idx: number) => void
  pauseAllVideoPreviews: (exceptIdx?: number) => void
  clearVideoPreviewRefs: () => void
  toggleVideoPreviewPlayback: (idx: number) => Promise<void>
  toggleSelectedVideoPreviewPlayback: () => void
  onVideoPreviewEnded: (idx: number) => void
  onVideoPreviewPause: (idx: number) => void
  handleFullscreenVideo: (idx: number) => Promise<void>
  handleDownloadVideo: (idx: number, v: any) => void
}

export type VideoModalCtx = VideoModalBaseCtx &
  VideoModalSessionApi &
  VideoModalRecordsApi &
  VideoModalGenerateApi &
  VideoModalGenerateActionsApi &
  VideoModalPromptApi &
  VideoModalPromptFlowsApi &
  VideoModalModelsApi &
  VideoModalReferencesApi &
  VideoPreviewPlaybackApi
