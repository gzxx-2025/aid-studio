'use client'

import { message } from 'antd'
import {
buildStoryboardVideoReferenceOverrides,
collectStoryboardPromptAssets,
mergePromptAssets,
patchEmptyResolvedPromptAssets
} from '~/utils/storyboardPromptAssetRef'
import { buildStoryboardVideoResolutionField } from '~/utils/storyboardVideoGenerateParams'
import {
validateEdgeVideoPromptPlain,
validateGridVideoPromptPlain,
validateImageToVideoPromptPlain,
validateMultiParamVideoPromptPlain
} from '~/utils/storyboardVideoPromptSave'
import { buildGenerateReferenceAudioFields } from '~/utils/storyboardVideoReferenceAudioWire'
import { buildGenerateReferenceVideoFields } from '~/utils/storyboardVideoReferenceVideoWire'
import {
  parseReferenceVideoCapability,
  validateReferenceVideoCount
} from '~/utils/referenceVideoCapability'
import type { VideoModalCtx,VideoModalGenerateActionsApi } from './types'

/** 各出片方向的 body 组装 / 校验 / 开始生成入口（拆自 useVideoModalGenerate，超 800 行体量红线） */
export function useVideoModalGenerateActions(ctx: VideoModalCtx): void {
  // ---------- 各出片方向：组装 body 并提交 ----------

  async function runStoryboardImageVideoGenerateTaskForScene(opts: {
    sceneIdx: number
    progressSubmit: string
    progressRunning: string
    videoPrompt?: string
    userInputText?: string
  }) {
    const storyboardId = ctx.currentStoryboardId()
    if (!storyboardId) {
      message.warning('分镜ID缺失，无法发起生成')
      return
    }

    const images = ctx.collectReferenceImageUrls()
    if (!ctx.validateImageToVideoReferenceImages(images)) return

    const modelName = String(ctx.imageToVideoModel.get() || '').trim()
    if (!modelName) {
      message.warning('请先选择图生视频模型')
      return
    }

    const durationSec = Number(ctx.videoDuration.get())
    const body = {
      storyboardIds: [storyboardId],
      ...(images.length ? { images } : {}),
      modelName,
      videoPrompt: opts.videoPrompt?.trim() || undefined,
      baseImageRecordId: ctx.resolveBaseImageRecordId(),
      aspectRatio: ctx.videoAspectRatio.get() || undefined,
      durationSeconds:
        ctx.videoConfigShowDuration() && Number.isFinite(durationSec) && durationSec > 0
          ? durationSec
          : undefined,
      ...buildStoryboardVideoResolutionField(ctx.videoQuality.get()),
      count: ctx.videoCount.get(),
      generateAudio: ctx.resolveCurrentGenerateAudio(),
      ...buildGenerateReferenceAudioFields(ctx.referenceAudios.get()),
      userInputText: opts.userInputText
    }

    persistVideoGenerateSettings(modelName)

    await ctx.runStoryboardVideoGenerateForScene(opts.sceneIdx, {
      taskKind: 'i2v',
      submitImageVideoBody: body,
      progressSubmit: opts.progressSubmit,
      progressRunning: opts.progressRunning
    })
  }

  async function runStoryboardMultiVideoGenerateTaskForScene(opts: {
    sceneIdx: number
    progressSubmit: string
    progressRunning: string
    videoPrompt?: string
    userInputText?: string
  }) {
    const storyboardId = ctx.currentStoryboardId()
    if (!storyboardId) {
      message.warning('分镜ID缺失，无法发起生成')
      return
    }

    const modelName = String(ctx.multiParamVideoModel.get() || '').trim()
    if (!modelName) {
      message.warning('请先选择多参生视频模型')
      return
    }
    const referenceVideoCheck = validateReferenceVideoCount(
      parseReferenceVideoCapability(ctx.activeVideoRawModel()),
      ctx.referenceVideos.get().length
    )
    if (referenceVideoCheck.ok === false) {
      message.warning(referenceVideoCheck.message)
      return
    }

    const durationSec = Number(ctx.videoDuration.get())
    const promptPlain = opts.videoPrompt?.trim() || ctx.multiParamPromptPlain().trim()
    const localAssets = collectStoryboardPromptAssets(
      ctx.sceneImages.get(),
      ctx.characterImages.get(),
      ctx.propImages.get(),
      ctx.otherImages.get()
    )
    const mergedAssets = mergePromptAssets(
      patchEmptyResolvedPromptAssets(ctx.resolvedMultiParamPromptAssets.get(), localAssets),
      localAssets
    )
    const referenceOverrides = buildStoryboardVideoReferenceOverrides(promptPlain, mergedAssets)
    const body = {
      storyboardIds: [storyboardId],
      modelName,
      videoPrompt: promptPlain || undefined,
      ...(Object.keys(referenceOverrides).length ? { referenceOverrides } : {}),
      aspectRatio: ctx.videoAspectRatio.get() || undefined,
      durationSeconds:
        ctx.videoConfigShowDuration() && Number.isFinite(durationSec) && durationSec > 0
          ? durationSec
          : undefined,
      ...buildStoryboardVideoResolutionField(ctx.videoQuality.get()),
      count: ctx.videoCount.get(),
      generateAudio: ctx.resolveCurrentGenerateAudio(),
      ...buildGenerateReferenceAudioFields(ctx.referenceAudios.get()),
      ...buildGenerateReferenceVideoFields(ctx.referenceVideos.get()),
      userInputText: opts.userInputText
    }

    persistVideoGenerateSettings(modelName)

    await ctx.runStoryboardVideoGenerateForScene(opts.sceneIdx, {
      taskKind: 'multi',
      submitMultiBody: body,
      progressSubmit: opts.progressSubmit,
      progressRunning: opts.progressRunning
    })
  }

  async function runStoryboardGridVideoGenerateTaskForScene(opts: {
    sceneIdx: number
    progressSubmit: string
    progressRunning: string
    videoPrompt?: string
    userInputText?: string
  }) {
    const storyboardId = ctx.currentStoryboardId()
    if (!storyboardId) {
      message.warning('分镜ID缺失，无法发起生成')
      return
    }

    const modelName = String(ctx.gridVideoModel.get() || '').trim()
    if (!modelName) {
      message.warning('请先选择宫格视频模型')
      return
    }

    const durationSec = Number(ctx.videoDuration.get())
    const body = {
      storyboardIds: [storyboardId],
      modelName,
      videoPrompt: opts.videoPrompt?.trim() || undefined,
      aspectRatio: ctx.videoAspectRatio.get() || undefined,
      durationSeconds:
        ctx.videoConfigShowDuration() && Number.isFinite(durationSec) && durationSec > 0
          ? durationSec
          : undefined,
      ...buildStoryboardVideoResolutionField(ctx.videoQuality.get()),
      count: ctx.videoCount.get(),
      generateAudio: ctx.resolveCurrentGenerateAudio(),
      ...buildGenerateReferenceAudioFields(ctx.referenceAudios.get()),
      userInputText: opts.userInputText
    }

    persistVideoGenerateSettings(modelName)

    await ctx.runStoryboardVideoGenerateForScene(opts.sceneIdx, {
      taskKind: 'grid',
      submitGridBody: body,
      progressSubmit: opts.progressSubmit,
      progressRunning: opts.progressRunning
    })
  }

  async function runStoryboardEdgeVideoGenerateTaskForScene(opts: {
    sceneIdx: number
    progressSubmit: string
    progressRunning: string
    videoPrompt?: string
    userInputText?: string
  }) {
    const storyboardId = ctx.currentStoryboardId()
    if (!storyboardId) {
      message.warning('分镜ID缺失，无法发起生成')
      return
    }

    if (!ctx.validateEdgeFrameImages()) return

    const modelName = String(ctx.edgeVideoModel.get() || '').trim()
    const durationSec = Number(ctx.videoDuration.get())
    const promptPlain = opts.videoPrompt?.trim() || ctx.edgeVideoPromptPlain().trim()
    const firstFields = ctx.buildEdgeFrameApiFields(ctx.firstFrameImage.get(), 'first')
    const lastFields = ctx.buildEdgeFrameApiFields(ctx.lastFrameImage.get(), 'last')
    const audioFields = buildGenerateReferenceAudioFields(ctx.referenceAudios.get())
    const item = {
      storyboardId,
      ...firstFields,
      ...lastFields,
      ...(audioFields.referenceAudioIds ? { referenceAudioIds: audioFields.referenceAudioIds } : {})
    }
    const count = Math.min(4, Math.max(1, Number(ctx.videoCount.get()) || 1))
    const body = {
      storyboardIds: [storyboardId],
      items: [item],
      ...(modelName ? { modelName } : {}),
      ...(promptPlain ? { videoPrompt: promptPlain } : {}),
      aspectRatio: ctx.videoAspectRatio.get() || undefined,
      durationSeconds:
        ctx.videoConfigShowDuration() && Number.isFinite(durationSec) && durationSec > 0
          ? durationSec
          : undefined,
      ...buildStoryboardVideoResolutionField(ctx.videoQuality.get()),
      count,
      generateAudio: ctx.resolveCurrentGenerateAudio(),
      ...(opts.userInputText?.trim()
        ? { userInputText: opts.userInputText.trim().slice(0, 500) }
        : {})
    }

    if (modelName) persistVideoGenerateSettings(modelName)

    await ctx.runStoryboardVideoGenerateForScene(opts.sceneIdx, {
      taskKind: 'edge',
      submitEdgeBody: body,
      progressSubmit: opts.progressSubmit,
      progressRunning: opts.progressRunning
    })
  }

  function persistVideoGenerateSettings(modelName: string) {
    const ar = ctx.videoAspectRatio.get()
    const savedAspect =
      ar === '16:9' || ar === '9:16' || ar === '4:3' || ar === '1:1' ? ar : undefined
    const savedResolution = String(ctx.videoQuality.get() || '')
      .trim()
      .toLowerCase()
    const durationSec = Number(ctx.videoDuration.get())
    ctx.store().setStoryboardVideoGenerateSettings({
      videoModel: modelName,
      ...(savedAspect ? { aspectRatio: savedAspect } : {}),
      ...(savedResolution ? { resolution: savedResolution } : {}),
      ...(ctx.videoConfigShowDuration() && Number.isFinite(durationSec) && durationSec > 0
        ? { durationSeconds: durationSec }
        : { durationSeconds: null }),
      // 不支持音画同出时不写入，避免把临时 coerce 的 silent 覆盖用户偏好
      ...(ctx.videoConfigShowAudio()
        ? { soundEffects: ctx.videoAudio.get() === 'with_audio' ? 'with-sound' : 'none' }
        : {})
    })
  }

  // ---------- 各出片方向：开始生成入口 ----------

  async function handleImageToVideoStartGenerate() {
    if (ctx.showImageToVideoGenerateLoadingGet()) return
    const promptPlain = ctx.imageToVideoPromptPlain().trim()
    if (!promptPlain) {
      message.warning('请输入视频提示词，或先生成提示词')
      return
    }
    const validation = validateImageToVideoPromptPlain(
      promptPlain,
      ctx.activeVideoPromptMaxLength(promptPlain)
    )
    if (validation.ok === false) {
      message.warning(validation.message)
      return
    }
    const supplementary = ctx.cameraMovementDesc.get().trim().slice(0, 500)
    await runStoryboardImageVideoGenerateTaskForScene({
      sceneIdx: ctx.currentSceneIndex.get(),
      progressSubmit: '图生视频任务提交中…',
      progressRunning: '图生视频生成中…',
      videoPrompt: promptPlain,
      userInputText: supplementary || undefined
    })
  }

  async function handleGridVideoStartGenerate() {
    if (ctx.showGridVideoGenerateLoadingGet()) return
    const promptPlain = ctx.imageToVideoPromptPlain().trim()
    if (!promptPlain) {
      message.warning('请输入视频提示词，或先生成提示词')
      return
    }
    const validation = validateGridVideoPromptPlain(
      promptPlain,
      ctx.activeVideoPromptMaxLength(promptPlain)
    )
    if (validation.ok === false) {
      message.warning(validation.message)
      return
    }
    const supplementary = ctx.cameraMovementDesc.get().trim().slice(0, 500)
    await runStoryboardGridVideoGenerateTaskForScene({
      sceneIdx: ctx.currentSceneIndex.get(),
      progressSubmit: '宫格视频任务提交中…',
      progressRunning: '宫格视频生成中…',
      videoPrompt: promptPlain,
      userInputText: supplementary || undefined
    })
  }

  async function handleMultiParamStartGenerate() {
    if (ctx.showMultiParamGenerateLoadingGet()) return
    const promptPlain = ctx.multiParamPromptPlain().trim()
    if (!promptPlain) {
      message.warning('请输入描述内容，或先生成提示词')
      return
    }
    const validation = validateMultiParamVideoPromptPlain(
      promptPlain,
      ctx.activeVideoPromptMaxLength(promptPlain)
    )
    if (validation.ok === false) {
      message.warning(validation.message)
      return
    }
    if (!ctx.currentStoryboardId()) {
      message.warning('分镜ID缺失，无法发起生成')
      return
    }
    const supplementary = ctx.multiParamShootingTechnique.get()?.value?.trim().slice(0, 500)
    await runStoryboardMultiVideoGenerateTaskForScene({
      sceneIdx: ctx.currentSceneIndex.get(),
      progressSubmit: '多参生视频任务提交中…',
      progressRunning: '多参视频生成中…',
      videoPrompt: promptPlain,
      userInputText: supplementary || undefined
    })
  }

  async function handleEdgeVideoStartGenerate() {
    if (ctx.showEdgeVideoGenerateLoadingGet()) return
    const promptPlain = ctx.edgeVideoPromptPlain().trim()
    if (promptPlain) {
      const validation = validateEdgeVideoPromptPlain(
        promptPlain,
        ctx.activeVideoPromptMaxLength(promptPlain)
      )
      if (validation.ok === false) {
        message.warning(validation.message)
        return
      }
    }
    if (!ctx.currentStoryboardId()) {
      message.warning('分镜ID缺失，无法发起生成')
      return
    }
    await runStoryboardEdgeVideoGenerateTaskForScene({
      sceneIdx: ctx.currentSceneIndex.get(),
      progressSubmit: '首尾帧视频任务提交中…',
      progressRunning: '首尾帧视频生成中…',
      videoPrompt: promptPlain || undefined
    })
  }

  const api: VideoModalGenerateActionsApi = {
    handleImageToVideoStartGenerate,
    handleGridVideoStartGenerate,
    handleMultiParamStartGenerate,
    handleEdgeVideoStartGenerate,
    persistVideoGenerateSettings
  }
  Object.assign(ctx, api)
}
