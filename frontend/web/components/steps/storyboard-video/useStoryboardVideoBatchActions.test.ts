// @vitest-environment jsdom
import { act, createElement } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ getState: vi.fn(), success: vi.fn(), warning: vi.fn() }))
vi.mock('~/stores/creation', () => ({ useCreationStore: { getState: mocks.getState } }))
vi.mock('antd', () => ({ message: { success: mocks.success, warning: mocks.warning, info: vi.fn(), error: vi.fn() } }))
vi.mock('./useStoryboardVideoPanelOps', () => ({ storyboardApiErr: vi.fn() }))
import { useStoryboardVideoBatchActions } from './useStoryboardVideoBatchActions'

const mounted: Array<ReturnType<typeof createRoot>> = []
afterEach(async () => { for (const root of mounted.splice(0)) await act(() => root.unmount()); vi.clearAllMocks() })

describe('batch video confirmed model parameters', () => {
  it('uses the dialog adaptive ratio before starting generation instead of the saved 16:9', async () => {
    ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
    const settings = { videoModel: 'old-model', aspectRatio: '16:9', resolution: '720p', soundEffects: 'none', durationSeconds: 12 }
    const setSettings = vi.fn((change: object) => Object.assign(settings, change))
    mocks.getState.mockReturnValue({
      formData: { globalSetting: { creationMode: 'i2v' } },
      storyboardVideoGenerateSettings: settings,
      setStoryboardVideoGenerateSettings: setSettings,
      isGeneratingStoryboardVideo: false
    })
    let actualRatio: string | undefined
    const runBatchVideosOnly = vi.fn(async () => {
      actualRatio = settings.aspectRatio
      return { ok: true }
    })
    let actions: ReturnType<typeof useStoryboardVideoBatchActions>
    const ref = <T,>(current: T) => ({ current })
    function Harness() {
      actions = useStoryboardVideoBatchActions({
        videoBatchGen: { runBatchVideosOnly } as any,
        pageDisposedRef: ref(false), panelsRef: ref([]), scriptPanelsRef: ref([]),
        onChangeRef: ref(vi.fn()), batchVideoSubmittingRef: ref(false),
        canAutoGenerateVideoRef: ref(true), batchVideoDisabledTooltipRef: ref(''),
        mergeStoryboardVideoPanelUiFromStore: vi.fn()
      })
      return null
    }
    const root = createRoot(document.createElement('div')); mounted.push(root)
    await act(() => root.render(createElement(Harness)))
    await act(() => actions!.handleBatchGenerateVideoConfirm({
      mode: 'video', selectedStoryboardIds: [15], videoModel: 'doubao-seedance-2.0-fast',
      aspectRatio: 'adaptive', resolution: '480p', durationSeconds: 5, soundEffects: 'with-sound'
    }))
    expect(setSettings).toHaveBeenCalledWith(expect.objectContaining({ aspectRatio: 'adaptive', videoModel: 'doubao-seedance-2.0-fast' }))
    expect(setSettings.mock.calls[0][0]).not.toHaveProperty('durationSeconds')
    expect(settings.durationSeconds).toBe(12)
    expect(actualRatio).toBe('adaptive')
    expect(runBatchVideosOnly).toHaveBeenCalledOnce()
  })
})
