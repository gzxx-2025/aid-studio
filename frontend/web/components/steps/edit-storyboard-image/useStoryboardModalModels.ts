'use client'

import { useEffect, useRef } from 'react'
import { message } from 'antd'
import { useModelList } from '~/composables/useModelList'
import { mapUserModelListItemToModelOption } from '~/utils/userModelOption'
import { useModelGenerateSettings } from '~/composables/useModelGenerateSettings'
import type { ModelOption } from '~/components/steps/ModelSelectDropdown'
import {
  AI_MODEL_FUNC_CODE,
  IMAGE_MULTI_GRID_FUNC_CODE_FALLBACKS,
  STORYBOARD_IMAGE_FUNC_CODE_FALLBACKS
} from '~/utils/aiModelFuncCodes'
import {
  clearAgentDefaultModelCache,
  fetchAgentDefaultModelCodes,
  getAgentDefaultModelCacheKey,
  resolvePreferredModelIdFromAgentCodes,
  resolveSelectedModelOption,
  STORYBOARD_IMAGE_AGENT_BIZ_CATEGORY,
  STORYBOARD_IMAGE_PROMPT_AGENT_BIZ_CATEGORY
} from '~/utils/extractAgentBiz'
import { userModelListByFuncCodes } from '~/utils/businessApi'
import {
  modelsFromListByFuncGroups,
  pickFirstNonEmptyModelPool,
  uniqueTrimmedCodes
} from '~/utils/modelListByFuncBatch'
import { buildAidAgentListScopeParams } from '~/utils/createFlowProjectContext'
import { advanceGenerationToken, replaceRefValue } from '~/utils/generationToken'
import type { SelectOption } from '~/utils/modelCapability'
import type { UserModelListItem } from '~/types/business-api'
import { findModelByReference } from '~/utils/modelReference'
import type { EditStoryboardImageModalCtx } from './types'

const fallbackModelOptions: ModelOption[] = [
  {
    id: 'dream-5.0-lite',
    name: '即梦5.0lite',
    iconBg: '#60A5FA',
    tag: '性价比最高',
    tagType: 'cost-effective',
    desc: '原生文字精准渲染、复杂逻辑推理',
    prices: [
      { resolution: '2k', cost: 4 },
      { resolution: '3k', cost: 4 }
    ]
  },
  {
    id: 'dream-4.5',
    name: '即梦4.5',
    iconBg: '#60A5FA',
    desc: '原生文字精准渲染、复杂逻辑推理',
    prices: [
      { resolution: '2k', cost: 3 },
      { resolution: '4k', cost: 3 }
    ]
  },
  {
    id: 'dream-4.0',
    name: '即梦4.0',
    iconBg: '#60A5FA',
    desc: '兼顾清晰度与美感,日常出图优选',
    prices: [
      { resolution: '2k', cost: 2 },
      { resolution: '4k', cost: 2 }
    ]
  }
]

function mapStoryboardModalModelItem(
  item: Parameters<typeof mapUserModelListItemToModelOption>[0]
): ModelOption {
  return mapUserModelListItemToModelOption(item, { iconBg: '#60A5FA' })
}

const fallbackMultiViewModelOptions: ModelOption[] = []

export interface StoryboardModalModelsApi {
  modelOptions: ModelOption[]
  dialogueModelOptions: ModelOption[]
  multiViewModelOptions: ModelOption[]
  nineGridModelOptions: ModelOption[]
  selectedModel: () => ModelOption
  selectedRawModel: () => UserModelListItem | null
  dialogueSelectedModel: () => ModelOption
  dialogueSelectedRawModel: () => UserModelListItem | null
  multiViewSelectedModel: () => ModelOption
  nineGridSelectedModel: () => ModelOption
  aspectRatioSelectOptions: SelectOption<string>[]
  countSelectOptions: SelectOption<number>[]
  qualitySelectOptions: SelectOption<string>[]
  dialogueAspectRatioSelectOptions: SelectOption<string>[]
  dialogueCountSelectOptions: SelectOption<number>[]
  dialogueQualitySelectOptions: SelectOption<string>[]
  handleSelectModel: (model: ModelOption) => void
  handleSelectDialogueModel: (model: ModelOption) => void
  handleSelectNineGridModel: (model: ModelOption) => void
  applyStoryboardImageModelDefault: (options: ModelOption[], agentDefaultCodes: string[]) => void
  applyStoryboardDialogueModelDefault: (options: ModelOption[], agentDefaultCodes: string[]) => void
  reapplyStoryboardImageModelDefaultIfEmpty: () => void
  initImageModelOptions: () => Promise<void>
}

export function useStoryboardModalModels(
  ctx: EditStoryboardImageModalCtx
): StoryboardModalModelsApi {
  const {
    modelList: modelOptions,
    setModelList: setModelOptions,
    getModelList: getModelOptions,
    rawModelList,
    setRawModelList,
    getRawModelList
  } = useModelList<ModelOption>({
    funcCode: AI_MODEL_FUNC_CODE.STORYBOARD_IMAGE,
    funcCodeFallbacks: STORYBOARD_IMAGE_FUNC_CODE_FALLBACKS,
    modelType: 'image',
    projectId: () => ctx.store().currentProjectId,
    episodeId: () => ctx.store().currentEpisodeId,
    fallback: fallbackModelOptions,
    mapItem: mapStoryboardModalModelItem,
    onError: (e) => {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '加载模型列表失败，已使用默认模型')
    }
  })

  const selectedModel = () =>
    resolveSelectedModelOption(getModelOptions(), ctx.generationSettings.get().model)

  const selectedRawModel = (): UserModelListItem | null =>
    findModelByReference(getRawModelList(), ctx.generationSettings.get().model)

  const {
    aspectRatioSelectOptions,
    countSelectOptions,
    qualitySelectOptions,
    syncSettingsToModel
  } = useModelGenerateSettings({
    getSelectedModel: selectedModel,
    getRawModelList,
    getGenerationSettings: () => ({
      aspectRatio: ctx.generationSettings.get().aspectRatio,
      count: ctx.generationSettings.get().count,
      quality: ctx.generationSettings.get().quality
    }),
    setGenerationSettings: (v) => {
      ctx.generationSettings.set({
        ...ctx.generationSettings.get(),
        aspectRatio: v.aspectRatio,
        count: v.count,
        quality: v.quality
      })
    },
    include3k: true
  })

  const handleSelectModel = (model: ModelOption) => {
    ctx.generationSettings.set({ ...ctx.generationSettings.get(), model: model.id })
    ctx.modelDropdownExpanded.set(false)
    syncSettingsToModel()
  }

  const {
    modelList: dialogueModelOptions,
    setModelList: setDialogueModelOptions,
    getModelList: getDialogueModelOptions,
    rawModelList: dialogueRawModelList,
    setRawModelList: setDialogueRawModelList,
    getRawModelList: getDialogueRawModelList
  } = useModelList<ModelOption>({
    funcCode: AI_MODEL_FUNC_CODE.IMAGE_EDIT,
    modelType: 'image',
    fallback: fallbackModelOptions,
    mapItem: mapStoryboardModalModelItem,
    onError: (e) => {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '加载对话作图模型失败，已使用默认模型')
    }
  })

  const dialogueSelectedModel = () =>
    resolveSelectedModelOption(getDialogueModelOptions(), ctx.dialogueSettings.get().model)

  const dialogueSelectedRawModel = (): UserModelListItem | null =>
    findModelByReference(getDialogueRawModelList(), ctx.dialogueSettings.get().model)

  const {
    aspectRatioSelectOptions: dialogueAspectRatioSelectOptions,
    countSelectOptions: dialogueCountSelectOptionsRaw,
    qualitySelectOptions: dialogueQualitySelectOptions,
    syncSettingsToModel: syncDialogueSettingsToModel
  } = useModelGenerateSettings({
    getSelectedModel: dialogueSelectedModel,
    getRawModelList: getDialogueRawModelList,
    getGenerationSettings: () => ({
      aspectRatio: ctx.dialogueSettings.get().aspectRatio,
      count: ctx.dialogueSettings.get().count,
      quality: ctx.dialogueSettings.get().quality
    }),
    setGenerationSettings: (v) => {
      ctx.dialogueSettings.set({
        ...ctx.dialogueSettings.get(),
        aspectRatio: v.aspectRatio,
        count: v.count,
        quality: v.quality
      })
    },
    include3k: true
  })

  /** 接口限制 imageCount 为 1~4 */
  const dialogueCountSelectOptions = (() => {
    const capped = dialogueCountSelectOptionsRaw.filter((o) => o.value >= 1 && o.value <= 4)
    if (capped.length) return capped
    return [
      { value: 1, label: '1张' },
      { value: 2, label: '2张' },
      { value: 3, label: '3张' },
      { value: 4, label: '4张' }
    ]
  })()

  function handleSelectDialogueModel(model: ModelOption) {
    ctx.dialogueSettings.set({ ...ctx.dialogueSettings.get(), model: model.id })
    ctx.dialogueModelDropdownExpanded.set(false)
    syncDialogueSettingsToModel()
  }

  function handleSelectNineGridModel(model: ModelOption) {
    ctx.nineGridSettings.set({ ...ctx.nineGridSettings.get(), model: model.id })
    ctx.multiViewModelDropdownExpanded.set(false)
  }

  function applyStoryboardImageModelDefault(options: ModelOption[], agentDefaultCodes: string[]) {
    if (!options.length) return
    ctx.generationSettings.set({
      ...ctx.generationSettings.get(),
      model: resolvePreferredModelIdFromAgentCodes(options, {
        agentDefaultCodes
      })
    })
    syncSettingsToModel()
  }

  function applyStoryboardDialogueModelDefault(
    options: ModelOption[],
    agentDefaultCodes: string[]
  ) {
    if (!options.length) return
    ctx.dialogueSettings.set({
      ...ctx.dialogueSettings.get(),
      model: resolvePreferredModelIdFromAgentCodes(options, {
        agentDefaultCodes
      })
    })
    syncDialogueSettingsToModel()
  }

  function reapplyStoryboardImageModelDefaultIfEmpty() {
    if (!ctx.props().open) return
    const codes = ctx.cachedStoryboardImageAgentModelCodes.current
    if (!String(ctx.generationSettings.get().model || '').trim() && getModelOptions().length) {
      applyStoryboardImageModelDefault(getModelOptions(), codes)
    }
    if (
      !String(ctx.dialogueSettings.get().model || '').trim() &&
      getDialogueModelOptions().length
    ) {
      applyStoryboardDialogueModelDefault(getDialogueModelOptions(), codes)
    }
  }

  async function initImageModelOptions() {
    const gen = advanceGenerationToken(ctx.initImageModelGen)
    clearAgentDefaultModelCache()
    /** 分镜图出片池 main_storyboard_image；优先用同 scope 已选分镜图提示词智能体 agentCode 在对应分组内匹配 modelCode */
    const creationStore = ctx.store()
    const storyboardImageAgentCode = String(
      creationStore.storyboardStylistGenerateSettings?.agentId ||
        creationStore.storyboardStylistAgent?.id ||
        ''
    ).trim()

    const storyboardImageFuncCodes = [
      AI_MODEL_FUNC_CODE.STORYBOARD_IMAGE,
      ...STORYBOARD_IMAGE_FUNC_CODE_FALLBACKS
    ]
    const funcCodes = uniqueTrimmedCodes([
      ...storyboardImageFuncCodes,
      AI_MODEL_FUNC_CODE.IMAGE_EDIT,
      AI_MODEL_FUNC_CODE.IMAGE_MULTI_VIEW,
      AI_MODEL_FUNC_CODE.IMAGE_UPSCALE,
      ...IMAGE_MULTI_GRID_FUNC_CODE_FALLBACKS
    ])
    const listScope = buildAidAgentListScopeParams(creationStore)
    const agentPayloads = [
      {
        bizCategoryCode: STORYBOARD_IMAGE_AGENT_BIZ_CATEGORY,
        agentCode: storyboardImageAgentCode,
        ...listScope
      },
      {
        bizCategoryCode: STORYBOARD_IMAGE_PROMPT_AGENT_BIZ_CATEGORY,
        agentCode: storyboardImageAgentCode,
        ...listScope
      }
    ]

    const [agentCodes, modelGroups] = await Promise.all([
      fetchAgentDefaultModelCodes(agentPayloads),
      userModelListByFuncCodes(funcCodes, listScope)
    ])

    if (gen !== ctx.initImageModelGen.current) return

    // 批量 listByFunc 已请求过各池；空结果不再用不同入参单码重打
    const imageList = pickFirstNonEmptyModelPool(modelGroups, storyboardImageFuncCodes)
    setRawModelList(imageList)
    setModelOptions(imageList.map(mapStoryboardModalModelItem))

    const dialogueList = modelsFromListByFuncGroups(modelGroups, AI_MODEL_FUNC_CODE.IMAGE_EDIT)
    setDialogueRawModelList(dialogueList)
    setDialogueModelOptions(dialogueList.map(mapStoryboardModalModelItem))

    const multiViewList = modelsFromListByFuncGroups(
      modelGroups,
      AI_MODEL_FUNC_CODE.IMAGE_MULTI_VIEW
    )
    setMultiViewModelOptions(multiViewList.map(mapStoryboardModalModelItem))

    const nineGridList = pickFirstNonEmptyModelPool(modelGroups, IMAGE_MULTI_GRID_FUNC_CODE_FALLBACKS)
    setNineGridModelOptions(nineGridList.map(mapStoryboardModalModelItem))

    ctx.upscaleModelPool.set(
      modelsFromListByFuncGroups(modelGroups, AI_MODEL_FUNC_CODE.IMAGE_UPSCALE)
    )

    const imageAgentDefault =
      agentCodes[
        getAgentDefaultModelCacheKey(
          STORYBOARD_IMAGE_AGENT_BIZ_CATEGORY,
          storyboardImageAgentCode,
          listScope
        )
      ] || ''
    const promptAgentDefault =
      agentCodes[
        getAgentDefaultModelCacheKey(
          STORYBOARD_IMAGE_PROMPT_AGENT_BIZ_CATEGORY,
          storyboardImageAgentCode,
          listScope
        )
      ] || ''

    replaceRefValue(
      ctx.cachedStoryboardImageAgentModelCodes,
      [imageAgentDefault, promptAgentDefault].filter(Boolean)
    )

    applyStoryboardImageModelDefault(
      getModelOptions(),
      ctx.cachedStoryboardImageAgentModelCodes.current
    )
    applyStoryboardDialogueModelDefault(
      getDialogueModelOptions(),
      ctx.cachedStoryboardImageAgentModelCodes.current
    )

    const mvFirst = getMultiViewModelOptions()[0]
    if (
      mvFirst &&
      !getMultiViewModelOptions().some((m) => m.id === ctx.multiViewSettings.get().model)
    ) {
      ctx.multiViewSettings.set({ ...ctx.multiViewSettings.get(), model: mvFirst.id })
    }
    const ngFirst = getNineGridModelOptions()[0]
    if (
      ngFirst &&
      !getNineGridModelOptions().some((m) => m.id === ctx.nineGridSettings.get().model)
    ) {
      ctx.nineGridSettings.set({ ...ctx.nineGridSettings.get(), model: ngFirst.id })
    }
  }

  const {
    modelList: multiViewModelOptions,
    setModelList: setMultiViewModelOptions,
    getModelList: getMultiViewModelOptions
  } = useModelList<ModelOption>({
    funcCode: AI_MODEL_FUNC_CODE.IMAGE_MULTI_VIEW,
    modelType: 'image',
    fallback: fallbackMultiViewModelOptions,
    mapItem: mapStoryboardModalModelItem
  })

  const multiViewSelectedModel = () =>
    getMultiViewModelOptions().find((m) => m.id === ctx.multiViewSettings.get().model) ||
    getMultiViewModelOptions()[0] ||
    fallbackMultiViewModelOptions[0]

  const {
    modelList: nineGridModelOptions,
    setModelList: setNineGridModelOptions,
    getModelList: getNineGridModelOptions
  } = useModelList<ModelOption>({
    funcCode: AI_MODEL_FUNC_CODE.IMAGE_MULTI_GRID,
    funcCodeFallbacks: IMAGE_MULTI_GRID_FUNC_CODE_FALLBACKS,
    modelType: 'image',
    fallback: fallbackMultiViewModelOptions,
    mapItem: mapStoryboardModalModelItem
  })

  const nineGridSelectedModel = () =>
    getNineGridModelOptions().find((m) => m.id === ctx.nineGridSettings.get().model) ||
    getNineGridModelOptions()[0] ||
    fallbackMultiViewModelOptions[0]

  /** 原 watch(modelOptions/dialogueModelOptions, flush:'post')：列表回填后按需补默认模型（非 immediate，首帧跳过） */
  const reapplyRef = useRef(reapplyStoryboardImageModelDefaultIfEmpty)
  reapplyRef.current = reapplyStoryboardImageModelDefaultIfEmpty
  const modelOptionsMountedRef = useRef(false)
  useEffect(() => {
    if (!modelOptionsMountedRef.current) {
      modelOptionsMountedRef.current = true
      return
    }
    reapplyRef.current()
     
  }, [modelOptions])
  const dialogueModelOptionsMountedRef = useRef(false)
  useEffect(() => {
    if (!dialogueModelOptionsMountedRef.current) {
      dialogueModelOptionsMountedRef.current = true
      return
    }
    reapplyRef.current()
     
  }, [dialogueModelOptions])

  void rawModelList
  void dialogueRawModelList

  return {
    modelOptions,
    dialogueModelOptions,
    multiViewModelOptions,
    nineGridModelOptions,
    selectedModel,
    selectedRawModel,
    dialogueSelectedModel,
    dialogueSelectedRawModel,
    multiViewSelectedModel,
    nineGridSelectedModel,
    aspectRatioSelectOptions,
    countSelectOptions,
    qualitySelectOptions,
    dialogueAspectRatioSelectOptions,
    dialogueCountSelectOptions,
    dialogueQualitySelectOptions,
    handleSelectModel,
    handleSelectDialogueModel,
    handleSelectNineGridModel,
    applyStoryboardImageModelDefault,
    applyStoryboardDialogueModelDefault,
    reapplyStoryboardImageModelDefaultIfEmpty,
    initImageModelOptions
  }
}
