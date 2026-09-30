import type { GlobalSettingData,WorkData } from '~/types'
import type { UserProjectType } from '~/types/business-api'
import type { TaskPartialFailedData } from '~/utils/taskPartialFailed'
import { EMPTY_COUNT_PROGRESS } from '~/utils/taskSseProgressText'
import type { ContextActions } from './contextSlice'
import type { ExtractActions } from './extractSlice'
import type { ModalTaskActions } from './modalTaskSlice'
import type { Step3Actions } from './step3Slice'
import type { Step4Actions } from './step4Slice'
import type {
CharacterImage,
ExtractAgents,
ExtractModelCodesMap,
ExtractUiScopeSnapshot,
OptionalModelCodesScopeSnapshot,
PendingExtractFormAssetItem,
SceneGenerationStatus,
SceneImage,
Step3GenVisualScopeMaps,
Step4PlusLiveGenSnapshot,
StoryboardVideoSettingsScopeSnapshot
} from './types'
import type { VideoBatchActions } from './videoBatchSlice'

/** 与原 Pinia state() 一一对应的初始状态（字段名、初始值、注释逐一保留） */
export function createInitialCreationState() {
  return {
    // 作品标题
    workTitle: '未命名作品',

    // 当前步骤索引
    currentStepIndex: 0,

    // 当前创作上下文（用于步骤接口等需要 projectId/episodeId 的场景）
    currentProjectId: null as number | null,
    /** 电影可为 0；未选择剧集时为 null */
    currentEpisodeId: null as number | null,
    /** 与后端一致：movie=电影（剧本 episodeId 用 0），series=剧集 */
    currentProjectType: null as UserProjectType | null,
    /** 剧集：是否已从「上传剧本」首屏进入剧本创作（避免 /create 再次挡回上传页） */
    seriesFlowEnteredStoryScript: false,
    /** 剧集列表页：分集总数（供顶栏「共 N 集」展示） */
    seriesEpisodeListTotal: null as number | null,
    /** 当前项目审核/公开状态（来自 project/detail） */
    currentProjectStatus: null as number | null,
    currentProjectStatusReason: null as string | null,
    currentProjectIsPublic: null as string | null,
    /** 当前项目/剧集成片上下文（电影取项目级，剧集取当前集） */
    currentEpisodeEditorId: null as number | null,
    currentFinalVideoUrl: null as string | null,
    currentPendingVideoUrl: null as string | null,
    currentExportStatus: null as number | null,
    currentEpisodeStatus: null as number | null,
    currentEpisodeStatusReason: null as string | null,

    // 表单数据
    formData: {
      globalSetting: {
        title: '',
        genre: '',
        style: '',
        description: '',
        aspectRatio: '16:9' as const,
        scriptType: 'plot' as const,
        modelStrategy: 'economy' as const,
        creationMode: 'pro' as const,
        selectedStyle: null as GlobalSettingData['selectedStyle'],
        styleSelectionTouched: false,
        styleLocked: false,
        myStyles: [] as Array<{ id: string; name: string; thumbnail: string }>
      },
      storyScript: {
        content: ''
      },
      sceneCharacter: {
        characters: [] as string[],
        scenes: [] as string[],
        props: [] as string[]
      },
      storyboardScript: {
        panels: [] as import('~/types').StoryboardPanel[]
      },
      storyboardVideo: {
        panels: [] as import('~/types').StoryboardVideoPanel[]
      },
      dubbing: {
        voiceActors: [] as string[],
        bgm: '',
        panels: [] as import('~/types').DubbingPanel[]
      }
    } as WorkData,

    /** 第三步：rps 列表 bootstrap（拉取 + pending 对齐 + 任务恢复）是否已完成 */
    step3AssetListSyncReady: false,

    // 第三步：提取智能体选择与提取状态
    showExtractAgentModal: false,
    isExtractingAssets: false,
    extractingStage: 'scene' as 'scene' | 'character' | 'prop',
    extractingStages: {
      scene: false,
      character: false,
      prop: false
    },
    /** 第三步：提取任务进度（SSE/轮询都会更新它） */
    extractingTaskProgress: {
      percent: 0,
      stepTitle: '',
      message: '',
      stepIndex: null as number | null,
      stepTotal: null as number | null
    },
    extractAgents: {
      scene: { id: '', name: '', desc: '', thumbnail: '' },
      character: { id: '', name: '', desc: '', thumbnail: '' },
      prop: { id: '', name: '', desc: '', thumbnail: '' }
    } as ExtractAgents,
    /** 智能提取 / 形态文案（text）modelCode */
    extractModelCodes: {
      scene: '',
      character: '',
      prop: ''
    } as ExtractModelCodesMap,
    /** 形态图 generate-image / 批量生图（image）modelCode，与 extractModelCodes 分桶 */
    extractImageModelCodes: {
      scene: '',
      character: '',
      prop: ''
    } as ExtractModelCodesMap,
    /** 与 step3GenVisualByScope 同 key：作品内各剧集/跨作品互不污染 modelCode */
    optionalModelCodesByScope: {} as Record<string, OptionalModelCodesScopeSnapshot>,
    /** 分镜视频生成设置（智能体/模型/比例等），按作品:剧集分桶 */
    storyboardVideoSettingsByScope: {} as Record<string, StoryboardVideoSettingsScopeSnapshot>,

    // 素材准备的扩展数据（图片、形态等）
    sceneImages: {} as Record<number, SceneImage[]>,
    characterImages: {} as Record<number, CharacterImage[]>,
    propImages: {} as Record<number, CharacterImage[]>,
    characterFormImages: {} as Record<string, CharacterImage[]>, // key: "characterIndex-formIndex"
    propFormImages: {} as Record<string, CharacterImage[]>, // key: "propIndex-formIndex"
    manualScenes: [] as number[], // 手动添加的场景索引数组
    /** 手动添加场景的主资产 id（刷新后按 id 恢复，不依赖列表索引） */
    manualSceneAssetIds: [] as number[],
    manualCharacters: [] as number[], // 手动添加的角色索引数组
    manualProps: [] as number[], // 手动添加的道具索引数组
    /** 手动添加的分镜 id（服务端 storyboard id，刷新后按 id 恢复） */
    manualStoryboardIds: [] as number[],
    characterForms: {} as Record<number, Array<{ name: string; voiceover?: string }>>,
    propForms: {} as Record<number, Array<{ name: string }>>,
    sceneGenerationStatus: {} as Record<number, SceneGenerationStatus>,
    characterFormGenerationStatus: {} as Record<string, SceneGenerationStatus>,
    propFormGenerationStatus: {} as Record<string, SceneGenerationStatus>,
    /** key 与 step3GenVisualScopeKey() 一致：`${projectId}:${episodeKey}` */
    step3GenVisualByScope: {} as Record<string, Step3GenVisualScopeMaps>,
    /** 智能提取 loading 按作品/剧集分桶（内存态，切作品时写入/读出） */
    extractUiByScope: {} as Record<string, ExtractUiScopeSnapshot>,
    /** 当前会话内各 scope 正在连接的 asset_extract SSE taskId（连接关闭后必须清除，否则切换回来不会重连） */
    assetExtractFollowByScope: {} as Record<string, number>,
    /** CreateFlowShell 内 useCreateFlowExtractAgents 当前仍打开的 SSE taskId */
    assetExtractShellLiveTaskId: null as number | null,
    /** 配音步骤：批量生成中的分镜下标（与分镜脚本/视频 loading 一并按作品隔离） */
    dubbingBatchGeneratingIndices: [] as number[],
    /** 分镜脚本 / 分镜视频 / 配音批量 等 UI 状态按作品分桶 */
    step4PlusLiveGenByScope: {} as Record<string, Step4PlusLiveGenSnapshot>,
    // 分镜脚本：生成设置与生成状态
    storyboardAgent: {
      id: '',
      name: '',
      desc: '',
      thumbnail: ''
    },
    storyboardGenerateSettings: {
      agentId: '',
      /** StoryboardShotDensityEnum.value，如「标准模式」 */
      shotDensity: '标准模式' as string,
      /** 分镜脚本智能体弹窗所选 modelCode，用于 POST /storyboard/generate/script */
      modelCode: ''
    },
    /** 分镜图提示词（image-prompt）：biz_category=main_storyboard_stylist */
    storyboardStylistAgent: {
      id: '',
      name: '',
      desc: '',
      thumbnail: ''
    },
    storyboardStylistGenerateSettings: {
      agentId: '',
      /** 文本模型 modelCode；未选则不传 image-prompt 的 modelCode */
      modelCode: ''
    },
    // 分镜视频：生成设置（未在「生成设置」选择前保持为空，接口不传 agentCode/modelCode）
    storyboardVideoAgent: {
      id: '',
      name: '',
      desc: '',
      thumbnail: ''
    },
    storyboardVideoGenerateSettings: {
      agentId: '',
      videoModel: '',
      videoPromptModelCode: '',
      aspectRatio: '16:9' as string,
      resolution: '720p',
      durationSeconds: undefined as number | undefined,
      soundEffects: 'with-sound' as 'none' | 'with-sound'
    },
    isGeneratingStoryboard: false,
    storyboardGenerationProgress: { ...EMPTY_COUNT_PROGRESS },
    storyboardGenerationError: null as string | null,
    /** 当前作品 scope 内进行中的分镜脚本批量任务 id（与 step4PlusLiveGenByScope 同步） */
    storyboardScriptActiveTaskId: null as number | null,
    storyboardScriptPartialFailedData: null as TaskPartialFailedData | null,
    /** 分镜图批量生成中（提示词 + 出图），与分镜脚本批量隔离 */
    isGeneratingStoryboardImageBatch: false,
    storyboardImageBatchProgress: { ...EMPTY_COUNT_PROGRESS },
    storyboardImageBatchError: null as string | null,
    storyboardImageBatchActiveTaskId: null as number | null,
    storyboardImageBatchActiveImageTaskId: null as number | null,
    storyboardPanelImageGenStatusByStoryboardId: {} as Record<string, SceneGenerationStatus>,
    storyboardImageBatchTargetStoryboardIds: [] as number[],
    // 分镜视频自动生成中（列表每项 loading、左侧步骤 loading、顶部进度 loading、按钮变停止生成）
    isGeneratingStoryboardVideo: false,
    storyboardVideoBatchProgress: { ...EMPTY_COUNT_PROGRESS },
    storyboardVideoBatchError: null as string | null,
    storyboardVideoBatchActivePromptTaskId: null as number | null,
    storyboardVideoBatchActiveVideoTaskId: null as number | null,
    storyboardPanelVideoGenStatusByStoryboardId: {} as Record<string, SceneGenerationStatus>,
    storyboardPanelVideoGenErrorByStoryboardId: {} as Record<string, string>,
    storyboardVideoBatchTargetStoryboardIds: [] as number[],

    /** 第三步：场景/角色形态/道具形态「自动生成」进行中的视觉反馈（流程步骤条 loading） */
    isGeneratingStep3Visual: false,

    /**
     * 第三步形态图 SSE 跟进计数（跨流程步骤切换时仍保持流程条 loading，组件卸载不重置）。
     * 与 scene/character/prop GenerationStatus 中的 generating 一并驱动 isGeneratingStep3Visual。
     */
    step3FormImageTaskFollowCount: 0,

    /**
     * 已 begin 尚未 end 的形态相关 taskId（切 Tab 断 SSE 不 end，切回重连勿重复 begin）。
     * 与 step3FormImageTaskFollowCount 成对维护。
     */
    step3FormImageTaskFollowTaskIds: [] as number[],

    // 持久化数据是否已恢复（用于避免刷新时的步骤闪烁）
    isHydrated: false,

    /** 与后端已同步的剧本 Markdown 原文（静默保存 dirty 判断；加载/显式保存后更新，不写入 persist paths） */
    scriptServerHtmlBaseline: '',
    /** 当前作品/剧集剧本 comicVersion（detail/save 回写；不 persist，切作品清零） */
    scriptComicVersion: 0,
    /** 提取弹窗按钮模式：空列表「开始提取」或剧本变更后「继续/重新提取」 */
    extractModalActionMode: 'start' as 'start' | 'continueOrReextract',
    /** 素材准备页：剧本有效变更轻提示条 */
    scriptChangeLightBannerVisible: false,
    /** 强提示点「去提取」后：跳转素材页再打开继续/重新提取弹窗 */
    pendingOpenContinueExtractModal: false,

    /** 提取完成、尚未执行形态生成的资产（小卡片列表，不写入 persist） */
    pendingExtractFormAssets: [] as PendingExtractFormAssetItem[],

    /**
     * 顶部「进行中」里用户已点「停止」的任务 id（不持久化）。
     * 用于在服务端仍返回 PROCESSING 时展示「继续跟进」而非再次自动连 SSE。
     */
    taskIdsWithLocalFollowPaused: [] as number[]
  }
}

export type CreationStateData = ReturnType<typeof createInitialCreationState>

export type CreationActions = ContextActions &
  ExtractActions &
  Step3Actions &
  Step4Actions &
  ModalTaskActions &
  VideoBatchActions

export type CreationStoreState = CreationStateData & CreationActions

/** slice 内部使用的 set/get 类型（与 zustand setState/getState 兼容） */
export type CreationSet = (
  partial:
    | Partial<CreationStoreState>
    | ((state: CreationStoreState) => Partial<CreationStoreState>)
) => void
export type CreationGet = () => CreationStoreState
