'use client'

import { message } from 'antd'
import { isStoryboardVideoTaskOngoing } from '~/composables/useStoryboardVideoGenerateTask'
import { userReferenceAudioDelete } from '~/utils/businessApi'
import { type ReferenceMediaItem } from '~/utils/referenceMediaItem'
import { fetchUserStoryboardDetailOnce } from '~/utils/storyboardDetailOnce'
import {
readStoryboardDetailPromptField
} from '~/utils/storyboardPromptGenerateFlow'
import { validateImageToVideoPromptPlain } from '~/utils/storyboardVideoPromptSave'
import {
collectNewlyAddedPromptAudioAssets,
mergeReferenceAudioLists,
removeAudioFromPromptAndList,
syncAudioPlaceholdersIntoPrompt
} from '~/utils/storyboardVideoReferenceAudioWire'
import {
  collectNewlyAddedPromptVideoAssets,
  mergeReferenceVideoLists,
  removeVideoFromPromptAndList,
  syncVideoPlaceholdersIntoPrompt
} from '~/utils/storyboardVideoReferenceVideoWire'
import type { VideoModalCtx,VideoModalPromptApi } from './types'

import { useVideoModalPromptEditorOps } from './useVideoModalPromptEditorOps'

/** 视频提示词：拉取回填 / 生成任务 / 参考音频占位联动（原 setup 提示词段逻辑） */
export function useVideoModalPrompt(ctx: VideoModalCtx): void {
  const { applyMultiParamPromptFromApi, applyVideoParamSelectionsFromPlain, applyVideoPromptFromApi, aspectRatioEnumOptions, beginVideoPromptApply, cameraMovementOptions, edgeVideoPromptPlain, ensureDictLoaded, handleEdgeVideoPromptEditorChange, handleImageToVideoPromptEditorChange, handleMultiParamPromptEditorChange, imageToVideoPromptPlain, invalidateVideoPromptApply, isStoryboardVideoPromptGeneratingForScene, multiParamPromptParamGroups, multiParamPromptPlain, renderStoryboardVideoPromptApiTextToEditor, shootingTechniqueOptions, showGeneratingMultiParamPromptForScene, showGeneratingVideoPromptForScene, videoPromptParamGroups } = useVideoModalPromptEditorOps(ctx)

  async function fetchStoryboardImageToVideoPrompt(storyboardId: number): Promise<string> {
    const row = await fetchUserStoryboardDetailOnce(storyboardId)
    return readStoryboardDetailPromptField(row, 'videoPromptImage')
  }

  async function fetchStoryboardMultiVideoPrompt(storyboardId: number): Promise<string> {
    const row = await fetchUserStoryboardDetailOnce(storyboardId)
    return readStoryboardDetailPromptField(row, 'videoPrompt')
  }

  function saveEdgeVideoPromptToCache(storyboardId: string | number | null | undefined) {
    if (!storyboardId) return
    ctx.edgeVideoPromptByStoryboardId.set({
      ...ctx.edgeVideoPromptByStoryboardId.get(),
      [String(storyboardId)]: ctx.edgeVideoPrompt.get()
    })
  }

  function restoreEdgeVideoPromptFromCache(storyboardId: string | number | null | undefined) {
    if (!storyboardId) {
      ctx.edgeVideoPrompt.set('')
      return
    }
    ctx.edgeVideoPrompt.set(ctx.edgeVideoPromptByStoryboardId.get()[String(storyboardId)] ?? '')
  }

  function loadStoryboardEdgeVideoPromptForScene() {
    restoreEdgeVideoPromptFromCache(ctx.currentStoryboardId())
  }

  async function loadStoryboardVideoPromptForScene() {
    if (isStoryboardVideoPromptGeneratingForScene()) return
    const id = ctx.currentStoryboardId()
    if (!id) {
      invalidateVideoPromptApply('imageToVideo')
      ctx.resolvedVideoPromptAssets.set([])
      ctx.imageToVideoPrompt.set('')
      return
    }
    const promptApplyTicket = beginVideoPromptApply('imageToVideo', id)
    const persisted = ctx.store().getStoryboardVideoPromptGenTask(id)
    if (
      (persisted?.taskKind === 'video-prompt-gen' ||
        persisted?.taskKind === 'grid-video-prompt-gen') &&
      (await isStoryboardVideoTaskOngoing(persisted.taskId))
    ) {
      return
    }
    try {
      const plain = await fetchStoryboardImageToVideoPrompt(id)
      await applyVideoPromptFromApi(plain, promptApplyTicket)
    } catch {
      // 请求失败或已切换分镜时保留当前输入。
    }
  }

  async function loadStoryboardMultiVideoPromptForScene() {
    if (isStoryboardVideoPromptGeneratingForScene()) return
    const id = ctx.currentStoryboardId()
    if (!id) {
      invalidateVideoPromptApply('multiParam')
      ctx.resolvedMultiParamPromptAssets.set([])
      ctx.multiParamPrompt.set('')
      return
    }
    const promptApplyTicket = beginVideoPromptApply('multiParam', id)
    const persisted = ctx.store().getStoryboardVideoPromptGenTask(id)
    if (
      persisted?.taskKind === 'multi-video-prompt-gen' &&
      (await isStoryboardVideoTaskOngoing(persisted.taskId))
    ) {
      return
    }
    try {
      const plain = await fetchStoryboardMultiVideoPrompt(id)
      await applyMultiParamPromptFromApi(plain, promptApplyTicket)
    } catch {
      // 请求失败或已切换分镜时保留当前输入。
    }
  }

  function writePromptPlainToActiveEditor(plain: string) {
    const text = String(plain || '')
    if (ctx.leftActiveTab.get() === 'multiParam') {
      handleMultiParamPromptEditorChange(
        renderStoryboardVideoPromptApiTextToEditor(text, {
          assets: ctx.resolvedMultiParamPromptAssets.get(),
          paramGroups: multiParamPromptParamGroups(),
          enableAssetRefs: true,
          enableMarkdown: true
        })
      )
      return
    }
    if (ctx.leftActiveTab.get() === 'startEndFrame') {
      handleEdgeVideoPromptEditorChange(
        renderStoryboardVideoPromptApiTextToEditor(text, {
          enableAssetRefs: true,
          enableMarkdown: false
        })
      )
      return
    }
    handleImageToVideoPromptEditorChange(
      renderStoryboardVideoPromptApiTextToEditor(text, {
        assets: ctx.resolvedVideoPromptAssets.get(),
        paramGroups: videoPromptParamGroups(),
        enableAssetRefs: true,
        enableMarkdown: false
      })
    )
  }

  function basePlainForActiveTab(): string {
    return ctx.leftActiveTab.get() === 'multiParam'
      ? multiParamPromptPlain()
      : ctx.leftActiveTab.get() === 'startEndFrame'
        ? edgeVideoPromptPlain()
        : imageToVideoPromptPlain()
  }

  function applyImportedReferenceAudios(audios: ReferenceMediaItem[]) {
    if (!audios.length) return
    const prev = ctx.referenceAudios.get()
    const merged = mergeReferenceAudioLists(prev, audios)
    ctx.referenceAudios.set(merged)
    const addedAssets = collectNewlyAddedPromptAudioAssets(prev, merged)
    if (!addedAssets.length) return
    // 优先走编辑器光标插入（与图片 upsert 一致）；编辑器未挂载时再回退文末追加
    if (ctx.getActiveStoryboardPanel()?.insertPromptAssetRefsAtCaret?.(addedAssets)) return
    writePromptPlainToActiveEditor(syncAudioPlaceholdersIntoPrompt(basePlainForActiveTab(), audios))
  }

  async function removeReferenceAudioAt(index: number) {
    const target = ctx.referenceAudios.get()[index]
    if (!target) return
    if (target.audioSource === 'upload' && Number(target.referenceAudioId) > 0) {
      try {
        await userReferenceAudioDelete({ id: Number(target.referenceAudioId) })
      } catch (e: unknown) {
        const err = e as { msg?: string; message?: string }
        message.error(err?.msg || err?.message || '删除参考音频失败')
        return
      }
    }
    const r = removeAudioFromPromptAndList(basePlainForActiveTab(), ctx.referenceAudios.get(), index)
    ctx.referenceAudios.set(r.audios)
    writePromptPlainToActiveEditor(r.plain)
  }

  function applyImportedReferenceVideos(videos: ReferenceMediaItem[]) {
    if (!videos.length) return
    const previous = ctx.referenceVideos.get()
    const merged = mergeReferenceVideoLists(previous, videos)
    ctx.referenceVideos.set(merged)
    const addedAssets = collectNewlyAddedPromptVideoAssets(previous, merged)
    if (!addedAssets.length) return
    ctx.resolvedMultiParamPromptAssets.set([
      ...ctx.resolvedMultiParamPromptAssets.get().filter((asset) => asset.assetType !== 'video'),
      ...collectNewlyAddedPromptVideoAssets([], merged)
    ])
    if (ctx.getActiveStoryboardPanel()?.insertPromptAssetRefsAtCaret?.(addedAssets)) return
    writePromptPlainToActiveEditor(syncVideoPlaceholdersIntoPrompt(basePlainForActiveTab(), merged))
  }

  function removeReferenceVideoAt(index: number) {
    const result = removeVideoFromPromptAndList(
      basePlainForActiveTab(),
      ctx.referenceVideos.get(),
      index
    )
    ctx.referenceVideos.set(result.videos)
    ctx.resolvedMultiParamPromptAssets.set([
      ...ctx.resolvedMultiParamPromptAssets.get().filter((asset) => asset.assetType !== 'video'),
      ...collectNewlyAddedPromptVideoAssets([], result.videos)
    ])
    writePromptPlainToActiveEditor(result.plain)
  }

  async function handleSaveVideoPrompt() {
    if (ctx.isSavingVideoPrompt.get() || showGeneratingVideoPromptForScene()) return
    const storyboardId = ctx.currentStoryboardId()
    if (!storyboardId) {
      message.warning('分镜ID缺失，无法保存提示词')
      return
    }

    const plain = imageToVideoPromptPlain().trim()
    const validation = validateImageToVideoPromptPlain(
      plain,
      ctx.activeVideoPromptMaxLength(plain)
    )
    if (validation.ok === false) {
      message.warning(validation.message)
      return
    }

    ctx.isSavingVideoPrompt.set(true)
    const hideLoading = message.loading('正在保存视频提示词...', 0)
    try {
      // 图生方向无独立 save 接口；出片时传 videoPrompt 会自动落库 video_prompt_image
      message.success('提示词已就绪，点击「开始生成」时将自动保存并出片')
    } finally {
      hideLoading()
      ctx.isSavingVideoPrompt.set(false)
    }
  }

  function copyCameraDesc() {
    if (ctx.cameraMovementDesc.get()) {
      navigator.clipboard.writeText(ctx.cameraMovementDesc.get())
      message.success('已复制')
    }
  }

  function copyImageToVideoPrompt() {
    const plain = imageToVideoPromptPlain()
    if (plain) {
      navigator.clipboard.writeText(plain)
      message.success('已复制')
    }
  }

  function copyMultiParamPrompt() {
    const plain = multiParamPromptPlain()
    if (plain) {
      navigator.clipboard.writeText(plain)
      message.success('已复制')
    }
  }

  function copyEdgeVideoPrompt() {
    const plain = edgeVideoPromptPlain()
    if (plain) {
      navigator.clipboard.writeText(plain)
      message.success('已复制')
    }
  }

  const api: VideoModalPromptApi = {
    imageToVideoPromptPlain,
    multiParamPromptPlain,
    edgeVideoPromptPlain,
    videoPromptParamGroups,
    multiParamPromptParamGroups,
    ensureDictLoaded,
    cameraMovementOptions,
    shootingTechniqueOptions,
    aspectRatioEnumOptions,
    showGeneratingVideoPromptForScene,
    showGeneratingMultiParamPromptForScene,
    isStoryboardVideoPromptGeneratingForScene,
    applyVideoPromptFromApi,
    applyMultiParamPromptFromApi,
    beginVideoPromptApply,
    invalidateVideoPromptApply,
    handleImageToVideoPromptEditorChange,
    handleMultiParamPromptEditorChange,
    handleEdgeVideoPromptEditorChange,
    loadStoryboardVideoPromptForScene,
    loadStoryboardMultiVideoPromptForScene,
    loadStoryboardEdgeVideoPromptForScene,
    saveEdgeVideoPromptToCache,
    handleSaveVideoPrompt,
    copyImageToVideoPrompt,
    copyMultiParamPrompt,
    copyEdgeVideoPrompt,
    copyCameraDesc,
    writePromptPlainToActiveEditor,
    applyImportedReferenceAudios,
    removeReferenceAudioAt,
    applyImportedReferenceVideos,
    removeReferenceVideoAt,
    applyVideoParamSelectionsFromPlain
  }
  Object.assign(ctx, api)
}
