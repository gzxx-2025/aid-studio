import type { ReactNode } from 'react'
import type { ParamSettingsConfirmPayload } from '~/components/steps/StoryboardParamSettingsModal'
import type { PromptAssetItem } from '~/utils/storyboardPromptAssetRef'
import type { BillingQuoteRequest } from '~/types/business-api'

export type StoryboardGeneratePanelMode = 'storyboard' | 'imageToVideo' | 'storyboardVideo' | 'edgeVideo'

export type PanelSelection = { key: string; value: string } | null

export type PanelReferenceImage = {
  id?: string
  url?: string
  thumbnail?: string
  title?: string
} | null

export type PanelSelectModalType =
  | 'scene'
  | 'character'
  | 'prop'
  | 'pose'
  | 'expression'
  | 'effect'
  | 'draft'
  | 'other'

/** 原 defineProps + defineEmits 的 React 契约（emit('update:x') → onXChange） */
export interface StoryboardGeneratePanelProps {
  mode: StoryboardGeneratePanelMode
  sceneFileName?: string
  prompt: string
  promptPlaceholder?: string
  /** 当前所选模型的提示词字符上限；模型未配置时由调用方传业务默认值。 */
  promptMaxLength?: number
  sceneImages: any[]
  characterImages: any[]
  propImages: any[]
  otherImages: any[]
  isSettingExpanded: boolean
  /** 点击左侧分镜脚本条是否打开编辑（分镜脚本/分镜视频弹窗内为 true） */
  sceneFileClickable?: boolean
  /** 是否展示顶部分镜脚本文件头（首尾帧视频等场景可关闭） */
  showScriptFileHeader?: boolean
  /** 顶部是否显示「参考图」（分镜图弹窗内可关闭） */
  showReferenceButton?: boolean
  /** button：可点击上传；label：仅展示文案 */
  referenceDisplayMode?: 'button' | 'label'
  /** 顶部是否显示「生成提示词」（分镜图弹窗内可关闭） */
  showGeneratePromptButton?: boolean
  /** 生成提示词进行中 */
  generatePromptLoading?: boolean
  /** 顶部“生成提示词”的服务端权威报价请求。 */
  promptBillingRequest?: BillingQuoteRequest | null
  /** 是否显示「保存提示词」（分镜视频手动落库） */
  showSavePromptButton?: boolean
  /** 保存提示词进行中 */
  savePromptLoading?: boolean
  /** 与 PromptScriptFileHeader 一致：panel 为步骤页；modal-dark 为弹窗深色 */
  headerTheme?: 'panel' | 'modal-dark' | 'scene-modal'
  /** 头部左侧图标类型 */
  iconType?: 'scene' | 'file-text'
  // 图生视频
  selectedShootingTechnique?: PanelSelection
  activeVideoSettingKey?: string | null
  // 生成分镜图
  selectedComposition?: PanelSelection
  selectedShotSize?: PanelSelection
  selectedCameraAngle?: PanelSelection
  selectedFocalLength?: PanelSelection
  selectedColorTone?: PanelSelection
  selectedLighting?: PanelSelection
  selectedTechnique?: PanelSelection
  compositionDesc?: string
  activeSettingKey?: string | null
  /** 分镜视频·图生视频：九宫格与参考图 */
  nineGridEnabled?: boolean
  /** 分镜视频·图生视频：是否展示九宫格开关区（宫格出片模式可关闭；文本域上方「导入参考图」始终展示） */
  showStoryboardVideoAssets?: boolean
  referenceImage?: PanelReferenceImage
  referenceImages?: any[]
  /** 分镜视频：参考音频（官方音色 + 上传），展示在文本域上方素材条 */
  referenceAudios?: any[]
  /** 多参生视频：参考视频（aid_gen_record），展示在文本域上方素材条 */
  referenceVideos?: any[]
  /** 分镜视频·图生视频：镜头运动 */
  selectedCameraMovement?: PanelSelection
  cameraMovementDesc?: string
  /** 多参灵感空间内展示的图生视频参数（与多参参数分离） */
  imageToVideoNineGridEnabled?: boolean
  imageToVideoReferenceImages?: any[]
  imageToVideoSelectedCameraMovement?: PanelSelection
  imageToVideoCameraMovementDesc?: string
  imageToVideoSelectedShootingTechnique?: PanelSelection
  imageToVideoActiveVideoSettingKey?: string | null
  /**
   * 为 true 时按面板剩余高度精确计算中部双栏高度（适合固定高度父级）。
   * 为 false 时仅用断点高度 + 由外层滚动承载（适合分镜图弹窗 Tab 下整块滚动）。
   */
  usePreciseLayout?: boolean
  /** 接口解析出的 @图片N[name] 资产（与生成分镜图脚本对齐） */
  extraPromptAssets?: PromptAssetItem[]
  /** 为 true 时中间素材/参数区移入「灵感空间」弹窗，描述框撑满 */
  useParamSettingsModal?: boolean
  /** 父组件程序化回填 prompt 时暂停参数/资产联动，避免递归更新 */
  suppressPromptReactiveSync?: boolean

  /** 原默认插槽：模型配置区 */
  children?: ReactNode
  /** 原 #prompt-prefix 插槽：首尾帧图卡等 */
  promptPrefix?: ReactNode

  // ---- 原 defineEmits ----
  onPromptChange?: (value: string) => void
  onIsSettingExpandedChange?: (value: boolean) => void
  onGeneratePrompt?: () => void
  onSavePrompt?: () => void
  onImportReference?: () => void
  /** 文本域上方素材导入按钮文案；多参视频同时支持图片与视频时使用“导入参考素材” */
  referenceImportLabel?: string
  onOpenScript?: () => void
  onOpenSelectModal?: (type: PanelSelectModalType) => void
  onRemoveOtherImage?: (index: number) => void
  onRemoveMultiParamAssetReference?: (index: number) => void
  onRemoveReferenceImage?: (index: number) => void
  onRemoveReferenceAudio?: (index: number) => void | Promise<void>
  onRemoveReferenceVideo?: (index: number) => void
  onPreviewAssetImage?: (img: any) => void
  onPreviewReferenceImage?: (img: { url?: string; thumbnail?: string }) => void
  onCopyPrompt?: () => void
  onCopyCompositionDesc?: () => void
  onCompositionDescChange?: (value: string) => void
  onActiveSettingKeyChange?: (value: string | null) => void
  onActiveVideoSettingKeyChange?: (value: string | null) => void
  onSelectedShootingTechniqueChange?: (value: PanelSelection) => void
  onSelectedCompositionChange?: (value: PanelSelection) => void
  onSelectedShotSizeChange?: (value: PanelSelection) => void
  onSelectedCameraAngleChange?: (value: PanelSelection) => void
  onSelectedFocalLengthChange?: (value: PanelSelection) => void
  onSelectedColorToneChange?: (value: PanelSelection) => void
  onSelectedLightingChange?: (value: PanelSelection) => void
  onSelectedTechniqueChange?: (value: PanelSelection) => void
  onNineGridEnabledChange?: (value: boolean) => void
  onReferenceImageChange?: (value: PanelReferenceImage) => void
  onReferenceImagesChange?: (value: any[]) => void
  onSelectedCameraMovementChange?: (value: PanelSelection) => void
  onCameraMovementDescChange?: (value: string) => void
  onClearReference?: () => void
  onPreviewReference?: () => void
  onCopyCameraMovementDesc?: () => void
  onParamSettingsConfirm?: (payload: ParamSettingsConfirmPayload) => void
}

/** withDefaults 之后的 props（默认值已填充） */
export type ResolvedStoryboardGeneratePanelProps = StoryboardGeneratePanelProps & {
  sceneFileName: string
  sceneFileClickable: boolean
  showScriptFileHeader: boolean
  showReferenceButton: boolean
  referenceDisplayMode: 'button' | 'label'
  showGeneratePromptButton: boolean
  generatePromptLoading: boolean
  showSavePromptButton: boolean
  savePromptLoading: boolean
  headerTheme: 'panel' | 'modal-dark' | 'scene-modal'
  iconType: 'scene' | 'file-text'
  usePreciseLayout: boolean
  useParamSettingsModal: boolean
  suppressPromptReactiveSync: boolean
  showStoryboardVideoAssets: boolean
  referenceImages: any[]
  referenceAudios: any[]
  referenceVideos: any[]
  imageToVideoReferenceImages: any[]
  referenceImportLabel: string
  promptMaxLength: number
}

/** 原 defineExpose 契约（forwardRef + useImperativeHandle） */
export interface StoryboardGeneratePanelHandle {
  isParamSettingsOpen: () => boolean
  applyParamDraftAssets: (type: PanelSelectModalType, items: any[]) => void
  applyParamDraftReference: (item: any) => void
  applyParamDraftReferences: (items: any[]) => void
  insertPromptAssetRefsAtCaret: (assets: PromptAssetItem[]) => boolean
}
