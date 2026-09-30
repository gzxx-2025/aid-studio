import type { UserModelListItem } from '@/types/business-api'
import {
  buildAspectRatioSelectOptions,
  buildCountSelectOptions,
  buildDurationSelectOptions,
  buildQualitySelectOptions,
  buildVideoCountSelectOptions,
  buildVideoQualitySelectOptions,
  parseModelCapability,
  type ModelCapabilitySnapshot,
  type SelectOption
} from '@/utils/modelCapability'

export const STUDIO_VIDEO_DURATION_OPTIONS = [3, 5, 8].map((value) => ({
  value,
  label: `${value} 秒`
}))

const VIDEO_FALLBACK_SIZES = ['720p', '1080p'] as const

/**
 * 多功能输入框参数面板：按模型 capability 解析比例 / 清晰度 / 数量 / 秒数。
 * 与编辑分镜视频 / 分镜图弹窗同源（modelCapability），禁止写死全量比例与清晰度。
 */
export function resolveStudioParamsPanelOptions(
  mode: 'image' | 'video',
  model?: UserModelListItem | null,
  allowAutomaticDuration = false
): {
  snapshot: ModelCapabilitySnapshot
  ratioOptions: SelectOption<string>[]
  qualityOptions: SelectOption<string>[]
  countOptions: SelectOption<number>[]
  durationOptions: SelectOption<string>[]
  supportsDuration: boolean
} {
  const parsed = parseModelCapability(model, undefined, allowAutomaticDuration)
  // parseModelCapability(null) 会填入图片清晰度默认档；视频无模型时改用视频档
  const snapshot: ModelCapabilitySnapshot = mode === 'video' && !model
    ? {
        ...parsed,
        sizeOptions: [...VIDEO_FALLBACK_SIZES],
        defaultSize: '1080p'
      }
    : parsed

  return {
    snapshot,
    ratioOptions: buildAspectRatioSelectOptions(snapshot),
    qualityOptions: mode === 'video'
      ? buildVideoQualitySelectOptions(snapshot)
      : buildQualitySelectOptions(snapshot),
    countOptions: mode === 'video'
      ? buildVideoCountSelectOptions(snapshot)
      : buildCountSelectOptions(snapshot),
    durationOptions: buildDurationSelectOptions(snapshot),
    supportsDuration: mode === 'video' && snapshot.supportsDuration && snapshot.durationOptions.length > 0
  }
}
