'use client'

import { AI_MODEL_FUNC_CODE } from '~/utils/aiModelFuncCodes'
import { aidAgentList,userModelListByFuncCodes } from '~/utils/businessApi'
import { buildAidAgentListScopeParams } from '~/utils/createFlowProjectContext'
import {
resolveStoryboardVideoAgentBizCategories,
resolveStoryboardVideoModelFuncCodes,
showStoryboardGridVideoTab,
showStoryboardImageToVideoTab,
showStoryboardMultiParamVideoTab
} from '~/utils/creationModeUiRules'
import {
clearAgentDefaultModelCache,
resolveAgentModelCodeInGroup,
STORYBOARD_VIDEO_AGENT_BIZ_CATEGORY,
STORYBOARD_VIDEO_GRID_AGENT_BIZ_CATEGORY,
STORYBOARD_VIDEO_IMAGE_AGENT_BIZ_CATEGORY,
STORYBOARD_VIDEO_PROMPT_AGENT_BIZ_CATEGORY,
STORYBOARD_VIDEO_PROMPT_GRID_AGENT_BIZ_CATEGORY,
STORYBOARD_VIDEO_PROMPT_IMAGE_AGENT_BIZ_CATEGORY
} from '~/utils/extractAgentBiz'
import { advanceGenerationToken,replaceRefValue } from '~/utils/generationToken'
import { EDIT_ASSET_PROMPT_MAX_CHARS } from '~/utils/htmlPlain'
import { resolveModelPromptCharacterLimit } from '~/utils/modelCapability'
import {
modelsFromListByFuncGroups,
pickFirstNonEmptyModelPool
} from '~/utils/modelListByFuncBatch'
import type { VideoModalCtx,VideoModalModelsApi } from './types'
import { useVideoModalModelCore } from './useVideoModalModelCore'

/** 模型池加载 / 选择守卫 / 视频参数 capability / 推荐时长（原 setup 模型段逻辑） */
export function useVideoModalModels(ctx: VideoModalCtx): void {
  const { activeVideoModelGet, activeVideoRawModel, applyRecommendedVideoDuration, applySavedVideoGenerateSettings, applyVideoModelDefaultFromAgent, edgeList, gridList, handleSelectEdgeVideoModel, handleSelectGridVideoModel, handleSelectImageToVideoModel, handleSelectMultiParamVideoModel, imageToVideoList, loadRecommendedDurationForScene, mapVideoModelOption, multiParamList, reapplyVideoModelDefaultIfEmpty, refreshRecommendedDurationAfterPromptGenerate, resolveCurrentGenerateAudio, resolveGridVideoPromptAgentCode, resolveImageVideoPromptAgentCode, resolveMultiVideoPromptAgentCode, selectedEdgeVideoModel, selectedGridVideoModel, selectedImageToVideoModel, selectedMultiParamVideoModel, syncVideoSettingsToModel, videoConfigShowAudio, videoConfigShowDuration, videoDurationTip, videoRawModelList, videoSettingsRef } = useVideoModalModelCore(ctx)
  async function initVideoModelOptions() {
    const gen = advanceGenerationToken(ctx.initVideoModelGen)
    clearAgentDefaultModelCache()

    const creationMode = ctx.projectCreationMode()
    const imagePromptAgentCode = resolveImageVideoPromptAgentCode()
    const multiPromptAgentCode = resolveMultiVideoPromptAgentCode()
    const gridPromptAgentCode = resolveGridVideoPromptAgentCode()
    const funcCodes = resolveStoryboardVideoModelFuncCodes(creationMode)
    const agentBizCodes = resolveStoryboardVideoAgentBizCategories(creationMode)

    const listScope = buildAidAgentListScopeParams(ctx.store())
    const [agentGroups, modelGroups] = await Promise.all([
      agentBizCodes.length
        ? aidAgentList({ bizCategoryCodes: agentBizCodes, ...listScope })
        : Promise.resolve([] as Awaited<ReturnType<typeof aidAgentList>>),
      // 专业版：带 projectId，后端将 main_storyboard_video 重映射为 multi_pro
      userModelListByFuncCodes(funcCodes, listScope),
      ctx.ensureDictLoaded()
    ])

    if (gen !== ctx.initVideoModelGen.current) return

    // 批量解析；仍空时用同 scope 的 loadModels 兜底（可落到 model/list，避免下拉空白）
    if (showStoryboardImageToVideoTab(creationMode)) {
      const imageToVideoPool = modelsFromListByFuncGroups(
        modelGroups,
        AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO_IMAGE
      )
      if (imageToVideoPool.length > 0) {
        imageToVideoList.setRawModelList(imageToVideoPool)
        imageToVideoList.setModelList(imageToVideoPool.map(mapVideoModelOption))
      } else {
        await imageToVideoList.loadModels()
      }
    }

    if (showStoryboardMultiParamVideoTab(creationMode)) {
      // 专业版 listByFunc 带 projectId 后分组 funcCode 可能为 multi_pro
      const multiParamPool = pickFirstNonEmptyModelPool(modelGroups, [
        AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO_MULTI_PRO,
        AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO
      ])
      if (multiParamPool.length > 0) {
        multiParamList.setRawModelList(multiParamPool)
        multiParamList.setModelList(multiParamPool.map(mapVideoModelOption))
      } else {
        await multiParamList.loadModels()
      }
    }

    const edgePool = modelsFromListByFuncGroups(modelGroups, AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO_EDGE)
    if (edgePool.length > 0) {
      edgeList.setRawModelList(edgePool)
      edgeList.setModelList(edgePool.map(mapVideoModelOption))
    } else {
      await edgeList.loadModels()
    }

    if (showStoryboardGridVideoTab(creationMode)) {
      const gridPool = modelsFromListByFuncGroups(
        modelGroups,
        AI_MODEL_FUNC_CODE.STORYBOARD_VIDEO_GRID
      )
      if (gridPool.length > 0) {
        gridList.setRawModelList(gridPool)
        gridList.setModelList(gridPool.map(mapVideoModelOption))
      } else {
        await gridList.loadModels()
      }
    }

    /** 优先出片智能体 modelCode，再尝试提示词智能体（与 listByFunc 视频池对齐） */
    if (showStoryboardImageToVideoTab(creationMode)) {
      replaceRefValue(
        ctx.cachedImageToVideoAgentModelCodes,
        [
          resolveAgentModelCodeInGroup(
            agentGroups,
            STORYBOARD_VIDEO_IMAGE_AGENT_BIZ_CATEGORY,
            imagePromptAgentCode
          ),
          resolveAgentModelCodeInGroup(
            agentGroups,
            STORYBOARD_VIDEO_PROMPT_IMAGE_AGENT_BIZ_CATEGORY,
            imagePromptAgentCode
          )
        ].filter(Boolean)
      )
    } else {
      replaceRefValue(ctx.cachedImageToVideoAgentModelCodes, [])
    }

    if (showStoryboardMultiParamVideoTab(creationMode)) {
      replaceRefValue(ctx.cachedMultiParamAgentModelCodes, [
        resolveAgentModelCodeInGroup(
          agentGroups,
          STORYBOARD_VIDEO_AGENT_BIZ_CATEGORY,
          multiPromptAgentCode
        ),
        resolveAgentModelCodeInGroup(
          agentGroups,
          STORYBOARD_VIDEO_PROMPT_AGENT_BIZ_CATEGORY,
          multiPromptAgentCode
        )
      ].filter(Boolean))
    } else {
      replaceRefValue(ctx.cachedMultiParamAgentModelCodes, [])
    }

    if (showStoryboardGridVideoTab(creationMode)) {
      replaceRefValue(ctx.cachedGridVideoAgentModelCodes, [
        resolveAgentModelCodeInGroup(
          agentGroups,
          STORYBOARD_VIDEO_GRID_AGENT_BIZ_CATEGORY,
          gridPromptAgentCode
        ),
        resolveAgentModelCodeInGroup(
          agentGroups,
          STORYBOARD_VIDEO_PROMPT_GRID_AGENT_BIZ_CATEGORY,
          gridPromptAgentCode
        )
      ].filter(Boolean))
    } else {
      replaceRefValue(ctx.cachedGridVideoAgentModelCodes, [])
    }

    applySavedVideoGenerateSettings()

    if (showStoryboardImageToVideoTab(creationMode)) {
      applyVideoModelDefaultFromAgent(
        'imageToVideo',
        imageToVideoList.getModelList(),
        ctx.cachedImageToVideoAgentModelCodes.current
      )
    }
    if (showStoryboardMultiParamVideoTab(creationMode)) {
      applyVideoModelDefaultFromAgent(
        'multiParam',
        multiParamList.getModelList(),
        ctx.cachedMultiParamAgentModelCodes.current
      )
    }
    if (!String(ctx.edgeVideoModel.get() || '').trim() && edgeList.getModelList().length) {
      ctx.edgeVideoModel.set(edgeList.getModelList()[0]!.id)
    }
    if (showStoryboardGridVideoTab(creationMode)) {
      applyVideoModelDefaultFromAgent(
        'gridVideo',
        gridList.getModelList(),
        ctx.cachedGridVideoAgentModelCodes.current
      )
    }
    syncVideoSettingsToModel()
    applyRecommendedVideoDuration()
  }

  const api: VideoModalModelsApi = {
    imageToVideoModelOptions: () => imageToVideoList.getModelList(),
    multiParamVideoModelOptions: () => multiParamList.getModelList(),
    edgeVideoModelOptions: () => edgeList.getModelList(),
    gridVideoModelOptions: () => gridList.getModelList(),
    selectedImageToVideoModel,
    selectedMultiParamVideoModel,
    selectedEdgeVideoModel,
    selectedGridVideoModel,
    activeVideoModelGet,
    videoRawModelList,
    activeVideoRawModel,
    activeVideoPromptMaxLength: (prompt) =>
      resolveModelPromptCharacterLimit(
        activeVideoRawModel(),
        prompt,
        EDIT_ASSET_PROMPT_MAX_CHARS
      ),
    handleSelectImageToVideoModel,
    handleSelectMultiParamVideoModel,
    handleSelectEdgeVideoModel,
    handleSelectGridVideoModel,
    initVideoModelOptions,
    reapplyVideoModelDefaultIfEmpty,
    applySavedVideoGenerateSettings,
    resolveCurrentGenerateAudio,
    syncVideoSettingsToModel,
    applyRecommendedVideoDuration,
    loadRecommendedDurationForScene,
    refreshRecommendedDurationAfterPromptGenerate,
    videoConfigShowDuration,
    videoConfigShowAudio,
    videoDurationTip,
    videoAspectRatioOptions: () => videoSettingsRef.current.aspectRatioSelectOptions,
    videoDurationOptions: () => videoSettingsRef.current.durationSelectOptions,
    videoCountOptions: () => videoSettingsRef.current.countSelectOptions,
    videoQualityOptions: () => videoSettingsRef.current.qualitySelectOptions,
    videoAudioOptions: () => videoSettingsRef.current.audioSelectOptions,
    resolveImageVideoPromptAgentCode,
    resolveGridVideoPromptAgentCode,
    resolveMultiVideoPromptAgentCode
  }
  Object.assign(ctx, api)
}
