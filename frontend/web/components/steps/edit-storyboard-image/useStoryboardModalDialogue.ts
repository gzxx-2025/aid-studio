'use client'

import { message } from 'antd'
import {
captureCreationLiveGenScope,
matchesCreationLiveGenScope
} from '~/composables/useCreationLiveGenScopeGuard'
import {
followStoryboardEditImageTask,
runStoryboardEditImageTask
} from '~/composables/useStoryboardEditImageTask'
import { isStoryboardImageTaskOngoing } from '~/composables/useStoryboardImageGenerateTask'
import { storyboardPromptHtmlToPlain } from '~/utils/storyboardPromptAssetRef'
import { EDIT_ASSET_PROMPT_MAX_CHARS } from '~/utils/htmlPlain'
import { resolveModelPromptCharacterLimit } from '~/utils/modelCapability'
import { modalGenSessionScopeFromScopeKey } from '~/utils/modalGenSessionScope'
import {
clearModalImageGenSession,
persistModalImageGenSession
} from '~/utils/storyboardImageModalGenSession'
import { activeStoryboardImageModalDialogueFollowIds } from '~/utils/storyboardImageModalOwnedFollow'
import type { DialogueSourceImage,EditStoryboardImageModalCtx } from './types'
import { buildStoryboardModalEditRequest } from './storyboardModalGenerateRequest'

const activeDialogueFollowStoryboardIds = activeStoryboardImageModalDialogueFollowIds

export interface StoryboardModalDialogueApi {
  handleDialogueImage: (imageIndex: number) => void
  handleDialogueImportMultiple: (payload: { sceneIndex: number; images: any[] }) => void
  removeDialogueSourceImage: (index: number) => void
  handleStartDialogueDraw: () => Promise<void>
  runStoryboardDialogueDrawForScene: (
    sceneIdx: number,
    imageIndex: number,
    opts: {
      submitPayload?: {
        storyboardId: number
        referenceImage: string
        prompt: string
        modelCode: string
        aspectRatio: string
        size: string
        imageCount: number
      }
      resumeTaskId?: number
      beforeCount?: number
      progressSubmitText?: string
      silentComplete?: boolean
    }
  ) => Promise<void>
  restoreStoryboardDialogueGenerateIfNeeded: (sceneIdx: number) => Promise<void>
}

export function useStoryboardModalDialogue(
  ctx: EditStoryboardImageModalCtx
): StoryboardModalDialogueApi {
  const handleDialogueImage = (imageIndex: number) => {
    ctx.leftActiveTab.set('dialogue')
    ctx.currentImageIndex.set(imageIndex)
    const img = ctx.currentSceneImages()[imageIndex]
    if (img && img.url) {
      ctx.dialogueSourceImages.set([{ url: img.url, title: img.title || img.name }])
      message.info('已切换到对话作图，当前图片已设为参考图')
    }
  }

  function handleDialogueImportMultiple(payload: { sceneIndex: number; images: any[] }) {
    void payload.sceneIndex
    const list = (payload.images || [])
      .map((img) => {
        const url = String(img?.url || img?.thumbnail || '').trim()
        if (!url) return null
        return { url, title: img?.title || img?.name }
      })
      .filter(Boolean) as DialogueSourceImage[]
    if (!list.length) {
      message.warning('未选择有效图片')
      return
    }
    ctx.dialogueSourceImages.set([list[0]])
    message.success(list.length > 1 ? '已选用第一张作为参考图（仅支持 1 张）' : '已导入参考图')
  }

  function removeDialogueSourceImage(index: number) {
    ctx.dialogueSourceImages.set(ctx.dialogueSourceImages.get().filter((_, i) => i !== index))
  }

  async function handleStartDialogueDraw() {
    if (ctx.showGeneratingDialogueButton()) return

    const storyboardId = ctx.currentStoryboardId()
    if (!storyboardId) {
      message.warning('分镜ID缺失，无法发起对话作图')
      return
    }

    const referenceImage = String(ctx.dialogueSourceImages.get()[0]?.url || '').trim()
    if (!referenceImage) {
      message.warning('请先添加参考图')
      return
    }

    const prompt = storyboardPromptHtmlToPlain(ctx.dialogueInstructionHtml.get() || '').trim()
    if (!prompt) {
      message.warning('请输入修改要求')
      return
    }
    const promptMaxLength = resolveModelPromptCharacterLimit(
      ctx.dialogueSelectedRawModel(),
      prompt,
      EDIT_ASSET_PROMPT_MAX_CHARS
    )
    if (prompt.length > promptMaxLength) {
      message.warning(`提示词不能超过 ${promptMaxLength.toLocaleString('zh-CN')} 个字符`)
      return
    }

    const modelCode = String(ctx.dialogueSelectedModel()?.id || '').trim()
    if (!modelCode) {
      message.warning('请先选择生图模型')
      return
    }

    const aspectRatio = ctx.dialogueSettings.get().aspectRatio || '16:9'
    const size =
      String(ctx.dialogueSettings.get().quality || '2k')
        .trim()
        .toUpperCase() || '2K'
    const imageCount = Math.max(1, Math.min(4, Number(ctx.dialogueSettings.get().count) || 1))

    const sceneIdx = ctx.currentSceneIndex.get()
    const imageIndex = ctx.currentImageIndex.get()
    const beforeCount = (ctx.props().scenes[sceneIdx]?.images || []).length

    const submitPayload = buildStoryboardModalEditRequest({
      storyboardId,
      referenceImage,
      prompt,
      modelCode,
      aspectRatio,
      size,
      imageCount
    })
    await runStoryboardDialogueDrawForScene(sceneIdx, imageIndex, {
      submitPayload,
      beforeCount
    })
  }

  async function runStoryboardDialogueDrawForScene(
    sceneIdx: number,
    imageIndex: number,
    opts: {
      submitPayload?: {
        storyboardId: number
        referenceImage: string
        prompt: string
        modelCode: string
        aspectRatio: string
        size: string
        imageCount: number
      }
      resumeTaskId?: number
      beforeCount?: number
      progressSubmitText?: string
      silentComplete?: boolean
    }
  ) {
    const storyboardId = Number(ctx.props().scenes[sceneIdx]?.storyboardId)
    if (!Number.isFinite(storyboardId) || storyboardId <= 0) return

    /** 剧集隔离：任务归属启动时 scope；切集后终态收尾不得写当前集扁平 store、不得 toast */
    const taskScope = captureCreationLiveGenScope()
    const taskSessionScope = modalGenSessionScopeFromScopeKey(taskScope.scopeKey)

    ctx.beginCanvasTaskOverlay(
      sceneIdx,
      imageIndex,
      'dialogue',
      opts.progressSubmitText || '对话作图任务提交中...'
    )
    persistModalImageGenSession(
      storyboardId,
      sceneIdx,
      taskScope.scopeKey,
      { tab: 'dialogue', imageIdx: imageIndex },
      taskSessionScope
    )
    if (sceneIdx === ctx.currentSceneIndex.get()) {
      ctx.leftActiveTab.set('dialogue')
    }

    const beforeCount = opts.beforeCount
    let completeHandled = false

    const finalizeDialogueDrawSuccess = async (
      recordId: number | null | undefined,
      options?: { skipMessage?: boolean; failCount?: number; successCount?: number }
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
        ctx.clearModalDialogueLoadingUi(storyboardId, sceneIdx, imageIndex)
        await ctx.refreshSceneRecords(sceneIdx, recordId ?? undefined, beforeCount, { force: true })
      }
      if (!opts.silentComplete && !options?.skipMessage) {
        const successCount = options?.successCount ?? 1
        const failMsg = options?.failCount ? `，${options.failCount} 张失败` : ''
        message.success(`对话作图完成，共生成 ${successCount} 张${failMsg}`)
      }
    }

    const onProgress = (p: {
      successCount?: number
      totalCount?: number
      stepTitle?: string
      message?: string
      items?: Array<{ recordId?: number; imageId?: number; imageUrl?: string }>
    }) => {
      if (p.successCount != null && p.totalCount != null) {
        ctx.upscaleProgressText.set(`已生成 ${p.successCount}/${p.totalCount} 张...`)
      } else {
        ctx.upscaleProgressText.set(p.message || p.stepTitle || '对话作图中...')
      }
      const task = ctx.getModalImageGenTask(storyboardId)
      if (task?.taskId) {
        ctx.store().setStoryboardImageGenTask(
          storyboardId,
          {
            taskId: task.taskId,
            sceneIdx,
            kind: 'dialogue',
            imageIdx: imageIndex,
            message: p.message,
            stepTitle: p.stepTitle
          },
          taskScope.scopeKey
        )
      }
      if (p.items && p.items.length > 0) {
        const lastRecordId =
          p.items[p.items.length - 1]?.recordId ?? p.items[p.items.length - 1]?.imageId ?? null
        void finalizeDialogueDrawSuccess(lastRecordId != null ? Number(lastRecordId) : null, {
          skipMessage: true
        })
      }
    }

    activeDialogueFollowStoryboardIds.add(storyboardId)

    try {
      let result: Awaited<ReturnType<typeof followStoryboardEditImageTask>>

      if (opts.resumeTaskId) {
        result = await followStoryboardEditImageTask({ taskId: opts.resumeTaskId, onProgress })
      } else if (opts.submitPayload) {
        result = await runStoryboardEditImageTask({
          ...opts.submitPayload,
          onSubmitted: ({ taskId }) => {
            ctx.store().setStoryboardImageGenTask(
              storyboardId,
              { taskId, sceneIdx, kind: 'dialogue', imageIdx: imageIndex },
              taskScope.scopeKey
            )
            ctx.syncModalImageGenSessionTaskId(
              storyboardId,
              sceneIdx,
              taskId,
              {
                tab: 'dialogue',
                imageIdx: imageIndex
              },
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
          message.error(
            'errorMessage' in result ? result.errorMessage || '对话作图失败' : '对话作图失败'
          )
        }
        ctx.clearModalDialogueLoadingUi(storyboardId, sceneIdx, imageIndex)
        return
      }

      const lastRecordId =
        result.items[result.items.length - 1]?.recordId ??
        result.items[result.items.length - 1]?.imageId ??
        null
      await finalizeDialogueDrawSuccess(lastRecordId, {
        failCount: result.failCount,
        successCount: result.items.length
      })
    } finally {
      activeDialogueFollowStoryboardIds.delete(storyboardId)
    }
  }

  /** 刷新或重新打开弹窗后，恢复当前分镜的对话作图 loading 与 SSE 追踪（按 storyboardId 隔离） */
  async function restoreStoryboardDialogueGenerateIfNeeded(sceneIdx: number) {
    const storyboardId = ctx.sceneStoryboardIdNum(sceneIdx)
    if (storyboardId == null) return
    if (!ctx.isModalImageGenOwnerScene(sceneIdx)) {
      ctx.clearLocalGeneratingPlaceholdersForScene(sceneIdx)
      return
    }

    ctx.primeDialogueLoadingUi(sceneIdx)

    if (activeDialogueFollowStoryboardIds.has(storyboardId)) {
      return
    }

    const gen = ++ctx.resumeDialogueFollowGen.current
    const persisted = ctx.getModalImageGenTask(storyboardId)
    const session = ctx.readSessionForScene(sceneIdx)
    const isDialogue = ctx.isDialogueModalTask(persisted) || session?.tab === 'dialogue'
    if (!isDialogue || ctx.isCanvasOverlayModalTask(persisted)) return

    const taskId = persisted?.taskId ?? session?.taskId ?? null
    const imageIdx = persisted?.imageIdx ?? session?.imageIdx ?? ctx.currentImageIndex.get()

    if (!taskId) {
      if (ctx.hasModalImageGenPendingState(storyboardId)) {
        ctx.primeDialogueLoadingUi(sceneIdx)
      }
      return
    }

    const ongoing = await isStoryboardImageTaskOngoing(taskId)
    if (gen !== ctx.resumeDialogueFollowGen.current) return

    if (!ongoing) {
      if (ctx.hasModalImageGenPendingState(storyboardId)) {
        ctx.primeDialogueLoadingUi(sceneIdx)
        await runStoryboardDialogueDrawForScene(sceneIdx, imageIdx, {
          resumeTaskId: taskId,
          silentComplete: true
        })
        return
      }
      ctx.clearModalDialogueLoadingUi(storyboardId, sceneIdx, imageIdx)
      return
    }

    await runStoryboardDialogueDrawForScene(sceneIdx, imageIdx, {
      resumeTaskId: taskId,
      silentComplete: true
    })
  }

  return {
    handleDialogueImage,
    handleDialogueImportMultiple,
    removeDialogueSourceImage,
    handleStartDialogueDraw,
    runStoryboardDialogueDrawForScene,
    restoreStoryboardDialogueGenerateIfNeeded
  }
}
