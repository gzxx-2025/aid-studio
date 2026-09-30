import { describe, expect, it, vi } from 'vitest'
import { createStoryboardVideoBatchSubmitFields } from './storyboardVideoBatchSubmitFields'
import type { StoryboardVideoBatchCore } from './storyboardVideoBatchFollowCore'
import type { StoryboardVideoBatchState } from './storyboardVideoBatchShared'

vi.mock('~/utils/projectGenConfig', () => ({
  STORYBOARD_GEN_CONFIG_SCENE_CODES: {}, resolveStoryboardGenConfigLlmFields: vi.fn()
}))

function builder(creationMode = 'i2v') {
  const settings = {
    videoModel: 'doubao-seedance-2.0-fast', aspectRatio: '16:9',
    resolution: '480p', soundEffects: 'with-sound', durationSeconds: 5
  }
  return createStoryboardVideoBatchSubmitFields(
    { manualVideoModelPick: true } as StoryboardVideoBatchState,
    { getStore: () => ({ formData: { globalSetting: { creationMode } }, storyboardVideoGenerateSettings: settings }) } as unknown as StoryboardVideoBatchCore
  ).buildVideoGenSubmitFields
}

describe('batch duration recommendation contract', () => {
  it.each(['i2v', 'auto_grid', 'multi', 'pro'])('does not reuse saved single-item duration in %s batches', (mode) => {
    expect(builder(mode)()).toEqual({
      genModelName: 'doubao-seedance-2.0-fast', genAspectRatio: '16:9',
      genResolution: '480P', genGenerateAudio: true
    })
  })
  it('preserves an explicitly provided individual image-to-video duration', () => {
    expect(builder()({ genDurationSeconds: 8 })).toHaveProperty('genDurationSeconds', 8)
  })
  it('does not replace an absent explicit duration with the saved setting', () => {
    expect(builder()({ genDurationSeconds: null })).not.toHaveProperty('genDurationSeconds')
  })
})
