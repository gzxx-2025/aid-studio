import type { CapabilityModel, InputPricing, Model, ParamMapping, Sku, SkuEditData } from './types';
import { makeEmptyCapabilityModel, inferMeterType } from './constants';
import {
  buildInputSupportFields,
  extractUnmanagedCapability,
  mergeManagedCapability,
  normalizeAllowedScenes,
  parseInputModalities
} from './capabilityMerge';

const normalizeStringList = (raw: unknown): string[] => Array.isArray(raw)
  ? raw.filter((value) => typeof value === 'string')
    .map((value) => value.trim().toLowerCase()).filter(Boolean)
  : [];

const positiveNumberOrNull = (raw: unknown): number | null => {
  const value = Number(raw);
  return Number.isFinite(value) && value > 0 ? value : null;
};

const positiveIntegerOrNull = (raw: unknown): number | null => {
  const value = positiveNumberOrNull(raw);
  return value == null ? null : Math.trunc(value);
};

const nonNegativeIntegerOrNull = (raw: unknown): number | null => {
  if (raw === null || raw === undefined || raw === '') return null;
  const value = Number(raw);
  return Number.isFinite(value) && value >= 0 ? Math.trunc(value) : null;
};

const integerOrNull = (raw: unknown): number | null => {
  if (raw === null || raw === undefined || raw === '') return null;
  const value = Number(raw);
  return Number.isFinite(value) ? Math.trunc(value) : null;
};

const nonNegativeNumberOrNull = (raw: unknown): number | null => {
  if (raw === null || raw === undefined || raw === '') return null;
  const value = Number(raw);
  return Number.isFinite(value) && value >= 0 ? value : null;
};

const countOrNull = (raw: unknown): number | null => {
  if (raw === null || raw === undefined || raw === '') return null;
  const value = Number(raw);
  return Number.isFinite(value) && value >= -1 ? Math.trunc(value) : null;
};

const writePositive = (target: Record<string, any>, key: string, raw: number | null | undefined) => {
  target[key] = raw != null && Number.isFinite(Number(raw)) && Number(raw) > 0 ? Number(raw) : null;
};

const writePositiveInteger = (target: Record<string, any>, key: string, raw: number | null | undefined) => {
  target[key] = raw != null && Number.isFinite(Number(raw)) && Number(raw) > 0
    ? Math.trunc(Number(raw)) : null;
};

const writeNonNegativeInteger = (target: Record<string, any>, key: string, raw: number | null | undefined) => {
  target[key] = raw != null && Number.isFinite(Number(raw)) && Number(raw) >= 0
    ? Math.trunc(Number(raw)) : null;
};

const writeNonNegative = (target: Record<string, any>, key: string, raw: number | null | undefined) => {
  target[key] = raw != null && Number.isFinite(Number(raw)) && Number(raw) >= 0 ? Number(raw) : null;
};

const writeInteger = (target: Record<string, any>, key: string, raw: number | null | undefined) => {
  target[key] = raw != null && Number.isFinite(Number(raw)) ? Math.trunc(Number(raw)) : null;
};

/** 模型行表格"能力"列单行紧凑摘要 */
export function buildCapSummary(row: Model): string {
  const parts: string[] = [];
  if (row.defaultSizeCode) parts.push(row.defaultSizeCode);
  if (row.defaultAspectRatio) parts.push(row.defaultAspectRatio);
  if (row.defaultDurationSeconds) parts.push(row.defaultDurationSeconds + 's');
  if (row.maxOutputCount && row.maxOutputCount > 1) parts.push('×' + row.maxOutputCount);
  return parts.length ? parts.join(' · ') : '—';
}

/** 从行数据解析 meterType（用于列表展示，与后端 BillingAmountCalculatorImpl.resolveMeterType 一致） */
export function resolveMeterType(row: Model): string {
  if (row.billingRuleJson) {
    try {
      const rule = JSON.parse(row.billingRuleJson);
      if (rule.meterType) return rule.meterType;
    } catch {
      /* ignore */
    }
  }
  return inferMeterType(row.modelType);
}

/** capabilityJson 字符串 → capabilityModel */
export function parseCapabilityJsonToModel(jsonStr?: string | null): CapabilityModel {
  const m = makeEmptyCapabilityModel();
  if (!jsonStr) return m;
  let obj: any;
  try {
    obj = JSON.parse(jsonStr);
  } catch {
    m.parseError = true;
    return m;
  }
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) {
    m.parseError = true;
    return m;
  }
  m.sourceCapability = JSON.parse(JSON.stringify(obj));
  m.preservedCapability = extractUnmanagedCapability(obj);
  m.supportsReasoning = obj.supportsReasoning === true;
  m.supportsReasoningDisable = obj.supportsReasoningDisable === true;
  m.returnsReasoningContent = obj.supportsReasoningContent === true || obj.returnsReasoningContent === true;
  m.supportsReasoningBudget = obj.supportsReasoningBudget === true;
  m.defaultReasoningEnabled = obj.defaultReasoningEnabled === true;
  m.reasoningApiStyle = typeof obj.reasoningApiStyle === 'string' ? obj.reasoningApiStyle : undefined;
  m.outputTokenApiField = typeof obj.outputTokenApiField === 'string' ? obj.outputTokenApiField : undefined;
  m.allowedReasoningLevels = Array.isArray(obj.allowedReasoningLevels)
    ? obj.allowedReasoningLevels.filter((value: unknown) => typeof value === 'string').map((value: string) => value.trim()).filter(Boolean) : [];
  m.supportsStreaming = typeof obj.supportsStreaming === 'boolean' ? obj.supportsStreaming : undefined;
  m.supportsToolCalling = typeof obj.supportsToolCalling === 'boolean' ? obj.supportsToolCalling : undefined;
  m.supportsChatPrefix = typeof obj.supportsChatPrefix === 'boolean' ? obj.supportsChatPrefix : undefined;
  m.supportsStructuredOutput = typeof obj.supportsStructuredOutput === 'boolean' ? obj.supportsStructuredOutput : undefined;
  m.supportsContextCaching = typeof obj.supportsContextCaching === 'boolean' ? obj.supportsContextCaching : undefined;
  m.supportsBuiltinTools = typeof obj.supportsBuiltinTools === 'boolean' ? obj.supportsBuiltinTools : undefined;
  m.defaultReasoningLevel = typeof obj.defaultReasoningLevel === 'string'
    ? obj.defaultReasoningLevel : undefined;
  {
    const value = Number(obj.defaultReasoningBudgetTokens);
    m.defaultReasoningBudgetTokens = Number.isFinite(value) && value > 0 ? Math.trunc(value) : null;
    const maxValue = Number(obj.maxReasoningBudgetTokens);
    m.maxReasoningBudgetTokens = Number.isFinite(maxValue) && maxValue > 0 ? Math.trunc(maxValue) : null;
  }
  m.inputModalities = parseInputModalities(obj);
  m.outputModalities = Array.isArray(obj.outputModalities)
    ? obj.outputModalities.filter((v: unknown) => typeof v === 'string')
      .map((v: string) => v.trim().toUpperCase()).filter(Boolean) : ['TEXT'];
  const countFields = ['maxInputImages', 'maxInputVideos', 'maxInputAudios', 'maxInputDocuments'] as const;
  countFields.forEach((field) => {
    m[field] = countOrNull(obj[field]);
  });
  const positiveDecimalFields = [
    'maxInputImageFileSizeMb', 'maxInputVideoFileSizeMb', 'maxInputAudioFileSizeMb',
    'maxInputDocumentFileSizeMb', 'minInputVideoDurationSeconds', 'maxInputVideoDurationSeconds',
    'minInputAudioDurationSeconds', 'maxInputAudioDurationSeconds',
    'maxInputVideoTotalDurationSeconds', 'maxInputAudioTotalDurationSeconds',
    'maxInputMediaTotalFileSizeMb'
  ] as const;
  positiveDecimalFields.forEach((field) => {
    m[field] = positiveNumberOrNull(obj[field]);
  });
  const positiveIntegerFields = [
    'maxInputDocumentPages', 'contextWindowTokens', 'maxOutputTokens', 'maxPromptCharacters',
    'maxPromptCharactersCjk',
    'minOutputPixels', 'maxOutputPixels',
    'inputImageMinDimensionPixels', 'inputImageMaxDimensionPixels',
    'inputImageHighCountThreshold', 'inputImageHighCountMaxDimensionPixels',
    'inputImageMinPixels', 'inputImageMaxPixels',
    'inputVideoMinDimensionPixels', 'inputVideoMaxDimensionPixels',
    'inputVideoMinPixels', 'inputVideoMaxPixels'
  ] as const;
  positiveIntegerFields.forEach((field) => {
    const value = Number(obj[field]);
    m[field] = Number.isFinite(value) && value > 0 ? Math.trunc(value) : null;
  });
  const formatFields = ['inputImageFormats', 'inputVideoFormats', 'inputAudioFormats', 'inputDocumentFormats'] as const;
  formatFields.forEach((field) => {
    m[field] = normalizeStringList(obj[field]);
  });
  m.inputMediaAllowedMessageRoles = normalizeStringList(obj.inputMediaAllowedMessageRoles);
  m.inputMediaMaxUrlLength = positiveIntegerOrNull(obj.inputMediaMaxUrlLength);
  const positiveRatioFields = [
    'minOutputAspectRatio', 'maxOutputAspectRatio',
    'inputImageMinAspectRatio', 'inputImageMaxAspectRatio',
    'inputVideoMinAspectRatio', 'inputVideoMaxAspectRatio',
    'inputVideoMinFps', 'inputVideoMaxFps'
  ] as const;
  positiveRatioFields.forEach((field) => {
    m[field] = positiveNumberOrNull(obj[field]);
  });
  if (Array.isArray(obj.sizeOptions)) {
    m.sizeOptions = obj.sizeOptions.filter((value: unknown) => typeof value === 'string')
      .map((value: string) => value.trim()).filter(Boolean);
  }
  if (Array.isArray(obj.aspectRatioOptions)) {
    m.aspectRatioOptions = obj.aspectRatioOptions.filter((value: unknown) => typeof value === 'string')
      .map((value: string) => value.trim()).filter(Boolean);
  }
  if (Array.isArray(obj.durationOptions)) {
    m.durationOptions = obj.durationOptions.map((value: unknown) => Number(value))
      .filter((value: number) => Number.isFinite(value) && (value === -1 || value > 0));
  }
  m.outputFormatOptions = normalizeStringList(obj.outputFormatOptions);
  m.defaultOutputFormat = typeof obj.defaultOutputFormat === 'string'
    ? obj.defaultOutputFormat.trim().toLowerCase() || null : null;
  m.outputFpsOptions = Array.isArray(obj.outputFpsOptions)
    ? Array.from(new Set<number>(obj.outputFpsOptions.map((value: unknown) => Number(value))
      .filter((value: number) => Number.isInteger(value) && value > 0))).sort((a, b) => a - b) : [];
  m.defaultOutputFps = positiveIntegerOrNull(obj.defaultOutputFps);
  m.audioModeOptions = normalizeStringList(obj.audioModeOptions);
  m.defaultAudio = typeof obj.defaultAudio === 'boolean' ? obj.defaultAudio : null;
  m.supportsBgm = typeof obj.supportsBgm === 'boolean' ? obj.supportsBgm : undefined;
  m.supportsVoiceId = typeof obj.supportsVoiceId === 'boolean' ? obj.supportsVoiceId : undefined;
  m.audioTypes = normalizeStringList(obj.audioTypes);
  m.supportsVoiceControl = typeof obj.supportsVoiceControl === 'boolean' ? obj.supportsVoiceControl : undefined;
  m.audioOperation = typeof obj.audioOperation === 'string' ? obj.audioOperation.trim().toLowerCase() || null : null;
  m.ttsTextRequired = typeof obj.ttsTextRequired === 'boolean' ? obj.ttsTextRequired : undefined;
  m.ttsVoiceRequired = typeof obj.ttsVoiceRequired === 'boolean' ? obj.ttsVoiceRequired : undefined;
  m.builtInVoiceOptions = normalizeStringList(obj.builtInVoiceOptions);
  m.audioFormatOptions = normalizeStringList(obj.audioFormatOptions);
  m.defaultAudioFormat = typeof obj.defaultAudioFormat === 'string'
    ? obj.defaultAudioFormat.trim().toLowerCase() || null : null;
  m.audioSampleRateOptions = Array.isArray(obj.audioSampleRateOptions)
    ? Array.from(new Set<number>(obj.audioSampleRateOptions.map((value: unknown) => Number(value))
      .filter((value: number) => Number.isInteger(value) && value > 0))).sort((a, b) => a - b) : [];
  m.defaultAudioSampleRate = positiveIntegerOrNull(obj.defaultAudioSampleRate);
  m.supportsAudioStreaming = typeof obj.supportsAudioStreaming === 'boolean' ? obj.supportsAudioStreaming : undefined;
  m.supportsTimestamp = typeof obj.supportsTimestamp === 'boolean' ? obj.supportsTimestamp : undefined;
  m.speechRateMin = integerOrNull(obj.speechRateMin);
  m.speechRateMax = integerOrNull(obj.speechRateMax);
  m.loudnessRateMin = integerOrNull(obj.loudnessRateMin);
  m.loudnessRateMax = integerOrNull(obj.loudnessRateMax);
  m.pitchMin = integerOrNull(obj.pitchMin);
  m.pitchMax = integerOrNull(obj.pitchMax);
  m.emotionOptions = normalizeStringList(obj.emotionOptions);
  m.supportsEmotionScale = typeof obj.supportsEmotionScale === 'boolean' ? obj.supportsEmotionScale : undefined;
  m.voiceSampleRequired = typeof obj.voiceSampleRequired === 'boolean' ? obj.voiceSampleRequired : undefined;
  m.voiceSampleFormats = normalizeStringList(obj.voiceSampleFormats);
  m.voiceSampleMaxFileSizeMb = positiveNumberOrNull(obj.voiceSampleMaxFileSizeMb);
  m.supportsElements = typeof obj.supportsElements === 'boolean' ? obj.supportsElements : undefined;
  m.maxElements = positiveIntegerOrNull(obj.maxElements);
  m.elementTypeRequired = typeof obj.elementTypeRequired === 'boolean' ? obj.elementTypeRequired : undefined;
  m.klingScenario = typeof obj.klingScenario === 'string' ? obj.klingScenario.trim() || null : null;
  m.videoScenario = typeof obj.videoScenario === 'string' ? obj.videoScenario.trim() || null : null;
  m.seedanceTaskTypeOptions = normalizeStringList(obj.seedanceTaskTypeOptions);
  m.allowedScenes = normalizeAllowedScenes(obj.allowedScenes);
  if (typeof obj.allowCustomWH === 'boolean') m.allowCustomWH = obj.allowCustomWH;
  m.durationMin = nonNegativeNumberOrNull(obj.durationMin);
  m.durationMax = nonNegativeNumberOrNull(obj.durationMax);
  m.strictSceneRules = obj.strictSceneRules === true;
  // 单次最多参考图张数（四态语义）：
  //   缺省/null → null（不设上限，回退厂商默认）
  //   -1 → 无限；0 → 禁止参考图；N(>0) → 上限 N
  //   非法值（非整数、< -1）一律归一为 null
  if (obj.maxReferenceImages === null || obj.maxReferenceImages === undefined) {
    m.maxReferenceImages = null;
  } else {
    const n = Number(obj.maxReferenceImages);
    m.maxReferenceImages = Number.isFinite(n) && n >= -1 ? Math.trunc(n) : null;
  }
  // 最少参考图张数：缺省/null → null（不要求带图）；N>=0 → 按值保留；
  // 非法值（非数字、负数）一律归一为 null，与后端 readMinFromCapabilityJson 的容错口径一致
  if (obj.minReferenceImages === null || obj.minReferenceImages === undefined) {
    m.minReferenceImages = null;
  } else {
    const n = Number(obj.minReferenceImages);
      m.minReferenceImages = Number.isFinite(n) && n >= 0 ? Math.trunc(n) : null;
  }
  m.referenceImageFormats = normalizeStringList(obj.referenceImageFormats);
  m.referenceImageMaxFileSizeMb = positiveNumberOrNull(obj.referenceImageMaxFileSizeMb);
  m.referenceImageMinDimensionPixels = positiveIntegerOrNull(obj.referenceImageMinDimensionPixels);
  m.referenceImageMaxDimensionPixels = positiveIntegerOrNull(obj.referenceImageMaxDimensionPixels);
  m.referenceImageMinPixels = positiveIntegerOrNull(obj.referenceImageMinPixels);
  m.referenceImageMaxPixels = positiveIntegerOrNull(obj.referenceImageMaxPixels);
  m.referenceImageMinAspectRatio = positiveNumberOrNull(obj.referenceImageMinAspectRatio);
  m.referenceImageMaxAspectRatio = positiveNumberOrNull(obj.referenceImageMaxAspectRatio);
  // Base64 传图：能力位（官方是否支持）+ 运营开关，缺省均 false
  m.supportsBase64Image = obj.supportsBase64Image === true;
  m.base64ImageEnabled = obj.base64ImageEnabled === true;
  // 音画同出用户开关：仅 video 有意义，缺省 false
  m.supportsAudio = obj.supportsAudio === true;
  m.upstreamAudioField = ['generate_audio', 'audio', 'none'].includes(obj.upstreamAudioField)
    ? obj.upstreamAudioField : undefined;
  m.upstreamResolutionMap = {};
  if (obj.upstreamResolutionMap && typeof obj.upstreamResolutionMap === 'object'
    && !Array.isArray(obj.upstreamResolutionMap)) {
    for (const [source, target] of Object.entries(obj.upstreamResolutionMap)) {
      const normalizedSource = source.trim();
      const normalizedTarget = typeof target === 'string' ? target.trim() : '';
      if (normalizedSource && normalizedTarget) {
        m.upstreamResolutionMap[normalizedSource] = normalizedTarget;
      }
    }
  }
  m.supportsReferenceAudio = obj.supportsReferenceAudio === true;
  m.referenceAudioRequiresGeneratedAudio = obj.referenceAudioRequiresGeneratedAudio !== false;
  m.referenceAudioRequiresVisualInput = obj.referenceAudioRequiresVisualInput === true;
  m.minReferenceAudios = nonNegativeIntegerOrNull(obj.minReferenceAudios);
  if (obj.maxReferenceAudios === null || obj.maxReferenceAudios === undefined) {
    m.maxReferenceAudios = null;
  } else {
    const maxReferenceAudios = Number(obj.maxReferenceAudios);
    m.maxReferenceAudios = Number.isFinite(maxReferenceAudios) && maxReferenceAudios >= -1
      ? Math.trunc(maxReferenceAudios) : null;
  }
  m.referenceAudioMinDurationSeconds = nonNegativeNumberOrNull(obj.referenceAudioMinDurationSeconds);
  m.referenceAudioMaxDurationSeconds = nonNegativeNumberOrNull(obj.referenceAudioMaxDurationSeconds);
  m.referenceAudioMaxTotalDurationSeconds = nonNegativeNumberOrNull(obj.referenceAudioMaxTotalDurationSeconds);
  m.referenceAudioMaxFileSizeMb = positiveNumberOrNull(obj.referenceAudioMaxFileSizeMb);
  m.referenceAudioFormats = normalizeStringList(obj.referenceAudioFormats);
  m.supportsVideoInput = obj.supportsVideoInput === true;
  m.minReferenceVideos = nonNegativeIntegerOrNull(obj.minReferenceVideos);
  m.maxReferenceVideos = countOrNull(obj.maxReferenceVideos);
  m.referenceVideoFormats = normalizeStringList(obj.referenceVideoFormats);
  m.referenceVideoMaxFileSizeMb = positiveNumberOrNull(obj.referenceVideoMaxFileSizeMb);
  m.referenceVideoMinDurationSeconds = nonNegativeNumberOrNull(obj.referenceVideoMinDurationSeconds);
  m.referenceVideoMaxDurationSeconds = nonNegativeNumberOrNull(obj.referenceVideoMaxDurationSeconds);
  m.referenceVideoMaxTotalDurationSeconds = nonNegativeNumberOrNull(obj.referenceVideoMaxTotalDurationSeconds);
  m.referenceVideoMinDimensionPixels = positiveIntegerOrNull(obj.referenceVideoMinDimensionPixels);
  m.referenceVideoMaxDimensionPixels = positiveIntegerOrNull(obj.referenceVideoMaxDimensionPixels);
  m.referenceVideoMinPixels = positiveIntegerOrNull(obj.referenceVideoMinPixels);
  m.referenceVideoMaxPixels = positiveIntegerOrNull(obj.referenceVideoMaxPixels);
  m.referenceVideoMinAspectRatio = positiveNumberOrNull(obj.referenceVideoMinAspectRatio);
  m.referenceVideoMaxAspectRatio = positiveNumberOrNull(obj.referenceVideoMaxAspectRatio);
  m.referenceVideoMinFps = positiveNumberOrNull(obj.referenceVideoMinFps);
  m.referenceVideoMaxFps = positiveNumberOrNull(obj.referenceVideoMaxFps);
  m.maxReferenceMaterials = countOrNull(obj.maxReferenceMaterials);
  m.maxInputOutputVideoDurationSeconds = positiveNumberOrNull(obj.maxInputOutputVideoDurationSeconds);
  const sr = obj.sceneRules || {};
  (['textOnly', 'textToImage', 'imageToImage', 'textToVideo', 'imageToVideo',
    'startEndToVideo', 'referenceToVideo', 'videoToVideo'] as const).forEach(
    (k) => {
      if (sr[k] && typeof sr[k] === 'object') {
        (m.sceneRules as any)[k] = Object.assign({}, (m.sceneRules as any)[k], sr[k]);
      }
    }
  );
  return m;
}

/** capabilityModel + 顶层 default* → capabilityJson 对象 */
export function buildCapabilityJsonObject(form: Model, cap: CapabilityModel): Record<string, any> {
  const t = form.modelType;
  if (t === 'text') {
    const supportsReasoning = cap.supportsReasoning === true;
    return mergeManagedCapability(cap.sourceCapability ?? cap.preservedCapability, {
      supportsReasoning,
      supportsReasoningDisable: supportsReasoning && cap.supportsReasoningDisable === true,
      returnsReasoningContent: supportsReasoning && cap.returnsReasoningContent === true,
      supportsReasoningContent: supportsReasoning && cap.returnsReasoningContent === true,
      supportsReasoningBudget: supportsReasoning && cap.supportsReasoningBudget === true,
      defaultReasoningEnabled: supportsReasoning && cap.defaultReasoningEnabled === true,
      reasoningApiStyle: supportsReasoning ? cap.reasoningApiStyle || null : null,
      outputTokenApiField: supportsReasoning ? cap.outputTokenApiField || null : null,
      allowedReasoningLevels: supportsReasoning ? cap.allowedReasoningLevels || [] : [],
      defaultReasoningLevel: supportsReasoning ? cap.defaultReasoningLevel || null : null,
      defaultReasoningBudgetTokens: supportsReasoning && cap.supportsReasoningBudget
        ? cap.defaultReasoningBudgetTokens ?? null : null,
      maxReasoningBudgetTokens: supportsReasoning && cap.supportsReasoningBudget
        ? cap.maxReasoningBudgetTokens ?? null : null,
      ...(cap.supportsStreaming == null ? {} : { supportsStreaming: cap.supportsStreaming }),
      ...(cap.supportsToolCalling == null ? {} : { supportsToolCalling: cap.supportsToolCalling }),
      ...(cap.supportsChatPrefix == null ? {} : { supportsChatPrefix: cap.supportsChatPrefix }),
      ...(cap.supportsStructuredOutput == null ? {} : { supportsStructuredOutput: cap.supportsStructuredOutput }),
      ...(cap.supportsContextCaching == null ? {} : { supportsContextCaching: cap.supportsContextCaching }),
      ...(cap.supportsBuiltinTools == null ? {} : { supportsBuiltinTools: cap.supportsBuiltinTools }),
      ...buildInputSupportFields(cap.inputModalities),
      outputModalities: Array.from(new Set((cap.outputModalities || ['TEXT']).map((v) => v.toUpperCase()))),
      maxInputImages: (cap.inputModalities || []).includes('IMAGE') ? cap.maxInputImages ?? null : 0,
      maxInputVideos: (cap.inputModalities || []).includes('VIDEO') ? cap.maxInputVideos ?? null : 0,
      maxInputAudios: (cap.inputModalities || []).includes('AUDIO') ? cap.maxInputAudios ?? null : 0,
      maxInputDocuments: (cap.inputModalities || []).includes('DOCUMENT') ? cap.maxInputDocuments ?? null : 0,
      inputImageFormats: (cap.inputModalities || []).includes('IMAGE') ? cap.inputImageFormats || [] : [],
      inputVideoFormats: (cap.inputModalities || []).includes('VIDEO') ? cap.inputVideoFormats || [] : [],
      inputAudioFormats: (cap.inputModalities || []).includes('AUDIO') ? cap.inputAudioFormats || [] : [],
      inputDocumentFormats: (cap.inputModalities || []).includes('DOCUMENT') ? cap.inputDocumentFormats || [] : [],
      maxInputImageFileSizeMb: (cap.inputModalities || []).includes('IMAGE') ? cap.maxInputImageFileSizeMb ?? null : null,
      maxInputVideoFileSizeMb: (cap.inputModalities || []).includes('VIDEO') ? cap.maxInputVideoFileSizeMb ?? null : null,
      maxInputAudioFileSizeMb: (cap.inputModalities || []).includes('AUDIO') ? cap.maxInputAudioFileSizeMb ?? null : null,
      maxInputDocumentFileSizeMb: (cap.inputModalities || []).includes('DOCUMENT') ? cap.maxInputDocumentFileSizeMb ?? null : null,
      maxInputMediaTotalFileSizeMb: (cap.inputModalities || []).some((value) => value !== 'TEXT')
        ? cap.maxInputMediaTotalFileSizeMb ?? null : null,
      inputMediaMaxUrlLength: (cap.inputModalities || []).some((value) => value !== 'TEXT')
        ? cap.inputMediaMaxUrlLength ?? null : null,
      inputMediaAllowedMessageRoles: (cap.inputModalities || []).some((value) => value !== 'TEXT')
        ? cap.inputMediaAllowedMessageRoles || [] : [],
      minInputVideoDurationSeconds: (cap.inputModalities || []).includes('VIDEO') ? cap.minInputVideoDurationSeconds ?? null : null,
      maxInputVideoDurationSeconds: (cap.inputModalities || []).includes('VIDEO') ? cap.maxInputVideoDurationSeconds ?? null : null,
      maxInputVideoTotalDurationSeconds: (cap.inputModalities || []).includes('VIDEO') ? cap.maxInputVideoTotalDurationSeconds ?? null : null,
      minInputAudioDurationSeconds: (cap.inputModalities || []).includes('AUDIO') ? cap.minInputAudioDurationSeconds ?? null : null,
      maxInputAudioDurationSeconds: (cap.inputModalities || []).includes('AUDIO') ? cap.maxInputAudioDurationSeconds ?? null : null,
      maxInputAudioTotalDurationSeconds: (cap.inputModalities || []).includes('AUDIO') ? cap.maxInputAudioTotalDurationSeconds ?? null : null,
      maxInputDocumentPages: (cap.inputModalities || []).includes('DOCUMENT') ? cap.maxInputDocumentPages ?? null : null,
      inputImageMinDimensionPixels: (cap.inputModalities || []).includes('IMAGE') ? cap.inputImageMinDimensionPixels ?? null : null,
      inputImageMaxDimensionPixels: (cap.inputModalities || []).includes('IMAGE') ? cap.inputImageMaxDimensionPixels ?? null : null,
      inputImageHighCountThreshold: (cap.inputModalities || []).includes('IMAGE') ? cap.inputImageHighCountThreshold ?? null : null,
      inputImageHighCountMaxDimensionPixels: (cap.inputModalities || []).includes('IMAGE') ? cap.inputImageHighCountMaxDimensionPixels ?? null : null,
      inputImageMinPixels: (cap.inputModalities || []).includes('IMAGE') ? cap.inputImageMinPixels ?? null : null,
      inputImageMaxPixels: (cap.inputModalities || []).includes('IMAGE') ? cap.inputImageMaxPixels ?? null : null,
      inputImageMinAspectRatio: (cap.inputModalities || []).includes('IMAGE') ? cap.inputImageMinAspectRatio ?? null : null,
      inputImageMaxAspectRatio: (cap.inputModalities || []).includes('IMAGE') ? cap.inputImageMaxAspectRatio ?? null : null,
      inputVideoMinDimensionPixels: (cap.inputModalities || []).includes('VIDEO') ? cap.inputVideoMinDimensionPixels ?? null : null,
      inputVideoMaxDimensionPixels: (cap.inputModalities || []).includes('VIDEO') ? cap.inputVideoMaxDimensionPixels ?? null : null,
      inputVideoMinPixels: (cap.inputModalities || []).includes('VIDEO') ? cap.inputVideoMinPixels ?? null : null,
      inputVideoMaxPixels: (cap.inputModalities || []).includes('VIDEO') ? cap.inputVideoMaxPixels ?? null : null,
      inputVideoMinAspectRatio: (cap.inputModalities || []).includes('VIDEO') ? cap.inputVideoMinAspectRatio ?? null : null,
      inputVideoMaxAspectRatio: (cap.inputModalities || []).includes('VIDEO') ? cap.inputVideoMaxAspectRatio ?? null : null,
      inputVideoMinFps: (cap.inputModalities || []).includes('VIDEO') ? cap.inputVideoMinFps ?? null : null,
      inputVideoMaxFps: (cap.inputModalities || []).includes('VIDEO') ? cap.inputVideoMaxFps ?? null : null,
      contextWindowTokens: cap.contextWindowTokens ?? null,
      maxOutputTokens: cap.maxOutputTokens ?? null,
      maxPromptCharacters: cap.maxPromptCharacters ?? null,
      maxPromptCharactersCjk: cap.maxPromptCharactersCjk ?? null,
      strictSceneRules: cap.strictSceneRules === true,
      sceneRules: { textOnly: { ...cap.sceneRules.textOnly } }
    });
  }
  const obj: Record<string, any> = {
    sizeOptions: cap.sizeOptions.slice(),
    defaultSize: form.defaultSizeCode || null,
    aspectRatioOptions: cap.aspectRatioOptions.slice(),
    defaultAspectRatio: form.defaultAspectRatio || null,
    outputFormatOptions: cap.outputFormatOptions.slice(),
    defaultOutputFormat: cap.defaultOutputFormat || null,
    outputFpsOptions: cap.outputFpsOptions.slice(),
    defaultOutputFps: cap.defaultOutputFps ?? null,
    strictSceneRules: cap.strictSceneRules === true
  };
  writePositiveInteger(obj, 'maxPromptCharacters', cap.maxPromptCharacters);
  writePositiveInteger(obj, 'maxPromptCharactersCjk', cap.maxPromptCharactersCjk);
  if (t === 'image') {
    obj.allowCustomWH = !!cap.allowCustomWH;
    writePositiveInteger(obj, 'minOutputPixels', cap.minOutputPixels);
    writePositiveInteger(obj, 'maxOutputPixels', cap.maxOutputPixels);
    writePositive(obj, 'minOutputAspectRatio', cap.minOutputAspectRatio);
    writePositive(obj, 'maxOutputAspectRatio', cap.maxOutputAspectRatio);
    obj.sceneRules = {
      textToImage: { ...cap.sceneRules.textToImage },
      imageToImage: { ...cap.sceneRules.imageToImage }
    };
    obj.allowedScenes = normalizeAllowedScenes(cap.allowedScenes);
  } else if (t === 'video') {
    obj.durationOptions = cap.durationOptions.slice();
    obj.defaultDurationSeconds = form.defaultDurationSeconds || null;
    obj.sceneRules = {
      textToVideo: { ...cap.sceneRules.textToVideo },
      imageToVideo: { ...cap.sceneRules.imageToVideo },
      startEndToVideo: { ...cap.sceneRules.startEndToVideo },
      referenceToVideo: { ...cap.sceneRules.referenceToVideo },
      videoToVideo: { ...cap.sceneRules.videoToVideo }
    };
    writeNonNegative(obj, 'durationMin', cap.durationMin);
    writeNonNegative(obj, 'durationMax', cap.durationMax);
    obj.audioModeOptions = cap.audioModeOptions.slice();
    obj.audioTypes = cap.audioTypes.slice();
    obj.defaultAudio = cap.defaultAudio == null ? null : cap.defaultAudio === true;
    if (cap.supportsBgm != null) obj.supportsBgm = cap.supportsBgm === true;
    if (cap.supportsVoiceId != null) obj.supportsVoiceId = cap.supportsVoiceId === true;
    if (cap.supportsVoiceControl != null) obj.supportsVoiceControl = cap.supportsVoiceControl === true;
    if (cap.supportsElements != null) obj.supportsElements = cap.supportsElements === true;
    if (cap.maxElements != null) writePositiveInteger(obj, 'maxElements', cap.maxElements);
    else if (Object.prototype.hasOwnProperty.call(cap.sourceCapability || {}, 'maxElements')) obj.maxElements = null;
    if (cap.elementTypeRequired != null) obj.elementTypeRequired = cap.elementTypeRequired === true;
    obj.klingScenario = cap.klingScenario || null;
    obj.videoScenario = cap.videoScenario || null;
    obj.seedanceTaskTypeOptions = cap.seedanceTaskTypeOptions.slice();
    obj.allowedScenes = normalizeAllowedScenes(cap.allowedScenes);
  } else if (t === 'audio') {
    obj.audioOperation = cap.audioOperation || null;
    if (cap.ttsTextRequired != null) obj.ttsTextRequired = cap.ttsTextRequired === true;
    if (cap.ttsVoiceRequired != null) obj.ttsVoiceRequired = cap.ttsVoiceRequired === true;
    obj.builtInVoiceOptions = Array.from(new Set(
      cap.builtInVoiceOptions.map((value) => value.trim()).filter(Boolean)
    ));
    obj.audioFormatOptions = Array.from(new Set(
      cap.audioFormatOptions.map((value) => value.trim().toLowerCase()).filter(Boolean)
    ));
    obj.defaultAudioFormat = cap.defaultAudioFormat || null;
    obj.audioSampleRateOptions = Array.from(new Set(
      cap.audioSampleRateOptions.map((value) => Math.trunc(Number(value)))
        .filter((value) => Number.isFinite(value) && value > 0)
    )).sort((a, b) => a - b);
    writePositiveInteger(obj, 'defaultAudioSampleRate', cap.defaultAudioSampleRate);
    if (cap.supportsAudioStreaming != null) obj.supportsAudioStreaming = cap.supportsAudioStreaming === true;
    if (cap.supportsTimestamp != null) obj.supportsTimestamp = cap.supportsTimestamp === true;
    writeInteger(obj, 'speechRateMin', cap.speechRateMin);
    writeInteger(obj, 'speechRateMax', cap.speechRateMax);
    writeInteger(obj, 'loudnessRateMin', cap.loudnessRateMin);
    writeInteger(obj, 'loudnessRateMax', cap.loudnessRateMax);
    writeInteger(obj, 'pitchMin', cap.pitchMin);
    writeInteger(obj, 'pitchMax', cap.pitchMax);
    obj.emotionOptions = Array.from(new Set(
      cap.emotionOptions.map((value) => value.trim().toLowerCase()).filter(Boolean)
    ));
    if (cap.supportsEmotionScale != null) obj.supportsEmotionScale = cap.supportsEmotionScale === true;
    if (cap.voiceSampleRequired != null) obj.voiceSampleRequired = cap.voiceSampleRequired === true;
    obj.voiceSampleFormats = Array.from(new Set(
      cap.voiceSampleFormats.map((value) => value.trim().toLowerCase()).filter(Boolean)
    ));
    writePositive(obj, 'voiceSampleMaxFileSizeMb', cap.voiceSampleMaxFileSizeMb);
  }
  // 单次最多参考图张数（四态）：留空=null 不写键（厂商默认兜底）；-1=无限；0=禁止；N=上限
  // 只要运营显式设置了值（含 -1 和 0），就写入该键
  if (cap.maxReferenceImages != null) {
    const n = Math.trunc(Number(cap.maxReferenceImages));
    if (Number.isFinite(n) && n >= -1) {
      obj.maxReferenceImages = n;
    }
  } else obj.maxReferenceImages = null;
  // 最少参考图张数：留空=null 不写键（不要求带图）；N>=1 写入，后端建任务前按此拦截缺图请求
  // 0 也允许显式写入（明确声明"不要求带图"，与缺省语义一致但可读性更好）
  if (cap.minReferenceImages != null) {
    const n = Math.trunc(Number(cap.minReferenceImages));
    if (Number.isFinite(n) && n >= 0) {
      obj.minReferenceImages = n;
    }
  } else obj.minReferenceImages = null;
  obj.referenceImageFormats = Array.from(new Set(
    (cap.referenceImageFormats || []).map((value) => value.trim().toLowerCase()).filter(Boolean)
  ));
  writePositive(obj, 'referenceImageMaxFileSizeMb', cap.referenceImageMaxFileSizeMb);
  writePositiveInteger(obj, 'referenceImageMinDimensionPixels', cap.referenceImageMinDimensionPixels);
  writePositiveInteger(obj, 'referenceImageMaxDimensionPixels', cap.referenceImageMaxDimensionPixels);
  writePositiveInteger(obj, 'referenceImageMinPixels', cap.referenceImageMinPixels);
  writePositiveInteger(obj, 'referenceImageMaxPixels', cap.referenceImageMaxPixels);
  writePositive(obj, 'referenceImageMinAspectRatio', cap.referenceImageMinAspectRatio);
  writePositive(obj, 'referenceImageMaxAspectRatio', cap.referenceImageMaxAspectRatio);
  writePositive(obj, 'maxInputMediaTotalFileSizeMb', cap.maxInputMediaTotalFileSizeMb);
  writePositiveInteger(obj, 'inputMediaMaxUrlLength', cap.inputMediaMaxUrlLength);
  obj.inputMediaAllowedMessageRoles = Array.from(new Set(
    (cap.inputMediaAllowedMessageRoles || []).map((value) => value.trim().toLowerCase()).filter(Boolean)
  ));
  writePositiveInteger(obj, 'inputImageHighCountThreshold', cap.inputImageHighCountThreshold);
  writePositiveInteger(obj, 'inputImageHighCountMaxDimensionPixels', cap.inputImageHighCountMaxDimensionPixels);
  // Base64 传图：官方支持才写能力位；仅在支持时才写启用开关（不支持时强制丢弃 enabled，防脏数据）
  obj.supportsBase64Image = cap.supportsBase64Image === true;
  obj.base64ImageEnabled = cap.supportsBase64Image === true && cap.base64ImageEnabled === true;
  // 音画同出：video 必写布尔位；true=C 端可开关，false=禁止选择
  if (t === 'video') {
    obj.supportsAudio = cap.supportsAudio === true;
    if (form.protocol === 'configurable-async-video'
      && cap.supportsAudio === true && cap.upstreamAudioField) {
      obj.upstreamAudioField = cap.upstreamAudioField;
    } else obj.upstreamAudioField = null;
    if (form.protocol === 'configurable-async-video') {
      const resolutionMap: Record<string, string> = {};
      for (const [source, target] of Object.entries(cap.upstreamResolutionMap || {})) {
        const configuredSource = cap.sizeOptions.find((option) => option.trim().toLowerCase() === source.trim().toLowerCase());
        const normalizedTarget = target.trim();
        if (configuredSource && normalizedTarget) {
          resolutionMap[configuredSource] = normalizedTarget;
        }
      }
      if (Object.keys(resolutionMap).length) {
        obj.upstreamResolutionMap = resolutionMap;
      } else obj.upstreamResolutionMap = null;
    } else obj.upstreamResolutionMap = null;
    obj.supportsReferenceAudio = cap.supportsReferenceAudio === true;
    if (cap.supportsReferenceAudio === true) {
      obj.referenceAudioRequiresGeneratedAudio = cap.referenceAudioRequiresGeneratedAudio !== false;
      obj.referenceAudioRequiresVisualInput = cap.referenceAudioRequiresVisualInput === true;
      writeNonNegativeInteger(obj, 'minReferenceAudios', cap.minReferenceAudios);
      const maxReferenceAudios = cap.maxReferenceAudios == null ? null : Math.trunc(Number(cap.maxReferenceAudios));
      obj.maxReferenceAudios = maxReferenceAudios != null
        && Number.isFinite(maxReferenceAudios) && maxReferenceAudios >= -1 ? maxReferenceAudios : null;
      writeNonNegative(obj, 'referenceAudioMinDurationSeconds', cap.referenceAudioMinDurationSeconds);
      writeNonNegative(obj, 'referenceAudioMaxDurationSeconds', cap.referenceAudioMaxDurationSeconds);
      writeNonNegative(obj, 'referenceAudioMaxTotalDurationSeconds', cap.referenceAudioMaxTotalDurationSeconds);
      obj.referenceAudioFormats = Array.from(new Set(
        cap.referenceAudioFormats.map((v) => v.trim().toLowerCase()).filter(Boolean)
      ));
      writePositive(obj, 'referenceAudioMaxFileSizeMb', cap.referenceAudioMaxFileSizeMb);
    } else {
      obj.referenceAudioRequiresGeneratedAudio = null;
      obj.referenceAudioRequiresVisualInput = null;
      obj.minReferenceAudios = null;
      obj.maxReferenceAudios = null;
      obj.referenceAudioMinDurationSeconds = null;
      obj.referenceAudioMaxDurationSeconds = null;
      obj.referenceAudioMaxTotalDurationSeconds = null;
      obj.referenceAudioFormats = null;
      obj.referenceAudioMaxFileSizeMb = null;
    }
    obj.supportsVideoInput = cap.supportsVideoInput === true;
    if (cap.supportsVideoInput === true) {
      writeNonNegativeInteger(obj, 'minReferenceVideos', cap.minReferenceVideos);
      const maxReferenceVideos = cap.maxReferenceVideos == null ? null : Math.trunc(Number(cap.maxReferenceVideos));
      obj.maxReferenceVideos = maxReferenceVideos != null
        && Number.isFinite(maxReferenceVideos) && maxReferenceVideos >= -1 ? maxReferenceVideos : null;
      obj.referenceVideoFormats = Array.from(new Set(
        (cap.referenceVideoFormats || []).map((value) => value.trim().toLowerCase()).filter(Boolean)
      ));
      writePositive(obj, 'referenceVideoMaxFileSizeMb', cap.referenceVideoMaxFileSizeMb);
      writeNonNegative(obj, 'referenceVideoMinDurationSeconds', cap.referenceVideoMinDurationSeconds);
      writeNonNegative(obj, 'referenceVideoMaxDurationSeconds', cap.referenceVideoMaxDurationSeconds);
      writeNonNegative(obj, 'referenceVideoMaxTotalDurationSeconds', cap.referenceVideoMaxTotalDurationSeconds);
      writePositiveInteger(obj, 'referenceVideoMinDimensionPixels', cap.referenceVideoMinDimensionPixels);
      writePositiveInteger(obj, 'referenceVideoMaxDimensionPixels', cap.referenceVideoMaxDimensionPixels);
      writePositiveInteger(obj, 'referenceVideoMinPixels', cap.referenceVideoMinPixels);
      writePositiveInteger(obj, 'referenceVideoMaxPixels', cap.referenceVideoMaxPixels);
      writePositive(obj, 'referenceVideoMinAspectRatio', cap.referenceVideoMinAspectRatio);
      writePositive(obj, 'referenceVideoMaxAspectRatio', cap.referenceVideoMaxAspectRatio);
      writePositive(obj, 'referenceVideoMinFps', cap.referenceVideoMinFps);
      writePositive(obj, 'referenceVideoMaxFps', cap.referenceVideoMaxFps);
    } else {
      obj.minReferenceVideos = null;
      obj.maxReferenceVideos = null;
      obj.referenceVideoFormats = null;
      obj.referenceVideoMaxFileSizeMb = null;
      obj.referenceVideoMinDurationSeconds = null;
      obj.referenceVideoMaxDurationSeconds = null;
      obj.referenceVideoMaxTotalDurationSeconds = null;
      obj.referenceVideoMinDimensionPixels = null;
      obj.referenceVideoMaxDimensionPixels = null;
      obj.referenceVideoMinPixels = null;
      obj.referenceVideoMaxPixels = null;
      obj.referenceVideoMinAspectRatio = null;
      obj.referenceVideoMaxAspectRatio = null;
      obj.referenceVideoMinFps = null;
      obj.referenceVideoMaxFps = null;
    }
    if (cap.maxReferenceMaterials != null) {
      const maxReferenceMaterials = Math.trunc(Number(cap.maxReferenceMaterials));
      if (Number.isFinite(maxReferenceMaterials) && maxReferenceMaterials >= -1) {
        obj.maxReferenceMaterials = maxReferenceMaterials;
      }
    } else obj.maxReferenceMaterials = null;
    writePositive(obj, 'maxInputOutputVideoDurationSeconds', cap.maxInputOutputVideoDurationSeconds);
  }
  const merged = mergeManagedCapability(cap.sourceCapability ?? cap.preservedCapability, obj);
  if (t === 'video' && cap.upstreamAudioField === 'none') {
    merged.forceGenerateAudio = null;
  }
  if (t === 'video' && form.protocol === 'configurable-async-video'
    && obj.upstreamResolutionMap && Object.keys(obj.upstreamResolutionMap).length) {
    merged.upstreamResolution = null;
  }
  return merged;
}

/** paramMappings 表格行 → paramMappingJson 对象 */
export function buildParamMappingJsonObject(rows: ParamMapping[]): Record<string, any> {
  const out: Record<string, any> = {};
  for (const r of rows) {
    if (!r.paramName || !r.provider) continue;
    if (!out[r.paramName]) out[r.paramName] = {};
    out[r.paramName][r.provider] = r.providerParamName || '';
  }
  return out;
}

/** paramMappingJson 字符串 → 表格行 */
export function parseParamMappingJsonToRows(jsonStr?: string | null): ParamMapping[] {
  const rows: ParamMapping[] = [];
  if (!jsonStr) return rows;
  let obj: any;
  try {
    obj = JSON.parse(jsonStr);
  } catch {
    return rows;
  }
  if (!obj || typeof obj !== 'object') return rows;
  Object.keys(obj).forEach((paramName) => {
    const inner = obj[paramName];
    if (inner && typeof inner === 'object') {
      Object.keys(inner).forEach((provider) => {
        rows.push({
          paramName,
          provider,
          providerParamName: inner[provider] || ''
        });
      });
    }
  });
  return rows;
}

/** billingRuleJson 字符串 → { meterType, skuEditData } */
export function parseBillingRuleJson(json: string): {
  meterType?: string;
  skuEditData: SkuEditData;
} {
  const result: { meterType?: string; skuEditData: SkuEditData } = {
    skuEditData: {
      charToTokenRatio: 2,
      usagePricingMode: 'AGGREGATE',
      allowExtraCharge: false,
      skuList: []
    }
  };
  if (!json) return result;
  try {
    const rule = JSON.parse(json);
    if (!rule || typeof rule !== 'object' || Array.isArray(rule)) {
      result.skuEditData.parseError = true;
      return result;
    }
    result.skuEditData.preservedBillingRule = cloneJsonRecord(rule);
    if (rule.meterType) result.meterType = rule.meterType;
    if (rule.settleRule != null
      && (!rule.settleRule || typeof rule.settleRule !== 'object' || Array.isArray(rule.settleRule))) {
      result.skuEditData.parseError = true;
    }
    if (rule.settleRule?.charToTokenRatio)
      result.skuEditData.charToTokenRatio = rule.settleRule.charToTokenRatio;
    result.skuEditData.usagePricingMode = rule.settleRule?.usagePricingMode === 'BUCKETED'
      ? 'BUCKETED' : 'AGGREGATE';
    result.skuEditData.allowExtraCharge = rule.settleRule?.allowExtraCharge === true;
    if (rule.settleRule?.imageOutputPixelTiers != null) {
      const tiers = rule.settleRule.imageOutputPixelTiers;
      if (!Array.isArray(tiers) || tiers.some((tier: any) => !tier || typeof tier !== 'object'
        || (tier.maxPixels != null && (!Number.isSafeInteger(tier.maxPixels) || tier.maxPixels <= 0))
        || (tier.price != null && (typeof tier.price !== 'number' || tier.price < 0)))) {
        result.skuEditData.parseError = true;
      } else {
        result.skuEditData.imageOutputPixelTiers = tiers.map((tier: any) => ({
          maxPixels: tier.maxPixels == null ? null : tier.maxPixels,
          price: tier.price == null ? null : tier.price
        }));
      }
    }
    // 规则级输入媒体计费（图片/视频输入附加费）
    if (isMalformedInputPricing(rule.inputPricing)) result.skuEditData.parseError = true;
    result.skuEditData.inputPricing = normalizeInputPricing(rule.inputPricing);
    if (rule.skus != null && !Array.isArray(rule.skus)) {
      result.skuEditData.parseError = true;
    }
    if (Array.isArray(rule.skus)) {
      const numericFields = [
        'priority', 'price', 'outputPixelsPerUnit', 'pricePerSecond', 'pricePerChar', 'fixedSurcharge',
        'inputPricePerMillion', 'outputPricePerMillion', 'cachedInputPricePerMillion',
        'cacheWritePricePerMillion', 'reasoningPricePerMillion'
      ];
      const malformedSku = rule.skus.some((s: unknown) => {
        if (!s || typeof s !== 'object' || Array.isArray(s)) return true;
        const item = s as Record<string, unknown>;
        if (item.match != null && (typeof item.match !== 'object' || Array.isArray(item.match))) return true;
        if (item.skuCode != null && typeof item.skuCode !== 'string') return true;
        if (item.skuName != null && typeof item.skuName !== 'string') return true;
        if (item.meterType != null && typeof item.meterType !== 'string') return true;
        if (item.remark != null && typeof item.remark !== 'string') return true;
        if (item.enabled != null && typeof item.enabled !== 'boolean') return true;
        if (isMalformedInputPricing(item.inputPricing)) return true;
        return numericFields.some((field) => {
          if (item[field] == null) return false;
          const validScalar = ['number', 'string'].includes(typeof item[field]);
          const numericValue = Number(item[field]);
          return !validScalar || !Number.isFinite(numericValue)
            || (field === 'priority' ? numericValue < 1 : numericValue < 0);
        });
      });
      if (malformedSku) result.skuEditData.parseError = true;
      result.skuEditData.skuList = rule.skus
        .filter((s: unknown) => !!s && typeof s === 'object' && !Array.isArray(s))
        .map((s: any) => ({
        preservedSku: cloneJsonRecord(s),
        skuCode: typeof s.skuCode === 'string' ? s.skuCode : '',
        skuName: typeof s.skuName === 'string' ? s.skuName : '',
        meterType: typeof s.meterType === 'string' ? s.meterType : null,
        enabled: s.enabled !== false,
        priority: s.priority != null ? s.priority : 1,
        match: s.match && typeof s.match === 'object' && !Array.isArray(s.match) ? { ...s.match } : {},
        price: s.price != null ? s.price : null,
        outputPixelsPerUnit: s.outputPixelsPerUnit != null ? s.outputPixelsPerUnit : null,
        pricePerSecond: s.pricePerSecond != null ? s.pricePerSecond : null,
        pricePerChar: s.pricePerChar != null ? s.pricePerChar : null,
        fixedSurcharge: s.fixedSurcharge != null ? s.fixedSurcharge : null,
        inputPricePerMillion: s.inputPricePerMillion != null ? s.inputPricePerMillion : null,
        outputPricePerMillion: s.outputPricePerMillion != null ? s.outputPricePerMillion : null,
        cachedInputPricePerMillion: s.cachedInputPricePerMillion != null ? s.cachedInputPricePerMillion : null,
        cacheWritePricePerMillion: s.cacheWritePricePerMillion != null ? s.cacheWritePricePerMillion : null,
        reasoningPricePerMillion: s.reasoningPricePerMillion != null ? s.reasoningPricePerMillion : null,
        inputPricing: normalizeInputPricing(s.inputPricing),
        remark: typeof s.remark === 'string' ? s.remark : ''
      }));
    }
  } catch {
    result.skuEditData.parseError = true;
  }
  return result;
}

/** JSON 规则深拷贝，隔离表单编辑与原始保留对象。 */
function cloneJsonRecord(raw: unknown): Record<string, any> | undefined {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined;
  try {
    return JSON.parse(JSON.stringify(raw)) as Record<string, any>;
  } catch {
    return undefined;
  }
}

function isMalformedInputPricing(raw: unknown): boolean {
  if (raw == null) return false;
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return true;
  const root = raw as Record<string, unknown>;
  for (const [segmentName, numericKeys] of [
    ['image', ['unitPrice', 'freeCount', 'maxCount']],
    ['video', ['unitPrice', 'maxSeconds', 'maxCount']]
  ] as const) {
    const segment = root[segmentName];
    if (segment == null) continue;
    if (!segment || typeof segment !== 'object' || Array.isArray(segment)) return true;
    for (const key of numericKeys) {
      const value = (segment as Record<string, unknown>)[key];
      if (value == null) continue;
      if (!['number', 'string'].includes(typeof value)
        || !Number.isFinite(Number(value)) || Number(value) < 0) return true;
    }
  }
  return false;
}

/** 解析 inputPricing 对象（image/video 两段），非法或全空返回 null */
export function normalizeInputPricing(raw: any): InputPricing | null {
  if (!raw || typeof raw !== 'object') return null;
  const out: InputPricing = {};
  if (raw.image && typeof raw.image === 'object') {
    out.image = {
      unitPrice: raw.image.unitPrice != null ? Number(raw.image.unitPrice) : null,
      freeCount: raw.image.freeCount != null ? Number(raw.image.freeCount) : null,
      maxCount: raw.image.maxCount != null ? Number(raw.image.maxCount) : null
    };
  }
  if (raw.video && typeof raw.video === 'object') {
    out.video = {
      unitPrice: raw.video.unitPrice != null ? Number(raw.video.unitPrice) : null,
      maxSeconds: raw.video.maxSeconds != null ? Number(raw.video.maxSeconds) : null,
      maxCount: raw.video.maxCount != null ? Number(raw.video.maxCount) : null
    };
  }
  return out.image || out.video ? out : null;
}

/** inputPricing → 序列化对象（剔除空段与空字段），全空返回 undefined 以便 JSON 省略该键 */
function mergeInputPricing(preserved: unknown, current?: InputPricing | null): any {
  const out = cloneJsonRecord(preserved) || {};
  mergeInputPricingSegment(out, 'image', current?.image, ['unitPrice', 'freeCount', 'maxCount']);
  mergeInputPricingSegment(out, 'video', current?.video, ['unitPrice', 'maxSeconds', 'maxCount']);
  return Object.keys(out).length > 0 ? out : undefined;
}

function mergeInputPricingSegment(
  target: Record<string, any>,
  segmentName: 'image' | 'video',
  current: Record<string, any> | null | undefined,
  managedKeys: string[]
) {
  const preservedSegment = cloneJsonRecord(target[segmentName]);
  const hasCurrentManagedValue = managedKeys.some((key) => {
    const value = current?.[key];
    return key === 'unitPrice' ? value != null : value != null && Number(value) > 0;
  });
  if (!hasCurrentManagedValue) {
    const remaining = preservedSegment || {};
    const hadManagedValue = managedKeys.some((key) => Object.prototype.hasOwnProperty.call(remaining, key));
    managedKeys.forEach((key) => {
      if (Object.prototype.hasOwnProperty.call(remaining, key)) remaining[key] = null;
    });
    // null 是服务端无损合并协议的显式删除标记；没有旧值时不产生无意义空段。
    if (Object.keys(remaining).length > 0 || hadManagedValue) target[segmentName] = remaining;
    else delete target[segmentName];
    return;
  }
  const next = preservedSegment || {};
  managedKeys.forEach((key) => {
    if (Object.prototype.hasOwnProperty.call(next, key)) next[key] = null;
    else delete next[key];
  });
  if (current?.unitPrice != null) next.unitPrice = Number(current.unitPrice);
  if (current?.freeCount != null && Number(current.freeCount) >= 0) next.freeCount = Math.trunc(Number(current.freeCount));
  if (current?.maxCount != null && Number(current.maxCount) > 0) next.maxCount = Math.trunc(Number(current.maxCount));
  if (current?.maxSeconds != null && Number(current.maxSeconds) > 0) next.maxSeconds = Number(current.maxSeconds);
  target[segmentName] = next;
}

/** skuEditData + modelForm → billingRuleJson 字符串 */
export function buildBillingRuleJson(
  form: Model,
  skuData: SkuEditData,
  isTokenBilling: boolean
): string {
  const isText = form.modelType === 'text';
  const chargeType = isText ? 'TEXT' : (form.modelType || '').toUpperCase();
  const meterType = form.meterType || inferMeterType(form.modelType);
  const skus = skuData.skuList.map((s) => {
    const sku: any = cloneJsonRecord(s.preservedSku) || {};
    sku.skuCode = s.skuCode.trim();
    sku.skuName = s.skuName.trim();
    if (s.meterType) sku.meterType = s.meterType;
    else if (Object.prototype.hasOwnProperty.call(sku, 'meterType')) sku.meterType = null;
    sku.enabled = s.enabled;
    sku.priority = s.priority;
    const originalMatch = cloneJsonRecord(s.preservedSku?.match) || {};
    const currentMatch = JSON.parse(JSON.stringify(s.match || {}));
    // 服务端递归合并时，缺失键表示保留；管理员显式删除条件必须发送 null tombstone。
    Object.keys(originalMatch).forEach((key) => {
      if (!Object.prototype.hasOwnProperty.call(currentMatch, key)) currentMatch[key] = null;
    });
    sku.match = currentMatch;
    sku.remark = s.remark || '';
    [
      'price', 'outputPixelsPerUnit', 'pricePerSecond', 'pricePerChar', 'fixedSurcharge', 'inputPricePerMillion',
      'outputPricePerMillion', 'cachedInputPricePerMillion',
      'cacheWritePricePerMillion', 'reasoningPricePerMillion'
    ].forEach((key) => {
      if (Object.prototype.hasOwnProperty.call(sku, key)) sku[key] = null;
    });
    const skuMeterType = s.meterType || (isTokenBilling ? 'TOKEN' : meterType);
    if (skuMeterType === 'TOKEN') {
      sku.inputPricePerMillion = s.inputPricePerMillion == null ? null : Number(s.inputPricePerMillion);
      sku.outputPricePerMillion = s.outputPricePerMillion == null ? null : Number(s.outputPricePerMillion);
      if (s.cachedInputPricePerMillion != null) sku.cachedInputPricePerMillion = Number(s.cachedInputPricePerMillion);
      if (s.cacheWritePricePerMillion != null) sku.cacheWritePricePerMillion = Number(s.cacheWritePricePerMillion);
      if (s.reasoningPricePerMillion != null) sku.reasoningPricePerMillion = Number(s.reasoningPricePerMillion);
    } else {
      sku.price = s.price == null ? null : Number(s.price);
      if (skuMeterType === 'PER_IMAGE' && s.outputPixelsPerUnit != null) {
        sku.outputPixelsPerUnit = Number(s.outputPixelsPerUnit);
      }
      // 按秒计费：每秒单价必须随 SKU 落库，否则结算兜底会用 price/durationMax 反推出错价
      if (skuMeterType === 'PER_SECOND' && s.pricePerSecond != null && Number(s.pricePerSecond) >= 0) {
        sku.pricePerSecond = Number(s.pricePerSecond);
      }
      // 按字符计费（TTS）：每字符单价
      if (skuMeterType === 'PER_CHAR' && s.pricePerChar != null && Number(s.pricePerChar) >= 0) {
        sku.pricePerChar = Number(s.pricePerChar);
      }
    }
    if (['PER_CHAR', 'SKU_PACKAGE'].includes(skuMeterType)
      && s.fixedSurcharge != null && Number(s.fixedSurcharge) >= 0) {
      sku.fixedSurcharge = Number(s.fixedSurcharge);
    }
    // SKU 级输入媒体计费覆盖（如视频输入单价随分辨率变化）
    const skuInput = mergeInputPricing(sku.inputPricing, s.inputPricing);
    if (skuInput) sku.inputPricing = skuInput;
    else if (Object.prototype.hasOwnProperty.call(sku, 'inputPricing')) sku.inputPricing = null;
    return sku;
  });
  const preservedRule = cloneJsonRecord(skuData.preservedBillingRule);
  const rule: any = preservedRule || {
    preHold: true,
    matchStrategy: 'FIRST_HIT',
    params: [],
    settleRule: {
      settleMode: 'REFUND_ONLY',
      usageSource: 'PROVIDER_USAGE',
      allowRefund: true
    }
  };
  rule.mode = 'SKU';
  rule.meterType = meterType;
  rule.chargeType = chargeType;
  rule.skus = skus;
  const settleRule = cloneJsonRecord(rule.settleRule) || {};
  settleRule.charToTokenRatio = skuData.charToTokenRatio || 2;
  settleRule.allowExtraCharge = skuData.allowExtraCharge === true;
  settleRule.usagePricingMode = skuData.usagePricingMode || 'AGGREGATE';
  if (skuData.imageOutputPixelTiers) {
    if (skuData.imageOutputPixelTiers.length > 0) {
      settleRule.imageOutputPixelTiers = skuData.imageOutputPixelTiers;
    } else {
      delete settleRule.imageOutputPixelTiers;
    }
  }
  rule.settleRule = settleRule;
  // 规则级输入媒体计费（图片/视频输入附加费默认值）
  const ruleInput = mergeInputPricing(rule.inputPricing, skuData.inputPricing);
  if (ruleInput) rule.inputPricing = ruleInput;
  else if (Object.prototype.hasOwnProperty.call(rule, 'inputPricing')) rule.inputPricing = null;
  return JSON.stringify(rule);
}

/**
 * 从 scheduleStrategyJson 中解析并发上限 maxConcurrency（供应商行与模型行共用同一键名）。
 * 约定：<=0 / 不配 / 字段为空 → 不限制（返回 null）。
 */
export function parseMaxConcurrency(jsonStr?: string | null): number | null {
  if (!jsonStr) return null;
  try {
    const obj = JSON.parse(jsonStr);
    const v = Number(obj?.maxConcurrency);
    return Number.isFinite(v) && v > 0 ? v : null;
  } catch {
    return null;
  }
}

/**
 * 把 maxConcurrency 合并写入 scheduleStrategyJson，保留原 JSON 里的其它键。
 * value 为 null / undefined / <=0 时表示"不限制"，会移除该键。
 * 返回的字符串：若合并后为空对象则返回 null。
 */
export function mergeMaxConcurrency(
  jsonStr: string | null | undefined,
  value: number | null | undefined
): string | null {
  let obj: Record<string, any> = {};
  if (jsonStr && String(jsonStr).trim()) {
    try {
      const parsed = JSON.parse(jsonStr);
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        obj = parsed;
      }
    } catch {
      /* 原值非法 JSON：丢弃重建，避免污染 */
    }
  }
  if (value != null && Number(value) > 0) {
    obj.maxConcurrency = Number(value);
  } else {
    delete obj.maxConcurrency;
  }
  // 清除已废弃的并发键：并发上限统一收口到 maxConcurrency，
  // 若不删则历史 JSON 里的旧键会被原样回写，留下"看着有配置但没人读"的死数据
  delete obj.providerConcurrency;
  delete obj.modelConcurrency;
  return Object.keys(obj).length ? JSON.stringify(obj) : null;
}

/** 新建一条空 SKU */
export function makeEmptySku(isTokenBilling: boolean, priority: number): Sku {
  return {
    skuCode: '',
    skuName: '',
    enabled: true,
    priority,
    match: isTokenBilling ? { inputTokensMin: 0, inputTokensMax: 32000 } : {},
    price: null,
    pricePerSecond: null,
    pricePerChar: null,
    fixedSurcharge: null,
    inputPricePerMillion: null,
    outputPricePerMillion: null,
    cachedInputPricePerMillion: null,
    cacheWritePricePerMillion: null,
    reasoningPricePerMillion: null,
    remark: ''
  };
}
