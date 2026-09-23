'use client'

import { message } from 'antd'
import type { ModelOption } from '~/components/steps/ModelSelectDropdown'
import { useModelList } from '~/composables/useModelList'
import { useModelGenerateSettings } from '~/composables/useModelGenerateSettings'
import { mapUserModelListItemToModelOption } from '~/utils/userModelOption'
import { AI_MODEL_FUNC_CODE } from '~/utils/aiModelFuncCodes'
import {
  fetchAgentDefaultModelCodes,
  getAgentDefaultModelCacheKey,
  FORM_IMAGE_AGENT_BIZ_CATEGORY,
  resolvePreferredModelId,
  resolveSelectedModelOption,
  IMAGE_MULTI_VIEW_AGENT_BIZ_CATEGORY,
  IMAGE_MULTI_VIEW_AGENT_CODE
} from '~/utils/extractAgentBiz'
import { modelsFromListByFuncGroups, uniqueTrimmedCodes } from '~/utils/modelListByFuncBatch'
import { buildAidAgentListScopeParams } from '~/utils/createFlowProjectContext'
import { userModelListByFuncCodes } from '~/utils/businessApi'
import type { UserModelListItem } from '~/types/business-api'
import type { SelectOption } from '~/utils/modelCapability'
import { findModelByReference } from '~/utils/modelReference'
import type { EditSceneImageModalCtx } from './types'
import { useMirrored, type Mirrored } from './useMirrored'

export interface SceneModalGenerationSettings {
  model: string
  aspectRatio: string
  count: number
  quality: string
}

export interface SceneModalModelsApi {
  /** 右侧「对话作图」的模型与出图参数（须在 selectedDialogueModel / useModelGenerateSettings 之前声明） */
  dialogueSettings: Mirrored<SceneModalGenerationSettings>
  multiViewSettings: Mirrored<{ model: string }>
  dialogueModelDropdownExpanded: Mirrored<boolean>
  multiViewModelDropdownExpanded: Mirrored<boolean>
  dialogueModelOptions: ModelOption[]
  multiViewModelOptions: ModelOption[]
  /** 变清晰：listByFunc(image_upscale) 模型池，供 UpscaleModelPopover 复用 */
  upscaleModelPool: Mirrored<UserModelListItem[]>
  initImageModelOptions: () => Promise<void>
  selectedDialogueModel: () => ModelOption
  selectedDialogueRawModel: () => UserModelListItem | null
  multiViewSelectedModel: () => ModelOption
  dialogueAspectRatioSelectOptions: SelectOption<string>[]
  dialogueCountSelectOptions: SelectOption<number>[]
  dialogueQualitySelectOptions: SelectOption<string>[]
  handleSelectDialogueModel: (model: ModelOption) => void
  handleSelectMultiViewModel: (model: ModelOption) => void
}

// 模型选项列表
const fallbackModelOptions: ModelOption[] = [

]

const fallbackMultiViewModelOptions: ModelOption[] = [
  {
    id: 'wan2.7-image',
    name: '万相 2.7',
    iconBg: '#60A5FA',
    desc: '多机位形态生图',
    prices: []
  }
]

const mapSceneModalModelItem = (item: Parameters<typeof mapUserModelListItemToModelOption>[0]): ModelOption =>
  mapUserModelListItemToModelOption(item, { iconBg: '#10B981' })

export function useSceneModalModels(ctx: EditSceneImageModalCtx): SceneModalModelsApi {
  /** 右侧「对话作图」的模型与出图参数（须在 selectedDialogueModel / useModelGenerateSettings 之前声明） */
  const dialogueSettings = useMirrored<SceneModalGenerationSettings>({
    model: '',
    aspectRatio: '16:9',
    count: 1,
    quality: '2k'
  })

  // 模型选择相关
  const dialogueModelDropdownExpanded = useMirrored(false)

  const {
    modelList: dialogueModelOptions,
    setModelList: setDialogueModelOptions,
    getModelList: getDialogueModelOptions,
    setRawModelList: setDialogueRawModelList,
    getRawModelList: getDialogueRawModelList
  } = useModelList<ModelOption>({
    funcCode: AI_MODEL_FUNC_CODE.IMAGE_EDIT,
    modelType: 'image',
    fallback: fallbackModelOptions,
    mapItem: mapSceneModalModelItem,
    onError: (e) => {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '加载对话作图模型失败，已使用默认模型')
    }
  })

  const {
    modelList: multiViewModelOptions,
    setModelList: setMultiViewModelOptions,
    getModelList: getMultiViewModelOptions
  } = useModelList<ModelOption>({
    funcCode: AI_MODEL_FUNC_CODE.IMAGE_MULTI_VIEW,
    modelType: 'image',
    fallback: fallbackMultiViewModelOptions,
    mapItem: (item) => mapUserModelListItemToModelOption(item, { iconBg: '#60A5FA' }),
    onError: (e) => {
      const err = e as { msg?: string; message?: string }
      message.warning(err?.msg || err?.message || '加载多机位模型失败，已使用默认模型')
    }
  })

  const multiViewSettings = useMirrored<{ model: string }>({ model: '' })
  const multiViewModelDropdownExpanded = useMirrored(false)

  /** 变清晰：listByFunc(image_upscale) 模型池，供 UpscaleModelPopover 复用 */
  const upscaleModelPool = useMirrored<UserModelListItem[]>([])

  /** 对话作图：image_edit */
  function applySceneModalDialogueModelPool(
    groups: Awaited<ReturnType<typeof userModelListByFuncCodes>>
  ) {
    const list = modelsFromListByFuncGroups(groups, AI_MODEL_FUNC_CODE.IMAGE_EDIT)
    if (list.length > 0) {
      setDialogueRawModelList(list)
      setDialogueModelOptions(list.map(mapSceneModalModelItem))
      return true
    }
    return false
  }

  function applySceneModalMultiViewModelPool(
    groups: Awaited<ReturnType<typeof userModelListByFuncCodes>>
  ) {
    const list = modelsFromListByFuncGroups(groups, AI_MODEL_FUNC_CODE.IMAGE_MULTI_VIEW)
    if (list.length > 0) {
      setMultiViewModelOptions(
        list.map((item) => mapUserModelListItemToModelOption(item, { iconBg: '#60A5FA' }))
      )
      return true
    }
    return false
  }

  async function initImageModelOptions() {
    const assetType = ctx.resolveSceneModalAssetType()
    const agentCode = String(ctx.store().extractAgents[assetType]?.id || '').trim()
    const formImageBiz = FORM_IMAGE_AGENT_BIZ_CATEGORY[assetType]
    const funcCodes = uniqueTrimmedCodes([
      AI_MODEL_FUNC_CODE.IMAGE_EDIT,
      AI_MODEL_FUNC_CODE.IMAGE_MULTI_VIEW,
      AI_MODEL_FUNC_CODE.IMAGE_UPSCALE
    ])
    const listScope = buildAidAgentListScopeParams(ctx.store())
    const agentPayloads = [
      { bizCategoryCode: formImageBiz, agentCode, ...listScope },
      {
        bizCategoryCode: IMAGE_MULTI_VIEW_AGENT_BIZ_CATEGORY,
        agentCode: IMAGE_MULTI_VIEW_AGENT_CODE,
        ...listScope
      }
    ]

    const [agentCodes, modelGroups] = await Promise.all([
      fetchAgentDefaultModelCodes(agentPayloads),
      userModelListByFuncCodes(funcCodes, listScope)
    ])

    // 批量 listByFunc 已请求过各池；空结果不再用不同入参单码重打
    if (!applySceneModalDialogueModelPool(modelGroups)) {
      setDialogueRawModelList([])
      setDialogueModelOptions([])
    }
    if (!applySceneModalMultiViewModelPool(modelGroups)) {
      setMultiViewModelOptions([])
    }

    upscaleModelPool.set(modelsFromListByFuncGroups(modelGroups, AI_MODEL_FUNC_CODE.IMAGE_UPSCALE))

    const agentDefaultModelCode =
      agentCodes[getAgentDefaultModelCacheKey(formImageBiz, agentCode, listScope)] || ''
    const multiViewAgentDefault =
      agentCodes[
        getAgentDefaultModelCacheKey(
          IMAGE_MULTI_VIEW_AGENT_BIZ_CATEGORY,
          IMAGE_MULTI_VIEW_AGENT_CODE,
          listScope
        )
      ] || ''

    dialogueSettings.set({
      ...dialogueSettings.get(),
      model: resolvePreferredModelId(getDialogueModelOptions(), {
        agentDefaultCode: agentDefaultModelCode
      })
    })
    syncDialogueSettingsToModel()
    multiViewSettings.set({
      model: resolvePreferredModelId(getMultiViewModelOptions(), {
        agentDefaultCode: multiViewAgentDefault
      })
    })
  }

  const selectedDialogueModel = (): ModelOption =>
    resolveSelectedModelOption(getDialogueModelOptions(), dialogueSettings.get().model)

  const selectedDialogueRawModel = (): UserModelListItem | null =>
    findModelByReference(getDialogueRawModelList(), dialogueSettings.get().model)

  const {
    aspectRatioSelectOptions: dialogueAspectRatioSelectOptions,
    countSelectOptions: dialogueCountSelectOptionsRaw,
    qualitySelectOptions: dialogueQualitySelectOptions,
    syncSettingsToModel: syncDialogueSettingsToModel
  } = useModelGenerateSettings({
    getSelectedModel: selectedDialogueModel,
    getRawModelList: getDialogueRawModelList,
    getGenerationSettings: () => {
      const s = dialogueSettings.get()
      return { aspectRatio: s.aspectRatio, count: s.count, quality: s.quality }
    },
    setGenerationSettings: (v) => {
      dialogueSettings.set({
        ...dialogueSettings.get(),
        aspectRatio: v.aspectRatio,
        count: v.count,
        quality: v.quality
      })
    },
    include3k: true
  })

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

  const handleSelectDialogueModel = (model: ModelOption) => {
    dialogueSettings.set({ ...dialogueSettings.get(), model: model.id })
    dialogueModelDropdownExpanded.set(false)
    syncDialogueSettingsToModel()
  }

  const handleSelectMultiViewModel = (model: ModelOption) => {
    multiViewSettings.set({ model: model.id })
    multiViewModelDropdownExpanded.set(false)
  }

  const multiViewSelectedModel = (): ModelOption =>
    resolveSelectedModelOption(getMultiViewModelOptions(), multiViewSettings.get().model)

  return {
    dialogueSettings,
    multiViewSettings,
    dialogueModelDropdownExpanded,
    multiViewModelDropdownExpanded,
    dialogueModelOptions,
    multiViewModelOptions,
    upscaleModelPool,
    initImageModelOptions,
    selectedDialogueModel,
    selectedDialogueRawModel,
    multiViewSelectedModel,
    dialogueAspectRatioSelectOptions,
    dialogueCountSelectOptions,
    dialogueQualitySelectOptions,
    handleSelectDialogueModel,
    handleSelectMultiViewModel
  }
}
