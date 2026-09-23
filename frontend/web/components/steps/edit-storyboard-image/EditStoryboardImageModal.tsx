'use client'

import { CreateEditorViewport } from '~/components/common/CreateEditorViewport'

/**
 * 编辑分镜图弹窗（原 aid-pc/components/steps/EditStoryboardImageModal.vue，5029 行拆分迁移）。
 *
 * 对外 props 契约（原 defineProps / defineEmits）：
 * - open: boolean                           —— 原 v-model:open
 * - sceneIndex: number                      —— 打开时定位的分镜下标
 * - initialImageIndex?: number | null       —— 打开时定位的生成记录下标
 * - scenes: { name; images?; scriptContent?; storyboardId? }[]
 * - editorScopeKey?: string                 —— 弹窗实例作用域（默认 'storyboard-image'）
 * - onOpenChange(value)                     —— 原 emit('update:open')
 * - onUpdate(sceneIndex, data)              —— 原 emit('update', sceneIndex, data)
 *
 * 原调用点（后续创作壳批次接线，本批次暂不接）：
 * - components/steps/StoryboardScript.vue（分镜脚本步骤页，主入口）
 * 实现拆分见 ./edit-storyboard-image/ 子目录（controller + 8 个逻辑 hook + 骨架屏子视图）。
 */

import { Button, Input, Modal } from 'antd'
import { useMemo } from 'react'
import {
  ArrowLeftOutlined,
  UploadOutlined,
  FolderOutlined,
  PictureOutlined,
  LoadingOutlined
} from '@ant-design/icons'
import { HorizontalScrollTabBar } from '~/components/common/HorizontalScrollTabBar'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { HistoryRecordWrap } from '~/components/common/HistoryRecordWrap'
import { EllipsisTooltip } from '~/components/common/EllipsisTooltip'
import { SubmitAreaBilling } from '~/components/common/SubmitAreaBilling'
import { useBillingQuote } from '~/hooks/useBillingQuote'
import { ImportScriptModal } from '~/components/steps/ImportScriptModal'
import { DialogueDrawPanel } from '~/components/steps/DialogueDrawPanel'
import { StoryboardGeneratePanel } from '~/components/steps/StoryboardGeneratePanel'
import { GenerateModelConfigBlock } from '~/components/steps/GenerateModelConfigBlock'
import { StoryboardScriptModal } from '~/components/steps/StoryboardScriptModal'
import { ModelSelectDropdown } from '~/components/steps/ModelSelectDropdown'
import { UpscaleModelPopover } from '~/components/steps/UpscaleModelPopover'
import { SelectSceneImageModal } from '~/components/steps/SelectSceneImageModal'
import { SelectAssetImageModal } from '~/components/steps/SelectAssetImageModal'
import { MultiAngleCameraModal } from '~/components/steps/MultiAngleCameraModal'
import { TouchEditModal } from '~/components/steps/TouchEditModal'
import { assetUrl } from '~/utils/assetUrl'
import { storyboardPromptHtmlToPlain } from '~/utils/storyboardPromptAssetRef'
import { EDIT_ASSET_PROMPT_MAX_CHARS } from '~/utils/htmlPlain'
import { resolveModelPromptCharacterLimit } from '~/utils/modelCapability'
import type { BillingQuoteRequest } from '~/types/business-api'
import {
  resolveStoryboardPromptAgentCode,
  resolveStoryboardPromptModelCode
} from '~/utils/storyboardPromptGenerateFlow'
import drawingNorIconRaw from '~/assets/img/icon/drawing-nor.svg'
import drawingSelIconRaw from '~/assets/img/icon/drawing-sel.svg'
import chatNorIconRaw from '~/assets/img/icon/chat-nor.svg'
import chatSelIconRaw from '~/assets/img/icon/chat-sel.svg'
import hdNorIconRaw from '~/assets/img/icon/hd-nor.svg'
import hdSelIconRaw from '~/assets/img/icon/hd-sel.svg'
import cameraNorIconRaw from '~/assets/img/icon/camera-nor.svg'
import cameraSelIconRaw from '~/assets/img/icon/camera-sel.svg'
import addIconRaw from '~/assets/img/icon/add.svg'
import addSelIconRaw from '~/assets/img/icon/add-sel.svg'
import deleteIconRaw from '~/assets/img/icon/del-black.svg'
import starWhiteIconRaw from '~/assets/img/icon/star_white.svg'
import { useEditStoryboardImageModalController } from './useEditStoryboardImageModalController'
import { StoryboardModalSkeleton } from './StoryboardModalSkeleton'
import {
  buildStoryboardModalEditRequest,
  buildStoryboardModalGenerateRequest
} from './storyboardModalGenerateRequest'
import { STORYBOARD_GENERATED_IMAGE_DEFAULT_TITLE } from './types'
import type { CanvasToolbarKey, EditStoryboardImageModalProps } from './types'
import './edit-storyboard-image-modal.css'

const drawingNorIcon = assetUrl(drawingNorIconRaw)
const drawingSelIcon = assetUrl(drawingSelIconRaw)
const chatNorIcon = assetUrl(chatNorIconRaw)
const chatSelIcon = assetUrl(chatSelIconRaw)
const hdNorIcon = assetUrl(hdNorIconRaw)
const hdSelIcon = assetUrl(hdSelIconRaw)
const cameraNorIcon = assetUrl(cameraNorIconRaw)
const cameraSelIcon = assetUrl(cameraSelIconRaw)
const addIcon = assetUrl(addIconRaw)
const addSelIcon = assetUrl(addSelIconRaw)
const deleteIcon = assetUrl(deleteIconRaw)
const starWhiteIcon = assetUrl(starWhiteIconRaw)

const canvasToolbarIconMap: Record<CanvasToolbarKey, { nor: string; sel: string }> = {
  drawing: { nor: drawingNorIcon, sel: drawingSelIcon },
  chat: { nor: chatNorIcon, sel: chatSelIcon },
  hd: { nor: hdNorIcon, sel: hdSelIcon },
  camera: { nor: cameraNorIcon, sel: cameraSelIcon },
  add: { nor: addIcon, sel: addSelIcon }
}

export type { EditStoryboardImageModalProps }

export function EditStoryboardImageModal(props: EditStoryboardImageModalProps) {
  const { ctx, headerTabsForDisplay } = useEditStoryboardImageModalController(props)

  const getCanvasToolbarIcon = (key: CanvasToolbarKey) =>
    ctx.canvasToolbarHoverKey.value === key
      ? canvasToolbarIconMap[key].sel
      : canvasToolbarIconMap[key].nor

  const currentSceneIndex = ctx.currentSceneIndex.value
  const storyboardPromptMaxLength = resolveModelPromptCharacterLimit(
    ctx.selectedRawModel(),
    storyboardPromptHtmlToPlain(ctx.storyboardPrompt.value || ''),
    EDIT_ASSET_PROMPT_MAX_CHARS
  )
  const dialoguePromptMaxLength = resolveModelPromptCharacterLimit(
    ctx.dialogueSelectedRawModel(),
    storyboardPromptHtmlToPlain(ctx.dialogueInstructionHtml.value || ''),
    EDIT_ASSET_PROMPT_MAX_CHARS
  )
  const currentImageIndex = ctx.currentImageIndex.value
  const currentScene = ctx.currentScene()
  const currentSceneImages = ctx.currentSceneImages()
  const currentImg = ctx.currentImg()
  const currentGenRecordId = currentImg ? ctx.resolveStoryboardRecordId(currentImg) : null
  const leftActiveTab = ctx.leftActiveTab.value
  const selectedGenerateModel = ctx.selectedModel()
  const selectedDialogueModel = ctx.dialogueSelectedModel()
  const selectedGenerateModelCode = String(selectedGenerateModel?.id || '').trim()
  const selectedDialogueModelCode = String(selectedDialogueModel?.id || '').trim()
  const imageAgentCode = String(
    ctx.store().storyboardStylistGenerateSettings?.agentId ||
      ctx.store().storyboardStylistAgent?.id ||
      ''
  ).trim()
  const quoteRequest = useMemo<BillingQuoteRequest | null>(() => {
    if (!props.open) return null
    const storyboardId = ctx.currentStoryboardId()
    if (leftActiveTab === 'generate') {
      const imagePrompt = ctx.storyboardPromptPlainText().trim()
      const modelName = selectedGenerateModelCode
      if (!modelName) return null
      const settings = ctx.generationSettings.value
      const aspectRatio = String(settings.aspectRatio || '').trim() || '16:9'
      const size = String(settings.quality || '').trim().toUpperCase() || '2K'
      const imageCount = Math.max(1, Math.min(8, Number(settings.count) || 1))
      if (storyboardId && imagePrompt) {
        const payload = buildStoryboardModalGenerateRequest({
          storyboardIds: [storyboardId],
          ...(imageAgentCode ? { agentCode: imageAgentCode } : {}),
          imagePrompt,
          modelName,
          aspectRatio,
          size,
          count: imageCount
        })
        return { quoteType: 'STORYBOARD_IMAGE', payload: { ...payload } }
      }
      return {
        quoteType: 'MEDIA_IMAGE_PRICING',
        payload: {
          modelCode: modelName,
          generateMode: 'TEXT_TO_IMAGE',
          size,
          resolution: size,
          aspectRatio,
          imageCount,
          expectedImageCount: imageCount,
          referenceImageCount: 0
        }
      }
    }
    const referenceImage = String(ctx.dialogueSourceImages.value[0]?.url || '').trim()
    const prompt = storyboardPromptHtmlToPlain(ctx.dialogueInstructionHtml.value || '').trim()
    const modelCode = selectedDialogueModelCode
    if (!modelCode) return null
    const settings = ctx.dialogueSettings.value
    const aspectRatio = String(settings.aspectRatio || '').trim() || '16:9'
    const size = String(settings.quality || '2k').trim().toUpperCase() || '2K'
    const imageCount = Math.max(1, Math.min(4, Number(settings.count) || 1))
    if (storyboardId && referenceImage && prompt) {
      const payload = buildStoryboardModalEditRequest({
        storyboardId,
        referenceImage,
        prompt,
        modelCode,
        aspectRatio,
        size,
        imageCount
      })
      return { quoteType: 'STORYBOARD_EDIT_IMAGE', payload: { ...payload } }
    }
    return {
      quoteType: 'MEDIA_IMAGE_PRICING',
      payload: {
        modelCode,
        generateMode: 'IMAGE_EDIT',
        size,
        resolution: size,
        aspectRatio,
        imageCount,
        expectedImageCount: imageCount,
        referenceImageCount: 1
      }
    }
  }, [
    ctx.dialogueInstructionHtml.value,
    ctx.dialogueSettings.value,
    ctx.dialogueSourceImages.value,
    ctx.generationSettings.value,
    ctx.storyboardPrompt.value,
    currentSceneIndex,
    imageAgentCode,
    leftActiveTab,
    props.open,
    selectedDialogueModelCode,
    selectedGenerateModelCode
  ])
  const generationQuote = useBillingQuote(quoteRequest)
  const quoteModel = leftActiveTab === 'generate' ? selectedGenerateModel : selectedDialogueModel
  const quoteModelSelected = Boolean(quoteModel?.id)
  const quoteIdleText = quoteModelSelected && !quoteRequest ? '正在准备预计费用…' : ''
  const promptAgentCode = resolveStoryboardPromptAgentCode(
    ctx.store().storyboardStylistGenerateSettings
  )
  const promptModelCode = resolveStoryboardPromptModelCode(
    ctx.store().storyboardStylistGenerateSettings
  )
  const promptQuoteRequest = useMemo(() => {
    if (!props.open) return null
    const storyboardId = ctx.currentStoryboardId()
    const projectId = Number(ctx.store().currentProjectId)
    const episodeId = Number(ctx.store().currentEpisodeId)
    if (
      !storyboardId ||
      !Number.isFinite(projectId) ||
      projectId <= 0 ||
      !Number.isFinite(episodeId) ||
      episodeId < 0
    ) {
      return null
    }
    return {
      quoteType: 'STORYBOARD_IMAGE_PROMPT',
      payload: {
        projectId,
        episodeId,
        storyboardIds: [storyboardId],
        ...(promptAgentCode ? { agentCode: promptAgentCode } : {}),
        ...(promptModelCode ? { modelCode: promptModelCode } : {}),
        overwrite: true
      }
    }
  }, [currentSceneIndex, promptAgentCode, promptModelCode, props.open])

  return (
    <Modal
      open={props.open}
      width="100vw"
      style={{ top: 0, paddingBottom: 0, maxWidth: '100vw' }}
      footer={null}
      closable={false}
      mask={{ closable: false }}
      wrapClassName="create-flow-modal edit-scene-image-modal edit-storyboard-image-modal"
      className="edit-scene-image-modal edit-storyboard-image-modal"
      onCancel={ctx.handleCancel}
    >
      <CreateEditorViewport className="edit-scene-image-container">
        {/* 头部：返回按钮和场景切换 */}
        <div className="modal-header">
          <Button
            type="text"
            className="back-btn"
            icon={<ArrowLeftOutlined />}
            onClick={ctx.handleCancel}
          >
            <span>返回</span>
          </Button>
          <HorizontalScrollTabBar
            ref={ctx.sceneTabBarRef}
            rootClass="scene-switcher"
            trackClass="scene-switcher-track"
          >
            {headerTabsForDisplay.map((tab, index) => (
              <div
                key={tab.storyboardId ?? `scene-${index}`}
                className={[
                  'scene-image-tab',
                  currentSceneIndex === index ? 'active' : '',
                  ctx.isSceneModalImageGenerating(index) ? 'scene-image-tab--generating' : ''
                ]
                  .filter(Boolean)
                  .join(' ')}
                onClick={() => void ctx.switchScene(index)}
              >
                <div className="scene-image-thumbnail">
                  {tab.thumbnailUrl ? (
                    <ShimmerImage
                      src={tab.thumbnailUrl}
                      imgClass="thumbnail-image"
                      objectFit="cover"
                      revealDirection="fade"
                    />
                  ) : (
                    <div className="thumbnail-placeholder">
                      <PictureOutlined />
                    </div>
                  )}
                  {ctx.isSceneModalImageGenerating(index) && (
                    <div className="scene-tab-generating-mask" role="status" aria-live="polite">
                      <LoadingOutlined spin className="scene-tab-generating-mask__icon" />
                    </div>
                  )}
                </div>
                <span className="scene-label">{tab.name}</span>
              </div>
            ))}
          </HorizontalScrollTabBar>
        </div>

        {/* 主要内容区域：与 EditSceneImageModal 一致的三栏布局（左：生成记录 | 中：画布与列表 | 右：配置） */}
        <div className="main-content-wrapper">
          <div className="right-panel storyboard-right-panel">
            {ctx.leftPanelLoading.value || ctx.rightPanelLoading.value ? (
              <StoryboardModalSkeleton />
            ) : (
              <div className="figma-stage-layout storyboard-figma-stage">
                {/* 左栏：生成记录 + 导入 */}
                <aside className="stage-history-panel">
                  <h4 className="panel-title">生成记录</h4>
                  <div className="history-list">
                    {currentSceneImages.map((img, index) => (
                      <HistoryRecordWrap
                        key={`history-${index}`}
                        showSetMain={ctx.canSetMainFromHistory(index)}
                        isMain={ctx.isHistoryItemMain(index)}
                        setMainLabel="添加分镜图"
                        selectedMainLabel="已设置主图"
                        unsetMainLabel="取消主图"
                        setMainLoading={ctx.isSettingFinalImage.value}
                        onSetMain={() => void ctx.handleSetMainFromHistory(index)}
                        onUnsetMain={() => void ctx.handleCancelAddImage(index)}
                      >
                        <button
                          type="button"
                          className={[
                            'history-item',
                            currentImageIndex === index ? 'active' : '',
                            ctx.isHistoryItemMain(index) ? 'history-item--main' : '',
                            ctx.isHistoryItemGenerating(index) ? 'history-item--generating' : ''
                          ]
                            .filter(Boolean)
                            .join(' ')}
                          onClick={() => ctx.switchImage(index)}
                        >
                          {img.url ? (
                            <ShimmerImage
                              src={img.url}
                              alt={`历史图${index + 1}`}
                              imgClass="history-item__image"
                              objectFit="cover"
                              revealDirection="fade"
                            />
                          ) : !ctx.isHistoryItemGenerating(index) ? (
                            <div className="history-empty">空</div>
                          ) : null}
                          {ctx.isHistoryItemGenerating(index) && (
                            <div className="history-generating-mask" role="status" aria-live="polite">
                              <LoadingOutlined spin className="history-generating-mask__icon" />
                            </div>
                          )}
                          {ctx.canDeleteHistoryImage(img) && (
                            <div
                              className="history-delete-icon"
                              role="button"
                              tabIndex={0}
                              onClick={(e) => {
                                e.stopPropagation()
                                e.preventDefault()
                                ctx.handleDeleteImage(index)
                              }}
                              onKeyDown={(e) => {
                                if (e.key !== 'Enter') return
                                e.stopPropagation()
                                e.preventDefault()
                                ctx.handleDeleteImage(index)
                              }}
                            >
                              <img src={deleteIcon} alt="删除" />
                            </div>
                          )}
                        </button>
                      </HistoryRecordWrap>
                    ))}
                  </div>
                  <div className="history-actions">
                    <Button
                      block
                      loading={ctx.isUploadingLocalImage()}
                      disabled={ctx.isUploadingLocalImage()}
                      icon={<UploadOutlined />}
                      onClick={ctx.handleUploadLocalImage}
                    >
                      <EllipsisTooltip title="选择本地文件" />
                    </Button>
                    <Button block icon={<FolderOutlined />} onClick={ctx.handleOpenAssetLibrary}>
                      <EllipsisTooltip title="资产库导入" />
                    </Button>
                  </div>
                </aside>

                {/* 中栏：与 EditSceneImageModal 一致 — 主工具栏 + 大图预览 */}
                <section className="stage-canvas-panel storyboard-stage-canvas">
                  <div
                    ref={ctx.mainContentRef}
                    className="canvas-content-stack storyboard-canvas-stack"
                  >
                    {/* 与 EditSceneImageModal 一致：无图时也展示完整工具栏 + 带边框画布区 */}
                    <div className="canvas-toolbar">
                      {ctx.showTouchEditToolbar && (
                        <Button
                          type="text"
                          size="small"
                          className={
                            ctx.canvasToolbarHoverKey.value === 'drawing' ? 'toolbar-tab-hover' : ''
                          }
                          onMouseEnter={() => ctx.canvasToolbarHoverKey.set('drawing')}
                          onMouseLeave={() => ctx.canvasToolbarHoverKey.set(null)}
                          onClick={() => ctx.handleModifyImage(currentImageIndex)}
                          icon={
                            <img className="toolbar-tab-icon" src={getCanvasToolbarIcon('drawing')} alt="" />
                          }
                        >
                          点选改图
                        </Button>
                      )}
                      <Button
                        type="text"
                        size="small"
                        loading={ctx.showDialogueToolbarLoading()}
                        disabled={ctx.showDialogueToolbarLoading()}
                        className={ctx.canvasToolbarHoverKey.value === 'chat' ? 'toolbar-tab-hover' : ''}
                        onMouseEnter={() => ctx.canvasToolbarHoverKey.set('chat')}
                        onMouseLeave={() => ctx.canvasToolbarHoverKey.set(null)}
                        onClick={() => ctx.handleDialogueImage(currentImageIndex)}
                        icon={<img className="toolbar-tab-icon" src={getCanvasToolbarIcon('chat')} alt="" />}
                      >
                        对话作图
                      </Button>
                      <UpscaleModelPopover
                        imageIndex={currentImageIndex}
                        resolutionFormat="upper"
                        generating={ctx.showUpscaleToolbarLoading()}
                        prefetchedModels={ctx.upscaleModelPool.value}
                        billingTarget={
                          currentGenRecordId != null
                            ? { kind: 'storyboard', genRecordId: currentGenRecordId }
                            : undefined
                        }
                        onSelect={(payload) => void ctx.handleUpscaleModelSelect(payload)}
                      >
                        <Button
                          type="text"
                          size="small"
                          loading={ctx.showUpscaleToolbarLoading()}
                          disabled={ctx.showUpscaleToolbarLoading()}
                          className={ctx.canvasToolbarHoverKey.value === 'hd' ? 'toolbar-tab-hover' : ''}
                          onMouseEnter={() => ctx.canvasToolbarHoverKey.set('hd')}
                          onMouseLeave={() => ctx.canvasToolbarHoverKey.set(null)}
                          icon={<img className="toolbar-tab-icon" src={getCanvasToolbarIcon('hd')} alt="" />}
                        >
                          变清晰
                        </Button>
                      </UpscaleModelPopover>
                      <Button
                        type="text"
                        size="small"
                        loading={ctx.showMultiViewToolbarLoading()}
                        disabled={ctx.showMultiViewToolbarLoading()}
                        className={ctx.canvasToolbarHoverKey.value === 'camera' ? 'toolbar-tab-hover' : ''}
                        onMouseEnter={() => ctx.canvasToolbarHoverKey.set('camera')}
                        onMouseLeave={() => ctx.canvasToolbarHoverKey.set(null)}
                        onClick={() => ctx.handleMultiAngle(currentImageIndex)}
                        icon={<img className="toolbar-tab-icon" src={getCanvasToolbarIcon('camera')} alt="" />}
                      >
                        多机位
                      </Button>
                      {ctx.showCancelAddStoryboardImage() ? (
                        <Button
                          type="text"
                          size="small"
                          loading={ctx.isSettingFinalImage.value}
                          disabled={ctx.isSettingFinalImage.value}
                          className={ctx.canvasToolbarHoverKey.value === 'add' ? 'toolbar-tab-hover' : ''}
                          onMouseEnter={() => ctx.canvasToolbarHoverKey.set('add')}
                          onMouseLeave={() => ctx.canvasToolbarHoverKey.set(null)}
                          onClick={() => void ctx.handleCancelAddImage(currentImageIndex)}
                          icon={<img className="toolbar-tab-icon" src={getCanvasToolbarIcon('add')} alt="" />}
                        >
                          取消添加
                        </Button>
                      ) : (
                        <Button
                          type="text"
                          size="small"
                          className={ctx.canvasToolbarHoverKey.value === 'add' ? 'toolbar-tab-hover' : ''}
                          onMouseEnter={() => ctx.canvasToolbarHoverKey.set('add')}
                          onMouseLeave={() => ctx.canvasToolbarHoverKey.set(null)}
                          onClick={() => void ctx.handleAddStoryboardImage()}
                          icon={<img className="toolbar-tab-icon" src={getCanvasToolbarIcon('add')} alt="" />}
                        >
                          添加分镜图
                        </Button>
                      )}
                    </div>

                    {currentImg && (
                      <div className="storyboard-canvas-meta">
                        <div className="storyboard-canvas-meta-left">
                          {ctx.editingImageTitleIndex.value === currentImageIndex ? (
                            <Input
                              value={ctx.editingImageTitle.value}
                              onChange={(e) => ctx.editingImageTitle.set(e.target.value)}
                              size="small"
                              className="storyboard-meta-title-input"
                              onBlur={() => ctx.handleImageTitleBlur(currentImageIndex)}
                              onPressEnter={() => ctx.handleImageTitleBlur(currentImageIndex)}
                            />
                          ) : (
                            <span
                              className="storyboard-canvas-meta-title"
                              onClick={() => ctx.startEditImageTitle(currentImageIndex)}
                            >
                              {currentImg.title || STORYBOARD_GENERATED_IMAGE_DEFAULT_TITLE}
                            </span>
                          )}
                          {currentImg.source && <span className="image-source">{currentImg.source}</span>}
                          {currentImg.importDate && (
                            <span className="image-date">{ctx.formatDate(currentImg.importDate)}</span>
                          )}
                        </div>
                      </div>
                    )}

                    <div className={['canvas-preview', currentImageIndex >= 0 ? 'is-selected' : ''].filter(Boolean).join(' ')}>
                      <div className="canvas-image-frame canvas-image-frame--enhance-wrap">
                        {ctx.showCanvasImageGenMask() || ctx.showUpscaleRunningOverlay() ? (
                          <div className="canvas-upscale-mask" role="status" aria-live="polite">
                            <LoadingOutlined spin className="canvas-upscale-mask__icon" />
                            <p className="canvas-upscale-mask__text">
                              {ctx.showCanvasImageGenMask()
                                ? ctx.sceneImageGenMaskText()
                                : ctx.upscaleProgressText.value}
                            </p>
                          </div>
                        ) : ctx.showUpscaleFailedOverlay() ? (
                          <div className="canvas-upscale-mask canvas-upscale-mask--failed" role="alert">
                            <p className="canvas-upscale-mask__err">{ctx.upscaleFailedMessage.value}</p>
                            <Button size="small" type="primary" ghost onClick={ctx.clearUpscaleOverlay}>
                              知道了
                            </Button>
                          </div>
                        ) : null}
                        {currentImg?.angles && currentImg.angles.length === 4 ? (
                          <div className="four-grid-images four-grid-images--canvas">
                            {currentImg.angles.map((angle: any, angleIndex: number) => (
                              <div key={angleIndex} className="grid-image-item">
                                <ShimmerImage
                                  src={angle.url}
                                  imgClass="grid-image"
                                  objectFit="cover"
                                  revealDirection="fade"
                                  wrapperClass="grid-shimmer-image"
                                  onClick={() => ctx.handlePreviewImageUrl(angle.url)}
                                />
                                <div className="angle-label">{angle.angle}</div>
                              </div>
                            ))}
                          </div>
                        ) : currentImg?.url ? (
                          <ShimmerImage
                            src={currentImg.url}
                            imgClass="canvas-image"
                            objectFit="contain"
                            revealDirection="fade"
                            wrapperClass="canvas-shimmer-image"
                            onClick={ctx.handlePreviewCanvasImage}
                          />
                        ) : ctx.showCurrentGeneratingPlaceholder() ? (
                          <div className="canvas-empty canvas-generating">
                            <LoadingOutlined spin className="canvas-upscale-mask__icon" />
                            <p className="canvas-generating__text">{ctx.storyboardGenerateOverlayText()}</p>
                          </div>
                        ) : (
                          <div className="canvas-empty">还没有内容,先去左侧创建一个吧</div>
                        )}
                      </div>
                    </div>
                  </div>
                </section>

                {/* 右栏：模式 Tab + 生成配置（与 EditSceneImageModal 右侧一致） */}
                <aside className="stage-config-panel storyboard-stage-config">
                  <div className="config-tabs">
                    <button
                      type="button"
                      className={['config-tab', leftActiveTab === 'generate' ? 'active' : ''].filter(Boolean).join(' ')}
                      onClick={() => ctx.leftActiveTab.set('generate')}
                    >
                      生成分镜图
                    </button>
                    <button
                      type="button"
                      className={['config-tab', leftActiveTab === 'dialogue' ? 'active' : ''].filter(Boolean).join(' ')}
                      onClick={() => ctx.leftActiveTab.set('dialogue')}
                    >
                      对话作图
                    </button>
                  </div>
                  {/* Tab 以下：中间可滚动，底部「开始生图」固定在右栏 */}
                  <div className="storyboard-config-below-tabs">
                    <div className="storyboard-config-scroll create-modal-config-scroll">
                      <div className="config-body storyboard-config-body create-modal-config-body">
                        {leftActiveTab === 'generate' && (
                          <div className="storyboard-left create-modal-tab-panel">
                            <StoryboardGeneratePanel
                              ref={ctx.storyboardGeneratePanelRef}
                              mode="storyboard"
                              usePreciseLayout={false}
                              suppressPromptReactiveSync={
                                ctx.storyboardPromptProgrammaticSyncDepth.value > 0
                              }
                              sceneFileName={currentScene.name}
                              showReferenceButton={true}
                              referenceDisplayMode="label"
                              showGeneratePromptButton={true}
                              generatePromptLoading={ctx.showGeneratingPromptForScene()}
                              iconType="scene"
                              headerTheme="scene-modal"
                              prompt={ctx.storyboardPrompt.value}
                              promptMaxLength={storyboardPromptMaxLength}
                              onPromptChange={ctx.handleStoryboardPromptEditorChange}
                              promptPlaceholder="描述想要生成的画面，如：一只可爱的猫咪"
                              sceneImages={ctx.sceneImages.value}
                              characterImages={ctx.characterImages.value}
                              propImages={ctx.propImages.value}
                              otherImages={ctx.otherImages.value}
                              extraPromptAssets={ctx.resolvedPromptAssets.value}
                              isSettingExpanded={ctx.isSettingExpanded.value}
                              onIsSettingExpandedChange={(v) => ctx.isSettingExpanded.set(v)}
                              selectedComposition={ctx.selectedComposition.value}
                              selectedShotSize={ctx.selectedShotSize.value}
                              selectedCameraAngle={ctx.selectedCameraAngle.value}
                              selectedFocalLength={ctx.selectedFocalLength.value}
                              selectedColorTone={ctx.selectedColorTone.value}
                              selectedLighting={ctx.selectedLighting.value}
                              selectedTechnique={ctx.selectedTechnique.value}
                              compositionDesc={ctx.compositionDesc.value}
                              activeSettingKey={ctx.activeSettingKey.value}
                              onActiveSettingKeyChange={(v) =>
                                ctx.activeSettingKey.set(v as typeof ctx.activeSettingKey.value)
                              }
                              onOpenScript={ctx.openStoryboardScriptEditor}
                              promptBillingRequest={promptQuoteRequest}
                              onGeneratePrompt={() => void ctx.handleGeneratePrompt()}
                              onOpenSelectModal={ctx.openSelectModal}
                              onRemoveOtherImage={ctx.removeOtherImage}
                              onRemoveMultiParamAssetReference={ctx.removeStoryboardAssetReference}
                              onPreviewAssetImage={ctx.previewAssetImage}
                              onCopyPrompt={ctx.copyStoryboardPrompt}
                              onCopyCompositionDesc={ctx.copyCompositionDesc}
                              onSelectedCompositionChange={(v) => ctx.selectedComposition.set(v)}
                              onSelectedShotSizeChange={(v) => ctx.selectedShotSize.set(v)}
                              onSelectedCameraAngleChange={(v) => ctx.selectedCameraAngle.set(v)}
                              onSelectedFocalLengthChange={(v) => ctx.selectedFocalLength.set(v)}
                              onSelectedColorToneChange={(v) => ctx.selectedColorTone.set(v)}
                              onSelectedLightingChange={(v) => ctx.selectedLighting.set(v)}
                              onSelectedTechniqueChange={(v) => ctx.selectedTechnique.set(v)}
                              onCompositionDescChange={(v) => ctx.compositionDesc.set(v)}
                              onParamSettingsConfirm={ctx.applyParamSettingsConfirm}
                            >
                              <GenerateModelConfigBlock
                                aspectRatio={ctx.generationSettings.value.aspectRatio}
                                onAspectRatioChange={(v) =>
                                  ctx.generationSettings.set({
                                    ...ctx.generationSettings.get(),
                                    aspectRatio: v
                                  })
                                }
                                count={ctx.generationSettings.value.count}
                                onCountChange={(v) =>
                                  ctx.generationSettings.set({
                                    ...ctx.generationSettings.get(),
                                    count: v
                                  })
                                }
                                quality={ctx.generationSettings.value.quality}
                                onQualityChange={(v) =>
                                  ctx.generationSettings.set({
                                    ...ctx.generationSettings.get(),
                                    quality: v
                                  })
                                }
                                aspectRatioOptions={ctx.aspectRatioSelectOptions}
                                countOptions={ctx.countSelectOptions}
                                qualityOptions={ctx.qualitySelectOptions}
                                selectClass="setting-select"
                                density="scene"
                                showQuality3k={true}
                                showAction={false}
                                modelSlot={
                                  <ModelSelectDropdown
                                    value={ctx.selectedModel()}
                                    options={ctx.modelOptions}
                                    expanded={ctx.modelDropdownExpanded.value}
                                    onToggle={() =>
                                      ctx.modelDropdownExpanded.set(!ctx.modelDropdownExpanded.get())
                                    }
                                    onClose={() => ctx.modelDropdownExpanded.set(false)}
                                    onSelect={ctx.handleSelectModel}
                                  />
                                }
                              />
                            </StoryboardGeneratePanel>
                          </div>
                        )}
                        {leftActiveTab === 'dialogue' && (
                          <DialogueDrawPanel
                            sourceType="storyboard"
                            maxSourceCount={1}
                            sourceImages={ctx.dialogueSourceImages.value}
                            instructionHtml={ctx.dialogueInstructionHtml.value}
                            promptMaxLength={dialoguePromptMaxLength}
                            modelValue={ctx.dialogueSelectedModel()}
                            modelOptions={ctx.dialogueModelOptions}
                            modelExpanded={ctx.dialogueModelDropdownExpanded.value}
                            aspectRatio={ctx.dialogueSettings.value.aspectRatio}
                            count={ctx.dialogueSettings.value.count}
                            quality={ctx.dialogueSettings.value.quality}
                            aspectRatioOptions={ctx.dialogueAspectRatioSelectOptions}
                            countOptions={ctx.dialogueCountSelectOptions}
                            qualityOptions={ctx.dialogueQualitySelectOptions}
                            onOpenSourcePicker={() => ctx.showDialogueImportModal.set(true)}
                            onRemoveSourceImage={ctx.removeDialogueSourceImage}
                            onInstructionHtmlChange={(v) => ctx.dialogueInstructionHtml.set(v)}
                            onModelExpandedChange={(v) => ctx.dialogueModelDropdownExpanded.set(v)}
                            onSelectModel={ctx.handleSelectDialogueModel}
                            onAspectRatioChange={(v) =>
                              ctx.dialogueSettings.set({ ...ctx.dialogueSettings.get(), aspectRatio: v })
                            }
                            onCountChange={(v) =>
                              ctx.dialogueSettings.set({ ...ctx.dialogueSettings.get(), count: v })
                            }
                            onQualityChange={(v) =>
                              ctx.dialogueSettings.set({ ...ctx.dialogueSettings.get(), quality: v })
                            }
                          />
                        )}
                      </div>
                    </div>
                    {leftActiveTab === 'generate' ? (
                      <div className="storyboard-config-footer">
                        <SubmitAreaBilling
                          modelSelected={quoteModelSelected}
                          billing={quoteModel?.billing}
                          hasQuoteRequest={Boolean(quoteRequest)}
                          quote={generationQuote.quote}
                          loading={generationQuote.loading}
                          error={generationQuote.error}
                          idleText={quoteIdleText}
                        />
                        <Button
                          type="primary"
                          block
                          size="large"
                          className="generate-btn"
                          loading={ctx.showStoryboardGenerateButtonLoading()}
                          disabled={
                            ctx.showStoryboardGenerateButtonLoading() ||
                            ctx.showGeneratingPromptForScene()
                          }
                          icon={<img src={starWhiteIcon} alt="" />}
                          onClick={() => void ctx.handleStartGenerate()}
                        >
                          开始生图
                        </Button>
                      </div>
                    ) : leftActiveTab === 'dialogue' ? (
                      <div className="storyboard-config-footer">
                        <SubmitAreaBilling
                          modelSelected={quoteModelSelected}
                          billing={quoteModel?.billing}
                          hasQuoteRequest={Boolean(quoteRequest)}
                          quote={generationQuote.quote}
                          loading={generationQuote.loading}
                          error={generationQuote.error}
                          idleText={quoteIdleText}
                        />
                        <Button
                          type="primary"
                          block
                          size="large"
                          className="generate-btn"
                          loading={ctx.showGeneratingDialogueButton()}
                          disabled={ctx.showGeneratingDialogueButton()}
                          icon={<img src={starWhiteIcon} alt="" />}
                          onClick={() => void ctx.handleStartDialogueDraw()}
                        >
                          开始作图
                        </Button>
                      </div>
                    ) : null}
                  </div>
                </aside>
              </div>
            )}
          </div>
        </div>
      </CreateEditorViewport>

      {/* 资源库导入弹窗（主内容区添加分镜图） */}
      <ImportScriptModal
        open={ctx.showAssetLibraryModal.value}
        onOpenChange={(v) => ctx.showAssetLibraryModal.set(v)}
        title="导入图片"
        acceptAssetType="image"
        onImport={ctx.handleAssetLibraryImport}
      />
      <SelectSceneImageModal
        open={ctx.showDialogueImportModal.value}
        onOpenChange={(v) => ctx.showDialogueImportModal.set(v)}
        scenes={props.scenes}
        editingSceneIndex={currentSceneIndex}
        multiple
        title="选择分镜画面"
        onSelectMultiple={ctx.handleDialogueImportMultiple}
      />
      {/* 姿态图/表情图/特效图：直达素材库对应子库 */}
      <ImportScriptModal
        open={ctx.showMaterialFromLibraryModal.value}
        onOpenChange={(v) => ctx.showMaterialFromLibraryModal.set(v)}
        title="导入图片"
        multiple={true}
        acceptAssetType="image"
        initialTab="material"
        initialMaterialCategory={ctx.materialLibraryCategoryKey.value}
        onImportMultiple={ctx.handleMaterialLibraryOtherImport}
      />

      {/* 选择场景/角色/道具/其他 通用弹窗（多选） */}
      <SelectAssetImageModal
        open={ctx.selectAssetModalOpen.value}
        onOpenChange={(v) => ctx.selectAssetModalOpen.set(v)}
        type={ctx.selectAssetModalType.value}
        stepTabName={currentScene.name?.trim() || `分镜${currentSceneIndex + 1}`}
        stepPanelImages={currentSceneImages}
        onConfirm={ctx.onSelectAssetConfirm}
      />

      <StoryboardScriptModal
        key={`sb-img-${currentSceneIndex}-${ctx.scriptEditorKey.value}`}
        open={ctx.showStoryboardScriptModal.value}
        onOpenChange={(v) => ctx.showStoryboardScriptModal.set(v)}
        panelTitle={currentScene.name}
        initialContent={ctx.currentScriptContentForModal()}
        onSave={ctx.handleSaveScriptInImageModal}
        onTitleChange={ctx.handleScriptTitleInImageModal}
      />
      <MultiAngleCameraModal
        open={ctx.showMultiAngleModal.value}
        onOpenChange={(v) => ctx.showMultiAngleModal.set(v)}
        imageUrl={ctx.multiAngleImageUrl.value}
        fixedNineGrid
        modelValue={ctx.nineGridSelectedModel()}
        modelOptions={ctx.nineGridModelOptions}
        modelExpanded={ctx.multiViewModelDropdownExpanded.value}
        onModelExpandedChange={(v) => ctx.multiViewModelDropdownExpanded.set(v)}
        onSelectModel={ctx.handleSelectNineGridModel}
        billingTarget={
          ctx.currentStoryboardId()
            ? {
                kind: 'storyboard',
                storyboardId: ctx.currentStoryboardId()!,
                aspectRatio: ctx.nineGridAspectRatio.value || '1:1'
              }
            : undefined
        }
        onGenerate={(payload) => void ctx.handleMultiAngleGenerate(payload)}
      />
      {ctx.showTouchEditToolbar && (
        <TouchEditModal
          open={ctx.showTouchEditModal.value}
          onOpenChange={(v) => ctx.showTouchEditModal.set(v)}
          imageUrl={ctx.touchEditImageUrl.value}
        />
      )}
    </Modal>
  )
}

export default EditStoryboardImageModal
