'use client'

import { useEffect, useMemo, useRef } from 'react'
import { Popover, Slider } from 'antd'
import { ControlOutlined } from '@ant-design/icons'
import { AspectRatioShape } from '@/components/common/aspect-ratio-picker/AspectRatioPickerPopover'
import { useStudioModels } from '@/hooks/studio/useStudioCatalog'
import { useStudioPreferredModelCode } from '@/hooks/studio/useStudioPreferredModelCode'
import type { UserModelListItem } from '@/types/business-api'
import type { StudioMediaKind, StudioNodeData } from '@/types/studio'
import {
  coerceGenerationSettings,
  coerceVideoGenerationSettings
} from '@/utils/modelCapability'
import {
  resolveStudioComposerModelFromNode
} from '@/utils/studio/studioComposerDefaultModel'
import { resolveStudioParamsPanelOptions } from '@/utils/studio/studioGenerationOptions'
import {
  readRecommendedDurationSeconds,
  resolveVideoDurationOption
} from '@/utils/resolveVideoDurationOption'
import { StudioComposerChip } from './StudioComposerChip'

type GenerationOptions = NonNullable<StudioNodeData['generationOptions']>

export function StudioParamsPopover({
  mode,
  value,
  onChange,
  modelCode,
  modelOptions,
  sceneCode,
  mediaKind,
  recommendedDurationSeconds
}: {
  mode: 'image' | 'video'
  value: GenerationOptions
  onChange: (next: GenerationOptions) => void
  /** 当前节点选中的模型编码；与 StudioModelPicker 一致 */
  modelCode?: string
  /** 外部模型池（流程节点专用）；不传则读通用 catalog */
  modelOptions?: UserModelListItem[]
  sceneCode?: string
  mediaKind?: StudioMediaKind
  /** 分镜详情返回的推荐时长；按当前模型档位向上归一。 */
  recommendedDurationSeconds?: number | null
}) {
  const resolvedMediaKind = mediaKind ?? mode
  const catalog = useStudioModels(resolvedMediaKind, { enabled: modelOptions == null })
  const options = modelOptions ?? catalog.data
  const preferredModelCode = useStudioPreferredModelCode(sceneCode)
  const model = useMemo(
    () => resolveStudioComposerModelFromNode(
      options,
      { data: { model: modelCode } },
      [preferredModelCode]
    ) ?? null,
    [modelCode, options, preferredModelCode]
  )

  const panel = useMemo(() => resolveStudioParamsPanelOptions(mode, model), [mode, model])
  const {
    snapshot,
    ratioOptions,
    qualityOptions,
    countOptions,
    durationOptions: durationSelectOptions,
    supportsDuration
  } = panel
  const durationValues = durationSelectOptions
    .map((item) => Number(item.value))
    .filter((n) => Number.isFinite(n) && n > 0)
  const normalizedRecommendedDuration = readRecommendedDurationSeconds({ recommendedDurationSeconds })
  const resolvedRecommendedDuration = normalizedRecommendedDuration == null
    ? null
    : resolveVideoDurationOption({
        recommendedDurationSeconds: normalizedRecommendedDuration,
        durationOptions: snapshot.durationOptions,
        defaultDurationSeconds: snapshot.defaultDurationSeconds
      })

  const onChangeRef = useRef(onChange)
  const valueRef = useRef(value)

  useEffect(() => {
    onChangeRef.current = onChange
    valueRef.current = value
  }, [onChange, value])

  // 与编辑分镜视频弹窗一致：模型 capability 变化后收敛当前选中值
  useEffect(() => {
    // 未取得模型能力时不能用兜底规格覆盖已保存的节点参数。
    if (!model) return
    const current = valueRef.current
    if (mode === 'video') {
      const desiredDuration = resolvedRecommendedDuration ?? current.duration ?? snapshot.defaultDurationSeconds
      const coerced = coerceVideoGenerationSettings({
        aspectRatio: current.aspectRatio ?? '',
        count: current.count ?? 1,
        quality: current.quality ?? '',
        duration: String(desiredDuration),
        audio: 'silent'
      }, snapshot)
      const nextDuration = Number(coerced.duration)
      const next: GenerationOptions = {
        ...current,
        aspectRatio: coerced.aspectRatio,
        count: coerced.count,
        quality: coerced.quality,
        ...(Number.isFinite(nextDuration) && nextDuration > 0 ? { duration: nextDuration } : {})
      }
      if (
        next.aspectRatio !== current.aspectRatio
        || next.count !== current.count
        || next.quality !== current.quality
        || next.duration !== current.duration
      ) {
        onChangeRef.current(next)
      }
      return
    }

    const coerced = coerceGenerationSettings({
      aspectRatio: current.aspectRatio ?? '',
      count: current.count ?? 1,
      quality: current.quality ?? ''
    }, snapshot)
    if (
      coerced.aspectRatio !== current.aspectRatio
      || coerced.count !== current.count
      || coerced.quality !== current.quality
    ) {
      onChangeRef.current({
        ...current,
        aspectRatio: coerced.aspectRatio,
        count: coerced.count,
        quality: coerced.quality
      })
    }
  }, [mode, model, resolvedRecommendedDuration, snapshot])

  const activeQualityLabel = qualityOptions.find((item) => item.value === value.quality)?.label
    ?? value.quality
    ?? snapshot.defaultSize
  const chipLabel = [
    value.aspectRatio ?? snapshot.defaultAspectRatio,
    activeQualityLabel,
    mode === 'video' && supportsDuration
      ? `${value.duration ?? resolvedRecommendedDuration ?? snapshot.defaultDurationSeconds}s`
      : ''
  ].filter(Boolean).join(' · ')

  return (
    <Popover
      trigger="click"
      placement="topLeft"
      arrow={false}
      classNames={{ root: 'studio-composer-popover studio-composer-popover--canvas' }}
      content={
        <div className="studio-params-panel">
          {ratioOptions.length ? (
            <ParamGroup title="比例">
              {ratioOptions.map(({ value: ratio }) => (
                <button
                  key={ratio}
                  type="button"
                  className={value.aspectRatio === ratio ? 'is-active' : ''}
                  onClick={() => onChange({ ...value, aspectRatio: ratio })}
                >
                  <AspectRatioShape value={ratio} /><span>{ratio}</span>
                </button>
              ))}
            </ParamGroup>
          ) : null}
          {supportsDuration ? (
            <ParamGroup title={resolvedRecommendedDuration == null ? '秒数' : `秒数 · 推荐 ${resolvedRecommendedDuration}s`}>
              <Slider
                min={durationValues[0]}
                max={durationValues.at(-1)}
                step={null}
                marks={Object.fromEntries(durationValues.map((duration) => [duration, `${duration}s`]))}
                value={value.duration ?? snapshot.defaultDurationSeconds}
                onChange={(duration) => onChange({ ...value, duration })}
              />
            </ParamGroup>
          ) : null}
          {countOptions.length ? (
            <ParamGroup title={mode === 'video' ? '视频数' : '张数'}>
              {countOptions.map(({ value: count }) => (
                <button
                  key={count}
                  type="button"
                  className={(value.count ?? 1) === count ? 'is-active' : ''}
                  onClick={() => onChange({ ...value, count })}
                >
                  {count}
                </button>
              ))}
            </ParamGroup>
          ) : null}
          {qualityOptions.length ? (
            <ParamGroup title="清晰度">
              {qualityOptions.map(({ value: quality, label }) => (
                <button
                  key={quality}
                  type="button"
                  className={value.quality === quality ? 'is-active' : ''}
                  onClick={() => onChange({ ...value, quality })}
                >
                  {label}
                </button>
              ))}
            </ParamGroup>
          ) : null}
        </div>
      }
    >
      <StudioComposerChip
        icon={<ControlOutlined />}
        label={chipLabel}
      />
    </Popover>
  )
}

function ParamGroup({ title, children }: { title: string; children: React.ReactNode }) {
  return <section className="studio-params-panel__group"><h4>{title}</h4><div>{children}</div></section>
}
