'use client'

import { message } from 'antd'
import {
captureCreationLiveGenScope,
matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import { formatCreationImageProgressText,runEditImageTask } from '~/composables/useEditImageTask'
import { userAssetRpsFormImageSceneSplit } from '~/utils/businessApi'
import { resolveDialogueToolbarSourceImages } from '~/utils/formImageEditPrefill'
import { storyboardPromptHtmlToPlain } from '~/utils/storyboardPromptAssetRef'
import { EDIT_ASSET_PROMPT_MAX_CHARS } from '~/utils/htmlPlain'
import { resolveModelPromptCharacterLimit } from '~/utils/modelCapability'
import { shouldApplyModalTaskProgressToCanvas } from '~/utils/liveGenScopeIsolation'
import { createSceneModalCanvasGenerateActions } from './sceneModalCanvasGenerateActions'
import {
resolveRpsImageIdFromLocalImage,
resolveRpsSourceType
} from './sceneModalTaskParsers'
import {
buildSceneModalGenerateRequest,
resolveSceneModalGenerationFormId
} from './sceneModalGenerateRequest'
import type {
EditSceneImageModalCtx,
SceneModalGenerateApi,
SceneModalTaskOwner
} from './types'

export type { SceneModalGenerateApi }

export function useSceneModalGenerate(ctx: EditSceneImageModalCtx): SceneModalGenerateApi {
  /** 「对话作图」Tab：genMode=chat，参考图 0~N 张（0 张为纯文生图） */
  const handleStartDialogueGenerate = async () => {
    if (ctx.showGenerateFooterButtonLoading()) return
    const instructionText = storyboardPromptHtmlToPlain(ctx.dialogueInstructionHtml.get() || '').trim()
    if (!instructionText) {
      message.warning('请输入修改要求')
      return
    }
    const promptMaxLength = resolveModelPromptCharacterLimit(
      ctx.selectedDialogueRawModel(),
      instructionText,
      EDIT_ASSET_PROMPT_MAX_CHARS
    )
    if (instructionText.length > promptMaxLength) {
      message.warning(`提示词不能超过 ${promptMaxLength.toLocaleString('zh-CN')} 个字符`)
      return
    }

    const refUrls = ctx.dialogueSourceImages
      .get()
      .map((img) => String(img.url || '').trim())
      .filter(Boolean)

    const currentImgRow = ctx.localSceneImages.get()[ctx.currentImageIndex.get()] as Record<string, unknown> | undefined
    const formId = resolveSceneModalGenerationFormId(currentImgRow, ctx.activeRpsFormIds())
    if (formId == null) {
      message.warning('当前缺少形态信息，无法发起生图')
      return
    }

    const modelCode = String(ctx.selectedDialogueModel()?.id || '').trim()
    if (!modelCode) {
      message.warning('请先选择生图模型')
      return
    }

    ctx.preserveDialogueComposer.current = true

    const sceneIdx = ctx.currentSceneIndex.get()
    const modalScope = ctx.captureModalScopeSnapshot(sceneIdx)
    const taskScopeKey = modalScope.editorScopeKey
    /** 剧集隔离：任务归属启动时作品/集；切集后终态收尾不得写当前集 store、不得 toast */
    const liveScope = captureCreationLiveGenScope()
    ctx.ensureGeneratingPlaceholderImage(sceneIdx)
    const imgIdx = ctx.currentImageIndex.get()
    ctx.beginCanvasTaskOverlay(sceneIdx, imgIdx, '生图任务提交中...', 'dialogue')

    let submittedTaskId: number | null = null
    const submitPayload = buildSceneModalGenerateRequest({
      formId,
      genMode: 'chat',
      referenceImages: refUrls.length > 0 ? refUrls : undefined,
      prompt: instructionText,
      modelCode,
      aspectRatio: ctx.dialogueSettings.get().aspectRatio || '1:1',
      size: ctx.dialogueSettings.get().quality?.toUpperCase() || '2K',
      imageCount: ctx.dialogueSettings.get().count || 1
    })
    const result = await runEditImageTask({
      ...submitPayload,
      onSubmitted: ({ taskId }) => {
        submittedTaskId = taskId
        ctx.persistSceneModalSseTask(sceneIdx, imgIdx, 'dialogue', taskId, { formId })
      },
      onProgress: (p) => {
        const hit = ctx.store().getSceneModalSseTask(taskScopeKey, liveScope.scopeKey)
        if (hit && Number(hit.taskId) === Number(submittedTaskId)) {
          ctx.syncSceneModalSseProgress(hit, p, liveScope.scopeKey)
        }
        ctx.applyCanvasProgressIfCurrent({
          liveGenScopeKey: liveScope.scopeKey,
          editorScopeKey: taskScopeKey,
          taskId: submittedTaskId,
          text: formatCreationImageProgressText(p)
        })
        if (
          p.items?.length &&
          shouldApplyModalTaskProgressToCanvas({
            taskLiveGenScopeKey: liveScope.scopeKey,
            currentLiveGenScopeKey: ctx.currentModalLiveGenScopeKey(),
            taskEditorScopeKey: taskScopeKey,
            currentEditorScopeKey: ctx.buildEditorScopeKeyForSceneIndex(ctx.currentSceneIndex.get()),
            modalOpen: ctx.props().open
          })
        ) {
          void ctx.claimFormImagesForModal(submittedTaskId ?? 0, 'form_edit_chat', { items: p.items }).then(async () => {
            if (!ctx.isSameModalScope(modalScope)) return
            const focusId = p.items![p.items!.length - 1]?.imageId ?? null
            await ctx.initFormImageListOnOpen({ focusImageId: focusId })
            if (ctx.isSameModalScope(modalScope)) ctx.emitSceneTabUpdate(ctx.buildVisibleImagesForParent())
          })
        }
      }
    })

    // 关弹窗再进 / 双重 restore / suspend：旧 SSE 被抢占，勿清 Pinia / 勿 toast；释放锁后交给 restore 续跟
    if (result.ok === false && result.deferred) {
      if (taskScopeKey) {
        ctx.handleDeferredSceneModalFollow({
          sceneIdx,
          editorScopeKey: taskScopeKey,
          liveGenScopeKey: liveScope.scopeKey,
          errorMessage: result.errorMessage
        })
      }
      return
    }

    const taskOwner: SceneModalTaskOwner = {
      editorScopeKey: taskScopeKey,
      taskId: submittedTaskId,
      liveGenScopeKey: liveScope.scopeKey
    }

    /** 剧集隔离：已切集则只清任务所属 scope 桶快照，不写当前集 UI/store、不 toast、不回写图片 */
    if (!matchesCreationLiveGenScope(liveScope)) {
      ctx.clearSceneModalTaskStateIfOwned(taskOwner)
      return
    }

    const canClearTaskUi = ctx.clearSceneModalTaskStateIfOwned(taskOwner, { sceneIdx })
    if (canClearTaskUi) ctx.endCanvasTaskOverlay(sceneIdx, imgIdx)

    if (result.ok === false) {
      if (ctx.isSameModalScope(modalScope)) message.error(result.errorMessage || '生图失败')
      return
    }
    await ctx.claimFormImagesForModal(submittedTaskId ?? 0, 'form_edit_chat', { items: result.items })
    await ctx.refreshAfterEditChatGenerate(result.items, modalScope)

    const successCount = result.items.length
    const failMsg = result.failCount ? `，${result.failCount} 张失败` : ''
    if (ctx.isSameModalScope(modalScope)) {
      message.success(`对话作图完成，共生成 ${successCount} 张${failMsg}`)
    }

    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('create-flow-global-tasks-updated'))
    }
  }

  /** 仅当前画布条目展示拆分 loading，避免切 Tab/换图后按钮仍转圈挡住其它入口 */
  const showSceneSplitOverlay = () => {
    if (!ctx.isSceneSplitting.get()) return false
    return (
      ctx.sceneSplitTargetKey.get() ===
      ctx.buildCanvasOverlayKey(ctx.currentSceneIndex.get(), ctx.currentImageIndex.get())
    )
  }

  const showSceneSplitToolbarLoading = () => showSceneSplitOverlay()

  /** 场景图：中间工具栏「拆分四宫格」— 后端切图、上传 OSS 并入库 */
  async function handleSceneSplitFourGrid(index: number) {
    if (!ctx.isSceneEditMode()) return
    if (ctx.isSceneSplitting.get()) return

    const img = ctx.currentSceneImages()[index] as {
      url?: string
      title?: string
      name?: string
      _pending?: boolean
      rpsImageId?: number
      canSplit?: boolean
    } | undefined

    if (!img?.url) {
      message.warning('请先选择一张可用图片')
      return
    }
    if (img._pending) {
      message.warning(`请先完成「${ctx.addImageButtonLabel()}」后再拆分`)
      return
    }

    ctx.currentImageIndex.set(index)
    const sceneIdx = ctx.currentSceneIndex.get()
    ctx.sceneSplitTargetKey.set(ctx.buildCanvasOverlayKey(sceneIdx, index))
    ctx.isSceneSplitting.set(true)
    ctx.sceneSplitProgressText.set('正在准备…')

    try {
      let sourceImageId = resolveRpsImageIdFromLocalImage(img)
      if (sourceImageId == null) {
        ctx.sceneSplitProgressText.set('正在同步图片信息…')
        const syncResult = await ctx.syncImageToRpsApi(
          String(img.url).trim(),
          String(img.title || img.name || ''),
          resolveRpsSourceType(img),
          img
        )
        if (syncResult?.imageId != null) {
          img.rpsImageId = syncResult.imageId
          sourceImageId = syncResult.imageId
        }
      }
      if (sourceImageId == null) {
        message.warning('当前图片尚未同步到服务端，无法拆分')
        return
      }

      const projectId = Number(ctx.store().currentProjectId)
      if (!Number.isFinite(projectId) || projectId <= 0) {
        message.warning('项目信息缺失，无法拆分')
        return
      }

      ctx.sceneSplitProgressText.set('正在拆分四宫格…')
      const result = await userAssetRpsFormImageSceneSplit({ projectId, sourceImageId })

      ctx.lastInitFormImageListKey.current = ''
      await ctx.initFormImageListOnOpen()

      const firstChildId = result.children?.[0]?.id
      if (firstChildId != null) {
        const childIdx = ctx.localSceneImages.get().findIndex(
          (x: { rpsImageId?: number }) => Number(x?.rpsImageId) === Number(firstChildId)
        )
        if (childIdx >= 0) ctx.currentImageIndex.set(childIdx)
      }

      ctx.emitSceneTabUpdate(ctx.buildVisibleImagesForParent())
      message.success('四宫格拆分完成，已加入左侧列表')
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '四宫格拆分失败')
    } finally {
      ctx.isSceneSplitting.set(false)
      ctx.sceneSplitTargetKey.set('')
      ctx.sceneSplitProgressText.set('正在拆分四宫格…')
    }
  }

  /** 场景 / 道具 / 形态(form) 等：保存参考图优先，否则沿用当前图单图兜底。 */
  function handleDialogueImage(index: number) {
    ctx.releaseDialogueComposerPreserve()
    ctx.currentImageIndex.set(index)
    ctx.applyCurrentFormImageEditPrefill()
    const hasSavedReferences = ctx.dialogueSourceImages.get().length > 0
    ctx.dialogueSourceImages.set(
      resolveDialogueToolbarSourceImages(
        ctx.dialogueSourceImages.get(),
        ctx.currentSceneImages()[index]
      )
    )
    message.info(
      hasSavedReferences
        ? '已切换到对话作图，并载入历史参考图'
        : ctx.dialogueSourceImages.get().length > 0
          ? '已切换到对话作图，并添加当前图片为参考图'
          : '已切换到对话作图'
    )
  }

  const {
    handleSettingCardSelect,
    handleUpscaleModelSelect,
    handleMultiAngle,
    handleMultiAngleGenerate
  } = createSceneModalCanvasGenerateActions(ctx)
  return {
    handleStartDialogueGenerate,
    showSceneSplitOverlay,
    showSceneSplitToolbarLoading,
    handleSceneSplitFourGrid,
    handleDialogueImage,
    handleSettingCardSelect,
    handleUpscaleModelSelect,
    handleMultiAngle,
    handleMultiAngleGenerate
  }
}
