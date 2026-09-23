import type { ModelParameter } from './modelDefinition';

const definitions: Record<string, [string, ModelParameter['type']]> = {
  prompt: ['提示词', 'string'], options: ['协议扩展参数', 'object'], messages: ['对话消息', 'array'],
  size: ['输出规格', 'string'], negativePrompt: ['反向提示词', 'string'], referenceImageUrl: ['参考图片', 'string'],
  maskImageUrl: ['蒙版图片', 'string'], maskEncoding: ['蒙版选区语义', 'string'],
  targetWidth: ['目标宽度', 'integer'], targetHeight: ['目标高度', 'integer'],
  sourceX: ['原图左上角 X', 'integer'], sourceY: ['原图左上角 Y', 'integer'],
  brightness: ['亮度', 'number'], colorTemperature: ['色温', 'integer'],
  keyLightAzimuth: ['主光方位角', 'number'], keyLightElevation: ['主光仰角', 'number'],
  rimLightEnabled: ['轮廓光', 'boolean'], rimLightAzimuth: ['轮廓光方位角', 'number'],
  rimLightElevation: ['轮廓光仰角', 'number'],
  expectedImageCount: ['生成图片数量', 'integer'], imageUrl: ['首帧图片', 'string'], durationSeconds: ['输出时长（秒）', 'integer'],
  aspectRatio: ['画面比例', 'string'], audio: ['生成声音', 'boolean'], bgm: ['生成背景音乐', 'boolean'],
  audioType: ['声音类型', 'string'], voiceId: ['视频音色', 'string'], referenceAudios: ['参考音频列表', 'array'],
  referenceVideoRecordIds: ['参考视频记录', 'array'], ttsText: ['配音文本', 'string'], voiceCode: ['音色', 'string'],
  language: ['语言', 'string'], emotion: ['情绪', 'string'], emotionScale: ['情绪强度', 'integer'],
  speechRate: ['语速', 'integer'], loudnessRate: ['音量', 'integer'], pitch: ['音调', 'integer'],
  audioFormat: ['输出音频格式', 'string'], sampleRate: ['采样率', 'integer'], enableTimestamp: ['时间戳', 'boolean'],
  reasoningEnabled: ['启用思考', 'boolean'], reasoningLevel: ['思考强度', 'string'],
  reasoningBudgetTokens: ['思考预算', 'integer'], includeReasoning: ['返回思考', 'boolean']
};
const fields: Record<string, string[]> = {
  text: ['prompt', 'messages', 'options', 'reasoningEnabled', 'reasoningLevel', 'reasoningBudgetTokens', 'includeReasoning'],
  image: ['prompt', 'size', 'negativePrompt', 'referenceImageUrl', 'maskImageUrl', 'maskEncoding',
    'targetWidth', 'targetHeight', 'sourceX', 'sourceY', 'brightness', 'colorTemperature',
    'keyLightAzimuth', 'keyLightElevation', 'rimLightEnabled', 'rimLightAzimuth', 'rimLightElevation',
    'expectedImageCount', 'options'],
  video: ['prompt', 'imageUrl', 'durationSeconds', 'aspectRatio', 'audio', 'bgm', 'audioType', 'voiceId', 'referenceAudios', 'referenceVideoRecordIds', 'options'],
  audio: ['ttsText', 'voiceCode', 'language', 'emotion', 'emotionScale', 'speechRate', 'loudnessRate', 'pitch', 'audioFormat', 'sampleRate', 'enableTimestamp', 'options']
};

export function requestFieldOptions(modelType: string) {
  return (fields[modelType] || []).map((name) => ({ value: name, label: `${definitions[name][0]}（${name}）` }));
}

export function requestFieldDefinition(name: string): ModelParameter {
  const [label, type] = definitions[name] || [name, 'string'];
  if (name === 'referenceImageUrl') return { name, label, type, widget: 'image', materialRole: 'reference_image', formats: ['png', 'jpeg', 'jpg', 'webp'] };
  if (name === 'maskImageUrl') return { name, label, type, widget: 'image', materialRole: 'mask', formats: ['png', 'jpeg', 'jpg', 'webp'],
    description: '区域编辑的选区图片；服务端会按供应商协议转换，不需要运营人员手写协议参数。' };
  if (name === 'maskEncoding') return { name, label, type, widget: 'select', choices: ['BLACK_WHITE', 'ALPHA'], defaultValue: 'BLACK_WHITE',
    description: '黑白选区或 Alpha 选区；具体编码由服务端适配。' };
  if (['targetWidth', 'targetHeight'].includes(name)) return { name, label, type, unit: 'px', minimum: 1 };
  if (['sourceX', 'sourceY'].includes(name)) return { name, label, type, unit: 'px', minimum: 0 };
  if (name === 'colorTemperature') return { name, label, type, unit: 'K' };
  if (['keyLightAzimuth', 'keyLightElevation', 'rimLightAzimuth', 'rimLightElevation'].includes(name)) return { name, label, type, unit: '°' };
  return { name, label, type, properties: type === 'object' ? [] : undefined,
    items: type === 'array' ? { name: 'item', label: '列表项', type: name === 'referenceVideoRecordIds' ? 'integer' : 'object', properties: [] } : undefined };
}
