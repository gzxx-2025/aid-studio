import { shouldPassStoryboardVideoDuration } from '~/utils/creationModeUiRules'
import { resolveStoryboardVideoPromptSubmitAgentCode } from '~/utils/extractAgentBiz'
import {
  STORYBOARD_GEN_CONFIG_SCENE_CODES,
  resolveStoryboardGenConfigLlmFields
} from '~/utils/projectGenConfig'
import { sanitizeStoryboardPromptModelCode } from '~/utils/storyboardPromptGenerateFlow'
import { formatVideoResolutionForApi } from '~/utils/storyboardVideoGenerateParams'
import type { StoryboardVideoBatchCore } from '~/utils/storyboardVideoBatchFollowCore'
import type { StoryboardVideoBatchState } from '~/utils/storyboardVideoBatchShared'

function normalizePositiveInteger(raw: unknown): number | undefined {
  const value = Number(raw)
  return Number.isInteger(value) && value > 0 ? value : undefined
}

export function createStoryboardVideoBatchSubmitFields(
  state: StoryboardVideoBatchState,
  core: StoryboardVideoBatchCore
) {
  const { getStore } = core

  function resolveVideoPromptModelCode(): string {
    return sanitizeStoryboardPromptModelCode(
      getStore().storyboardVideoGenerateSettings.videoPromptModelCode
    )
  }

  async function buildVideoPromptSubmitFields(projectId: number) {
    const settings = getStore().storyboardVideoGenerateSettings
    return resolveStoryboardGenConfigLlmFields(
      projectId,
      STORYBOARD_GEN_CONFIG_SCENE_CODES.videoPrompt,
      state.manualPromptAgentModelPick,
      resolveStoryboardVideoPromptSubmitAgentCode('video_prompt', settings.agentId),
      resolveVideoPromptModelCode()
    )
  }

  function buildVideoGenSubmitFields(options?: {
    genDurationSeconds?: number | null
    manualVideoModelPick?: boolean
  }) {
    const store = getStore()
    const settings = store.storyboardVideoGenerateSettings
    const modelName = String(settings.videoModel || '').trim()
    const passDuration = shouldPassStoryboardVideoDuration(
      store.formData.globalSetting?.creationMode
    )
    // Batch generation leaves duration to each storyboard; never reuse the single-item setting.
    const durationSeconds = passDuration
      ? normalizePositiveInteger(options?.genDurationSeconds)
      : undefined
    const resolution = formatVideoResolutionForApi(settings.resolution)
    return {
      ...((options?.manualVideoModelPick ?? state.manualVideoModelPick) && modelName
        ? { genModelName: modelName }
        : {}),
      ...(settings.aspectRatio ? { genAspectRatio: settings.aspectRatio } : {}),
      ...(durationSeconds ? { genDurationSeconds: durationSeconds } : {}),
      ...(resolution ? { genResolution: resolution } : {}),
      genGenerateAudio: settings.soundEffects === 'with-sound'
    }
  }

  return {
    buildVideoPromptSubmitFields,
    buildVideoGenSubmitFields
  }
}
