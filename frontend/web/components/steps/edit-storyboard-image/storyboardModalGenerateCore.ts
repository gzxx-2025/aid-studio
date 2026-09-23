'use client'

import { message } from 'antd'
import { EDIT_ASSET_PROMPT_MAX_CHARS } from '~/utils/htmlPlain'
import { resolveModelPromptCharacterLimit } from '~/utils/modelCapability'
import {
captureCreationLiveGenScope,
matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import {
findStoryboardImageGenTaskInScopes
} from '~/composables/useCreationStoreHydration'
import { buildModalTaskOverlayKey } from '~/composables/useModalTaskScope'
import {
followStoryboardImageGenerateTask,
runStoryboardImageGenerateTask
} from '~/composables/useStoryboardImageGenerateTask'
import { modalGenSessionScopeFromScopeKey } from '~/utils/modalGenSessionScope'
import {
clearModalImageGenSession,
persistModalImageGenSession
} from '~/utils/storyboardImageModalGenSession'
import { activeStoryboardImageModalGenFollowIds } from '~/utils/storyboardImageModalOwnedFollow'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
formatTaskSseLiveText,
formatTaskSseLiveTextWithCounts
} from '~/utils/taskSseProgressText'
import type { EditStoryboardImageModalCtx } from './types'
import { buildStoryboardModalGenerateRequest } from './storyboardModalGenerateRequest'

const activeStoryboardImageFollowStoryboardIds = activeStoryboardImageModalGenFollowIds

export function createStoryboardModalGenerateCore(ctx: EditStoryboardImageModalCtx) {
  const showStoryboardGenerateOverlay = () => {
    const sceneIdx = ctx.currentSceneIndex.get()
    const sid = ctx.currentStoryboardId()
    if (sid != null && ctx.isDialogueGenerationInProgress(sid)) return false

    if (ctx.isModalStoryboardImageUiActive(sid, sceneIdx)) return true

    const creationStore = ctx.store()
    if (
      creationStore.isGeneratingStoryboardImageBatch &&
      sid != null &&
      ctx.isStoryboardPanelImageGenerating(sid) &&
      !findStoryboardImageGenTaskInScopes(creationStore, sid, ctx.route())
    ) {
      return true
    }

    return false
  }

  const showCanvasImageGenMask = () => {
    const sid = ctx.currentStoryboardId()
    if (
      sid != null &&
      (ctx.isDialogueGenerationInProgress(sid) || ctx.isModalOverlayGenerationInProgress(sid))
    ) {
      return false
    }
    return showStoryboardGenerateOverlay()
  }

  const sceneImageGenMaskText = () => {
    const sid = ctx.currentStoryboardId()
    if (sid != null && ctx.isDialogueGenerationInProgress(sid)) {
      return ctx.upscaleProgressText.get() || '对话作图中...'
    }
    if (sid != null && ctx.isModalOverlayGenerationInProgress(sid)) {
      const session = ctx.readSessionForScene(ctx.currentSceneIndex.get())
      const overlayKind = ctx.resolveCanvasOverlayTaskKind(
        ctx.getModalImageGenTask(sid),
        session?.tab
      )
      return (
        ctx.upscaleProgressText.get() ||
        (overlayKind ? ctx.canvasOverlayDefaultProgressText(overlayKind) : '生成中...')
      )
    }
    return storyboardGenerateOverlayText()
  }

  const showStoryboardGenerateButtonLoading = () => showStoryboardGenerateOverlay()

  const storyboardGenerateOverlayText = () => {
    const sid = ctx.currentStoryboardId()
    const creationStore = ctx.store()
    if (
      creationStore.isGeneratingStoryboardImageBatch &&
      ctx.isStoryboardPanelImageGenerating(sid)
    ) {
      return formatTaskSseLiveTextWithCounts(
        creationStore.storyboardImageBatchProgress,
        '分镜图生成中'
      )
    }
    const persisted =
      sid != null ? findStoryboardImageGenTaskInScopes(creationStore, sid, ctx.route()) : null
    const fromStore = formatTaskSseLiveText(persisted || {}, '')
    if (fromStore) return fromStore
    return formatTaskSseLiveText(
      { message: ctx.storyboardGenerateProgressText.get() },
      ctx.storyboardGenerateProgressText.get() || '分镜图生成中…'
    )
  }

  const handleStartGenerate = async () => {
    if (showStoryboardGenerateButtonLoading()) return

    const promptPlain = ctx.storyboardPromptPlainText().trim()
    if (!promptPlain) {
      message.warning('请输入描述内容或先生成提示词')
      return
    }
    const promptMaxLength = resolveModelPromptCharacterLimit(
      ctx.selectedRawModel(),
      promptPlain,
      EDIT_ASSET_PROMPT_MAX_CHARS
    )
    if (promptPlain.length > promptMaxLength) {
      message.warning(`提示词不能超过 ${promptMaxLength.toLocaleString('zh-CN')} 个字符`)
      return
    }
    if (!ctx.currentStoryboardId()) {
      message.warning('分镜ID缺失，无法发起生成')
      return
    }

    const modelCode = String(ctx.selectedModel()?.id || '').trim()
    if (!modelCode) {
      message.warning('请先选择生图模型')
      return
    }

    const quality = String(ctx.generationSettings.get().quality || '').trim()
    const size = quality ? quality.toUpperCase() : ''
    const aspectRatio = String(ctx.generationSettings.get().aspectRatio || '').trim() || '16:9'
    const count = Math.max(1, Math.min(8, Number(ctx.generationSettings.get().count) || 1))
    const creationStore = ctx.store()
    const agentCode = String(
      creationStore.storyboardStylistGenerateSettings?.agentId ||
        creationStore.storyboardStylistAgent?.id ||
        ''
    ).trim()

    const sceneIdx = ctx.currentSceneIndex.get()
    const beforeCount = (ctx.props().scenes[sceneIdx]?.images || []).length

    const submitBody = buildStoryboardModalGenerateRequest({
      storyboardIds: [ctx.currentStoryboardId()!],
      ...(agentCode ? { agentCode } : {}),
      imagePrompt: promptPlain,
      modelName: modelCode,
      aspectRatio,
      size: size || undefined,
      count
    })

    // size=清晰度档（1K/2K…），与 aspectRatio 同时下发；对齐对话作图 / 场景弹窗 / 批量出图
    await runStoryboardImageGenerateForScene(sceneIdx, {
      submitBody,
      beforeCount,
      progressSubmitText: '分镜图提交中…'
    })
  }

  async function runStoryboardImageGenerateForScene(
    sceneIdx: number,
    opts: {
      submitBody?: Parameters<typeof runStoryboardImageGenerateTask>[0]['body']
      resumeTaskId?: number
      resumeRecordId?: number | null
      beforeCount?: number
      progressSubmitText?: string
      silentComplete?: boolean
    }
  ) {
    const storyboardId = Number(ctx.props().scenes[sceneIdx]?.storyboardId)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) return
    if (!opts.submitBody) {
      const session = ctx.readSessionForScene(sceneIdx)
      const task = ctx.getModalImageGenTask(storyboardId)
      if (
        ctx.isDialogueModalTask(task) ||
        ctx.isCanvasOverlayModalTask(task) ||
        session?.tab === 'dialogue' ||
        ctx.isModalOverlaySessionTab(session?.tab)
      ) {
        return
      }
    }

    /** 剧集隔离：任务归属启动时 scope；切集后终态收尾不得写当前集扁平 store、不得 toast、不得回写记录 */
    const taskScope = captureCreationLiveGenScope()
    const taskSessionScope = modalGenSessionScopeFromScopeKey(taskScope.scopeKey)

    const overlayParts = ctx.overlayKeyParts(sceneIdx, -1, 'storyboard-gen')
    ctx.storyboardGenerateTargetKey.set(buildModalTaskOverlayKey(overlayParts))
    ctx.isGeneratingStoryboardImage.set(true)
    if (opts.resumeTaskId) {
      const persisted = findStoryboardImageGenTaskInScopes(ctx.store(), storyboardId, ctx.route())
      const batchLive = formatTaskSseLiveTextWithCounts(
        ctx.store().storyboardImageBatchProgress,
        '分镜图生成中'
      )
      const singleLive = formatTaskSseLiveText(persisted || {}, '')
      ctx.storyboardGenerateProgressText.set(
        singleLive ||
          (ctx.store().isGeneratingStoryboardImageBatch ? batchLive : '') ||
          '分镜图生成中…'
      )
    } else {
      ctx.storyboardGenerateProgressText.set(opts.progressSubmitText || '分镜图生成中…')
    }
    persistModalImageGenSession(
      storyboardId,
      sceneIdx,
      taskScope.scopeKey,
      { tab: 'generate' },
      taskSessionScope
    )
    ctx.ensureGeneratingPlaceholderImage(sceneIdx)

    const beforeCount = opts.beforeCount
    let completeHandled = false
    let keepPendingUi = false

    const finalizeStoryboardImageGenerateSuccess = async (
      recordId: number | null | undefined,
      options?: { skipMessage?: boolean }
    ) => {
      if (!matchesCreationLiveGenScope(taskScope)) {
        if (!completeHandled) {
          completeHandled = true
          ctx.store().clearStoryboardImageGenTask(storyboardId, taskScope.scopeKey)
          clearModalImageGenSession(taskSessionScope)
        }
        return
      }
      if (!completeHandled) {
        completeHandled = true
        ctx.clearModalStoryboardImageLoadingUi(storyboardId, sceneIdx)
        ctx.storyboardGenerateProgressText.set('同步生成记录…')
        // SSE 完成后必须绕过打开弹窗时的空/旧 list 缓存，否则会不打 list-by-storyboard、画布空白
        await ctx.refreshSceneRecords(sceneIdx, recordId ?? undefined, beforeCount, { force: true })
      }
      if (!opts.silentComplete && !options?.skipMessage) {
        message.success('分镜图生成成功')
      }
    }

    const onProgress = (p: {
      successCount?: number
      totalCount?: number
      stepTitle?: string
      message?: string
      recordId?: number | null
      items?: Array<{ recordId?: number; imageUrl?: string; imageId?: number }>
    }) => {
      const live = String(p.message || p.stepTitle || '').trim()
      if (live) {
        ctx.storyboardGenerateProgressText.set(live)
        const task = findStoryboardImageGenTaskInScopes(ctx.store(), storyboardId, ctx.route())
        if (task?.taskId) {
          ctx.store().setStoryboardImageGenTask(
            storyboardId,
            {
              taskId: task.taskId,
              sceneIdx,
              kind: 'storyboard',
              message: p.message,
              stepTitle: p.stepTitle
            },
            taskScope.scopeKey
          )
        }
      } else if (p.successCount != null && p.totalCount != null) {
        ctx.storyboardGenerateProgressText.set(`已生成 ${p.successCount}/${p.totalCount} 张…`)
      }
      if (
        p.recordId != null ||
        (p.items && p.items.length > 0) ||
        (p.stepTitle === '生成完成' && (p.successCount ?? 0) > 0)
      ) {
        const rid =
          p.recordId ??
          p.items?.[p.items.length - 1]?.recordId ??
          p.items?.[p.items.length - 1]?.imageId ??
          null
        void finalizeStoryboardImageGenerateSuccess(rid != null ? Number(rid) : null, {
          skipMessage: true
        })
      }
    }

    activeStoryboardImageFollowStoryboardIds.add(storyboardId)

    try {
      const projectEpisode = await resolveStoryScriptSaveContext(ctx.store(), ctx.route())
      if (!matchesCreationLiveGenScope(taskScope)) {
        ctx.store().clearStoryboardImageGenTask(storyboardId, taskScope.scopeKey)
        clearModalImageGenSession(taskSessionScope)
        return
      }

      let result: Awaited<ReturnType<typeof runStoryboardImageGenerateTask>>

      if (opts.resumeTaskId) {
        result = await followStoryboardImageGenerateTask({
          taskId: opts.resumeTaskId,
          storyboardId,
          recordId: opts.resumeRecordId ?? null,
          projectEpisode,
          onProgress
        })
      } else if (opts.submitBody) {
        result = await runStoryboardImageGenerateTask({
          body: opts.submitBody,
          projectEpisode,
          onSubmitted: ({ taskId }) => {
            ctx.store().setStoryboardImageGenTask(
              storyboardId,
              { taskId, sceneIdx, kind: 'storyboard' },
              taskScope.scopeKey
            )
            ctx.syncModalImageGenSessionTaskId(
              storyboardId,
              sceneIdx,
              taskId,
              { tab: 'generate' },
              taskSessionScope,
              taskScope.scopeKey
            )
            ctx.suspendLateModalImageFollowIfScopeChanged(taskId, taskScope)
          },
          onProgress
        })
      } else {
        return
      }

      if (!result.ok && 'deferred' in result && result.deferred) {
        keepPendingUi = true
        return
      }

      if (!result.ok) {
        /** 剧集隔离：已切集则只清任务所属 scope 桶快照，不写当前集 UI/store、不 toast */
        if (!matchesCreationLiveGenScope(taskScope)) {
          ctx.store().clearStoryboardImageGenTask(storyboardId, taskScope.scopeKey)
          clearModalImageGenSession(taskSessionScope)
          return
        }
        if (!opts.silentComplete) {
          message.error('errorMessage' in result ? result.errorMessage || '生图失败' : '生图失败')
        }
        ctx.clearModalStoryboardImageLoadingUi(storyboardId, sceneIdx)
        await ctx.refreshSceneRecords(sceneIdx, undefined, undefined, { force: true })
        return
      }

      await finalizeStoryboardImageGenerateSuccess(result.recordId)
    } catch (e: unknown) {
      if (!matchesCreationLiveGenScope(taskScope)) {
        ctx.store().clearStoryboardImageGenTask(storyboardId, taskScope.scopeKey)
        clearModalImageGenSession(taskSessionScope)
        return
      }
      if (!opts.silentComplete) {
        const err = e as { msg?: string; message?: string }
        message.error(String(err?.msg || err?.message || '生图失败'))
      }
      ctx.clearModalStoryboardImageLoadingUi(storyboardId, sceneIdx)
      await ctx.refreshSceneRecords(sceneIdx, undefined, undefined, { force: true })
    } finally {
      activeStoryboardImageFollowStoryboardIds.delete(storyboardId)
      if (!keepPendingUi && !activeStoryboardImageFollowStoryboardIds.size) {
        ctx.isGeneratingStoryboardImage.set(false)
      }
    }
  }

  /** 刷新或重新打开弹窗后，恢复当前分镜的生图 loading 与 SSE 追踪（按 storyboardId 隔离） */
  return {
    handleStartGenerate,
    runStoryboardImageGenerateForScene,
    sceneImageGenMaskText,
    showCanvasImageGenMask,
    showStoryboardGenerateButtonLoading,
    showStoryboardGenerateOverlay,
    storyboardGenerateOverlayText,
  }
}
