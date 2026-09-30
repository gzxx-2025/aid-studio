import type { UserModelListItem } from '~/types/business-api'
import { coerceVideoGenerationSettings, parseModelCapability } from '~/utils/modelCapability'

export interface VideoGenerationRequestSettings {
  durationSeconds?: number
  aspectRatio?: string
  options: { resolution?: string }
}

/** 报价与提交复用当前能力的合法规格；-1 仅在模型明确声明自动时长时保留。 */
export function resolveVideoGenerationRequestSettings(
  value: { quality?: string; duration?: number; aspectRatio?: string },
  model: UserModelListItem
): VideoGenerationRequestSettings {
  const snapshot = parseModelCapability(model, {
    durationSeconds: value.duration,
    aspectRatio: value.aspectRatio,
    options: { resolution: value.quality }
  }, true)
  const settings = coerceVideoGenerationSettings({
    quality: value.quality ?? '', duration: String(value.duration ?? snapshot.defaultDurationSeconds),
    aspectRatio: value.aspectRatio ?? '', count: 1, audio: 'silent'
  }, snapshot)
  if (!snapshot.sizeOptions.length) throw new Error('当前模型没有可用清晰度')
  if (model.supportsDuration !== false && !snapshot.durationOptions.length) throw new Error('当前模型没有可用时长')
  return {
    ...(snapshot.supportsDuration ? { durationSeconds: Number(settings.duration) } : {}),
    ...(snapshot.aspectRatioOptions.length ? { aspectRatio: settings.aspectRatio } : {}),
    options: { resolution: settings.quality }
  }
}
