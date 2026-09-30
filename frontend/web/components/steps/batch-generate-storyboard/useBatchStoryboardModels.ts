'use client'

import { useEffect, useRef, useState, type RefObject } from 'react'
import { message } from 'antd'
import type { ModelOption } from '~/types/modelAgentOptions'
import { aidAgentList, userModelList, userModelListByFuncCodes } from '~/utils/businessApi'
import { modelsFromListByFuncGroups, pickFirstNonEmptyModelPool } from '~/utils/modelListByFuncBatch'
import { waitForCreationStoreHydrated } from '~/composables/useCreationStoreHydration'
import { useCreationStore } from '~/stores/creation'
import { buildAidAgentListScopeParams } from '~/utils/createFlowProjectContext'
import {
  STORYBOARD_IMAGE_AGENT_BIZ_CATEGORY,
  agentOptionsFromGroup,
  fetchAgentDefaultModelCode,
  resolveAgentModelCodeInGroup,
  resolvePreferredModelId,
  resolvePreferredModelIdFromAgentCodes,
  resolveSelectedModelOption
} from '~/utils/extractAgentBiz'
import { STORYBOARD_GEN_CONFIG_SCENE_CODES, getProjectGenConfigBySceneCode } from '~/utils/projectGenConfig'
import {
  resolveBatchStoryboardVideoAgentBizCategories,
  resolveBatchStoryboardVideoModelFuncCodes,
  shouldPassStoryboardVideoDuration
} from '~/utils/creationModeUiRules'
import { useVideoModelGenerateSettings } from '~/composables/useVideoModelGenerateSettings'
import { mapUserModelListItemToModelOption } from '~/utils/userModelOption'
import { pickMatchedOption } from '~/utils/modelCapability'
import type { UserModelListItem } from '~/types/business-api'
import type { RouteLikeLocation } from '~/types/routeLike'

const mapVideoModelOption = (item: UserModelListItem): ModelOption =>
  mapUserModelListItemToModelOption(item, { iconBg: '#60A5FA' })

/** 与 store 的 projectCreationMode computed 同源；异步流程内取实时值 */
function liveProjectCreationMode(): string {
  return useCreationStore.getState().formData.globalSetting?.creationMode || 'i2v'
}

function modelListScope() {
  return buildAidAgentListScopeParams(useCreationStore.getState())
}

/** 批量弹窗底部智能体 / 生图模型 / 生视频模型选择（原组件 script 段平移） */
export function useBatchStoryboardModels(options: {
  projectCreationMode: string
  routeRef: RefObject<RouteLikeLocation>
}) {
  const { projectCreationMode, routeRef } = options

  const videoModelLoadGenRef = useRef(0)

  const [agent, setAgent] = useState('')
  const agentRef = useRef('')
  const [model, setModel] = useState('')
  const [agentOptions, setAgentOptions] = useState<Array<{ label: string; value: string }>>([])
  const [modelOptions, setModelOptionsState] = useState<Array<{ label: string; value: string }>>([])
  const modelOptionsRef = useRef<Array<{ label: string; value: string }>>([])
  const [agentsLoading, setAgentsLoading] = useState(false)
  const [videoModel, setVideoModelState] = useState('')
  const videoModelRef = useRef('')
  const [videoModelOptions, setVideoModelOptionsState] = useState<ModelOption[]>([])
  const videoModelOptionsRef = useRef<ModelOption[]>([])
  const [videoRawModelList, setVideoRawModelListState] = useState<UserModelListItem[]>([])
  const videoRawModelListRef = useRef<UserModelListItem[]>([])
  const [videoModelsLoading, setVideoModelsLoading] = useState(false)
  const [videoModelDropdownExpanded, setVideoModelDropdownExpanded] = useState(false)
  const [videoQuality, setVideoQualityState] = useState('1080p')
  const videoQualityRef = useRef('1080p')
  const [videoDuration, setVideoDurationState] = useState('5')
  const videoDurationRef = useRef('5')
  const projectAspectRatio = useCreationStore((state) => state.formData.globalSetting?.aspectRatio || '16:9')
  const [videoAspectRatio, setVideoAspectRatio] = useState<string>(projectAspectRatio)
  const [videoCount, setVideoCount] = useState(1)
  const [videoAudio, setVideoAudioState] = useState('with_audio')
  const videoAudioRef = useRef('with_audio')

  const setVideoQuality = (v: string) => {
    videoQualityRef.current = v
    setVideoQualityState(v)
  }
  const setVideoDuration = (v: string) => {
    videoDurationRef.current = v
    setVideoDurationState(v)
  }
  const setVideoAudio = (v: string) => {
    videoAudioRef.current = v
    setVideoAudioState(v)
  }

  const setVideoModel = (v: string) => {
    videoModelRef.current = v
    setVideoModelState(v)
  }
  const setModelOptions = (list: Array<{ label: string; value: string }>) => {
    modelOptionsRef.current = list
    setModelOptionsState(list)
  }
  const setVideoModelOptions = (list: ModelOption[]) => {
    videoModelOptionsRef.current = list
    setVideoModelOptionsState(list)
  }
  const setVideoRawModelList = (list: UserModelListItem[]) => {
    videoRawModelListRef.current = list
    setVideoRawModelListState(list)
  }

  const selectedVideoModel = resolveSelectedModelOption(videoModelOptions, videoModel)

  const {
    capabilitySnapshot,
    qualitySelectOptions: videoQualityOptions,
    durationSelectOptions: videoDurationOptions,
    audioSelectOptions: videoAudioOptions,
    supportsDuration: modelSupportsDuration,
    supportsAudio: videoConfigShowAudio,
    syncSettingsToModel: syncVideoSettingsToModel
  } = useVideoModelGenerateSettings({
    selectedModelCode: videoModel,
    rawModelList: videoRawModelList,
    generationSettings: {
      aspectRatio: videoAspectRatio,
      count: videoCount,
      quality: videoQuality,
      duration: videoDuration,
      audio: videoAudio
    },
    onGenerationSettingsChange: (v) => {
      setVideoAspectRatio(v.aspectRatio)
      setVideoCount(v.count)
      setVideoQuality(v.quality)
      setVideoDuration(v.duration)
      setVideoAudio(v.audio)
    }
  })

  /** 仅图生视频展示时长；其它创作模式隐藏，且确认时不传 durationSeconds */
  const videoConfigShowDuration =
    shouldPassStoryboardVideoDuration(projectCreationMode) && modelSupportsDuration

  function handleSelectVideoModel(option: ModelOption) {
    setVideoModel(option.id)
    setVideoModelDropdownExpanded(false)
    syncVideoSettingsToModel({ selectedModelCode: option.id })
  }

  /** 切到支持音画同出的模型时，按本地偏好恢复；不支持时强制无声（不污染 persist） */
  const audioWatchFirstRunRef = useRef(true)
  useEffect(() => {
    if (audioWatchFirstRunRef.current) {
      audioWatchFirstRunRef.current = false
      return
    }
    if (!videoConfigShowAudio) {
      setVideoAudio('silent')
      return
    }
    const saved = useCreationStore.getState().storyboardVideoGenerateSettings.soundEffects
    if (saved === 'with-sound') setVideoAudio('with_audio')
    else if (saved === 'none') setVideoAudio('silent')
    else setVideoAudio('with_audio')
  }, [videoConfigShowAudio])

  const videoModelWatchFirstRunRef = useRef(true)
  useEffect(() => {
    if (videoModelWatchFirstRunRef.current) {
      videoModelWatchFirstRunRef.current = false
      return
    }
    syncVideoSettingsToModel()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [videoModel])

  async function loadImageAgents() {
    setAgentsLoading(true)
    try {
      const bizCategoryCode = STORYBOARD_IMAGE_AGENT_BIZ_CATEGORY
      const groups = await aidAgentList({
        bizCategoryCodes: [bizCategoryCode],
        ...modelListScope()
      })
      const opts = agentOptionsFromGroup(groups, bizCategoryCode)
      setAgentOptions(opts.map((o) => ({ label: o.name, value: o.id })))

      const projectId = Number(useCreationStore.getState().currentProjectId)
      let defaultAgent = ''
      if (Number.isFinite(projectId) && projectId > 0) {
        try {
          const cfg = await getProjectGenConfigBySceneCode(projectId, STORYBOARD_GEN_CONFIG_SCENE_CODES.image)
          defaultAgent = String(cfg?.agentCode || '').trim()
        } catch {
          /* ignore */
        }
      }
      const nextAgent =
        defaultAgent && opts.some((o) => o.id === defaultAgent) ? defaultAgent : ''
      agentRef.current = nextAgent
      setAgent(nextAgent)
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '加载智能体列表失败')
      setAgentOptions([])
      agentRef.current = ''
      setAgent('')
    } finally {
      setAgentsLoading(false)
    }
  }

  async function loadImageModelOptions() {
    const bizCategoryCode = STORYBOARD_IMAGE_AGENT_BIZ_CATEGORY
    let list: UserModelListItem[] = []
    try {
      const groups = await userModelListByFuncCodes([bizCategoryCode], modelListScope())
      list = modelsFromListByFuncGroups(groups, bizCategoryCode)
    } catch {
      list = []
    }
    if (!list.length) {
      try {
        list = await userModelList({ modelType: 'image' })
      } catch {
        list = []
      }
    }
    setModelOptions(
      list.map((item) => ({
        label: item.modelName || item.modelCode || '未命名模型',
        value: String(item.modelCode || item.id)
      }))
    )
  }

  async function initImageModelSelection() {
    await loadImageModelOptions()
    const projectId = Number(useCreationStore.getState().currentProjectId)
    let cfgModel = ''
    if (Number.isFinite(projectId) && projectId > 0) {
      try {
        const cfg = await getProjectGenConfigBySceneCode(projectId, STORYBOARD_GEN_CONFIG_SCENE_CODES.image)
        cfgModel = String(cfg?.modelCode || '').trim()
      } catch {
        /* ignore */
      }
    }
    const scope = modelListScope()
    const agentDefaultModelCode = await fetchAgentDefaultModelCode({
      bizCategoryCode: STORYBOARD_IMAGE_AGENT_BIZ_CATEGORY,
      agentCode: agentRef.current,
      ...scope
    })
    const optionIds = modelOptionsRef.current.map((x) => ({ id: x.value }))
    setModel(
      resolvePreferredModelId(optionIds, {
        savedId: cfgModel,
        agentDefaultCode: agentDefaultModelCode
      })
    )
  }

  async function loadVideoModelOptions() {
    if (typeof window === 'undefined') return
    const gen = ++videoModelLoadGenRef.current
    setVideoModelsLoading(true)
    try {
      await waitForCreationStoreHydrated(useCreationStore.getState(), routeRef.current)
      if (gen !== videoModelLoadGenRef.current) return

      // 与编辑分镜视频弹窗一致：listByFunc + 按创作模式主 Tab 解析 funcCode（含 projectId 作用域）
      const funcCodes = resolveBatchStoryboardVideoModelFuncCodes(liveProjectCreationMode())
      let list: UserModelListItem[] = []
      if (funcCodes.length) {
        try {
          const groups = await userModelListByFuncCodes(funcCodes, modelListScope())
          if (gen !== videoModelLoadGenRef.current) return
          list = pickFirstNonEmptyModelPool(groups, funcCodes)
        } catch {
          list = []
        }
      }
      if (!list.length) {
        try {
          list = await userModelList({ modelType: 'video' })
          if (gen !== videoModelLoadGenRef.current) return
        } catch {
          list = []
        }
      }
      setVideoRawModelList(list)
      setVideoModelOptions(list.map(mapVideoModelOption))
    } catch (e: unknown) {
      if (gen !== videoModelLoadGenRef.current) return
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '加载视频模型失败')
      setVideoRawModelList([])
      setVideoModelOptions([])
    } finally {
      if (gen === videoModelLoadGenRef.current) {
        setVideoModelsLoading(false)
      }
    }
  }

  async function initVideoModelSelection() {
    await loadVideoModelOptions()
    const saved = useCreationStore.getState().storyboardVideoGenerateSettings
    let nextQuality = ''
    if (saved.resolution) {
      nextQuality = String(saved.resolution).toLowerCase()
      setVideoQuality(nextQuality)
    }
    let nextDuration = ''
    const savedDuration = Number(saved.durationSeconds)
    if (Number.isFinite(savedDuration) && savedDuration > 0) {
      nextDuration = String(Math.floor(savedDuration))
      setVideoDuration(nextDuration)
    }
    // 音频先按本地偏好恢复；不支持音画同出的模型由后续 syncVideoSettingsToModel 强制 silent
    let nextAudio = 'with_audio'
    if (saved.soundEffects === 'with-sound') nextAudio = 'with_audio'
    else if (saved.soundEffects === 'none') nextAudio = 'silent'
    else nextAudio = 'with_audio'
    setVideoAudio(nextAudio)
    if (!nextQuality) nextQuality = videoQualityRef.current
    if (!nextDuration) nextDuration = videoDurationRef.current

    const agentBizCodes = resolveBatchStoryboardVideoAgentBizCategories(liveProjectCreationMode())
    let agentDefaultCodes: string[] = []
    if (agentBizCodes.length) {
      try {
        const groups = await aidAgentList({
          bizCategoryCodes: agentBizCodes,
          ...modelListScope()
        })
        agentDefaultCodes = agentBizCodes
          .map((biz) => resolveAgentModelCodeInGroup(groups, biz))
          .filter(Boolean)
      } catch {
        agentDefaultCodes = []
      }
    }

    const preferred =
      resolvePreferredModelIdFromAgentCodes(videoModelOptionsRef.current, {
        savedId: String(saved.videoModel || '').trim(),
        agentDefaultCodes
      }) ||
      videoModelOptionsRef.current[0]?.id ||
      ''
    setVideoModel(preferred)
    syncVideoSettingsToModel({
      selectedModelCode: preferred,
      rawModelList: videoRawModelListRef.current,
      settings: {
        aspectRatio: useCreationStore.getState().formData.globalSetting?.aspectRatio || '16:9',
        count: videoCount,
        quality: nextQuality,
        duration: nextDuration,
        audio: nextAudio
      }
    })
  }

  /** 弹窗关闭 / 重开时作废在途加载（原 videoModelLoadGen++） */
  function invalidateVideoModelLoad() {
    videoModelLoadGenRef.current++
  }

  return {
    agent,
    setAgent,
    model,
    agentOptions,
    modelOptions,
    agentsLoading,
    loadImageAgents,
    initImageModelSelection,
    videoModel,
    projectAspectRatio,
    // 批量生成没有独立比例选择：报价与确认均优先采用项目比例，再按模型能力校正。
    videoAspectRatio: pickMatchedOption(projectAspectRatio, capabilitySnapshot.aspectRatioOptions, capabilitySnapshot.defaultAspectRatio),
    videoModelOptions,
    videoModelsLoading,
    videoModelDropdownExpanded,
    setVideoModelDropdownExpanded,
    videoQuality,
    setVideoQuality,
    videoDuration,
    setVideoDuration,
    videoAudio,
    setVideoAudio,
    videoQualityOptions,
    videoDurationOptions,
    videoAudioOptions,
    videoConfigShowAudio,
    videoConfigShowDuration,
    selectedVideoModel,
    handleSelectVideoModel,
    initVideoModelSelection,
    invalidateVideoModelLoad
  }
}
