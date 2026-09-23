'use client'

/**
 * 分镜/视频出片配置面板（原 components/steps/StoryboardGeneratePanel.vue，2469 行）。
 * 拆分：布局自适应 → storyboard-generate-panel/useStoryboardGeneratePanelLayout；
 *       描述框三方联动 → storyboard-generate-panel/useStoryboardPanelPromptSync；
 *       左侧素材行 → storyboard-generate-panel/StoryboardPanelAssetRows。
 */

import { forwardRef, useImperativeHandle, useMemo, useRef, type CSSProperties } from 'react'
import { Button, Input } from 'antd'
import { CopyOutlined, DeleteOutlined } from '@ant-design/icons'
import { RichTextEditor, type RichTextEditorHandle } from '~/components/common/RichTextEditor'
import { BillingQuoteHint } from '~/components/common/BillingQuoteHint'
import { useBillingQuote } from '~/hooks/useBillingQuote'
import { GenerateSourceImagesStrip } from './GenerateSourceImagesStrip'
import { SettingSelectField } from './SettingSelectField'
import { PromptScriptFileHeader } from './PromptScriptFileHeader'
import {
  StoryboardParamSettingsModal,
  type StoryboardParamSettingsModalHandle
} from './StoryboardParamSettingsModal'
import type { PromptAssetItem } from '~/utils/storyboardPromptAssetRef'
import { EDIT_ASSET_PROMPT_MAX_CHARS } from '~/utils/htmlPlain'
import { useStoryboardGeneratePanelLayout } from './storyboard-generate-panel/useStoryboardGeneratePanelLayout'
import { useStoryboardPanelPromptSync } from './storyboard-generate-panel/useStoryboardPanelPromptSync'
import { StoryboardPanelAssetRows } from './storyboard-generate-panel/StoryboardPanelAssetRows'
import type {
  PanelSelectModalType,
  ResolvedStoryboardGeneratePanelProps,
  StoryboardGeneratePanelHandle,
  StoryboardGeneratePanelProps
} from './storyboard-generate-panel/types'
import './storyboard-generate-panel/storyboard-generate-panel.css'

export type {
  StoryboardGeneratePanelHandle,
  StoryboardGeneratePanelProps,
  StoryboardGeneratePanelMode
} from './storyboard-generate-panel/types'

export const StoryboardGeneratePanel = forwardRef<
  StoryboardGeneratePanelHandle,
  StoryboardGeneratePanelProps
>(function StoryboardGeneratePanel(rawProps, ref) {
  // 原 withDefaults
  const props: ResolvedStoryboardGeneratePanelProps = {
    ...rawProps,
    sceneFileName: rawProps.sceneFileName ?? '',
    sceneFileClickable: rawProps.sceneFileClickable ?? true,
    showScriptFileHeader: rawProps.showScriptFileHeader ?? true,
    showReferenceButton: rawProps.showReferenceButton ?? false,
    referenceDisplayMode: rawProps.referenceDisplayMode ?? 'button',
    showGeneratePromptButton: rawProps.showGeneratePromptButton ?? true,
    generatePromptLoading: rawProps.generatePromptLoading ?? false,
    showSavePromptButton: rawProps.showSavePromptButton ?? false,
    savePromptLoading: rawProps.savePromptLoading ?? false,
    headerTheme: rawProps.headerTheme ?? 'panel',
    iconType: rawProps.iconType ?? 'file-text',
    usePreciseLayout: rawProps.usePreciseLayout ?? true,
    useParamSettingsModal: rawProps.useParamSettingsModal ?? true,
    suppressPromptReactiveSync: rawProps.suppressPromptReactiveSync ?? false,
    showStoryboardVideoAssets: rawProps.showStoryboardVideoAssets ?? true,
    referenceImages: rawProps.referenceImages ?? [],
    referenceAudios: rawProps.referenceAudios ?? [],
    referenceVideos: rawProps.referenceVideos ?? [],
    imageToVideoReferenceImages: rawProps.imageToVideoReferenceImages ?? [],
    referenceImportLabel: rawProps.referenceImportLabel ?? '导入参考图',
    promptMaxLength: rawProps.promptMaxLength ?? EDIT_ASSET_PROMPT_MAX_CHARS
  }
  /** 事件回调 / 异步流程内一律读最新 props，避免闭包捕获旧值 */
  const propsRef = useRef(props)
  propsRef.current = props

  const paramSettingsModalRef = useRef<StoryboardParamSettingsModalHandle | null>(null)
  const promptEditorExpandedRef = useRef<RichTextEditorHandle | null>(null)
  const promptEditorCollapsedRef = useRef<RichTextEditorHandle | null>(null)

  const panelRootRef = useRef<HTMLDivElement | null>(null)
  const headerWrapRef = useRef<HTMLDivElement | null>(null)
  const slotWrapRef = useRef<HTMLDivElement | null>(null)
  const promptCollapsedRef = useRef<HTMLDivElement | null>(null)

  const { isCompactHeight, panelCssVars, promptHeightExpanded, promptHeightCollapsed } =
    useStoryboardGeneratePanelLayout({
      usePreciseLayout: props.usePreciseLayout,
      isSettingExpanded: props.isSettingExpanded,
      panelRootRef,
      headerWrapRef,
      slotWrapRef,
      promptCollapsedRef
    })

  const sync = useStoryboardPanelPromptSync({
    props,
    propsRef,
    promptEditorExpandedRef,
    promptEditorCollapsedRef
  })
  const promptQuote = useBillingQuote(props.promptBillingRequest ?? null)
  const {
    paramSettingsOpen,
    setParamSettingsOpen,
    paramSettingsOpenRef,
    enablePromptAssetRefs,
    enablePromptParamRefs,
    storyboardPromptAssets,
    storyboardPromptParamGroups,
    storyboardVideoReferenceList,
    multiParamAssetReferenceList,
    onPromptParamChange,
    onDraftToggleAssetRef,
    onDraftSyncAssetRefs,
    onParamSettingsConfirm,
    insertPromptAssetRefsAtCaret,
    onMultiParamStripRemove,
    dictionary
  } = sync

  const referenceAudioStripItems = useMemo(
    () =>
      (props.referenceAudios ?? []).map((a: any, i: number) => ({
        ...a,
        kind: 'audio' as const,
        id: a.id ?? `audio-${i}`,
        title: a.title || a.name,
        name: a.name || a.title
      })),
    [props.referenceAudios]
  )
  const referenceVideoStripItems = useMemo(
    () =>
      (props.referenceVideos ?? []).map((video: any, index: number) => ({
        ...video,
        kind: 'video' as const,
        id: video.id ?? video.referenceVideoRecordId ?? `video-${index}`,
        title: video.title || video.name,
        name: video.name || video.title
      })),
    [props.referenceVideos]
  )

  /** 图生/宫格：图片 + 音频混排素材条 */
  const storyboardVideoStripItems = useMemo(
    () => [
      ...storyboardVideoReferenceList.map((img: any) => ({ ...img, kind: 'image' as const })),
      ...referenceAudioStripItems
    ],
    [storyboardVideoReferenceList, referenceAudioStripItems]
  )

  /** 多参：资产图 + 视频 + 音频混排素材条 */
  const multiParamStripItems = useMemo(
    () => [
      ...multiParamAssetReferenceList.map((img: any) => ({ ...img, kind: 'image' as const })),
      ...referenceVideoStripItems,
      ...referenceAudioStripItems
    ],
    [multiParamAssetReferenceList, referenceVideoStripItems, referenceAudioStripItems]
  )

  function onStoryboardVideoStripRemove(index: number) {
    const imgCount = storyboardVideoReferenceList.length
    if (index < imgCount) {
      propsRef.current.onRemoveReferenceImage?.(index)
      return
    }
    propsRef.current.onRemoveReferenceAudio?.(index - imgCount)
  }

  function onStoryboardVideoReferenceClick() {
    const r = propsRef.current.referenceImage
    if (r?.url || r?.thumbnail) {
      propsRef.current.onPreviewReference?.()
    } else {
      propsRef.current.onImportReference?.()
    }
  }

  function onStoryboardVideoReferenceThumbClick(img: { url?: string; thumbnail?: string }) {
    if (img?.url || img?.thumbnail) {
      propsRef.current.onPreviewReferenceImage?.(img)
    } else {
      propsRef.current.onImportReference?.()
    }
  }

  function onSceneFileClick() {
    if (propsRef.current.sceneFileClickable) {
      propsRef.current.onOpenScript?.()
    }
  }

  // 原 defineExpose
  useImperativeHandle(
    ref,
    () => ({
      isParamSettingsOpen: () => paramSettingsOpenRef.current,
      applyParamDraftAssets: (type: PanelSelectModalType, items: any[]) => {
        paramSettingsModalRef.current?.appendDraftImages(type as any, items)
      },
      applyParamDraftReference: (item: any) => {
        paramSettingsModalRef.current?.appendDraftReferences([item])
      },
      applyParamDraftReferences: (items: any[]) => {
        paramSettingsModalRef.current?.appendDraftReferences(items)
      },
      insertPromptAssetRefsAtCaret
    }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    []
  )

  const rootClass = [
    'storyboard-generate-panel',
    isCompactHeight && !props.useParamSettingsModal ? 'is-compact-height' : '',
    props.useParamSettingsModal ? 'use-param-modal' : ''
  ]
    .filter(Boolean)
    .join(' ')

  function renderPromptFooterActions(showSave: boolean) {
    return (
      <div className="prompt-actions">
        {showSave && props.showSavePromptButton ? (
          <Button
            type="primary"
            size="small"
            className="save-prompt-btn"
            loading={props.savePromptLoading}
            disabled={props.savePromptLoading || props.generatePromptLoading}
            onClick={() => props.onSavePrompt?.()}
          >
            保存提示词
          </Button>
        ) : null}
        <Button type="text" size="small" onClick={() => props.onCopyPrompt?.()}>
          <CopyOutlined />
        </Button>
        <Button type="text" size="small" onClick={() => props.onPromptChange?.('')}>
          <DeleteOutlined />
        </Button>
      </div>
    )
  }

  function renderSettingBody() {
    if (props.mode === 'storyboardVideo') {
      return (
        <>
          <div className="setting-field">
            <div className="setting-title">镜头运动</div>
            <SettingSelectField
              modelValue={props.selectedCameraMovement ?? null}
              options={dictionary.cameraMovementOptions}
              placeholder="请选择镜头运动"
              panelTitle="选择镜头运动"
              open={props.activeVideoSettingKey === 'cameraMovement'}
              onOpenChange={(v: boolean) =>
                props.onActiveVideoSettingKeyChange?.(v ? 'cameraMovement' : null)
              }
              onModelValueChange={(v) => props.onSelectedCameraMovementChange?.(v)}
            />
            <div className="setting-textarea">
              <Input
                value={props.cameraMovementDesc ?? ''}
                placeholder="请输入镜头运动描述"
                className="setting-input-with-actions"
                onChange={(e) => props.onCameraMovementDescChange?.(e.target.value)}
                suffix={
                  <>
                    <DeleteOutlined
                      className="input-action"
                      onClick={(e) => {
                        e.stopPropagation()
                        props.onCameraMovementDescChange?.('')
                      }}
                    />
                    <CopyOutlined
                      className="input-action"
                      onClick={(e) => {
                        e.stopPropagation()
                        props.onCopyCameraMovementDesc?.()
                      }}
                    />
                  </>
                }
              />
            </div>
          </div>
          <div className="setting-field">
            <div className="setting-title">特殊拍摄手法</div>
            <SettingSelectField
              modelValue={props.selectedShootingTechnique ?? null}
              options={dictionary.shootingTechniqueOptions}
              placeholder="请选择特殊拍摄手法"
              panelTitle="选择特殊拍摄手法"
              open={props.activeVideoSettingKey === 'shootingTechnique'}
              onOpenChange={(v: boolean) =>
                props.onActiveVideoSettingKeyChange?.(v ? 'shootingTechnique' : null)
              }
              onModelValueChange={(v) => props.onSelectedShootingTechniqueChange?.(v)}
            />
          </div>
        </>
      )
    }
    if (props.mode === 'imageToVideo') {
      return (
        <div className="setting-field">
          <div className="setting-title">特殊拍摄手法</div>
          <SettingSelectField
            modelValue={props.selectedShootingTechnique ?? null}
            options={dictionary.shootingTechniqueOptions}
            placeholder="请选择特殊拍摄手法"
            panelTitle="选择特殊拍摄手法"
            open={props.activeVideoSettingKey === 'shootingTechnique'}
            onOpenChange={(v: boolean) =>
              props.onActiveVideoSettingKeyChange?.(v ? 'shootingTechnique' : null)
            }
            onModelValueChange={(v) => props.onSelectedShootingTechniqueChange?.(v)}
          />
        </div>
      )
    }
    // 生成分镜图：构图、景别、拍摄角度等 7 项
    const imageFields: Array<{
      key: string
      title: string
      value: { key: string; value: string } | null | undefined
      options: typeof dictionary.compositionOptions
      placeholder: string
      panelTitle: string
      onChange?: (v: { key: string; value: string } | null) => void
    }> = [
      {
        key: 'shotSize',
        title: '景别',
        value: props.selectedShotSize,
        options: dictionary.shotSizeOptions,
        placeholder: '请选择景别',
        panelTitle: '选择景别',
        onChange: props.onSelectedShotSizeChange
      },
      {
        key: 'cameraAngle',
        title: '拍摄角度',
        value: props.selectedCameraAngle,
        options: dictionary.cameraAngleOptions,
        placeholder: '请选择拍摄角度',
        panelTitle: '选择拍摄角度',
        onChange: props.onSelectedCameraAngleChange
      },
      {
        key: 'focalLength',
        title: '镜头焦距',
        value: props.selectedFocalLength,
        options: dictionary.focalLengthOptions,
        placeholder: '请选择镜头焦距',
        panelTitle: '选择镜头焦距',
        onChange: props.onSelectedFocalLengthChange
      },
      {
        key: 'colorTone',
        title: '色彩倾向',
        value: props.selectedColorTone,
        options: dictionary.colorToneOptions,
        placeholder: '请选择色彩倾向',
        panelTitle: '选择色彩倾向',
        onChange: props.onSelectedColorToneChange
      },
      {
        key: 'lighting',
        title: '光线',
        value: props.selectedLighting,
        options: dictionary.lightingOptions,
        placeholder: '请选择光线',
        panelTitle: '选择光线',
        onChange: props.onSelectedLightingChange
      },
      {
        key: 'technique',
        title: '摄影技法',
        value: props.selectedTechnique,
        options: dictionary.techniqueOptions,
        placeholder: '请选择摄影技法',
        panelTitle: '选择摄影技法',
        onChange: props.onSelectedTechniqueChange
      }
    ]
    return (
      <>
        <div className="setting-field">
          <div className="setting-title">构图</div>
          <SettingSelectField
            modelValue={props.selectedComposition ?? null}
            options={dictionary.compositionOptions}
            placeholder="请选择构图"
            panelTitle="选择构图"
            open={props.activeSettingKey === 'composition'}
            onOpenChange={(v: boolean) => props.onActiveSettingKeyChange?.(v ? 'composition' : null)}
            onModelValueChange={(v) => props.onSelectedCompositionChange?.(v)}
          />
          <div className="setting-textarea">
            <RichTextEditor
              value={props.compositionDesc ?? ''}
              minHeight="52px"
              placeholder="请输入构图描述"
              onChange={(v) => props.onCompositionDescChange?.(v)}
            />
            <div className="setting-actions">
              <Button type="text" size="small" onClick={() => props.onCopyCompositionDesc?.()}>
                <CopyOutlined />
              </Button>
              <Button type="text" size="small" onClick={() => props.onCompositionDescChange?.('')}>
                <DeleteOutlined />
              </Button>
            </div>
          </div>
        </div>
        {imageFields.map((field) => (
          <div key={field.key} className="setting-field">
            <div className="setting-title">{field.title}</div>
            <SettingSelectField
              modelValue={field.value ?? null}
              options={field.options}
              placeholder={field.placeholder}
              panelTitle={field.panelTitle}
              open={props.activeSettingKey === field.key}
              onOpenChange={(v: boolean) => props.onActiveSettingKeyChange?.(v ? field.key : null)}
              onModelValueChange={(v) => field.onChange?.(v)}
            />
          </div>
        ))}
      </>
    )
  }

  return (
    <div ref={panelRootRef} className={rootClass} style={panelCssVars as CSSProperties}>
      {props.showScriptFileHeader ? (
        <div ref={headerWrapRef}>
          <PromptScriptFileHeader
            iconType={props.iconType}
            theme={props.headerTheme}
            fileName={props.sceneFileName}
            showReferenceButton={props.showReferenceButton}
            referenceDisplayMode={props.referenceDisplayMode}
            showGeneratePromptButton={props.showGeneratePromptButton}
            generatePromptLoading={props.generatePromptLoading}
            sceneFileClickable={props.sceneFileClickable}
            onClickFile={onSceneFileClick}
            onImportReference={() => props.onImportReference?.()}
            onGeneratePrompt={() => props.onGeneratePrompt?.()}
          />
          <BillingQuoteHint
            quote={promptQuote.quote}
            loading={promptQuote.loading}
            error={promptQuote.error}
            active={props.showGeneratePromptButton && Boolean(props.promptBillingRequest)}
          />
        </div>
      ) : null}

      {!props.useParamSettingsModal ? (
        <div className={`storyboard-top${props.isSettingExpanded ? ' storyboard-top-expanded' : ''}`}>
          <div className="storyboard-left-col">
            <StoryboardPanelAssetRows
              mode={props.mode}
              showStoryboardVideoAssets={props.showStoryboardVideoAssets}
              nineGridEnabled={props.nineGridEnabled ?? false}
              referenceImage={props.referenceImage ?? null}
              sceneImages={props.sceneImages}
              characterImages={props.characterImages}
              propImages={props.propImages}
              otherImages={props.otherImages}
              onNineGridEnabledChange={props.onNineGridEnabledChange}
              onStoryboardVideoReferenceClick={onStoryboardVideoReferenceClick}
              onClearReference={props.onClearReference}
              onOpenSelectModal={props.onOpenSelectModal}
              onPreviewAssetImage={props.onPreviewAssetImage}
              onRemoveOtherImage={props.onRemoveOtherImage}
            />
            {/* 展开时：描述框在左侧栏下方 */}
            {props.isSettingExpanded ? (
              <div className="storyboard-prompt storyboard-prompt-in-left-col">
                <RichTextEditor
                  ref={promptEditorExpandedRef}
                  value={props.prompt}
                  placeholder={props.promptPlaceholder}
                  minHeight={promptHeightExpanded}
                  maxHeight={promptHeightExpanded}
                  maxLength={props.promptMaxLength}
                  showCount
                  enablePromptAssetRefs={enablePromptAssetRefs}
                  promptAssets={storyboardPromptAssets}
                  enablePromptParamRefs={enablePromptParamRefs}
                  promptParamGroups={storyboardPromptParamGroups}
                  onChange={(v) => props.onPromptChange?.(v)}
                  onPromptParamChange={onPromptParamChange}
                />
                <div className="prompt-footer">{renderPromptFooterActions(true)}</div>
              </div>
            ) : null}
          </div>
          <div className={`setting-panel${props.isSettingExpanded ? ' expanded' : ''}`}>
            <div className="setting-body">{renderSettingBody()}</div>
            <div
              className="setting-header"
              onClick={() => props.onIsSettingExpandedChange?.(!props.isSettingExpanded)}
            >
              <div className="text-gradient">
                <span>{props.isSettingExpanded ? '收起' : '展开'}</span>
              </div>
            </div>
          </div>
        </div>
      ) : null}

      {/* 参数弹窗模式：描述框撑满中间区域 */}
      {props.useParamSettingsModal ? (
        <div
          ref={promptCollapsedRef}
          className="storyboard-prompt storyboard-prompt-full storyboard-prompt-modal create-modal-prompt-shell"
        >
          {props.promptPrefix}
          {/* 分镜视频·图生/宫格：参考图导入区（在文本域上方；与多参布局一致。九宫格开关仍由 showStoryboardVideoAssets 控制） */}
          {props.mode === 'storyboardVideo' ? (
            <GenerateSourceImagesStrip
              key="i2v-storyboard-video"
              variant="i2v"
              useShimmer
              enablePreview
              images={storyboardVideoStripItems}
              showAdder={storyboardVideoReferenceList.length < 1}
              showAdderText
              adderText="导入参考图"
              onRemove={onStoryboardVideoStripRemove}
              onPreview={onStoryboardVideoReferenceThumbClick}
              onOpenAdder={() => props.onImportReference?.()}
            />
          ) : props.mode === 'imageToVideo' ? (
            /* 多参生视频：场景/角色/道具参考图导入区 */
            <GenerateSourceImagesStrip
              key="i2v-multi-param"
              variant="i2v"
              useShimmer
              enablePreview
              images={multiParamStripItems}
              showAdder
              showAdderText
              adderText={props.referenceImportLabel}
              onRemove={onMultiParamStripRemove}
              onPreview={(img) => props.onPreviewAssetImage?.(img)}
              onOpenAdder={() => props.onImportReference?.()}
            />
          ) : props.mode === 'edgeVideo' && referenceAudioStripItems.length > 0 ? (
            /* 首尾帧：文本域上方仅展示参考音频（首尾帧图在 prompt-prefix） */
            <GenerateSourceImagesStrip
              key="i2v-edge-audio"
              variant="i2v"
              useShimmer
              enablePreview
              images={referenceAudioStripItems}
              showAdder={false}
              onRemove={(index) => props.onRemoveReferenceAudio?.(index)}
            />
          ) : null}
          <RichTextEditor
            ref={promptEditorCollapsedRef}
            value={props.prompt}
            placeholder={props.promptPlaceholder}
            minHeight="120px"
            maxLength={props.promptMaxLength}
            showCount
            enablePromptAssetRefs={enablePromptAssetRefs}
            promptAssets={storyboardPromptAssets}
            enablePromptParamRefs={enablePromptParamRefs}
            promptParamGroups={storyboardPromptParamGroups}
            onChange={(v) => props.onPromptChange?.(v)}
            onPromptParamChange={onPromptParamChange}
          />
          <div className="prompt-footer prompt-footer-modal">
            {renderPromptFooterActions(false)}
            {props.mode !== 'storyboardVideo' && props.mode !== 'edgeVideo' ? (
              <div className="prompt-footer-right">
                <button
                  type="button"
                  className="param-settings-btn"
                  onClick={() => setParamSettingsOpen(true)}
                >
                  <div className="text-gradient">
                    <span>灵感空间</span>
                  </div>
                </button>
              </div>
            ) : null}
          </div>
        </div>
      ) : !props.isSettingExpanded ? (
        /* 未展开时：描述框占满一行（内联模式） */
        <div ref={promptCollapsedRef} className="storyboard-prompt storyboard-prompt-full">
          <RichTextEditor
            ref={promptEditorCollapsedRef}
            value={props.prompt}
            placeholder={props.promptPlaceholder}
            minHeight={promptHeightCollapsed}
            maxHeight={promptHeightCollapsed}
            maxLength={props.promptMaxLength}
            showCount
            enablePromptAssetRefs={enablePromptAssetRefs}
            promptAssets={storyboardPromptAssets}
            enablePromptParamRefs={enablePromptParamRefs}
            promptParamGroups={storyboardPromptParamGroups}
            onChange={(v) => props.onPromptChange?.(v)}
            onPromptParamChange={onPromptParamChange}
          />
          <div className="prompt-footer">{renderPromptFooterActions(false)}</div>
        </div>
      ) : null}

      <div ref={slotWrapRef} className="storyboard-slot-wrap">
        {props.children}
      </div>

      {props.useParamSettingsModal && props.mode !== 'storyboardVideo' && props.mode !== 'edgeVideo' ? (
        <StoryboardParamSettingsModal
          ref={paramSettingsModalRef}
          open={paramSettingsOpen}
          onOpenChange={setParamSettingsOpen}
          mode={props.mode}
          sceneImages={props.sceneImages}
          characterImages={props.characterImages}
          propImages={props.propImages}
          otherImages={props.otherImages}
          nineGridEnabled={props.nineGridEnabled}
          referenceImage={props.referenceImage}
          referenceImages={props.referenceImages}
          selectedComposition={props.selectedComposition}
          selectedShotSize={props.selectedShotSize}
          selectedCameraAngle={props.selectedCameraAngle}
          selectedFocalLength={props.selectedFocalLength}
          selectedColorTone={props.selectedColorTone}
          selectedLighting={props.selectedLighting}
          selectedTechnique={props.selectedTechnique}
          compositionDesc={props.compositionDesc}
          activeSettingKey={props.activeSettingKey}
          selectedCameraMovement={props.selectedCameraMovement}
          cameraMovementDesc={props.cameraMovementDesc}
          selectedShootingTechnique={props.selectedShootingTechnique}
          activeVideoSettingKey={props.activeVideoSettingKey}
          imageToVideoNineGridEnabled={props.imageToVideoNineGridEnabled ?? props.nineGridEnabled}
          imageToVideoReferenceImages={props.imageToVideoReferenceImages ?? props.referenceImages}
          imageToVideoSelectedCameraMovement={
            props.imageToVideoSelectedCameraMovement ?? props.selectedCameraMovement
          }
          imageToVideoCameraMovementDesc={
            props.imageToVideoCameraMovementDesc ?? props.cameraMovementDesc
          }
          imageToVideoSelectedShootingTechnique={
            props.imageToVideoSelectedShootingTechnique ?? props.selectedShootingTechnique
          }
          imageToVideoActiveVideoSettingKey={
            props.imageToVideoActiveVideoSettingKey ?? props.activeVideoSettingKey
          }
          prompt={props.prompt}
          extraPromptAssets={props.extraPromptAssets}
          onOpenSelectModal={(type) => props.onOpenSelectModal?.(type as PanelSelectModalType)}
          onPreviewAssetImage={(img) => props.onPreviewAssetImage?.(img)}
          onImportReference={() => props.onImportReference?.()}
          onPreviewReference={() => props.onPreviewReference?.()}
          onToggleAssetRef={onDraftToggleAssetRef}
          onSyncAssetRefs={onDraftSyncAssetRefs}
          onConfirm={onParamSettingsConfirm}
        />
      ) : null}
    </div>
  )
})

export default StoryboardGeneratePanel

export type { PromptAssetItem }
