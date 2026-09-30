'use client'

import { message } from 'antd'
import { useState,type MutableRefObject } from 'react'
import type { StoryboardVideoBatchGenerate } from '~/composables/useStoryboardVideoBatchGenerate'
import { useCreationStore } from '~/stores/creation'
import type { StoryboardPanel,StoryboardVideoPanel } from '~/types'
import type { BillingQuoteRequest } from '~/types/business-api'
import { parseServerStoryboardId } from '~/composables/useStoryboardWorkbenchMutations'
import { shouldSilentStoryboardBatchToast } from '~/utils/taskSseSilentDisconnect'
import { storyboardApiErr } from './useStoryboardVideoPanelOps'

/**
 * StoryboardVideo 步骤的批量生成动作（原 StoryboardVideo.vue script 内
 * handleBatchGenerateVideoConfirm / startBatchVideoGenerate / stopVideoGeneration /
 * regeneratePanel 部分原样搬迁）。
 */
export function useStoryboardVideoBatchActions(opts: {
  videoBatchGen: StoryboardVideoBatchGenerate
  pageDisposedRef: MutableRefObject<boolean>
  panelsRef: MutableRefObject<StoryboardVideoPanel[]>
  scriptPanelsRef: MutableRefObject<StoryboardPanel[]>
  onChangeRef: MutableRefObject<(next: StoryboardVideoPanel[]) => void>
  batchVideoSubmittingRef: MutableRefObject<boolean>
  canAutoGenerateVideoRef: MutableRefObject<boolean>
  batchVideoDisabledTooltipRef: MutableRefObject<string>
  mergeStoryboardVideoPanelUiFromStore: () => void
}) {
  const {
    videoBatchGen,
    pageDisposedRef,
    panelsRef,
    scriptPanelsRef,
    onChangeRef,
    batchVideoSubmittingRef,
    canAutoGenerateVideoRef,
    batchVideoDisabledTooltipRef,
    mergeStoryboardVideoPanelUiFromStore
  } = opts

  const [batchVideoSubmitting, setBatchVideoSubmittingState] = useState(false)
  const [videoGenerationStopped, setVideoGenerationStopped] = useState(false)
  void videoGenerationStopped
  /** 单条停止时标记，不关全局 */
  const [videoGenerationAborted, setVideoGenerationAborted] = useState(false)
  void videoGenerationAborted

  function setBatchVideoSubmitting(v: boolean) {
    batchVideoSubmittingRef.current = v
    setBatchVideoSubmittingState(v)
  }

  async function handleBatchGenerateVideoConfirm(payload: {
    mode: 'image' | 'video'
    selectedStoryboardIds: number[]
    videoModel?: string
    aspectRatio?: string
    resolution?: string
    durationSeconds?: number
    soundEffects?: 'none' | 'with-sound'
  }) {
    if (payload.mode !== 'video') return
    const videoModel = String(payload.videoModel || '').trim()
    const aspectRatio = String(payload.aspectRatio || '').trim()
    const resolution = String(payload.resolution || '')
      .trim()
      .toLowerCase()
    const soundEffects =
      payload.soundEffects === 'with-sound' || payload.soundEffects === 'none'
        ? payload.soundEffects
        : 'none'
    useCreationStore.getState().setStoryboardVideoGenerateSettings({
      ...(videoModel ? { videoModel } : {}),
      ...(aspectRatio ? { aspectRatio } : {}),
      ...(resolution ? { resolution } : {}),
      soundEffects
    })
    await startBatchVideoGenerate(payload.selectedStoryboardIds, !!videoModel)
  }

  async function startBatchVideoGenerate(
    selectedStoryboardIds?: number[],
    manualVideoModelPick = false
  ) {
    if (
      useCreationStore.getState().isGeneratingStoryboardVideo ||
      batchVideoSubmittingRef.current
    ) {
      return
    }
    if (!canAutoGenerateVideoRef.current) {
      message.warning(batchVideoDisabledTooltipRef.current || '暂无分镜视频')
      return
    }
    setBatchVideoSubmitting(true)
    setVideoGenerationStopped(false)
    setVideoGenerationAborted(false)
    try {
      const result = await videoBatchGen.runBatchVideosOnly({
        scriptPanels: scriptPanelsRef.current,
        videoPanels: panelsRef.current,
        selectedStoryboardIds,
        manualVideoModelPick,
        onPanelsUpdate: (next) => {
          if (pageDisposedRef.current) return
          onChangeRef.current(next)
        }
      })
      if (pageDisposedRef.current) return
      if (result.ok) {
        message.success('分镜视频批量生成完成')
      } else if (result.message && !shouldSilentStoryboardBatchToast(result.message)) {
        if (result.message.includes('停止') || result.message.includes('取消')) {
          message.info(result.message)
        } else if (result.message.includes('部分')) {
          message.warning(result.message)
        } else {
          message.error(result.message)
        }
      }
    } catch (e: unknown) {
      if (pageDisposedRef.current) return
      const errMsg = storyboardApiErr(e)
      if (shouldSilentStoryboardBatchToast(errMsg)) return
      message.error(errMsg)
      useCreationStore.getState().stopStoryboardVideoBatchGeneration()
    } finally {
      setBatchVideoSubmitting(false)
      // 提交期间 store watcher 会主动让路给批量 owner；owner 结束后立即按终态 store
      // 重投影一次，避免最后一次 watcher 变更因 submitting gate 被跳过而遗留旧 loading。
      mergeStoryboardVideoPanelUiFromStore()
    }
  }

  async function stopVideoGeneration() {
    setVideoGenerationStopped(true)
    const stopResult = await videoBatchGen.requestStop()
    const next = panelsRef.current.map((p) => ({
      ...p,
      generating: false
    }))
    onChangeRef.current(next)
    if (stopResult.requested) {
      message.info('已请求停止；已开始的视频仍会结算，未开始的将退回积分')
    } else {
      message.warning('停止请求失败，已停止本页跟进')
    }
  }

  async function regeneratePanel(index: number) {
    const scriptPanel = scriptPanelsRef.current[index]
    const videoPanel = panelsRef.current[index]
    if (!scriptPanel || !videoPanel) return

    setVideoGenerationStopped(false)
    const result = await videoBatchGen.regenerateSinglePanel({
      scriptPanel,
      videoPanel,
      panelIndex: index,
      videoPanels: panelsRef.current,
      onPanelsUpdate: (next) => {
        if (pageDisposedRef.current) return
        onChangeRef.current(next)
      }
    })

    if (pageDisposedRef.current) return
    if (result.ok) {
      message.success('重新生成成功')
    } else if (result.message && !shouldSilentStoryboardBatchToast(result.message)) {
      message.error(result.message)
    }
  }

  async function resolveRegeneratePanelBillingRequest(
    index: number
  ): Promise<BillingQuoteRequest | null> {
    const storyboardId = parseServerStoryboardId(scriptPanelsRef.current[index]?.id)
    if (storyboardId == null) return null
    const submitBody = await videoBatchGen.buildVideoWithPromptSubmitBody({
      storyboardIds: [storyboardId],
      overwrite: true,
      manualVideoModelPick: false
    })
    return submitBody
      ? {
          quoteType: 'STORYBOARD_VIDEO_WITH_PROMPT',
          payload: { ...submitBody }
        }
      : null
  }

  return {
    batchVideoSubmitting,
    handleBatchGenerateVideoConfirm,
    startBatchVideoGenerate,
    stopVideoGeneration,
    regeneratePanel,
    resolveRegeneratePanelBillingRequest
  }
}
