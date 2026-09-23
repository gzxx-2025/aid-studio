import type { ConfigValue } from './StructuredValueEditor';

export interface ModelParameter {
  name: string;
  label: string;
  type: 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array';
  widget?: string;
  description?: string;
  unit?: string;
  required?: boolean;
  defaultValue?: ConfigValue;
  minimum?: number;
  maximum?: number;
  step?: number;
  choices?: ConfigValue[];
  properties?: ModelParameter[];
  items?: ModelParameter;
  materialRole?: string;
  formats?: string[];
  minDurationSeconds?: number;
  maxDurationSeconds?: number;
  maxTotalDurationSeconds?: number;
  maxFileSizeMb?: number;
  maxFileSizeBytes?: number;
  clipDurationSeconds?: number;
}
export interface ModelRuleCondition {
  match?: 'all' | 'any';
  conditions?: ModelRuleCondition[];
  field: string;
  operator: string;
  value?: ConfigValue;
  valueField?: string;
}
export interface ModelParameterRule {
  label: string;
  match: 'all' | 'any';
  conditions: ModelRuleCondition[];
  actions: Array<{ field: string; operator: string; value?: ConfigValue; valueField?: string }>;
}
export const MATERIAL_STATISTICS = [
  ['materials.imageCount', '参考图片数量'], ['materials.videoCount', '参考视频数量'], ['materials.audioCount', '参考音频数量'],
  ['materials.totalCount', '素材总数量'], ['materials.videoDurationSeconds', '输入视频总时长（秒）'], ['materials.audioDurationSeconds', '输入音频总时长（秒）']
].map(([value, label]) => ({ value, label }));
export interface ModelProtocolBinding {
  code: string;
  protocol: string;
  upstreamModel?: string;
  apiSuffix?: string;
  apiVersion?: string;
  taskQuerySuffix?: string;
  defaultBinding: boolean;
  enabled: boolean;
  capability?: Record<string, ConfigValue>;
  presentation?: Record<string, ConfigValue>;
  fixedParameters?: Record<string, ConfigValue>;
  parameterMapping?: Record<string, ConfigValue>;
  mappings?: Array<{ source: string; target: string; type?: string }>;
  billingMode?: string;
  billingRule?: Record<string, ConfigValue>;
  costCredits?: number;
}
export interface ModelCapabilityDefinition {
  code: string;
  label: string;
  generateMode: string;
  enabled: boolean;
  defaultCapability: boolean;
  evidenceStatus?: string;
  sourceUrls?: string[];
  parameters: ModelParameter[];
  rules: ModelParameterRule[];
  presentation?: Record<string, ConfigValue>;
  bindings: ModelProtocolBinding[];
}

export const CAPABILITY_OPTIONS = [
  ['text', '文本生成'], ['text_to_image', '文生图'], ['image_to_image', '图生图'],
  ['image_edit', '图片编辑'], ['image_inpainting', '区域编辑'], ['image_outpainting', '图片扩展'],
  ['image_layer_decomposition', '图层分离'],
  ['image_upscale', '图片高清'], ['text_to_video', '文生视频'],
  ['image_to_video', '首帧图生视频'], ['start_end_to_video', '首尾帧'], ['reference_to_video', '多参考'],
  ['last_frame_to_video', '尾帧图生视频'],
  ['feature_video', '视频特征参考'], ['video_edit', '视频编辑'], ['video_extend', '视频延长'],
  ['video_to_video', '视频转换'], ['lip_sync', '对口型'], ['audio', '语音合成'], ['voice_clone', '音色复刻']
].map(([value, label]) => ({ value, label }));

export const parameterPaths = (fields: ModelParameter[], prefix = ''): string[] => fields.flatMap((field) => [
  prefix + field.name, ...parameterPaths(field.properties || [], `${prefix}${field.name}.`)
]);

export function definitionErrors(definitions: ModelCapabilityDefinition[]): string[] {
  const errors: string[] = [];
  if (!definitions.length) errors.push('请添加模型能力');
  if (definitions.filter((d) => d.enabled && d.defaultCapability).length !== 1) errors.push('请选择一个默认能力');
  const codes = new Set<string>();
  const checkFields = (fields: ModelParameter[], prefix: string) => {
    const names = new Set<string>();
    for (const field of fields) {
      if (!/^[a-zA-Z][a-zA-Z0-9_]*$/.test(field.name) || names.has(field.name)) errors.push(`${prefix}：参数名称无效或重复`);
      names.add(field.name);
      if (field.minimum != null && field.maximum != null && field.minimum > field.maximum) errors.push(`${prefix}${field.label}：最小值不能大于最大值`);
      if (field.step != null && field.step <= 0) errors.push(`${prefix}${field.label}：步长必须大于零`);
      checkFields(field.properties || [], `${prefix}${field.label} / `);
      if (field.type === 'array' && !field.items) errors.push(`${prefix}${field.label}：请配置列表项`);
      if (field.items) checkFields([field.items], `${prefix}${field.label} / `);
    }
  };
  for (const definition of definitions) {
    if (!/^[a-z][a-z0-9_-]{0,95}$/.test(definition.code) || codes.has(definition.code)) errors.push('能力编码无效或重复');
    codes.add(definition.code);
    if (!definition.label || !definition.generateMode) errors.push('请填写能力名称并选择能力类型');
    if (definition.enabled && definition.bindings.filter((r) => r.enabled && r.defaultBinding).length !== 1) errors.push(`${definition.label}：请选择一个默认协议`);
    if (definition.bindings.some((r) => !r.protocol || !/^[a-z][a-z0-9_-]{0,95}$/.test(r.code))) errors.push(`${definition.label}：调用协议配置不完整`);
    checkFields(definition.parameters, `${definition.label} / `);
    const paths = [...parameterPaths(definition.parameters), ...MATERIAL_STATISTICS.map((item) => item.value)];
    const validCondition = (condition: ModelRuleCondition, depth = 0): boolean => depth <= 8 && (condition.match
      ? Boolean(condition.conditions?.length) && condition.conditions!.every((child) => validCondition(child, depth + 1))
      : paths.includes(condition.field));
    for (const rule of definition.rules) {
      if (!rule.conditions.length || !rule.actions.length || !rule.conditions.every((condition) => validCondition(condition)) || rule.actions.some((action) => !paths.includes(action.field))) errors.push(`${definition.label}：条件规则字段不完整`);
    }
  }
  return [...new Set(errors)];
}
