'use client'

import { CreateEditorViewport } from '~/components/common/CreateEditorViewport'

/**
 * 编辑场景图/形态图弹窗（原 aid-pc/components/steps/EditSceneImageModal.vue，5665 行）。
 *
 * 对外契约（原 defineProps / defineEmits）：
 * - props：open、sceneIndex、initialImageIndex?、scenes、imageType?('scene'|'character'|'prop'|'form')、
 *   rpsAssetId?、rpsFormIds?、rpsAssetIdsByIndex?、rpsFormIdsByIndex?、formParentAssetType?、
 *   manualSettingEditBlockedTooltip?、editorScopeKey?、canAutoGenerateImage?、
 *   onAutoRegenerateImage?（完整定义见 edit-scene-image/types.ts 的 EditSceneImageModalProps）
 * - 事件回调：onOpenChange(value)（原 emit('update:open')）、
 *   onUpdate(sceneIndex, data, editorScopeKey?)（原 emit('update')）
 *
 * 原调用点（创作壳批次迁移时接线，本批次暂不接线）：
 * - components/steps/SceneCharacterProp.vue：场景/角色/道具三个列表的「编辑」入口
 *   （imageType=scene/character/prop + rpsAssetIdsByIndex/rpsFormIdsByIndex）
 * - 角色/道具「形态」Tab 编辑入口（imageType=form + rpsAssetId/rpsFormIds + formParentAssetType + editorScopeKey）
 */
import { useMemo, useRef, useState } from 'react'
import { Button, Input, message, Modal, Tooltip } from 'antd'
import {
  ArrowLeftOutlined,
  UploadOutlined,
  FolderOutlined,
  PictureOutlined,
  LoadingOutlined
} from '@ant-design/icons'
import {
  HorizontalScrollTabBar
} from '~/components/common/HorizontalScrollTabBar'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { HistoryRecordWrap } from '~/components/common/HistoryRecordWrap'
import { EllipsisTooltip } from '~/components/common/EllipsisTooltip'
import { BillingQuoteConfirm } from '~/components/common/BillingQuoteConfirm'
import { SubmitAreaBilling } from '~/components/common/SubmitAreaBilling'
import { useBillingQuote } from '~/hooks/useBillingQuote'
import { SceneSettingModal } from './SceneSettingModal'
import { resolveSettingEditBlockedTooltip } from './edit-scene-image/settingEditPermission'
import {
  buildSceneModalGenerateRequest,
  resolveSceneModalGenerationFormId
} from './edit-scene-image/sceneModalGenerateRequest'
import { ImportScriptModal } from './ImportScriptModal'
import { DialogueDrawPanel } from './DialogueDrawPanel'
import { UpscaleModelPopover } from './UpscaleModelPopover'
import { SettingCardImagePopover } from './SettingCardImagePopover'
import { SelectSceneImageModal } from './SelectSceneImageModal'
import { TouchEditModal } from './TouchEditModal'
import { MultiAngleCameraModal } from './MultiAngleCameraModal'
import { FORM_IMAGE_REFERENCE_LIMIT } from '~/utils/formImageEditPrefill'
import { shouldShowAssetImageRegenerateAction } from '~/utils/assetImageActionMode'
import { assetUrl } from '~/utils/assetUrl'
import { isFormIdUnderActiveStep3FormImageTask } from '~/utils/step3FormImageTaskRegistry'
import { storyboardPromptHtmlToPlain } from '~/utils/storyboardPromptAssetRef'
import { EDIT_ASSET_PROMPT_MAX_CHARS } from '~/utils/htmlPlain'
import { resolveModelPromptCharacterLimit } from '~/utils/modelCapability'
import type { BillingQuoteRequest } from '~/types/business-api'
import {
  useEditSceneImageModalController
} from './edit-scene-image/useEditSceneImageModalController'
import type { CanvasToolbarKey, EditSceneImageModalProps } from './edit-scene-image/types'
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
import fourlNorIconRaw from '~/assets/img/icon/fourl-nor.svg'
import fourlSelIconRaw from '~/assets/img/icon/fourl-sel.svg'
import deleteIconRaw from '~/assets/img/icon/del-black.svg'
import starWhiteIconRaw from '~/assets/img/icon/star_white.svg'
import regenerateIconRaw from '~/assets/img/icon/autGenerate.svg'
import regenerateNorIconRaw from '~/assets/img/icon/cxsc-icon.svg'
import '~/assets/css/history-record-card.css'
import './edit-scene-image/edit-scene-image.css'
import './edit-scene-image/edit-scene-image-panels.css'
import './edit-scene-image/edit-scene-image-stage.css'

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
const fourlNorIcon = assetUrl(fourlNorIconRaw)
const fourlSelIcon = assetUrl(fourlSelIconRaw)
const deleteIcon = assetUrl(deleteIconRaw)
const starWhiteIcon = assetUrl(starWhiteIconRaw)
const regenerateIcon = assetUrl(regenerateIconRaw)
const regenerateNorIcon = assetUrl(regenerateNorIconRaw)

const canvasToolbarIconMap: Record<CanvasToolbarKey, { nor: string; sel: string }> = {
  drawing: { nor: drawingNorIcon, sel: drawingSelIcon },
  regenerate: { nor: regenerateNorIcon, sel: regenerateIcon },
  chat: { nor: chatNorIcon, sel: chatSelIcon },
  hd: { nor: hdNorIcon, sel: hdSelIcon },
  camera: { nor: cameraNorIcon, sel: cameraSelIcon },
  add: { nor: addIcon, sel: addSelIcon },
  fourGrid: { nor: fourlNorIcon, sel: fourlSelIcon }
}

export type { EditSceneImageModalProps }

export function EditSceneImageModal(props: EditSceneImageModalProps) {
  const c = useEditSceneImageModalController(props)

  const [canvasToolbarHoverKey, setCanvasToolbarHoverKey] = useState<CanvasToolbarKey | null>(null)
  const autoRegenerateSubmittingScopesRef = useRef(new Set<string>())
  const [autoRegenerateSubmittingScopeKeys, setAutoRegenerateSubmittingScopeKeys] = useState<
    string[]
  >([])
  const getCanvasToolbarIcon = (key: CanvasToolbarKey) =>
    canvasToolbarHoverKey === key ? canvasToolbarIconMap[key].sel : canvasToolbarIconMap[key].nor

  const currentImg = c.currentImg()
  const currentSceneImages = c.currentSceneImages()
  const currentSceneIndex = c.currentSceneIndex.value
  const settingEditBlockedTooltip = resolveSettingEditBlockedTooltip(
    props.manualSettingEditBlockedTooltip,
    currentSceneIndex
  )
  const currentAutoRegenerateScopeKey = `${c.currentModalLiveGenScopeKey()}::${c.buildEditorScopeKeyForSceneIndex(currentSceneIndex)}`
  const showAutoRegenerate = Boolean(
    props.onAutoRegenerateImage &&
      shouldShowAssetImageRegenerateAction(
        currentImg,
        props.canAutoGenerateImage?.(currentSceneIndex) === true
      )
  )
  const autoRegenerateSubmitting = autoRegenerateSubmittingScopeKeys.includes(
    currentAutoRegenerateScopeKey
  )
  const selectedDialogueModel = c.selectedDialogueModel()
  const selectedDialogueModelCode = String(selectedDialogueModel?.id || '').trim()
  const dialoguePromptMaxLength = resolveModelPromptCharacterLimit(
    c.selectedDialogueRawModel(),
    storyboardPromptHtmlToPlain(c.dialogueInstructionHtml.value || ''),
    EDIT_ASSET_PROMPT_MAX_CHARS
  )
  const currentImageFormId = Number(
    (currentImg as { rpsFormId?: unknown } | null)?.rpsFormId ?? NaN
  )
  const resolvedCurrentFormId = resolveSceneModalGenerationFormId(
    currentImg as Record<string, unknown> | null,
    c.activeRpsFormIds()
  )
  const quoteRequest = useMemo<BillingQuoteRequest | null>(() => {
    if (!props.open) return null
    const prompt = storyboardPromptHtmlToPlain(c.dialogueInstructionHtml.value || '').trim()
    const modelCode = selectedDialogueModelCode
    if (!modelCode) return null
    const referenceImages = c.dialogueSourceImages.value
      .map((image) => String(image.url || '').trim())
      .filter(Boolean)
    const settings = c.dialogueSettings.value
    const aspectRatio = String(settings.aspectRatio || '').trim() || '1:1'
    const size = String(settings.quality || '').trim().toUpperCase() || '2K'
    const imageCount = Math.max(1, Math.min(4, Number(settings.count) || 1))
    if (resolvedCurrentFormId != null && prompt) {
      const payload = buildSceneModalGenerateRequest({
        formId: resolvedCurrentFormId,
        genMode: 'chat',
        ...(referenceImages.length ? { referenceImages } : {}),
        prompt,
        modelCode,
        aspectRatio,
        size,
        imageCount
      })
      return { quoteType: 'FORM_EDIT_CHAT_IMAGE', payload: { ...payload } }
    }
    return {
      quoteType: 'MEDIA_IMAGE_PRICING',
      payload: {
        modelCode,
        generateMode: referenceImages.length ? 'IMAGE_EDIT' : 'TEXT_TO_IMAGE',
        size,
        resolution: size,
        aspectRatio,
        imageCount,
        expectedImageCount: imageCount,
        referenceImageCount: referenceImages.length
      }
    }
  }, [
    c.dialogueInstructionHtml.value,
    c.dialogueSettings.value,
    c.dialogueSourceImages.value,
    props.open,
    resolvedCurrentFormId,
    selectedDialogueModelCode
  ])
  const generationQuote = useBillingQuote(quoteRequest)
  const quoteModel = selectedDialogueModel
  const quoteModelSelected = Boolean(quoteModel?.id)
  const quoteIdleText = quoteModelSelected && !quoteRequest ? '正在准备预计费用…' : ''
  // 外层槽位状态还承载设定卡等任务；重新生成只认 form_image，避免跨按钮 loading 污染。
  const isAutoRegenerateTaskRunning = (sceneIndex: number) => {
    const hasCurrentImageFormId =
      sceneIndex === currentSceneIndex &&
      Number.isFinite(currentImageFormId) &&
      currentImageFormId > 0
    const formId =
      hasCurrentImageFormId ? currentImageFormId : c.resolveFormIdForSceneIndex(sceneIndex)
    if (formId != null && isFormIdUnderActiveStep3FormImageTask(formId)) return true
    return c.resolveActiveSceneModalTaskKind(sceneIndex) === 'form-image'
  }
  const autoRegenerateGenerating = isAutoRegenerateTaskRunning(currentSceneIndex)
  const autoRegenerateDisabled = autoRegenerateSubmitting || autoRegenerateGenerating

  const handleAutoRegenerate = async () => {
    const action = props.onAutoRegenerateImage
    const sceneIndex = c.currentSceneIndex.get()
    const imageIndex = c.currentImageIndex.get()
    const image = c.currentImg()
    const scopeKey = `${c.currentModalLiveGenScopeKey()}::${c.buildEditorScopeKeyForSceneIndex(sceneIndex)}`
    if (
      !action ||
      autoRegenerateSubmittingScopesRef.current.has(scopeKey) ||
      isAutoRegenerateTaskRunning(sceneIndex) ||
      !shouldShowAssetImageRegenerateAction(
        image,
        props.canAutoGenerateImage?.(sceneIndex) === true
      )
    ) {
      return
    }

    autoRegenerateSubmittingScopesRef.current.add(scopeKey)
    setAutoRegenerateSubmittingScopeKeys((scopeKeys) =>
      scopeKeys.includes(scopeKey) ? scopeKeys : [...scopeKeys, scopeKey]
    )
    try {
      await action(sceneIndex, imageIndex, image)
    } catch (error: unknown) {
      const err = error as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '重新生成失败，请稍后重试')
    } finally {
      autoRegenerateSubmittingScopesRef.current.delete(scopeKey)
      setAutoRegenerateSubmittingScopeKeys((scopeKeys) =>
        scopeKeys.filter((item) => item !== scopeKey)
      )
    }
  }

  const autoRegenerateButton = (
    <Button
      type="text"
      size="small"
      loading={autoRegenerateSubmitting || autoRegenerateGenerating}
      disabled={autoRegenerateDisabled}
      className={canvasToolbarHoverKey === 'regenerate' ? 'toolbar-tab-hover' : ''}
      onMouseEnter={() => setCanvasToolbarHoverKey('regenerate')}
      onMouseLeave={() => setCanvasToolbarHoverKey(null)}
      onClick={props.resolveAutoRegenerateBillingRequest ? undefined : () => void handleAutoRegenerate()}
      icon={
        <img
          className="toolbar-tab-icon"
          src={getCanvasToolbarIcon('regenerate')}
          alt=""
        />
      }
    >
      重新生成
    </Button>
  )

  return (
    <Modal
      open={props.open}
      width={'100vw'}
      style={{ top: 0, paddingBottom: 0, maxWidth: '100vw' }}
      footer={null}
      closable={false}
      mask={{ closable: false }}
      wrapClassName="create-flow-modal edit-scene-image-modal"
      className="edit-scene-image-modal"
      onCancel={c.handleCancel}
    >
      <CreateEditorViewport className="edit-scene-image-container">
        {/* 头部：返回按钮和场景切换 */}
        <div className="modal-header">
          <Button type="text" className="back-btn" icon={<ArrowLeftOutlined />} onClick={c.handleCancel}>
            <span>返回</span>
          </Button>
          <HorizontalScrollTabBar
            ref={c.sceneTabBarRef}
            rootClass="scene-switcher"
            trackClass="scene-switcher-track"
          >
            {props.scenes.map((scene, index) => (
              <div
                key={index}
                className={[
                  'scene-image-tab',
                  (c.isSelectingSceneImage.value
                    ? c.selectedSceneImageIndex.value === index
                    : c.currentSceneIndex.value === index)
                    ? 'active'
                    : '',
                  c.isSelectingSceneImage.value ? 'selecting-mode' : '',
                  c.isSceneModalImageGenerating(index) ? 'scene-image-tab--generating' : ''
                ]
                  .filter(Boolean)
                  .join(' ')}
                onClick={() =>
                  c.isSelectingSceneImage.value ? c.selectSceneImageFromTab(index) : c.switchScene(index)
                }
              >
                <div className="scene-image-thumbnail">
                  {c.getFirstSceneImage(index)?.url ? (
                    <ShimmerImage
                      src={c.getFirstSceneImage(index)!.url}
                      imgClass="thumbnail-image"
                      objectFit="cover"
                      revealDirection="fade"
                    />
                  ) : (
                    <div className="thumbnail-placeholder">
                      <PictureOutlined />
                    </div>
                  )}
                  {c.isSceneModalImageGenerating(index) && (
                    <div className="scene-tab-generating-mask" role="status" aria-live="polite">
                      <LoadingOutlined spin className="scene-tab-generating-mask__icon" />
                    </div>
                  )}
                </div>
                <span className="scene-label">{scene.name}</span>
              </div>
            ))}
          </HorizontalScrollTabBar>
        </div>

        <div className="main-content-wrapper">
          <div className="right-panel">
            {c.rightPanelLoading.value ? (
              <div className="panel-skeleton right-panel-skeleton">
                <div className="skeleton-stage-layout">
                  <aside className="skeleton-history-panel">
                    <div className="skeleton-panel-title"></div>
                    <div className="skeleton-history-list">
                      {[1, 2, 3, 4, 5, 6].map((n) => (
                        <div key={`sk-h-${n}`} className="skeleton-history-item"></div>
                      ))}
                    </div>
                    <div className="skeleton-history-actions">
                      <div className="skeleton-btn"></div>
                      <div className="skeleton-btn"></div>
                    </div>
                  </aside>

                  <section className="skeleton-canvas-panel">
                    <div className="skeleton-canvas-toolbar">
                      {[1, 2, 3, 4, 5].map((n) => (
                        <div key={`sk-t-${n}`} className="skeleton-chip"></div>
                      ))}
                    </div>
                    <div className="skeleton-canvas-main"></div>
                  </section>

                  <aside className="skeleton-config-panel">
                    <div className="skeleton-config-tabs">
                      <div className="skeleton-tab"></div>
                      <div className="skeleton-tab"></div>
                    </div>
                    <div className="skeleton-file-row"></div>
                    <div className="skeleton-textarea"></div>
                    <div className="skeleton-select-row">
                      <div className="skeleton-select"></div>
                      <div className="skeleton-select"></div>
                      <div className="skeleton-select"></div>
                      <div className="skeleton-select"></div>
                    </div>
                    <div className="skeleton-primary-btn"></div>
                  </aside>
                </div>
              </div>
            ) : (
              <div className="figma-stage-layout">
                <aside className="stage-history-panel">
                  <h4 className="panel-title">生成记录</h4>
                  <div className="history-list">
                    {currentSceneImages.map((img, index) => (
                      <HistoryRecordWrap
                        key={`history-${index}`}
                        showSetMain={c.canSetMainFromHistory(index)}
                        isMain={c.isHistoryItemMain(index)}
                        setMainLabel={c.addImageButtonLabel()}
                        selectedMainLabel="已设置主图"
                        unsetMainLabel="取消主图"
                        setMainLoading={c.isAddingSceneImage() || c.isCancellingAdd()}
                        onSetMain={() => void c.handleSetMainFromHistory(index)}
                        onUnsetMain={() => void c.handleCancelAddImage(index)}
                      >
                        <button
                          type="button"
                          className={[
                            'history-item',
                            c.currentImageIndex.value === index ? 'active' : '',
                            c.isHistoryItemMain(index) ? 'history-item--main' : '',
                            c.isHistoryItemGenerating(index) ? 'history-item--generating' : ''
                          ]
                            .filter(Boolean)
                            .join(' ')}
                          onClick={() => void c.switchImage(index)}
                        >
                          {img.url ? (
                            <ShimmerImage
                              src={img.url}
                              alt={`历史图${index + 1}`}
                              imgClass="history-item__image"
                              objectFit="cover"
                              revealDirection="fade"
                            />
                          ) : !c.isHistoryItemGenerating(index) ? (
                            <div className="history-empty">空</div>
                          ) : null}
                          {c.isHistoryItemGenerating(index) && (
                            <div className="history-generating-mask" role="status" aria-live="polite">
                              <LoadingOutlined spin className="history-generating-mask__icon" />
                            </div>
                          )}
                          {c.canDeleteHistoryImage(img) && (
                            <div
                              className="history-delete-icon"
                              role="button"
                              tabIndex={0}
                              onClick={(e) => {
                                e.stopPropagation()
                                e.preventDefault()
                                c.handleDeleteImage(index)
                              }}
                              onKeyDown={(e) => {
                                if (e.key === 'Enter') {
                                  e.stopPropagation()
                                  e.preventDefault()
                                  c.handleDeleteImage(index)
                                }
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
                    <Button block icon={<UploadOutlined />} onClick={c.handleUploadLocalImage}>
                      <EllipsisTooltip title="选择本地文件" />
                    </Button>
                    <Button block icon={<FolderOutlined />} onClick={c.handleOpenAssetLibrary}>
                      <EllipsisTooltip title="资产库导入" />
                    </Button>
                  </div>
                </aside>

                <section className="stage-canvas-panel">
                  <div className="canvas-content-stack">
                    <div className="canvas-toolbar">
                      {c.showTouchEditToolbar && (
                        <Button
                          type="text"
                          size="small"
                          className={canvasToolbarHoverKey === 'drawing' ? 'toolbar-tab-hover' : ''}
                          onMouseEnter={() => setCanvasToolbarHoverKey('drawing')}
                          onMouseLeave={() => setCanvasToolbarHoverKey(null)}
                          onClick={() => c.handleModifyImage(c.currentImageIndex.value)}
                          icon={<img className="toolbar-tab-icon" src={getCanvasToolbarIcon('drawing')} alt="" />}
                        >
                          点选改图
                        </Button>
                      )}
                      {showAutoRegenerate && props.resolveAutoRegenerateBillingRequest ? (
                        <BillingQuoteConfirm
                          key={currentAutoRegenerateScopeKey}
                          disabled={autoRegenerateDisabled}
                          title="确认重新生成当前形态图？"
                          resolveRequest={() => {
                            const sceneIndex = c.currentSceneIndex.get()
                            return props.resolveAutoRegenerateBillingRequest!(
                              sceneIndex,
                              c.currentImageIndex.get(),
                              c.currentImg()
                            )
                          }}
                          onConfirm={handleAutoRegenerate}
                        >
                          {autoRegenerateButton}
                        </BillingQuoteConfirm>
                      ) : showAutoRegenerate ? (
                        autoRegenerateButton
                      ) : null}
                      {c.showToolbarSettingCard() && !c.whiteBaseImageReadyForSettingCard() ? (
                        <Tooltip title="请先选择平台生成或本地上传的角色图">
                          <span className="canvas-toolbar-tooltip-wrap">
                            <Button
                              type="text"
                              size="small"
                              disabled
                              className={canvasToolbarHoverKey === 'chat' ? 'toolbar-tab-hover' : ''}
                              onMouseEnter={() => setCanvasToolbarHoverKey('chat')}
                              onMouseLeave={() => setCanvasToolbarHoverKey(null)}
                              icon={<img className="toolbar-tab-icon" src={getCanvasToolbarIcon('chat')} alt="" />}
                            >
                              生成设定图
                            </Button>
                          </span>
                        </Tooltip>
                      ) : c.showToolbarSettingCard() ? (
                        <SettingCardImagePopover
                          imageIndex={c.currentImageIndex.value}
                          isSupported={c.isSettingCardTypeSupported()}
                          isWhiteBaseReady={c.whiteBaseImageReadyForSettingCard()}
                          generating={c.showSettingCardToolbarLoading()}
                          billingImageId={
                            Number.isFinite(
                              Number((currentImg as { rpsImageId?: unknown } | null)?.rpsImageId)
                            ) &&
                            Number((currentImg as { rpsImageId?: unknown } | null)?.rpsImageId) > 0
                              ? Number(
                                  (currentImg as { rpsImageId?: unknown } | null)?.rpsImageId
                                )
                              : undefined
                          }
                          onSelect={(payload) => void c.handleSettingCardSelect(payload)}
                        >
                          <Button
                            type="text"
                            size="small"
                            loading={c.showSettingCardToolbarLoading()}
                            disabled={c.showSettingCardToolbarLoading()}
                            className={canvasToolbarHoverKey === 'chat' ? 'toolbar-tab-hover' : ''}
                            onMouseEnter={() => setCanvasToolbarHoverKey('chat')}
                            onMouseLeave={() => setCanvasToolbarHoverKey(null)}
                            icon={<img className="toolbar-tab-icon" src={getCanvasToolbarIcon('chat')} alt="" />}
                          >
                            生成设定图
                          </Button>
                        </SettingCardImagePopover>
                      ) : c.isSceneEditMode() ? (
                        <Button
                          type="text"
                          size="small"
                          loading={c.showSceneSplitToolbarLoading()}
                          disabled={c.showSceneSplitToolbarLoading()}
                          className={canvasToolbarHoverKey === 'fourGrid' ? 'toolbar-tab-hover' : ''}
                          onMouseEnter={() => setCanvasToolbarHoverKey('fourGrid')}
                          onMouseLeave={() => setCanvasToolbarHoverKey(null)}
                          onClick={() => void c.handleSceneSplitFourGrid(c.currentImageIndex.value)}
                          icon={<img className="toolbar-tab-icon" src={getCanvasToolbarIcon('fourGrid')} alt="" />}
                        >
                          拆分四宫格
                        </Button>
                      ) : (
                        <Button
                          type="text"
                          size="small"
                          className={canvasToolbarHoverKey === 'chat' ? 'toolbar-tab-hover' : ''}
                          onMouseEnter={() => setCanvasToolbarHoverKey('chat')}
                          onMouseLeave={() => setCanvasToolbarHoverKey(null)}
                          onClick={() => c.handleDialogueImage(c.currentImageIndex.value)}
                          icon={<img className="toolbar-tab-icon" src={getCanvasToolbarIcon('chat')} alt="" />}
                        >
                          对话作图
                        </Button>
                      )}
                      <UpscaleModelPopover
                        imageIndex={c.currentImageIndex.value}
                        resolutionFormat="lower"
                        generating={c.showUpscaleToolbarLoading()}
                        prefetchedModels={c.upscaleModelPool.value}
                        billingTarget={
                          Number.isFinite(Number((currentImg as { rpsImageId?: unknown } | null)?.rpsImageId)) &&
                          Number((currentImg as { rpsImageId?: unknown } | null)?.rpsImageId) > 0
                            ? {
                                kind: 'form',
                                imageId: Number(
                                  (currentImg as { rpsImageId?: unknown } | null)?.rpsImageId
                                )
                              }
                            : undefined
                        }
                        onSelect={(payload) => void c.handleUpscaleModelSelect(payload)}
                      >
                        <Button
                          type="text"
                          size="small"
                          loading={c.showUpscaleToolbarLoading()}
                          disabled={c.showUpscaleToolbarLoading()}
                          className={canvasToolbarHoverKey === 'hd' ? 'toolbar-tab-hover' : ''}
                          onMouseEnter={() => setCanvasToolbarHoverKey('hd')}
                          onMouseLeave={() => setCanvasToolbarHoverKey(null)}
                          icon={<img className="toolbar-tab-icon" src={getCanvasToolbarIcon('hd')} alt="" />}
                        >
                          变清晰
                        </Button>
                      </UpscaleModelPopover>
                      <Button
                        type="text"
                        size="small"
                        loading={c.showMultiViewToolbarLoading()}
                        disabled={c.showMultiViewToolbarLoading()}
                        className={canvasToolbarHoverKey === 'camera' ? 'toolbar-tab-hover' : ''}
                        onMouseEnter={() => setCanvasToolbarHoverKey('camera')}
                        onMouseLeave={() => setCanvasToolbarHoverKey(null)}
                        onClick={() => c.handleMultiAngle(c.currentImageIndex.value)}
                        icon={<img className="toolbar-tab-icon" src={getCanvasToolbarIcon('camera')} alt="" />}
                      >
                        多机位
                      </Button>
                      {currentImg?._pending ? (
                        <Button
                          type="text"
                          size="small"
                          loading={c.isAddingSceneImage()}
                          disabled={c.isAddingSceneImage()}
                          className={canvasToolbarHoverKey === 'add' ? 'toolbar-tab-hover' : ''}
                          onMouseEnter={() => setCanvasToolbarHoverKey('add')}
                          onMouseLeave={() => setCanvasToolbarHoverKey(null)}
                          onClick={() => void c.handleAddSceneImage()}
                          icon={<img className="toolbar-tab-icon" src={getCanvasToolbarIcon('add')} alt="" />}
                        >
                          {c.addImageButtonLabel()}
                        </Button>
                      ) : currentImg && c.isCurrentImageCancelAddVisible() ? (
                        <Button
                          type="text"
                          size="small"
                          loading={c.isCancellingAdd()}
                          disabled={Boolean(c.cancelAddDisabledTooltip()) || c.isCancellingAdd()}
                          className={canvasToolbarHoverKey === 'add' ? 'toolbar-tab-hover' : ''}
                          onMouseEnter={() => setCanvasToolbarHoverKey('add')}
                          onMouseLeave={() => setCanvasToolbarHoverKey(null)}
                          onClick={() => void c.handleCancelAddImage(c.currentImageIndex.value)}
                          icon={<img className="toolbar-tab-icon" src={getCanvasToolbarIcon('add')} alt="" />}
                        >
                          {c.cancelAddDisabledTooltip() ? (
                            <Tooltip title={c.cancelAddDisabledTooltip()}>
                              <span>取消添加</span>
                            </Tooltip>
                          ) : (
                            <>取消添加</>
                          )}
                        </Button>
                      ) : (
                        <Button
                          type="text"
                          size="small"
                          loading={c.isAddingSceneImage()}
                          disabled={c.isAddingSceneImage()}
                          className={canvasToolbarHoverKey === 'add' ? 'toolbar-tab-hover' : ''}
                          onMouseEnter={() => setCanvasToolbarHoverKey('add')}
                          onMouseLeave={() => setCanvasToolbarHoverKey(null)}
                          onClick={() => void c.handleAddSceneImage()}
                          icon={<img className="toolbar-tab-icon" src={getCanvasToolbarIcon('add')} alt="" />}
                        >
                          {c.addImageButtonLabel()}
                        </Button>
                      )}
                    </div>
                    {/* 与 EditStoryboardImageModal 中间卡片头部一致：展示当前图标题（与外层列表同源） */}
                    {currentImg && (
                      <div className="scene-canvas-meta">
                        <div className="scene-canvas-meta-left">
                          {c.editingImageTitleIndex.value === c.currentImageIndex.value ? (
                            <Input
                              value={c.editingImageTitle.value}
                              onChange={(e) => c.editingImageTitle.set(e.target.value)}
                              size="small"
                              className="scene-meta-title-input"
                              onBlur={() => void c.handleImageTitleBlur(c.currentImageIndex.value)}
                              onPressEnter={() => void c.handleImageTitleBlur(c.currentImageIndex.value)}
                            />
                          ) : (
                            <span
                              className="scene-canvas-meta-title"
                              onClick={() => c.startEditImageTitle(c.currentImageIndex.value)}
                            >
                              {c.currentImageDisplayTitle()}
                            </span>
                          )}
                        </div>
                      </div>
                    )}
                    <div className={`canvas-preview${c.currentImageIndex.value >= 0 ? ' is-selected' : ''}`}>
                      <div className="canvas-image-frame canvas-image-frame--enhance-wrap">
                        {c.showSceneSplitOverlay() ? (
                          <div className="canvas-upscale-mask" role="status" aria-live="polite">
                            <LoadingOutlined spin className="canvas-upscale-mask__icon" />
                            <p className="canvas-upscale-mask__text">{c.sceneSplitProgressText.value}</p>
                          </div>
                        ) : c.showCanvasTaskRunningOverlay() ? (
                          <div className="canvas-upscale-mask" role="status" aria-live="polite">
                            <LoadingOutlined spin className="canvas-upscale-mask__icon" />
                            <p className="canvas-upscale-mask__text">{c.upscaleProgressText.value}</p>
                          </div>
                        ) : c.showUpscaleFailedOverlay() ? (
                          <div className="canvas-upscale-mask canvas-upscale-mask--failed" role="alert">
                            <p className="canvas-upscale-mask__err">{c.upscaleFailedMessage.value}</p>
                            <Button size="small" type="primary" ghost onClick={c.clearUpscaleOverlay}>
                              知道了
                            </Button>
                          </div>
                        ) : null}
                        {currentImg?.url ? (
                          <ShimmerImage
                            src={currentImg.url}
                            imgClass="canvas-image"
                            objectFit="contain"
                            revealDirection="fade"
                            wrapperClass="canvas-shimmer-image"
                            onClick={c.handlePreviewCanvasImage}
                          />
                        ) : c.showCurrentGeneratingPlaceholder() ? (
                          <div className="canvas-empty canvas-generating">
                            <LoadingOutlined spin className="canvas-upscale-mask__icon" />
                            <p className="canvas-generating__text">{c.sceneGenerateOverlayText()}</p>
                          </div>
                        ) : (
                          <div className="canvas-empty">还没有内容,先去左侧创建一个吧</div>
                        )}
                      </div>
                    </div>
                  </div>
                </section>

                <aside className="stage-config-panel">
                  <div className="config-tabs">
                    <button type="button" className="config-tab active">
                      对话作图
                    </button>
                  </div>
                  <div className="scene-config-below-tabs">
                    <div className="scene-config-scroll create-modal-config-scroll">
                      <div className="config-body create-modal-config-body">
                        <DialogueDrawPanel
                          sourceType="asset"
                          maxSourceCount={FORM_IMAGE_REFERENCE_LIMIT}
                          sourceImages={c.dialogueSourceImages.value}
                          instructionHtml={c.dialogueInstructionHtml.value}
                          promptMaxLength={dialoguePromptMaxLength}
                          modelValue={c.selectedDialogueModel()}
                          modelOptions={c.dialogueModelOptions}
                          modelExpanded={c.dialogueModelDropdownExpanded.value}
                          aspectRatio={c.dialogueSettings.value.aspectRatio}
                          count={c.dialogueSettings.value.count}
                          quality={c.dialogueSettings.value.quality}
                          aspectRatioOptions={c.dialogueAspectRatioSelectOptions}
                          countOptions={c.dialogueCountSelectOptions}
                          qualityOptions={c.dialogueQualitySelectOptions}
                          onOpenSourcePicker={() => c.showDialogueImportModal.set(true)}
                          onRemoveSourceImage={c.removeDialogueSourceImage}
                          onInstructionHtmlChange={(v) => c.dialogueInstructionHtml.set(v)}
                          onModelExpandedChange={(v) => c.dialogueModelDropdownExpanded.set(v)}
                          onSelectModel={c.handleSelectDialogueModel}
                          onAspectRatioChange={(v) =>
                            c.dialogueSettings.set({ ...c.dialogueSettings.get(), aspectRatio: v })
                          }
                          onCountChange={(v) =>
                            c.dialogueSettings.set({ ...c.dialogueSettings.get(), count: v })
                          }
                          onQualityChange={(v) =>
                            c.dialogueSettings.set({ ...c.dialogueSettings.get(), quality: v })
                          }
                        />
                      </div>
                    </div>
                    <div className="scene-config-footer">
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
                        loading={c.showGenerateFooterButtonLoading()}
                        disabled={c.showGenerateFooterButtonLoading()}
                        onClick={() => void c.handleStartDialogueGenerate()}
                        icon={<img src={starWhiteIcon} alt="" />}
                      >
                        开始生图
                      </Button>
                    </div>
                  </div>
                </aside>
              </div>
            )}
          </div>
        </div>
      </CreateEditorViewport>

      {/* 场景设定编辑弹窗 */}
      <SceneSettingModal
        open={c.showSceneSettingModal.value}
        onOpenChange={(v) => c.showSceneSettingModal.set(v)}
        settingVariant={
          props.imageType === 'character'
            ? 'character'
            : props.imageType === 'prop'
              ? 'prop'
              : 'scene'
        }
        promptOnly={props.imageType !== 'form'}
        editable={!settingEditBlockedTooltip}
        readOnlyTip={settingEditBlockedTooltip || undefined}
        sceneName={c.currentScene().name}
        initialContent={c.sceneSettingContent.value}
        onSyncTitle={c.handleSettingModalSyncSceneTitle}
        onSave={c.handleSaveSceneSetting}
        onSaveAndUpdate={c.handleSaveAndUpdateSceneSetting}
      />

      {/* 资源库导入弹窗 */}
      <ImportScriptModal
        open={c.showAssetLibraryModal.value}
        onOpenChange={(v) => c.showAssetLibraryModal.set(v)}
        title="导入图片"
        acceptAssetType="image"
        onImport={c.handleAssetLibraryImport}
      />
      <SelectSceneImageModal
        open={c.showDialogueImportModal.value}
        onOpenChange={(v) => c.showDialogueImportModal.set(v)}
        scenes={c.scenesForImportModal()}
        editingSceneIndex={c.currentSceneIndex.value}
        multiple
        title="选择参考画面"
        onSelectMultiple={c.handleDialogueImportMultiple}
      />
      <MultiAngleCameraModal
        open={c.showMultiAngleModal.value}
        onOpenChange={(v) => c.showMultiAngleModal.set(v)}
        imageUrl={c.multiAngleImageUrl.value}
        modelValue={c.multiViewSelectedModel()}
        modelOptions={c.multiViewModelOptions}
        modelExpanded={c.multiViewModelDropdownExpanded.value}
        onModelExpandedChange={(v) => c.multiViewModelDropdownExpanded.set(v)}
        onSelectModel={c.handleSelectMultiViewModel}
        billingTarget={
          resolvedCurrentFormId != null
            ? {
                kind: 'form',
                formId: resolvedCurrentFormId,
                aspectRatio: c.dialogueSettings.value.aspectRatio || '1:1'
              }
            : undefined
        }
        onGenerate={(payload) => void c.handleMultiAngleGenerate(payload)}
      />
      {c.showTouchEditToolbar && (
        <TouchEditModal
          open={c.showTouchEditModal.value}
          onOpenChange={(v) => c.showTouchEditModal.set(v)}
          imageUrl={c.touchEditImageUrl.value}
        />
      )}
    </Modal>
  )
}

export default EditSceneImageModal
