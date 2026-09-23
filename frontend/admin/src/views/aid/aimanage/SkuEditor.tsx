import React, { useEffect, useState } from 'react';
import { Alert, AutoComplete, Button, Input, InputNumber, Modal, Select, Space, Switch, Form, message, Tag } from 'antd';
import { ArrowDownOutlined, ArrowUpOutlined, CalculatorOutlined, DeleteOutlined, HolderOutlined, PlusOutlined } from '@ant-design/icons';
import type { CapabilityModel, InputPricing, Sku, SkuEditData, PreviewResult } from './types';
import { makeEmptySku } from './helpers';
import { METER_TYPE_OPTIONS } from '@/utils/enums';
import {
  PRESET_DURATION, PRESET_SIZE, classifySizeOption, compareSizeOptions, formatSizeLabel
} from './constants';
import type { ModelSkuCoverageReport, ModelSkuCoverageStatus } from '@/api/aid/aimanage';
import { isSkuMainPriceConfigured, skuPriceLabel } from './billingSummary';

interface Props {
  data: SkuEditData;
  isTokenBilling: boolean;
  /** 计费口径（TOKEN / PER_IMAGE / PER_SECOND / SKU_PACKAGE / PER_CHAR），决定价格列与提示文案 */
  meterType?: string;
  modelType?: string;
  protocol?: string;
  capability?: CapabilityModel;
  /** 影响服务端覆盖推演的模型草稿摘要；变化后旧结果立即失效。 */
  coverageRevision?: string;
  onChange: (d: SkuEditData) => void;
  onCheckCoverage?: () => Promise<ModelSkuCoverageReport>;
  previewResult: PreviewResult | null;
  previewLoading: boolean;
  onPreview: (inputTokens: number, outputTokens: number) => void;
}

/** 带表头标签的字段容器：输入框上方显示灰色小标签，运营一眼知道每个框是什么 */
function Field({ label, width, children }: { label: string; width?: number | string; children: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2, width }}>
      <span style={{ fontSize: 11, color: '#94a3b8', lineHeight: '14px' }}>{label}</span>
      {children}
    </div>
  );
}

function DurationMatchInput({ value, onChange }: { value: number | null; onChange: (value: number) => void }) {
  const [draft, setDraft] = useState(value == null ? '' : String(value));
  useEffect(() => setDraft(value == null ? '' : String(value)), [value]);
  const commit = (raw: string) => {
    const parsed = Number(raw);
    if (raw.trim() !== '' && Number.isFinite(parsed) && parsed >= 0) {
      setDraft(String(parsed));
      onChange(parsed);
    }
    else setDraft(value == null ? '' : String(value));
  };
  return <AutoComplete size="small" style={{ width: 150 }} value={draft}
    options={PRESET_DURATION.map((seconds) => ({ value: String(seconds), label: `${seconds} 秒` }))}
    onChange={setDraft} onSelect={(next) => commit(next)} onBlur={() => commit(draft)} />;
}

type MatchKind = 'number' | 'boolean' | 'enum';
interface MatchDefinition { key: string; label: string; kind: MatchKind; options?: string[]; }

const GENERATE_MODE_VALUES = [
  'TEXT_TO_IMAGE', 'IMAGE_EDIT', 'UPSCALE',
  'TEXT_TO_VIDEO', 'IMAGE_TO_VIDEO', 'VIDEO_TO_VIDEO', 'EDGE_TO_VIDEO', 'MULTI_TO_VIDEO'
];
const OPERATION_VALUES = [
  'TEXT_GENERATE', 'IMAGE_GENERATE', 'VIDEO_GENERATE', 'SYNTHESIZE',
  'REGISTER_CLONE', 'REFERENCE_CLONE', 'DESIGN'
];
const SCENE_VALUES = [...GENERATE_MODE_VALUES, ...OPERATION_VALUES];

/** 公共计费提取器会产出的常用字段；扩展字段仍无损保留，但不要求运营手写键名。 */
const MATCH_DEFINITIONS: MatchDefinition[] = [
  { key: 'resolution', label: '分辨率/清晰度', kind: 'enum' },
  { key: 'generateMode', label: '生成模式', kind: 'enum', options: GENERATE_MODE_VALUES },
  { key: 'audioMode', label: '音频模式', kind: 'enum' },
  { key: 'scene', label: '可信业务场景', kind: 'enum', options: SCENE_VALUES },
  { key: 'protocol', label: '协议', kind: 'enum' },
  { key: 'operation', label: '操作类型', kind: 'enum', options: OPERATION_VALUES },
  { key: 'durationMin', label: '生成时长下限(秒)', kind: 'number' },
  { key: 'durationMax', label: '生成时长上限(秒)', kind: 'number' },
  { key: 'inputTokensMin', label: '输入 Token 下限', kind: 'number' },
  { key: 'inputTokensMax', label: '输入 Token 上限', kind: 'number' },
  { key: 'outputPixelsMin', label: '输出总像素下限', kind: 'number' },
  { key: 'outputPixelsMax', label: '输出总像素上限', kind: 'number' },
  { key: 'inputVideoCountMin', label: '输入视频数量下限', kind: 'number' },
  { key: 'inputVideoCountMax', label: '输入视频数量上限', kind: 'number' },
  { key: 'referenceVideoCountMin', label: '参考视频数量下限', kind: 'number' },
  { key: 'referenceVideoCountMax', label: '参考视频数量上限', kind: 'number' },
  { key: 'referenceAudioCountMin', label: '参考音频数量下限', kind: 'number' },
  { key: 'referenceAudioCountMax', label: '参考音频数量上限', kind: 'number' },
  { key: 'inputVideoSecondsMin', label: '输入视频总时长下限(秒)', kind: 'number' },
  { key: 'inputVideoSecondsMax', label: '输入视频总时长上限(秒)', kind: 'number' },
  { key: 'referenceImageCountMin', label: '参考图片数量下限', kind: 'number' },
  { key: 'referenceImageCountMax', label: '参考图片数量上限', kind: 'number' },
  { key: 'imageCount', label: '输出图片数量', kind: 'number' },
  { key: 'outputCount', label: '输出数量', kind: 'number' },
  { key: 'audio', label: '音画同出', kind: 'boolean' },
  { key: 'generateAudio', label: '生成声音', kind: 'boolean' },
  { key: 'hasVideoInput', label: '包含视频输入', kind: 'boolean' }
];

const MATCH_DEFINITION_MAP = new Map(MATCH_DEFINITIONS.map((definition) => [definition.key, definition]));

const scalarStrings = (value: unknown): string[] => {
  const values = Array.isArray(value) ? value : [value];
  if (values.some((item) => item !== null && typeof item === 'object')) return [];
  return values.filter((item) => ['string', 'number'].includes(typeof item)).map((item) => String(item));
};

const finiteNumber = (value: unknown): number | null => {
  if (value == null || value === '' || typeof value === 'object') return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
};

const COVERAGE_META: Record<ModelSkuCoverageStatus, { color: string; label: string }> = {
  NOT_APPLICABLE: { color: 'default', label: '当前模式无需检查' },
  COMPLETE: { color: 'success', label: '有限组合已覆盖' },
  GAPS: { color: 'error', label: '存在缺价组合' },
  CONFLICTS: { color: 'error', label: '存在同优先级冲突' },
  REVIEW_REQUIRED: { color: 'warning', label: '仍需人工核验' }
};

const displayCoverageValue = (value: unknown): string => {
  if (value == null) return '空';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (['string', 'number'].includes(typeof value)) return String(value);
  if (Array.isArray(value) && value.every((item) => ['string', 'number', 'boolean'].includes(typeof item))) {
    return value.map((item) => displayCoverageValue(item)).join('、');
  }
  return '复合值（需核验）';
};

const skuMatchSummary = (sku: Sku): string => {
  const entries = Object.entries(sku.match || {});
  if (entries.length === 0) return '兜底规则：所有未命中请求';
  const visible = entries.slice(0, 3).map(([key, value]) => {
    const label = MATCH_DEFINITION_MAP.get(key)?.label || key;
    return `${label} ${displayCoverageValue(value)}`;
  });
  if (entries.length > visible.length) visible.push(`另 ${entries.length - visible.length} 项`);
  return visible.join(' · ');
};

export default function SkuEditor({
  data, isTokenBilling, meterType, modelType, protocol, capability, coverageRevision,
  onChange, onCheckCoverage, previewResult, previewLoading, onPreview
}: Props) {
  const mt = (meterType || (isTokenBilling ? 'TOKEN' : '')).toUpperCase();
  const effectiveMeterType = (sku: Sku) => (sku.meterType || mt).toUpperCase();
  const hasTokenSku = isTokenBilling || data.skuList.some((sku) => effectiveMeterType(sku) === 'TOKEN');
  const enabledSkuCount = data.skuList.filter((sku) => sku.enabled).length;
  const pricedSkuCount = data.skuList.filter((sku) => sku.enabled
    && isSkuMainPriceConfigured(sku as unknown as Record<string, unknown>, mt)).length;
  const pixelTiers = data.imageOutputPixelTiers || [];
  const pixelTierInvalid = pixelTiers.length > 0 && (pixelTiers.some((tier, index) =>
    tier.price == null || tier.price < 0 || (index < pixelTiers.length - 1
      ? tier.maxPixels == null || tier.maxPixels <= 0
        || (index > 0 && tier.maxPixels <= (pixelTiers[index - 1].maxPixels || 0))
      : tier.maxPixels != null))
    || pixelTiers.some((tier) => (tier.price || 0) > Math.max(0, ...data.skuList
      .filter((sku) => sku.enabled).map((sku) => Number(sku.price || 0)))));
  const [matchDlgOpen, setMatchDlgOpen] = useState(false);
  const [matchTargetIdx, setMatchTargetIdx] = useState<number | null>(null);
  const [matchNewKey, setMatchNewKey] = useState('');
  const [coverage, setCoverage] = useState<ModelSkuCoverageReport | null>(null);
  const [coverageLoading, setCoverageLoading] = useState(false);
  const coverageRequestRef = React.useRef(false);
  const coverageSequenceRef = React.useRef(0);
  useEffect(() => {
    coverageSequenceRef.current += 1;
    setCoverage(null);
  }, [data, capability, coverageRevision, meterType, modelType, protocol]);
  const resolutionValues = [...(capability?.sizeOptions || [])];
  const resolutionLabels = new Set(resolutionValues.map((value) => formatSizeLabel(value).toLowerCase()));
  (PRESET_SIZE[modelType || ''] || []).forEach((value) => {
    const canonical = formatSizeLabel(value).toLowerCase();
    if (!resolutionLabels.has(canonical)) {
      resolutionLabels.add(canonical);
      resolutionValues.push(value);
    }
  });
  resolutionValues.sort(compareSizeOptions);
  const resolutionOptions = [
    { kind: 'pixel' as const, label: '像素尺寸' },
    { kind: 'p' as const, label: 'P 清晰度' },
    { kind: 'k' as const, label: 'K 清晰度' },
    { kind: 'other' as const, label: '模型专用规格' }
  ].map((group) => ({
    label: group.label,
    options: resolutionValues.filter((value) => classifySizeOption(value) === group.kind)
      .map((value) => ({ value, label: formatSizeLabel(value) }))
  })).filter((group) => group.options.length > 0);
  const capabilityWarnings: string[] = [];
  const rawImageLimit = modelType === 'text' ? capability?.maxInputImages : capability?.maxReferenceImages;
  const rawVideoLimit = modelType === 'text' ? capability?.maxInputVideos : capability?.maxReferenceVideos;
  const rawVideoSecondsLimit = modelType === 'text'
    ? capability?.maxInputVideoTotalDurationSeconds : capability?.referenceVideoMaxTotalDurationSeconds;
  const imageLimit = rawImageLimit == null ? null : Number(rawImageLimit);
  const videoLimit = rawVideoLimit == null ? null : Number(rawVideoLimit);
  const videoSecondsLimit = rawVideoSecondsLimit == null ? null : Number(rawVideoSecondsLimit);
  if (imageLimit != null && imageLimit >= 0 && Number(data.inputPricing?.image?.maxCount) > imageLimit) {
    capabilityWarnings.push(`图片计费上限超过能力上限 ${imageLimit}`);
  }
  if (videoLimit != null && videoLimit >= 0 && Number(data.inputPricing?.video?.maxCount) > videoLimit) {
    capabilityWarnings.push(`视频计费上限超过能力上限 ${videoLimit}`);
  }
  if (videoSecondsLimit != null && videoSecondsLimit >= 0
    && Number(data.inputPricing?.video?.maxSeconds) > videoSecondsLimit) {
    capabilityWarnings.push(`视频计费秒数上限超过能力上限 ${videoSecondsLimit}`);
  }
  if (data.inputPricing?.image?.freeCount != null && data.inputPricing?.image?.maxCount != null
    && Number(data.inputPricing.image.freeCount) > Number(data.inputPricing.image.maxCount)) {
    capabilityWarnings.push('图片免费张数不能超过图片计费张数上限');
  }
  data.skuList.forEach((sku, index) => {
    const resolution = sku.match?.resolution;
    const values = scalarStrings(resolution);
    const supported = new Set((capability?.sizeOptions || []).map((value) => formatSizeLabel(value).toLowerCase()));
    // OpenAI image billing derives a tier from pixel dimensions before SKU matching.
    // A tier such as 2K is therefore valid even when sizeOptions stores 2048x2048.
    const derivedTiers = new Set<string>();
    if (modelType === 'image' && protocol === 'openai-image') {
      for (const size of capability?.sizeOptions || []) {
        if (String(size).toLowerCase() === 'auto') {
          derivedTiers.add('1k');
          continue;
        }
        const dimensions = String(size).match(/^(\d+)\s*[x×*]\s*(\d+)$/i);
        if (!dimensions) continue;
        const maxDimension = Math.max(Number(dimensions[1]), Number(dimensions[2]));
        derivedTiers.add(maxDimension >= 3840 ? '4k' : maxDimension >= 2048 ? '2k' : maxDimension >= 1024 ? '1k' : 'sd');
      }
    }
    const validTierInSku = values.some((value) => derivedTiers.has(String(value).toLowerCase()));
    if (values.some((value) => {
      const normalized = formatSizeLabel(value).toLowerCase();
      return supported.size > 0 && !supported.has(normalized) && !derivedTiers.has(normalized)
        && !(normalized === 'sd' && validTierInSku);
    })) {
      capabilityWarnings.push(`SKU-${index + 1} 使用了能力中未启用的规格`);
    }
    if ((sku.match?.audio === true || sku.match?.generateAudio === true) && capability?.supportsAudio !== true) {
      capabilityWarnings.push(`SKU-${index + 1} 要求生成声音，但能力未启用`);
    }
    if ((sku.match?.hasVideoInput === true || sku.match?.hasVideo === true) && capability?.supportsVideoInput !== true) {
      capabilityWarnings.push(`SKU-${index + 1} 要求视频输入，但能力未启用`);
    }
    const imageCountMax = finiteNumber(sku.match?.referenceImageCountMax);
    const inputVideoCountMax = finiteNumber(sku.match?.inputVideoCountMax);
    const referenceVideoCountMax = finiteNumber(sku.match?.referenceVideoCountMax);
    const referenceAudioCountMax = finiteNumber(sku.match?.referenceAudioCountMax);
    const inputVideoSecondsMax = finiteNumber(sku.match?.inputVideoSecondsMax);
    const audioLimit = capability?.maxReferenceAudios == null ? null : Number(capability.maxReferenceAudios);
    if (imageLimit != null && imageLimit >= 0 && imageCountMax != null && imageCountMax > imageLimit) {
      capabilityWarnings.push(`SKU-${index + 1} 参考图片数量超过能力上限 ${imageLimit}`);
    }
    if (videoLimit != null && videoLimit >= 0
      && [inputVideoCountMax, referenceVideoCountMax].some((value) => value != null && value > videoLimit)) {
      capabilityWarnings.push(`SKU-${index + 1} 参考视频数量超过能力上限 ${videoLimit}`);
    }
    if (audioLimit != null && audioLimit >= 0 && referenceAudioCountMax != null && referenceAudioCountMax > audioLimit) {
      capabilityWarnings.push(`SKU-${index + 1} 参考音频数量超过能力上限 ${audioLimit}`);
    }
    if (videoSecondsLimit != null && videoSecondsLimit >= 0
      && inputVideoSecondsMax != null && inputVideoSecondsMax > videoSecondsLimit) {
      capabilityWarnings.push(`SKU-${index + 1} 输入视频时长超过能力上限 ${videoSecondsLimit}`);
    }
  });

  const update = (patch: Partial<SkuEditData>) => onChange({ ...data, ...patch });
  const checkCoverage = async () => {
    if (!onCheckCoverage || data.parseError || capability?.parseError || coverageRequestRef.current) return;
    const sequence = ++coverageSequenceRef.current;
    coverageRequestRef.current = true;
    setCoverageLoading(true);
    try {
      const result = await onCheckCoverage();
      if (sequence === coverageSequenceRef.current) setCoverage(result);
    } catch {
      // 统一请求拦截器负责错误提示；旧检查结果已经失效，不继续展示。
    } finally {
      coverageRequestRef.current = false;
      if (sequence === coverageSequenceRef.current) setCoverageLoading(false);
    }
  };
  const updateSku = (idx: number, patch: Partial<Sku>) => {
    const list = data.skuList.map((s, i) => (i === idx ? { ...s, ...patch } : s));
    update({ skuList: list });
  };
  /** 更新规则级输入媒体计费（image / video 两段按需合并） */
  const updateInputPricing = (patch: Partial<InputPricing>) => {
    update({ inputPricing: { ...(data.inputPricing || {}), ...patch } });
  };
  /** 更新某条 SKU 的输入媒体计费覆盖 */
  const updateSkuInputPricing = (idx: number, patch: Partial<InputPricing>) => {
    const sku = data.skuList[idx];
    updateSku(idx, { inputPricing: { ...(sku.inputPricing || {}), ...patch } });
  };
  const [draggedSkuIndex, setDraggedSkuIndex] = useState<number | null>(null);
  const moveSku = (from: number, to: number) => {
    if (from === to || from < 0 || to < 0 || to >= data.skuList.length) return;
    const list = [...data.skuList];
    const [moved] = list.splice(from, 1);
    list.splice(to, 0, moved);
    update({ skuList: list.map((sku, index) => ({ ...sku, priority: (index + 1) * 10 })) });
  };
  const addSku = () => update({ skuList: [...data.skuList, makeEmptySku(isTokenBilling, data.skuList.length + 1)] });
  const removeSku = (idx: number) => update({ skuList: data.skuList.filter((_, i) => i !== idx) });

  const openAddMatch = (idx: number) => {
    setMatchTargetIdx(idx);
    setMatchNewKey('');
    setMatchDlgOpen(true);
  };
  const confirmAddMatch = () => {
    if (!matchNewKey) { message.error('请选择条件'); return; }
    if (matchTargetIdx === null) return;
    const sku = data.skuList[matchTargetIdx];
    const definition = MATCH_DEFINITION_MAP.get(matchNewKey);
    const defaultValue = definition?.kind === 'boolean' ? false : definition?.kind === 'number' ? 0 : '';
    updateSku(matchTargetIdx, { match: { ...sku.match, [matchNewKey]: defaultValue } });
    setMatchDlgOpen(false);
  };
  const removeMatchKey = (idx: number, key: string) => {
    const sku = data.skuList[idx];
    const next: Record<string, unknown> = { ...sku.match };
    delete next[key];
    updateSku(idx, { match: next });
  };
  const updateMatchValue = (idx: number, key: string, value: unknown) => {
    const sku = data.skuList[idx];
    updateSku(idx, { match: { ...sku.match, [key]: value } });
  };

  const renderMatchEditor = (idx: number, key: string, value: unknown) => {
    const definition = MATCH_DEFINITION_MAP.get(key);
    const invalidKnownValue = !!definition && (
      (definition.kind === 'number' && value != null && value !== ''
        && (typeof value === 'object' || !Number.isFinite(Number(value))))
      || (definition.kind === 'boolean' && typeof value !== 'boolean')
      || (definition.kind === 'enum' && scalarStrings(value).length === 0 && value !== '' && value != null)
    );
    if (!definition || invalidKnownValue) {
      const kind = Array.isArray(value) ? '列表' : value !== null && typeof value === 'object' ? '对象' : typeof value;
      return (
        <Space size={6}>
          <Tag color={invalidKnownValue ? 'warning' : 'blue'}>{invalidKnownValue ? '保留未识别值' : '保留扩展条件'}</Tag>
          <span style={{ fontSize: 12 }}>{key}（{kind}）</span>
          <Button size="small" type="text" danger icon={<DeleteOutlined />} onClick={() => removeMatchKey(idx, key)} />
        </Space>
      );
    }
    let editor: React.ReactNode;
    if (definition.kind === 'number') {
      const parsed = value == null || value === '' ? null : typeof value === 'number' ? value : Number(value);
      const numericValue = parsed != null && Number.isFinite(parsed) ? parsed : null;
      editor = key.toLowerCase().includes('duration') || key.toLowerCase().includes('seconds')
        ? <DurationMatchInput value={numericValue} onChange={(next) => updateMatchValue(idx, key, next)} />
        : <InputNumber size="small" min={0} precision={0} style={{ width: 150 }}
            value={numericValue} onChange={(next) => updateMatchValue(idx, key, next ?? 0)} />;
    } else if (definition.kind === 'boolean') {
      editor = <Select size="small" style={{ width: 150 }} value={value === true}
        options={[{ value: true, label: '是' }, { value: false, label: '否' }]}
        onChange={(next) => updateMatchValue(idx, key, next)} />;
    } else {
      const options: any[] = key === 'resolution'
        ? resolutionOptions
        : key === 'audioMode'
          ? (capability?.audioModeOptions?.length ? capability.audioModeOptions : ['off', 'native', 'original'])
            .map((item) => ({ value: item, label: item }))
        : key === 'protocol' && protocol
          ? [{ value: protocol, label: protocol }]
        : (definition.options || []).map((item) => ({ value: item, label: item }));
      const selected = value == null || value === '' ? [] : scalarStrings(value);
      editor = <Select size="small" mode="tags" style={{ width: 220 }} value={selected} options={options}
        onChange={(next) => updateMatchValue(idx, key, next.length <= 1 ? next[0] || '' : next)} />;
    }
    return (
      <Space size={4} align="end" style={{ background: '#fff', padding: '4px 6px', borderRadius: 4, border: '1px solid #e5e7eb' }}>
        <Field label={definition.label} width={definition.kind === 'enum' ? 220 : 150}>{editor}</Field>
        <Button size="small" type="text" danger icon={<DeleteOutlined />} onClick={() => removeMatchKey(idx, key)} />
      </Space>
    );
  };

  return (
    <div>
      {data.parseError && <Alert type="error" showIcon style={{ marginBottom: 12 }}
        message="现有计费配置无法解析"
        description="结构化编辑器不会把非法历史配置静默重建为空规则；请先通过受控迁移修复后再修改 SKU。" />}
      <div className="sku-editor__overview">
        <div className="sku-editor__overview-copy">
          <strong>SKU 价格表</strong>
          <span>每条 SKU 对应一组命中条件和官方成本，按优先级从小到大匹配。</span>
        </div>
        <div className="sku-editor__stats" aria-label="SKU 配置统计">
          <div><span>全部 SKU</span><strong>{data.skuList.length}</strong></div>
          <div><span>已启用</span><strong>{enabledSkuCount}</strong></div>
          <div className={pricedSkuCount < enabledSkuCount ? 'warning' : ''}><span>已填写价格</span><strong>{pricedSkuCount}/{enabledSkuCount}</strong></div>
        </div>
        <Space className="sku-editor__actions" wrap>
          {onCheckCoverage && <Button size="small" loading={coverageLoading}
            disabled={data.parseError || capability?.parseError} onClick={checkCoverage}>检查价格覆盖</Button>}
          <Button size="small" type="primary" icon={<PlusOutlined />} onClick={addSku}>添加SKU</Button>
        </Space>
      </div>
      {modelType === 'image' && mt === 'PER_IMAGE' && (
        <div style={{ border: '1px solid #e5e7eb', borderRadius: 8, padding: 12, marginBottom: 12 }}>
          <Space wrap style={{ marginBottom: pixelTiers.length ? 10 : 0 }}>
            <strong>按每张实际输出像素结算</strong>
            <Button size="small" disabled={data.parseError} onClick={() => update({
              imageOutputPixelTiers: pixelTiers.length ? [] : [
                { maxPixels: 2610000, price: null }, { maxPixels: null, price: null }
              ]
            })}>{pixelTiers.length ? '关闭阶梯价' : '启用阶梯价'}</Button>
          </Space>
          {pixelTiers.length > 0 && <>
            <div style={{ color: '#64748b', marginBottom: 10 }}>
              按供应商实际返回的每张图片尺寸计费；上方 SKU 单张价应为最高档，用于最多输出张数的预冻结。
            </div>
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              {pixelTiers.map((tier, index) => <Space key={index} wrap align="end">
                <Field label={`档位 ${index + 1} · 像素上限`} width={170}>
                  {index === pixelTiers.length - 1 ? <Input value="以上像素" disabled /> :
                    <InputNumber aria-label={`档位 ${index + 1} 像素上限`} min={1} precision={0}
                      value={tier.maxPixels} style={{ width: '100%' }}
                      onChange={(value) => update({ imageOutputPixelTiers: pixelTiers.map((item, i) =>
                        i === index ? { ...item, maxPixels: value } : item) })} />}
                </Field>
                <Field label="基础价（元／张）" width={170}>
                  <InputNumber aria-label={`档位 ${index + 1} 基础价`} min={0} precision={6}
                    value={tier.price} style={{ width: '100%' }}
                    onChange={(value) => update({ imageOutputPixelTiers: pixelTiers.map((item, i) =>
                      i === index ? { ...item, price: value } : item) })} />
                </Field>
                {index < pixelTiers.length - 1 && <Button size="small" danger
                  onClick={() => update({ imageOutputPixelTiers: pixelTiers.filter((_, i) => i !== index) })}>移除</Button>}
              </Space>)}
              <Button size="small" disabled={data.parseError} onClick={() => update({
                imageOutputPixelTiers: [
                  ...pixelTiers.slice(0, -1),
                  { maxPixels: (pixelTiers[pixelTiers.length - 2]?.maxPixels || 0) + 1000000, price: null },
                  pixelTiers[pixelTiers.length - 1]
                ]
              })}>增加档位</Button>
            </Space>
            {pixelTierInvalid && <Alert type="error" showIcon style={{ marginTop: 10 }}
              message="阶梯价格不完整"
              description="各档像素上限必须递增，最后一档为以上像素；每档填写价格，且不得高于已启用 SKU 的最高单张预冻结价。" />}
          </>}
        </div>
      )}
      {coverage && (
        <div style={{ marginBottom: 12, padding: 10, border: '1px solid #e2e8f0', borderRadius: 8, background: '#f8fafc' }}>
          <Space wrap>
            <Tag color={COVERAGE_META[coverage.status].color}>{COVERAGE_META[coverage.status].label}</Tag>
            <span style={{ fontSize: 12, color: '#64748b' }}>已检查 {coverage.checkedCombinations} 个有限组合</span>
          </Space>
          {coverage.missingCombinations.length > 0 && (
            <div style={{ marginTop: 8 }}>
              <div style={{ fontSize: 12, fontWeight: 500, marginBottom: 4 }}>缺价组合示例（最多展示服务端返回的 50 条）</div>
              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                {coverage.missingCombinations.map((combination, index) => (
                  <Space key={index} wrap size={4}>
                    <span style={{ color: '#94a3b8', fontSize: 12 }}>#{index + 1}</span>
                    {Object.entries(combination).map(([key, value]) => (
                      <Tag key={key}>{key}：{displayCoverageValue(value)}</Tag>
                    ))}
                  </Space>
                ))}
              </Space>
            </div>
          )}
          {coverage.conflicts.length > 0 && (
            <Alert type="error" showIcon style={{ marginTop: 8 }} message="同优先级SKU同时命中"
              description={coverage.conflicts.join('；')} />
          )}
          {coverage.warnings.length > 0 && (
            <Alert type="warning" showIcon style={{ marginTop: 8 }} message="检查范围说明"
              description={coverage.warnings.join('；')} />
          )}
          <div className="help-text" style={{ marginTop: 6 }}>
            检查仅覆盖模型已声明的有限参数域，不生成费率、不发起模型调用；自定义或无限参数域仍需按真实请求人工核验。
          </div>
        </div>
      )}
      {capabilityWarnings.length > 0 && (
        <Alert style={{ marginBottom: 12 }} type="warning" showIcon
          message="SKU 条件与当前能力存在冲突"
          description={Array.from(new Set(capabilityWarnings)).join('；')} />
      )}
      {hasTokenSku && (
        <div style={{ marginBottom: 12 }}>
          <Space wrap>
            <span style={{ fontSize: 12, color: '#64748b' }}>字符Token比：</span>
            <InputNumber size="small" value={data.charToTokenRatio} min={1} max={10} onChange={(v) => update({ charToTokenRatio: v || 2 })} />
            <span style={{ fontSize: 12, color: '#64748b' }}>Usage 计费：</span>
            <Select size="small" style={{ width: 160 }} value={data.usagePricingMode || 'AGGREGATE'}
              onChange={(v) => update({ usagePricingMode: v })}
              options={[{ value: 'AGGREGATE', label: 'AGGREGATE 聚合' }, { value: 'BUCKETED', label: 'BUCKETED 分桶' }]} />
          </Space>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8 }}>
            <span style={{ fontSize: 12, color: '#64748b' }}>允许超额补扣：</span>
            <Switch size="small" checked={data.allowExtraCharge === true}
              onChange={(checked) => update({ allowExtraCharge: checked })} />
            <span style={{ fontSize: 12, color: '#64748b' }}>
              开启后，实际用量超过预冻结时可补扣；关闭时按冻结上限结算。
            </span>
          </div>
          <Alert style={{ marginTop: 8 }} type="info" showIcon
            message={data.usagePricingMode === 'BUCKETED'
              ? '分桶模式：未缓存、缓存读、缓存写互斥计费；正文输出与思考输出互斥计费。'
              : '聚合模式：仍按总输入/总输出计费，缓存与思考明细只用于审计，不会重复加收。'} />
        </div>
      )}

      {/* 规则级输入媒体计费：参考图/输入视频附加费默认值（0 或不填 = 不计费；SKU 内可按档位覆盖视频输入价） */}
      <div style={{ border: '1px dashed #cbd5e1', borderRadius: 8, padding: 10, marginBottom: 12, background: '#f8fafc' }}>
        <div style={{ fontWeight: 500, fontSize: 13, marginBottom: 6 }}>输入媒体计费（模型级默认）</div>
        <div style={{ fontSize: 12, color: '#94a3b8', marginBottom: 8 }}>
          参考图按张、输入视频按秒叠加到预扣金额；单价填 0 或留空 = 该类输入不计费。官方前 N 张免费可直接配置，不需要拍平价格。
        </div>
        <Space wrap align="end">
          <Field label="输入图片官方原价（元/张）" width={180}>
            <InputNumber size="small" style={{ width: '100%' }} min={0} precision={8}
              value={data.inputPricing?.image?.unitPrice ?? null}
              onChange={(v) => updateInputPricing({ image: { ...(data.inputPricing?.image || {}), unitPrice: v } })} />
          </Field>
          <Field label="输入图片免费张数" width={150}>
            <InputNumber size="small" style={{ width: '100%' }} min={0} precision={0}
              value={data.inputPricing?.image?.freeCount ?? null}
              onChange={(v) => updateInputPricing({ image: { ...(data.inputPricing?.image || {}), freeCount: v } })} />
          </Field>
          <Field label="图片计费张数上限（空=不限）" width={170}>
            <InputNumber size="small" style={{ width: '100%' }} min={0} precision={0}
              value={data.inputPricing?.image?.maxCount ?? null}
              onChange={(v) => updateInputPricing({ image: { ...(data.inputPricing?.image || {}), maxCount: v } })} />
          </Field>
          <Field label="输入视频官方原价（元/秒）" width={180}>
            <InputNumber size="small" style={{ width: '100%' }} min={0} precision={8}
              value={data.inputPricing?.video?.unitPrice ?? null}
              onChange={(v) => updateInputPricing({ video: { ...(data.inputPricing?.video || {}), unitPrice: v } })} />
          </Field>
          <Field label="视频计费秒数上限（时长未知按此预扣）" width={220}>
            <InputNumber size="small" style={{ width: 120 }} min={0} step={0.1}
              value={data.inputPricing?.video?.maxSeconds ?? null}
              onChange={(v) => updateInputPricing({ video: { ...(data.inputPricing?.video || {}), maxSeconds: v } })} />
          </Field>
          <Field label="输入视频段数上限（空=不限）" width={180}>
            <InputNumber size="small" style={{ width: 120 }} min={0} precision={0}
              value={data.inputPricing?.video?.maxCount ?? null}
              onChange={(v) => updateInputPricing({ video: { ...(data.inputPricing?.video || {}), maxCount: v } })} />
          </Field>
        </Space>
      </div>
      {data.skuList.length === 0 && (
        <div className="sku-editor__empty" role="status">
          <strong>SKU 计费已开启，但还没有价格规则</strong>
          <span>至少添加一条启用的 SKU，填写命中条件和对应官方原价后才能形成完整计费配置。</span>
          <Button type="primary" icon={<PlusOutlined />} onClick={addSku}>添加第一条 SKU</Button>
        </div>
      )}
      {data.skuList.map((sku, idx) => {
        const skuPrice = skuPriceLabel(sku as unknown as Record<string, unknown>, effectiveMeterType(sku));
        const priceMissing = sku.enabled
          && !isSkuMainPriceConfigured(sku as unknown as Record<string, unknown>, mt);
        return (
        <div key={idx} className={`sku-editor__card${sku.enabled ? '' : ' disabled'}${priceMissing ? ' price-missing' : ''}`}
          onDragOver={(event) => { if (draggedSkuIndex !== null) event.preventDefault(); }}
          onDrop={(event) => { event.preventDefault(); if (draggedSkuIndex !== null) moveSku(draggedSkuIndex, idx); setDraggedSkuIndex(null); }}>
          <div className="sku-editor__card-header">
            <div className="sku-editor__card-identity">
              <span className="sku-editor__index">SKU-{idx + 1}</span>
              <div>
                <strong>{sku.skuName || '未命名 SKU'}</strong>
                <span>{sku.skuCode || '尚未填写 SKU 编码'}</span>
              </div>
            </div>
            <Space wrap>
              <Button size="small" icon={<ArrowUpOutlined />} disabled={idx === 0}
                aria-label={`上移 ${sku.skuName || `SKU-${idx + 1}`}`} onClick={() => moveSku(idx, idx - 1)} />
              <Button size="small" icon={<ArrowDownOutlined />} disabled={idx === data.skuList.length - 1}
                aria-label={`下移 ${sku.skuName || `SKU-${idx + 1}`}`} onClick={() => moveSku(idx, idx + 1)} />
              <span draggable aria-hidden="true"
                title="拖动调整 SKU 顺序，也可使用左右两侧的上下按钮"
                style={{ cursor: 'grab', color: '#64748b', display: 'inline-flex', padding: 4 }}
                onDragStart={(event) => { setDraggedSkuIndex(idx); event.dataTransfer.effectAllowed = 'move'; }}
                onDragEnd={() => setDraggedSkuIndex(null)}><HolderOutlined /></span>
              <Tag color={priceMissing ? 'error' : sku.enabled ? 'success' : 'default'}>{sku.enabled ? skuPrice : '已停用'}</Tag>
              <Switch size="small" checked={sku.enabled} checkedChildren="启用" unCheckedChildren="停用"
                aria-label={`SKU-${idx + 1}启用状态`} onChange={(v) => updateSku(idx, { enabled: v })} />
              <Button size="small" type="link" danger icon={<DeleteOutlined />} onClick={() => removeSku(idx)}>删除</Button>
            </Space>
          </div>
          <div className="sku-editor__match-summary">{skuMatchSummary(sku)}</div>
          <Space wrap style={{ marginBottom: 8 }} align="end">
            <Field label="SKU编码（唯一标识，结算快照记录用）" width={200}>
              <Input size="small" placeholder="如 AGNES_VIDEO_V20_PER_TASK" value={sku.skuCode} onChange={(e) => updateSku(idx, { skuCode: e.target.value })} />
            </Field>
            <Field label="SKU名称（C端计费详情展示）" width={200}>
              <Input size="small" placeholder="如 Agnes视频v2.0单次" value={sku.skuName} onChange={(e) => updateSku(idx, { skuName: e.target.value })} />
            </Field>
            <Field label="优先级（小的先匹配）" width={130}>
              <InputNumber size="small" style={{ width: '100%' }} placeholder="1" value={sku.priority} min={1} onChange={(v) => updateSku(idx, { priority: v || 1 })} />
            </Field>
            <Field label="本SKU计费口径（留空=继承模型）" width={210}>
              <Select
                size="small"
                allowClear
                placeholder={`继承：${mt}`}
                value={sku.meterType || undefined}
                options={METER_TYPE_OPTIONS.map((o) => ({ label: o.label, value: o.value }))}
                onChange={(v) => updateSku(idx, { meterType: v || null })}
              />
            </Field>
            <Field label="备注（内部说明）" width={200}>
              <Input size="small" placeholder="如 官方$0.005/秒×700" value={sku.remark} onChange={(e) => updateSku(idx, { remark: e.target.value })} />
            </Field>
          </Space>

          {/* 匹配条件 */}
          <div style={{ background: '#fafbfc', padding: 10, borderRadius: 6, marginBottom: 8 }}>
            <div style={{ fontSize: 12, color: '#64748b', marginBottom: 6 }}>
              匹配条件（请求参数满足全部条件才命中本档；空条件 = 兜底全部命中）：
            </div>
            <Space wrap align="end">
              {Object.entries(sku.match || {}).map(([key, value]) => (
                <React.Fragment key={key}>{renderMatchEditor(idx, key, value)}</React.Fragment>
              ))}
              <Button size="small" icon={<PlusOutlined />} onClick={() => openAddMatch(idx)}>添加条件</Button>
              {Object.keys(sku.match || {}).length === 0 && (
                <span style={{ color: '#94a3b8', fontSize: 12 }}>暂无条件，本档为兜底价。</span>
              )}
            </Space>
          </div>

          {/* 价格（按计费口径显示对应单价列） */}
          <div className="sku-editor__price-section">
            <div className="sku-editor__section-title">
              <span>官方原价</span>
              <Tag color={priceMissing ? 'error' : 'blue'}>{skuPrice}</Tag>
            </div>
            {effectiveMeterType(sku) === 'TOKEN' ? (
              <Space wrap align="end">
                <Field label="输入官方原价（元/百万Token）" width={210}>
                  <InputNumber size="small" style={{ width: '100%' }} min={0} precision={8} value={sku.inputPricePerMillion} onChange={(v) => updateSku(idx, { inputPricePerMillion: v })} />
                </Field>
                <Field label="输出官方原价（元/百万Token）" width={210}>
                  <InputNumber size="small" style={{ width: '100%' }} min={0} precision={8} value={sku.outputPricePerMillion} onChange={(v) => updateSku(idx, { outputPricePerMillion: v })} />
                </Field>
                <Field label="缓存读取原价（元/百万Token）" width={210}>
                  <InputNumber size="small" style={{ width: '100%' }} min={0} precision={8} value={sku.cachedInputPricePerMillion} onChange={(v) => updateSku(idx, { cachedInputPricePerMillion: v })} />
                </Field>
                <Field label="缓存写入原价（元/百万Token）" width={210}>
                  <InputNumber size="small" style={{ width: '100%' }} min={0} precision={8} value={sku.cacheWritePricePerMillion} onChange={(v) => updateSku(idx, { cacheWritePricePerMillion: v })} />
                </Field>
                <Field label="思考输出原价（元/百万Token）" width={210}>
                  <InputNumber size="small" style={{ width: '100%' }} min={0} precision={8} value={sku.reasoningPricePerMillion} onChange={(v) => updateSku(idx, { reasoningPricePerMillion: v })} />
                </Field>
              </Space>
            ) : effectiveMeterType(sku) === 'PER_SECOND' ? (
              <Space wrap align="end">
                <Field label="每秒官方原价（元/秒）" width={190}>
                  <InputNumber size="small" style={{ width: '100%' }} min={0} precision={8} value={sku.pricePerSecond} onChange={(v) => updateSku(idx, { pricePerSecond: v })} />
                </Field>
                <Field label="整包官方原价（仅旧继承规则兼容；新配置须填每秒价）" width={360}>
                  <InputNumber size="small" style={{ width: 130 }} min={0} precision={6} value={sku.price} onChange={(v) => updateSku(idx, { price: v })} />
                </Field>
                <Field label="输入视频官方原价（元/秒，选填；覆盖模型级输入计费）" width={330}>
                  <InputNumber size="small" style={{ width: 130 }} min={0} precision={8}
                    value={sku.inputPricing?.video?.unitPrice ?? null}
                    onChange={(v) => updateSkuInputPricing(idx, { video: { ...(sku.inputPricing?.video || {}), unitPrice: v } })} />
                </Field>
              </Space>
            ) : effectiveMeterType(sku) === 'PER_CHAR' ? (
              <Space wrap align="end">
                <Field label="每字符官方原价（元/字符）" width={220}>
                  <InputNumber size="small" style={{ width: '100%' }} min={0} precision={8} value={sku.pricePerChar} onChange={(v) => updateSku(idx, { pricePerChar: v })} />
                </Field>
                <Field label="单次官方原价（仅旧继承规则兼容；新配置须填字符价）" width={350}>
                  <InputNumber size="small" style={{ width: 130 }} min={0} precision={6} value={sku.price} onChange={(v) => updateSku(idx, { price: v })} />
                </Field>
                <Field label="每次固定附加成本（元/次，可与字符费叠加）" width={330}>
                  <InputNumber size="small" style={{ width: 150 }} min={0} precision={6}
                    value={sku.fixedSurcharge} onChange={(v) => updateSku(idx, { fixedSurcharge: v })} />
                </Field>
              </Space>
            ) : (
              <Space wrap align="end">
                <Field
                  label={effectiveMeterType(sku) === 'PER_IMAGE' && sku.outputPixelsPerUnit ? '每输出像素单位原价（元/单位）'
                    : effectiveMeterType(sku) === 'PER_IMAGE' ? '每张官方原价（元/张）'
                    : effectiveMeterType(sku) === 'SKU_PACKAGE' ? '整包官方原价（元/次）'
                    : '固定官方原价（元/次）'}
                  width={260}
                >
                  <InputNumber size="small" style={{ width: 130 }} min={0} precision={6} value={sku.price} onChange={(v) => updateSku(idx, { price: v })} />
                </Field>
                {effectiveMeterType(sku) === 'PER_IMAGE' && (
                  <>
                    <Field label="每价格单位覆盖输出像素（可选；如 24000000 = 24 MP，不足一单位按一单位）" width={380}>
                      <InputNumber size="small" style={{ width: '100%' }} min={1} precision={0}
                        value={sku.outputPixelsPerUnit} onChange={(v) => updateSku(idx, { outputPixelsPerUnit: v })} />
                    </Field>
                    <Field label="输入图片官方原价（元/张，选填；覆盖模型级输入计费）" width={330}>
                      <InputNumber size="small" style={{ width: 130 }} min={0} precision={8}
                        value={sku.inputPricing?.image?.unitPrice ?? null}
                        onChange={(v) => updateSkuInputPricing(idx, { image: { ...(sku.inputPricing?.image || {}), unitPrice: v } })} />
                    </Field>
                    <Field label="输入图片免费张数（SKU 覆盖）" width={230}>
                      <InputNumber size="small" style={{ width: 130 }} min={0} precision={0}
                        value={sku.inputPricing?.image?.freeCount ?? null}
                        onChange={(v) => updateSkuInputPricing(idx, { image: { ...(sku.inputPricing?.image || {}), freeCount: v } })} />
                    </Field>
                  </>
                )}
                {effectiveMeterType(sku) === 'SKU_PACKAGE' && (
                  <>
                    <Field label="输入视频官方原价（元/秒，选填；覆盖模型级输入计费）" width={330}>
                      <InputNumber size="small" style={{ width: 130 }} min={0} precision={8}
                        value={sku.inputPricing?.video?.unitPrice ?? null}
                        onChange={(v) => updateSkuInputPricing(idx, { video: { ...(sku.inputPricing?.video || {}), unitPrice: v } })} />
                    </Field>
                    <Field label="每次固定附加成本（元/次）" width={240}>
                      <InputNumber size="small" style={{ width: 150 }} min={0} precision={6}
                        value={sku.fixedSurcharge} onChange={(v) => updateSku(idx, { fixedSurcharge: v })} />
                    </Field>
                  </>
                )}
              </Space>
            )}
          </div>
        </div>
        );
      })}

      {/* 试算 */}
      {data.skuList.length > 0 && (
        <TrialCalc isTokenBilling={hasTokenSku} loading={previewLoading} result={previewResult} onCalc={onPreview} />
      )}

      {/* 添加匹配条件弹窗 */}
      <Modal title="添加匹配条件" open={matchDlgOpen} onCancel={() => setMatchDlgOpen(false)} onOk={confirmAddMatch} width={460} destroyOnClose>
        <Form layout="vertical">
          <Form.Item label="条件类型" required help="数值区间请分别添加下限和上限；同一 SKU 内的全部条件同时满足才会命中。">
            <Select
              showSearch
              placeholder="选择匹配条件"
              value={matchNewKey || undefined}
              onChange={setMatchNewKey}
              options={MATCH_DEFINITIONS
                .filter((definition) => !Object.prototype.hasOwnProperty.call(
                  data.skuList[matchTargetIdx ?? -1]?.match || {}, definition.key
                ))
                .map((definition) => ({ value: definition.key, label: definition.label }))}
            />
          </Form.Item>
          <Alert type="info" showIcon message="添加后在 SKU 卡片中选择或填写条件值；历史扩展条件会保留，但不会要求手工编辑结构化内容。" />
        </Form>
      </Modal>
    </div>
  );
}

function TrialCalc({ isTokenBilling, loading, result, onCalc }: { isTokenBilling: boolean; loading: boolean; result: PreviewResult | null; onCalc: (i: number, o: number) => void }) {
  const [inputT, setInputT] = useState<number>(1000);
  const [outputT, setOutputT] = useState<number>(500);
  const MAX_TOKENS = 10_000_000; // 限制试算规模
  const snapshot = result?.snapshot;
  const calculatedInputTokens = Number(snapshot?.requestParams?.inputTokens ?? inputT);
  const calculatedOutputTokens = Number(snapshot?.requestParams?.outputTokens ?? outputT);
  const formatAmount = (value?: number) => {
    if (value == null || Number.isNaN(Number(value))) return '0';
    return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 8, useGrouping: false });
  };
  return (
    <div style={{ marginTop: 12, padding: 12, background: 'rgba(37, 99, 235, 0.04)', borderRadius: 8, border: '1px dashed rgba(37, 99, 235, 0.3)' }}>
      <div style={{ fontWeight: 500, fontSize: 13, marginBottom: 8, color: '#2563eb' }}><CalculatorOutlined /> 计费试算</div>
      <Space wrap align="center">
        {isTokenBilling ? (
          <>
            <span style={{ fontSize: 12 }}>输入Token:</span>
            <InputNumber size="small" min={0} max={MAX_TOKENS} style={{ width: 120 }} value={inputT} onChange={(v) => setInputT(v ?? 0)} />
            <span style={{ fontSize: 12 }}>输出Token:</span>
            <InputNumber size="small" min={0} max={MAX_TOKENS} style={{ width: 120 }} value={outputT} onChange={(v) => setOutputT(v ?? 0)} />
          </>
        ) : (
          <span style={{ fontSize: 12, color: '#64748b' }}>将使用第一个 SKU 的匹配条件进行试算</span>
        )}
        <Button size="small" type="primary" loading={loading} onClick={() => onCalc(Math.min(inputT, MAX_TOKENS), Math.min(outputT, MAX_TOKENS))}>试算</Button>
      </Space>
      {result && (
        <div style={{ marginTop: 10, padding: '10px 12px', background: '#fff', borderRadius: 6, fontSize: 13, lineHeight: '22px' }}>
          <div>
            {result.skuCode && <span style={{ color: '#10b981', marginRight: 12 }}>✓ 命中：{result.skuCode}</span>}
            {result.amount != null && <span style={{ fontWeight: 600, color: '#dc2626' }}>预扣：{formatAmount(result.amount)} 积分</span>}
            {result.matched === false && <span style={{ color: '#dc2626' }}>{result.errorMessage || '未命中计费规则'}</span>}
          </div>
          {snapshot && result.amount != null && (
            <>
              {isTokenBilling && snapshot.inputPricePerMillion != null && snapshot.outputPricePerMillion != null && (
                <div style={{ color: '#64748b' }}>
                  官方原价：({formatAmount(calculatedInputTokens)} × {formatAmount(snapshot.inputPricePerMillion)} ÷ 1,000,000)
                  {' + '}({formatAmount(calculatedOutputTokens)} × {formatAmount(snapshot.outputPricePerMillion)} ÷ 1,000,000)
                  {' = '}{formatAmount(snapshot.baseAmount)} 元
                </div>
              )}
              <div style={{ color: '#334155', fontWeight: 500 }}>
                计费公式：{formatAmount(snapshot.baseAmount)} 元 × {formatAmount(snapshot.globalBillingMultiplier)} 积分/元
                {' × '}{formatAmount(snapshot.modelBillingMultiplier)} = {formatAmount(result.amount)} 积分
              </div>
              <div style={{ color: '#94a3b8', fontSize: 12 }}>
                官方原价 × 模型基础倍率 × 单模型倍率 = 最终预扣积分
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}
