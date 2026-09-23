'use client'

import { Button } from 'antd'
import { PlusOutlined, CloseOutlined, SwapOutlined } from '@ant-design/icons'
import { ShimmerImage } from '~/components/common/ShimmerImage'
import { BillingQuoteHint } from '~/components/common/BillingQuoteHint'
import { useBillingQuote } from '~/hooks/useBillingQuote'
import type { BillingQuoteRequest } from '~/types/business-api'
import { StoryboardGeneratePanel } from '../StoryboardGeneratePanel'
import { GenerateModelConfigBlock } from '../GenerateModelConfigBlock'
import { ModelSelectDropdown } from '../ModelSelectDropdown'
import {
  showStoryboardGridVideoTab,
  showStoryboardImageToVideoTab,
  showStoryboardMultiParamVideoTab
} from '~/utils/creationModeUiRules'
import { assetUrl } from '~/utils/assetUrl'
import {
  blurVideoPromptEditorIfFocused,
  resolveVideoModalSelectPopupContainer
} from '~/utils/videoModalSelectPopup'
import starWhiteIconRaw from '~/assets/img/icon/star_white.svg'
import type { ImageToVideoSettingKey, VideoModalCtx } from './types'
import {
  buildStoryboardVideoReferenceOverrides,
  collectStoryboardPromptAssets,
  mergePromptAssets,
  patchEmptyResolvedPromptAssets
} from '~/utils/storyboardPromptAssetRef'
import { buildStoryboardVideoResolutionField } from '~/utils/storyboardVideoGenerateParams'
import { buildGenerateReferenceAudioFields } from '~/utils/storyboardVideoReferenceAudioWire'
import { buildGenerateReferenceVideoFields } from '~/utils/storyboardVideoReferenceVideoWire'

const starWhiteIcon = assetUrl(starWhiteIconRaw)

const PROMPT_PLACEHOLDER = '描述你想要生成的画面内容和动作,如:一个穿着红色裙子的小女孩在草地上奔跑'

export function buildVideoQuoteRequest(ctx: VideoModalCtx): BillingQuoteRequest | null {
  if (!ctx.props().open) return null
  const storyboardId = ctx.currentStoryboardId()
  if (!storyboardId) return null
  const tab = ctx.leftActiveTab.value
  const duration = Number(ctx.videoDuration.value)
  const common = {
    aspectRatio: ctx.videoAspectRatio.value || undefined,
    durationSeconds:
      ctx.videoConfigShowDuration() && Number.isFinite(duration) && duration > 0
        ? duration
        : undefined,
    ...buildStoryboardVideoResolutionField(ctx.videoQuality.value),
    count: ctx.videoCount.value,
    generateAudio: ctx.resolveCurrentGenerateAudio()
  }
  if (tab === 'imageToVideo') {
    const images = ctx.collectReferenceImageUrls()
    const modelName = String(ctx.imageToVideoModel.value || '').trim()
    const videoPrompt = ctx.imageToVideoPromptPlain().trim()
    if (!modelName || !videoPrompt) return null
    return {
      quoteType: 'STORYBOARD_VIDEO_IMAGE',
      payload: {
        storyboardIds: [storyboardId],
        ...(images.length ? { images } : {}),
        modelName,
        videoPrompt,
        baseImageRecordId: ctx.resolveBaseImageRecordId(),
        ...common,
        ...buildGenerateReferenceAudioFields(ctx.referenceAudios.value),
        ...(ctx.cameraMovementDesc.value.trim()
          ? { userInputText: ctx.cameraMovementDesc.value.trim().slice(0, 500) }
          : {})
      }
    }
  }
  if (tab === 'gridVideo') {
    const modelName = String(ctx.gridVideoModel.value || '').trim()
    const videoPrompt = ctx.imageToVideoPromptPlain().trim()
    if (!modelName || !videoPrompt) return null
    return {
      quoteType: 'STORYBOARD_VIDEO_GRID',
      payload: {
        storyboardIds: [storyboardId],
        modelName,
        videoPrompt,
        ...common,
        ...buildGenerateReferenceAudioFields(ctx.referenceAudios.value),
        ...(ctx.cameraMovementDesc.value.trim()
          ? { userInputText: ctx.cameraMovementDesc.value.trim().slice(0, 500) }
          : {})
      }
    }
  }
  if (tab === 'multiParam') {
    const modelName = String(ctx.multiParamVideoModel.value || '').trim()
    const videoPrompt = ctx.multiParamPromptPlain().trim()
    if (!modelName || !videoPrompt) return null
    const localAssets = collectStoryboardPromptAssets(
      ctx.sceneImages.value,
      ctx.characterImages.value,
      ctx.propImages.value,
      ctx.otherImages.value
    )
    const mergedAssets = mergePromptAssets(
      patchEmptyResolvedPromptAssets(ctx.resolvedMultiParamPromptAssets.value, localAssets),
      localAssets
    )
    const referenceOverrides = buildStoryboardVideoReferenceOverrides(videoPrompt, mergedAssets)
    return {
      quoteType: 'STORYBOARD_VIDEO',
      payload: {
        storyboardIds: [storyboardId],
        modelName,
        videoPrompt,
        ...(Object.keys(referenceOverrides).length ? { referenceOverrides } : {}),
        ...common,
        ...buildGenerateReferenceAudioFields(ctx.referenceAudios.value),
        ...buildGenerateReferenceVideoFields(ctx.referenceVideos.value),
        ...(ctx.multiParamShootingTechnique.value?.value?.trim()
          ? { userInputText: ctx.multiParamShootingTechnique.value.value.trim().slice(0, 500) }
          : {})
      }
    }
  }
  if (tab === 'startEndFrame') {
    const modelName = String(ctx.edgeVideoModel.value || '').trim()
    const videoPrompt = ctx.edgeVideoPromptPlain().trim()
    const firstFields = ctx.buildEdgeFrameApiFields(ctx.firstFrameImage.value, 'first')
    if (!Object.keys(firstFields).length) return null
    const lastFields = ctx.buildEdgeFrameApiFields(ctx.lastFrameImage.value, 'last')
    const audioFields = buildGenerateReferenceAudioFields(ctx.referenceAudios.value)
    return {
      quoteType: 'STORYBOARD_VIDEO_EDGE',
      payload: {
        storyboardIds: [storyboardId],
        items: [
          {
            storyboardId,
            ...firstFields,
            ...lastFields,
            ...(audioFields.referenceAudioIds
              ? { referenceAudioIds: audioFields.referenceAudioIds }
              : {})
          }
        ],
        ...(modelName ? { modelName } : {}),
        ...(videoPrompt ? { videoPrompt } : {}),
        ...common,
        count: Math.min(4, Math.max(1, Number(ctx.videoCount.value) || 1))
      }
    }
  }
  return null
}

function buildVideoPromptQuoteRequest(ctx: VideoModalCtx): BillingQuoteRequest | null {
  if (!ctx.props().open) return null
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
  const tab = ctx.leftActiveTab.value
  const modelCode = String(
    ctx.store().storyboardVideoGenerateSettings.videoPromptModelCode || ''
  ).trim()
  const agentCode = String(
    tab === 'multiParam'
      ? ctx.resolveMultiVideoPromptAgentCode()
      : tab === 'gridVideo'
        ? ctx.resolveGridVideoPromptAgentCode()
        : ctx.resolveImageVideoPromptAgentCode()
  ).trim()
  return {
    quoteType:
      tab === 'multiParam'
        ? 'STORYBOARD_VIDEO_PROMPT'
        : tab === 'gridVideo'
          ? 'STORYBOARD_VIDEO_PROMPT_GRID'
          : 'STORYBOARD_VIDEO_PROMPT_IMAGE',
    payload: {
      projectId,
      episodeId,
      storyboardIds: [storyboardId],
      ...(agentCode ? { agentCode } : {}),
      ...(modelCode ? { modelCode } : {})
    }
  }
}

/** 模型配置区（三个 Tab 共用同一组 props，仅 model 插槽不同） */
function ModelConfigBlock({ ctx, modelSlot }: { ctx: VideoModalCtx; modelSlot: React.ReactNode }) {
  return (
    <GenerateModelConfigBlock
      aspectRatio={ctx.videoAspectRatio.value}
      onAspectRatioChange={(v) => ctx.videoAspectRatio.set(v)}
      count={ctx.videoCount.value}
      onCountChange={(v) => ctx.videoCount.set(v)}
      quality={ctx.videoQuality.value}
      onQualityChange={(v) => ctx.videoQuality.set(v)}
      duration={ctx.videoDuration.value}
      onDurationChange={(v) => ctx.videoDuration.set(v)}
      audio={ctx.videoAudio.value}
      onAudioChange={(v) => ctx.videoAudio.set(v)}
      mode="video"
      selectClass="setting-select-inline"
      density="scene"
      showQuality3k={false}
      showAction={false}
      showDuration={ctx.videoConfigShowDuration()}
      showAudio={ctx.videoConfigShowAudio()}
      durationTip={ctx.videoDurationTip()}
      videoAspectRatioOptions={ctx.videoAspectRatioOptions()}
      videoDurationOptions={ctx.videoDurationOptions()}
      videoCountOptions={ctx.videoCountOptions()}
      videoQualityOptions={ctx.videoQualityOptions()}
      videoAudioOptions={ctx.videoAudioOptions()}
      selectPopupClassName="video-modal-select-dropdown"
      // 浮层挂 body：点选项才能命中；自动合上由文本域失焦守卫处理，不要挂进弹窗。
      getPopupContainer={resolveVideoModalSelectPopupContainer}
      onSelectOpenChange={(open) => {
        if (open) blurVideoPromptEditorIfFocused()
      }}
      modelSlot={modelSlot}
    />
  )
}

/** 右：生成配置（原右侧表单，含四个出片方向 Tab） */
export function VideoConfigPanel({ ctx }: { ctx: VideoModalCtx }) {
  const creationMode = ctx.projectCreationMode()
  const showImageToVideoTab = showStoryboardImageToVideoTab(creationMode)
  const showMultiParamTab = showStoryboardMultiParamVideoTab(creationMode)
  const showGridVideoTab = showStoryboardGridVideoTab(creationMode)
  const showStartEndFrameTab = true

  const visibleVideoTabCount =
    Number(showImageToVideoTab) +
    Number(showMultiParamTab) +
    Number(showGridVideoTab) +
    Number(showStartEndFrameTab)
  const videoTabBarClass = visibleVideoTabCount <= 2 ? 'config-tabs--two' : 'config-tabs--three'

  const leftActiveTab = ctx.leftActiveTab.value
  const isGridVideoTab = leftActiveTab === 'gridVideo'
  const isImageToVideoTab = leftActiveTab === 'imageToVideo'
  const isPrimaryVideoTab = isImageToVideoTab || isGridVideoTab

  const suppressSync = ctx.videoPromptProgrammaticSyncDepth.value > 0
  const quoteRequest = buildVideoQuoteRequest(ctx)
  const generationQuote = useBillingQuote(quoteRequest)
  const promptQuoteRequest = buildVideoPromptQuoteRequest(ctx)

  const primaryModelDropdown = isGridVideoTab ? (
    <ModelSelectDropdown
      key={`grid-model-${ctx.gridVideoModel.value}-${ctx.gridVideoModelOptions().length}`}
      value={ctx.selectedGridVideoModel()}
      options={ctx.gridVideoModelOptions()}
      expanded={ctx.gridVideoModelDropdownExpanded.value}
      onToggle={() => ctx.gridVideoModelDropdownExpanded.set(!ctx.gridVideoModelDropdownExpanded.get())}
      onClose={() => ctx.gridVideoModelDropdownExpanded.set(false)}
      onSelect={ctx.handleSelectGridVideoModel}
    />
  ) : (
    <ModelSelectDropdown
      key={`i2v-model-${ctx.imageToVideoModel.value}-${ctx.imageToVideoModelOptions().length}`}
      value={ctx.selectedImageToVideoModel()}
      options={ctx.imageToVideoModelOptions()}
      expanded={ctx.imageToVideoModelDropdownExpanded.value}
      onToggle={() =>
        ctx.imageToVideoModelDropdownExpanded.set(!ctx.imageToVideoModelDropdownExpanded.get())
      }
      onClose={() => ctx.imageToVideoModelDropdownExpanded.set(false)}
      onSelect={ctx.handleSelectImageToVideoModel}
    />
  )

  function renderEdgeFrameThumb(target: 'first' | 'last') {
    const frame = target === 'first' ? ctx.firstFrameImage.value : ctx.lastFrameImage.value
    const hasImage = !!(frame?.url || frame?.thumbnail)
    return (
      <div className="edge-frame-card">
        <div
          className="edge-frame-thumb"
          role="button"
          tabIndex={0}
          onClick={() => ctx.onEdgeFrameCardClick(target)}
          onKeyDown={(e) => {
            if (e.key !== 'Enter') return
            e.preventDefault()
            ctx.onEdgeFrameCardClick(target)
          }}
        >
          {hasImage ? (
            <ShimmerImage
              src={frame!.url || frame!.thumbnail || ''}
              alt={target === 'first' ? '首帧' : '尾帧'}
              imgClass="edge-frame-thumb__media"
              objectFit="cover"
              revealDirection="fade"
            />
          ) : (
            <div className="edge-frame-placeholder">
              <PlusOutlined />
              <span>{target === 'first' ? '上传首帧' : '上传尾帧'}</span>
            </div>
          )}
          {hasImage ? (
            <button
              type="button"
              className="edge-frame-remove"
              aria-label={target === 'first' ? '删除首帧' : '删除尾帧'}
              onClick={(e) => {
                e.stopPropagation()
                ctx.clearEdgeFrame(target)
              }}
            >
              <CloseOutlined />
            </button>
          ) : null}
        </div>
      </div>
    )
  }

  return (
    <aside className="stage-config-panel video-stage-config">
      <div className={`config-tabs ${videoTabBarClass}`}>
        {showImageToVideoTab ? (
          <button
            type="button"
            className={`config-tab${leftActiveTab === 'imageToVideo' ? ' active' : ''}`}
            onClick={() => ctx.leftActiveTab.set('imageToVideo')}
          >
            图生视频
          </button>
        ) : null}
        {showMultiParamTab ? (
          <button
            type="button"
            className={`config-tab${leftActiveTab === 'multiParam' ? ' active' : ''}`}
            onClick={() => ctx.leftActiveTab.set('multiParam')}
          >
            多参生视频
          </button>
        ) : null}
        {showGridVideoTab ? (
          <button
            type="button"
            className={`config-tab${leftActiveTab === 'gridVideo' ? ' active' : ''}`}
            onClick={() => ctx.leftActiveTab.set('gridVideo')}
          >
            宫格生视频
          </button>
        ) : null}
        {showStartEndFrameTab ? (
          <button
            type="button"
            className={`config-tab${leftActiveTab === 'startEndFrame' ? ' active' : ''}`}
            onClick={() => ctx.leftActiveTab.set('startEndFrame')}
          >
            首尾帧视频
          </button>
        ) : null}
      </div>

      <div className="video-config-below-tabs">
        <div className="video-config-scroll create-modal-config-scroll">
          <div className="video-config-body create-modal-config-body">
            {/* 图生视频 / 宫格生视频：共用提示词与模型配置；宫格模式隐藏九宫格开关，文本域上方仍展示导入参考图 */}
            {isPrimaryVideoTab ? (
              <div className="video-left-content create-modal-tab-panel">
                <StoryboardGeneratePanel
                  ref={ctx.imageToVideoPanelRef}
                  mode="storyboardVideo"
                  usePreciseLayout={false}
                  showStoryboardVideoAssets={!isGridVideoTab}
                  suppressPromptReactiveSync={suppressSync}
                  sceneFileName={ctx.scriptRowLabel()}
                  iconType="scene"
                  showReferenceButton={false}
                  showGeneratePromptButton={true}
                  generatePromptLoading={ctx.showGeneratingVideoPromptForScene()}
                  promptBillingRequest={promptQuoteRequest}
                  showSavePromptButton={!isGridVideoTab}
                  savePromptLoading={ctx.isSavingVideoPrompt.value}
                  extraPromptAssets={ctx.resolvedVideoPromptAssets.value}
                  prompt={ctx.imageToVideoPrompt.value}
                  promptMaxLength={ctx.activeVideoPromptMaxLength(ctx.imageToVideoPromptPlain())}
                  onPromptChange={ctx.handleImageToVideoPromptEditorChange}
                  promptPlaceholder={PROMPT_PLACEHOLDER}
                  isSettingExpanded={ctx.isImageToVideoSettingExpanded.value}
                  onIsSettingExpandedChange={(v) => ctx.isImageToVideoSettingExpanded.set(v)}
                  nineGridEnabled={ctx.nineGridEnabled.value}
                  onNineGridEnabledChange={(v) => ctx.nineGridEnabled.set(v)}
                  referenceImage={ctx.referenceImageGet()}
                  onReferenceImageChange={(v) => ctx.setReferenceImage(v)}
                  referenceImages={ctx.referenceImages.value}
                  referenceAudios={ctx.referenceAudios.value}
                  selectedCameraMovement={ctx.selectedCameraMovement.value}
                  onSelectedCameraMovementChange={(v) => ctx.selectedCameraMovement.set(v)}
                  cameraMovementDesc={ctx.cameraMovementDesc.value}
                  onCameraMovementDescChange={(v) => ctx.cameraMovementDesc.set(v)}
                  selectedShootingTechnique={ctx.selectedImageToVideoShootingTechnique.value}
                  onSelectedShootingTechniqueChange={(v) =>
                    ctx.selectedImageToVideoShootingTechnique.set(v)
                  }
                  activeVideoSettingKey={ctx.activeImageToVideoSettingKey.value}
                  onActiveVideoSettingKeyChange={(v) =>
                    ctx.activeImageToVideoSettingKey.set(v as ImageToVideoSettingKey | null)
                  }
                  sceneImages={ctx.sceneImages.value}
                  characterImages={ctx.characterImages.value}
                  propImages={ctx.propImages.value}
                  otherImages={ctx.otherImages.value}
                  onOpenScript={ctx.openStoryboardScriptEditor}
                  onGeneratePrompt={() => void ctx.handleImageToVideoGeneratePrompt()}
                  onSavePrompt={() => void ctx.handleSaveVideoPrompt()}
                  onImportReference={ctx.handleImportReference}
                  onPreviewReference={ctx.onPreviewReferenceImage}
                  onPreviewReferenceImage={ctx.previewReferenceImage}
                  onRemoveReferenceImage={ctx.removeReferenceImageAt}
                  onRemoveReferenceAudio={(i) => ctx.removeReferenceAudioAt(i)}
                  onClearReference={ctx.clearReferenceImage}
                  onCopyPrompt={ctx.copyImageToVideoPrompt}
                  onCopyCameraMovementDesc={ctx.copyCameraDesc}
                  onParamSettingsConfirm={ctx.applyImageToVideoParamSettingsConfirm}
                >
                  <ModelConfigBlock ctx={ctx} modelSlot={primaryModelDropdown} />
                </StoryboardGeneratePanel>
              </div>
            ) : leftActiveTab === 'multiParam' ? (
              /* 多参生视频：场景/角色/道具/其他 + 描述框 + 右侧仅特殊拍摄手法 */
              <div className="video-left-content create-modal-tab-panel">
                <StoryboardGeneratePanel
                  ref={ctx.multiParamPanelRef}
                  mode="imageToVideo"
                  usePreciseLayout={false}
                  suppressPromptReactiveSync={suppressSync}
                  sceneFileName={ctx.scriptRowLabel()}
                  iconType="scene"
                  showGeneratePromptButton={true}
                  generatePromptLoading={ctx.showGeneratingMultiParamPromptForScene()}
                  promptBillingRequest={promptQuoteRequest}
                  prompt={ctx.multiParamPrompt.value}
                  promptMaxLength={ctx.activeVideoPromptMaxLength(ctx.multiParamPromptPlain())}
                  onPromptChange={ctx.handleMultiParamPromptEditorChange}
                  extraPromptAssets={ctx.resolvedMultiParamPromptAssets.value}
                  promptPlaceholder={PROMPT_PLACEHOLDER}
                  sceneImages={ctx.sceneImages.value}
                  characterImages={ctx.characterImages.value}
                  propImages={ctx.propImages.value}
                  otherImages={ctx.otherImages.value}
                  isSettingExpanded={ctx.isMultiParamSettingExpanded.value}
                  onIsSettingExpandedChange={(v) => ctx.isMultiParamSettingExpanded.set(v)}
                  selectedShootingTechnique={ctx.multiParamShootingTechnique.value}
                  onSelectedShootingTechniqueChange={(v) => ctx.multiParamShootingTechnique.set(v)}
                  activeVideoSettingKey={ctx.activeMultiParamSettingKey.value}
                  onActiveVideoSettingKeyChange={(v) => ctx.activeMultiParamSettingKey.set(v)}
                  referenceAudios={ctx.referenceAudios.value}
                  referenceVideos={ctx.referenceVideos.value}
                  referenceImportLabel="导入素材"
                  imageToVideoNineGridEnabled={ctx.nineGridEnabled.value}
                  imageToVideoReferenceImages={ctx.referenceImages.value}
                  imageToVideoSelectedCameraMovement={ctx.selectedCameraMovement.value}
                  imageToVideoCameraMovementDesc={ctx.cameraMovementDesc.value}
                  imageToVideoSelectedShootingTechnique={
                    ctx.selectedImageToVideoShootingTechnique.value
                  }
                  imageToVideoActiveVideoSettingKey={ctx.activeImageToVideoSettingKey.value}
                  onOpenScript={ctx.openStoryboardScriptEditor}
                  onGeneratePrompt={() => void ctx.handleMultiParamGeneratePrompt()}
                  onImportReference={ctx.handleMultiParamImportReference}
                  onOpenSelectModal={ctx.openSelectAssetModal}
                  onRemoveMultiParamAssetReference={ctx.removeMultiParamAssetReference}
                  onRemoveReferenceAudio={(i) => ctx.removeReferenceAudioAt(i)}
                  onRemoveReferenceVideo={ctx.removeReferenceVideoAt}
                  onRemoveOtherImage={ctx.removeOtherImage}
                  onPreviewAssetImage={ctx.previewAssetImage}
                  onCopyPrompt={ctx.copyMultiParamPrompt}
                  onParamSettingsConfirm={ctx.applyMultiParamSettingsConfirm}
                >
                  <ModelConfigBlock
                    ctx={ctx}
                    modelSlot={
                      <ModelSelectDropdown
                        key={`multi-model-${ctx.multiParamVideoModel.value}-${ctx.multiParamVideoModelOptions().length}`}
                        value={ctx.selectedMultiParamVideoModel()}
                        options={ctx.multiParamVideoModelOptions()}
                        expanded={ctx.multiParamVideoModelDropdownExpanded.value}
                        onToggle={() =>
                          ctx.multiParamVideoModelDropdownExpanded.set(
                            !ctx.multiParamVideoModelDropdownExpanded.get()
                          )
                        }
                        onClose={() => ctx.multiParamVideoModelDropdownExpanded.set(false)}
                        onSelect={ctx.handleSelectMultiParamVideoModel}
                      />
                    }
                  />
                </StoryboardGeneratePanel>
              </div>
            ) : leftActiveTab === 'startEndFrame' ? (
              <div className="video-left-content create-modal-tab-panel">
                <StoryboardGeneratePanel
                  ref={ctx.edgeVideoPanelRef}
                  mode="edgeVideo"
                  usePreciseLayout={false}
                  showScriptFileHeader={false}
                  showGeneratePromptButton={false}
                  prompt={ctx.edgeVideoPrompt.value}
                  promptMaxLength={ctx.activeVideoPromptMaxLength(ctx.edgeVideoPromptPlain())}
                  onPromptChange={ctx.handleEdgeVideoPromptEditorChange}
                  promptPlaceholder={PROMPT_PLACEHOLDER}
                  isSettingExpanded={ctx.isEdgeVideoSettingExpanded.value}
                  onIsSettingExpandedChange={(v) => ctx.isEdgeVideoSettingExpanded.set(v)}
                  referenceAudios={ctx.referenceAudios.value}
                  sceneImages={[]}
                  characterImages={[]}
                  propImages={[]}
                  otherImages={[]}
                  onRemoveReferenceAudio={(i) => ctx.removeReferenceAudioAt(i)}
                  onCopyPrompt={ctx.copyEdgeVideoPrompt}
                  promptPrefix={
                    <div className="edge-frame-strip">
                      {renderEdgeFrameThumb('first')}
                      {ctx.showEdgeFrameSwap() ? (
                        <button
                          type="button"
                          className="edge-frame-swap"
                          aria-label="交换首尾帧"
                          onClick={(e) => {
                            e.stopPropagation()
                            ctx.swapEdgeFrames()
                          }}
                        >
                          <SwapOutlined />
                        </button>
                      ) : null}
                      {renderEdgeFrameThumb('last')}
                    </div>
                  }
                >
                  <ModelConfigBlock
                    ctx={ctx}
                    modelSlot={
                      <ModelSelectDropdown
                        key={`edge-model-${ctx.edgeVideoModel.value}-${ctx.edgeVideoModelOptions().length}`}
                        value={ctx.selectedEdgeVideoModel()}
                        options={ctx.edgeVideoModelOptions()}
                        expanded={ctx.edgeVideoModelDropdownExpanded.value}
                        onToggle={() =>
                          ctx.edgeVideoModelDropdownExpanded.set(!ctx.edgeVideoModelDropdownExpanded.get())
                        }
                        onClose={() => ctx.edgeVideoModelDropdownExpanded.set(false)}
                        onSelect={ctx.handleSelectEdgeVideoModel}
                      />
                    }
                  />
                </StoryboardGeneratePanel>
              </div>
            ) : (
              <div className="video-left-content">
                <div className="tab-placeholder">
                  <p>请选择视频生成方式</p>
                </div>
              </div>
            )}
          </div>
        </div>
        {isPrimaryVideoTab || leftActiveTab === 'multiParam' || leftActiveTab === 'startEndFrame' ? (
          <div className="video-config-footer">
            <BillingQuoteHint
              quote={generationQuote.quote}
              loading={generationQuote.loading}
              error={generationQuote.error}
              active={Boolean(quoteRequest)}
              className="billing-quote-hint--submit"
            />
            {isPrimaryVideoTab ? (
              <Button
                type="primary"
                block
                size="large"
                className="generate-btn"
                loading={
                  isGridVideoTab
                    ? ctx.showGridVideoGenerateLoadingGet()
                    : ctx.showImageToVideoGenerateLoadingGet()
                }
                disabled={
                  (isGridVideoTab
                    ? ctx.showGridVideoGenerateLoadingGet()
                    : ctx.showImageToVideoGenerateLoadingGet()) ||
                  ctx.showGeneratingVideoPromptForScene()
                }
                icon={<img src={starWhiteIcon} alt="" />}
                onClick={() =>
                  isGridVideoTab
                    ? void ctx.handleGridVideoStartGenerate()
                    : void ctx.handleImageToVideoStartGenerate()
                }
              >
                开始生成视频
              </Button>
            ) : leftActiveTab === 'multiParam' ? (
              <Button
                type="primary"
                block
                size="large"
                className="generate-btn"
                loading={ctx.showMultiParamGenerateLoadingGet()}
                disabled={ctx.showMultiParamGenerateLoadingGet()}
                icon={<img src={starWhiteIcon} alt="" />}
                onClick={() => void ctx.handleMultiParamStartGenerate()}
              >
                开始生成视频
              </Button>
            ) : (
              <Button
                type="primary"
                block
                size="large"
                className="generate-btn"
                loading={ctx.showEdgeVideoGenerateLoadingGet()}
                disabled={ctx.showEdgeVideoGenerateLoadingGet()}
                icon={<img src={starWhiteIcon} alt="" />}
                onClick={() => void ctx.handleEdgeVideoStartGenerate()}
              >
                开始生成视频
              </Button>
            )}
          </div>
        ) : null}
      </div>
    </aside>
  )
}
