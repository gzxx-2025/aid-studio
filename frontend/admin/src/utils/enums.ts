/**
 * 业务枚举常量定义
 * 与后端 com.ruoyi.enums 包下的枚举对应
 * 来自原 aid-manager/src/utils/enums.js
 */

export interface EnumOption<V = string | number> {
  label: string;
  value: V;
  elTagType?: string;
}

// ==================== 项目相关枚举 ====================

export const PROJECT_TYPE_OPTIONS: EnumOption<string>[] = [
  { label: '剧集', value: 'series' },
  { label: '电影', value: 'movie' }
];

export const SCRIPT_TYPE_OPTIONS: EnumOption<string>[] = [
  { label: '剧情演绎', value: 'plot' },
  { label: '真人解说漫', value: 'monologue' }
];

export const ASPECT_RATIO_OPTIONS: EnumOption<string>[] = [
  { label: '16:9横屏', value: '16:9' },
  { label: '9:16竖屏', value: '9:16' },
  { label: '4:3比例', value: '4:3' },
  { label: '3:4比例', value: '3:4' },
  { label: '1:1正方形', value: '1:1' },
  { label: '4:5比例', value: '4:5' },
  { label: '2.35:1电影宽屏', value: '2.35:1' }
];

export const VIDEO_STYLE_TYPE_OPTIONS: EnumOption<string>[] = [
  { label: '自定义', value: 'custom' },
  { label: 'AI生成', value: 'ai_gen' },
  { label: '官方预设', value: 'official' }
];

export const GEN_MODE_OPTIONS: EnumOption<string>[] = [
  { label: '经济模式', value: 'economy' },
  { label: '性能模式', value: 'performance' }
];

export const STORYBOARD_MODE_OPTIONS: EnumOption<string>[] = [
  { label: '单张分镜', value: 'single' },
  { label: '九宫格', value: 'grid' }
];

export const CREATION_MODE_OPTIONS: EnumOption<string>[] = [
  { label: '图生视频', value: 'i2v' },
  { label: '多参生视频', value: 'multi' }
];

// ==================== 步骤枚举 ====================

export const CURRENT_STEP_OPTIONS: EnumOption<number>[] = [
  { label: '项目配置', value: 1, elTagType: 'info' },
  { label: '剧本创作', value: 2, elTagType: 'primary' },
  { label: '素材准备', value: 3, elTagType: 'primary' },
  { label: '分镜设计', value: 4, elTagType: 'warning' },
  { label: '视频生成', value: 5, elTagType: 'warning' },
  { label: '音画同步', value: 6, elTagType: 'success' },
  { label: '成品预览', value: 7, elTagType: 'success' }
];

export const CURRENT_STEP_WITH_MOVIE_OPTIONS: EnumOption<number>[] = [
  { label: '剧集模式', value: -1, elTagType: 'info' },
  ...CURRENT_STEP_OPTIONS
];

// ==================== 状态类枚举 ====================

export const PROJECT_STATUS_OPTIONS: EnumOption<number>[] = [
  { label: '草稿', value: 0, elTagType: 'info' },
  { label: '制作中', value: 1, elTagType: 'warning' },
  { label: '完成未提交', value: 2, elTagType: 'primary' },
  { label: '审核中', value: 3, elTagType: 'primary' },
  { label: '审核通过', value: 4, elTagType: 'success' },
  { label: '审核失败', value: 5, elTagType: 'danger' }
];

export const EPISODE_STATUS_OPTIONS: EnumOption<number>[] = [
  { label: '草稿', value: 0, elTagType: 'info' },
  { label: '制作中', value: 1, elTagType: 'warning' },
  { label: '完成未审核', value: 2, elTagType: 'primary' },
  { label: '审核中', value: 3, elTagType: 'primary' },
  { label: '审核通过', value: 4, elTagType: 'success' },
  { label: '审核失败', value: 5, elTagType: 'danger' }
];

export const SCRIPT_STATUS_OPTIONS: EnumOption<number>[] = [
  { label: '草稿', value: 0, elTagType: 'info' },
  { label: '使用', value: 1, elTagType: 'success' },
  { label: '历史版本', value: 2, elTagType: 'warning' }
];

// ==================== 资产 ====================

export const ASSET_TYPE_OPTIONS: EnumOption<string>[] = [
  { label: '风格', value: 'style' },
  { label: '场景', value: 'scene' },
  { label: '角色', value: 'character' },
  { label: '道具', value: 'prop' },
  { label: '文件', value: 'file' },
  { label: '姿势', value: 'pose' },
  { label: '特效', value: 'effect' },
  { label: '表情', value: 'expression' }
];

// ==================== 提示词 / 模型 ====================

export const PROMPT_TYPE_OPTIONS: EnumOption<string>[] = [
  { label: '视频风格', value: 'style' },
  { label: '镜头语言', value: 'camera' },
  { label: '主体描述', value: 'subject' }
];

export const MODEL_TYPE_OPTIONS: EnumOption<string>[] = [
  { label: '生图', value: 'image', elTagType: 'primary' },
  { label: '生视频', value: 'video', elTagType: 'success' },
  { label: '配音', value: 'audio', elTagType: 'warning' },
  { label: '文本', value: 'text', elTagType: '' }
];

export const BILLING_MODE_OPTIONS: EnumOption<string>[] = [
  { label: '固定价', value: 'FIXED', elTagType: 'primary' },
  { label: 'SKU规则', value: 'SKU', elTagType: 'warning' }
];

export const METER_TYPE_OPTIONS: EnumOption<string>[] = [
  { label: '按Token计费', value: 'TOKEN', elTagType: 'primary' },
  { label: '按张计费', value: 'PER_IMAGE', elTagType: 'success' },
  { label: '按秒计费', value: 'PER_SECOND', elTagType: 'warning' },
  { label: '按套餐计费', value: 'SKU_PACKAGE', elTagType: 'danger' },
  { label: '按字符计费', value: 'PER_CHAR', elTagType: 'info' }
];

export const IMAGE_REFINE_OPTIONS: EnumOption<number>[] = [
  { label: '文生图', value: 1, elTagType: 'primary' },
  { label: '图生图', value: 2, elTagType: 'success' },
  { label: '图片高清', value: 3, elTagType: 'warning' },
  { label: '图片编辑', value: 4, elTagType: 'danger' }
];

export const GENERATE_MODE_OPTIONS: EnumOption<string>[] = [
  { label: '文本大模型', value: 'text', elTagType: '' },
  { label: '文生图', value: 'text_to_image', elTagType: 'primary' },
  { label: '图生图', value: 'image_to_image', elTagType: 'success' },
  { label: '图片编辑', value: 'image_edit', elTagType: 'warning' },
  { label: '区域编辑', value: 'image_inpainting', elTagType: 'warning' },
  { label: '图片扩展', value: 'image_outpainting', elTagType: 'primary' },
  { label: '图片高清', value: 'image_upscale', elTagType: 'danger' },
  { label: '文生视频', value: 'text_to_video', elTagType: 'primary' },
  { label: '图生视频', value: 'image_to_video', elTagType: 'success' },
  { label: '首尾帧视频', value: 'start_end_to_video', elTagType: 'primary' },
  { label: '参考图生视频', value: 'reference_to_video', elTagType: 'success' },
  { label: '多帧视频', value: 'multi_frame', elTagType: 'warning' },
  { label: '视频生视频', value: 'video_to_video', elTagType: 'warning' },
  { label: '音频', value: 'audio', elTagType: 'info' }
];

/**
 * 模型「输入要求」标签（后端 ModelInputRequirementResolver 统一推导，非库表字段）。
 * 用于后台模型列表展示与模型池选择器筛选：
 * 同为图片模型时可区分「文字+图片(必传)→生图」与「文字+图片(可选)→生图」两类。
 */
export const INPUT_REQUIREMENT_OPTIONS: EnumOption<string>[] = [
  { label: '纯文本', value: 'text_only', elTagType: '' },
  { label: '图片可选', value: 'image_optional', elTagType: 'primary' },
  { label: '图片必传', value: 'image_required', elTagType: 'danger' },
  { label: '视频必传', value: 'video_required', elTagType: 'warning' }
];

export const DISPATCH_MODE_OPTIONS: EnumOption<string>[] = [
  { label: '同步直返', value: 'DIRECT', elTagType: 'info' },
  { label: '回调优先', value: 'CALLBACK_FIRST', elTagType: 'success' },
  { label: '纯轮询', value: 'POLL_ONLY', elTagType: 'warning' }
];

// ==================== 任务状态（v2.59 任务排队 + 多维并发调度） ====================

/**
 * aid_extract_task.status 取值。
 * v2.59 新增 QUEUED（排队中）：已预冻结、等待并发名额。
 */
export const TASK_STATUS_OPTIONS: EnumOption<string>[] = [
  { label: '排队中', value: 'QUEUED', elTagType: 'info' },
  { label: '等待执行', value: 'PENDING', elTagType: 'info' },
  { label: '执行中', value: 'PROCESSING', elTagType: 'primary' },
  { label: '已完成', value: 'SUCCEEDED', elTagType: 'success' },
  { label: '部分完成', value: 'PARTIAL_FAILED', elTagType: 'warning' },
  { label: '失败', value: 'FAILED', elTagType: 'danger' },
  { label: '已取消', value: 'CANCELLED', elTagType: 'info' }
];

export const TASK_STATUS_MAP = getEnumMap(TASK_STATUS_OPTIONS);

export const GEN_TYPE_OPTIONS: EnumOption<string>[] = [
  { label: '单图', value: 'image' },
  { label: '九宫格', value: 'grid' },
  { label: '图生视频', value: 'i2v' },
  { label: '多参视频', value: 'multi' },
  { label: '首尾视频', value: 'edge' }
];

// ==================== 音频 ====================

export const AUDIO_SOURCE_OPTIONS: EnumOption<number>[] = [
  { label: 'AI文字配音', value: 1 },
  { label: '用户上传', value: 2 }
];

// ==================== 通用 ====================

export const SYSTEM_PROMPT_TYPE_OPTIONS: EnumOption<string>[] = [
  { label: '教学提示词', value: 'main_teacher_prompt' }
];

export const ENABLE_STATUS_OPTIONS: EnumOption<string>[] = [
  { label: '正常', value: '0', elTagType: 'success' },
  { label: '停用', value: '1', elTagType: 'danger' }
];

export const YES_NO_OPTIONS: EnumOption<string>[] = [
  { label: '否', value: '0', elTagType: 'danger' },
  { label: '是', value: '1', elTagType: 'success' }
];

// ==================== 工具函数 ====================

/** 值转 antd Tag 颜色 */
const tagColorMap: Record<string, string> = {
  primary: 'blue',
  success: 'green',
  warning: 'orange',
  danger: 'red',
  info: 'default'
};

export function getLabelByValue<V>(options: EnumOption<V>[], value: V, defaultLabel = '--'): string {
  const hit = options.find((o) => String(o.value) === String(value));
  return hit ? hit.label : defaultLabel;
}

export function getTagTypeByValue<V>(options: EnumOption<V>[], value: V, defaultType = 'info'): string {
  const hit = options.find((o) => String(o.value) === String(value));
  return hit && hit.elTagType ? hit.elTagType : defaultType;
}

/** 给 antd Tag 用：把 elTagType 转成 antd 颜色 */
export function getAntdTagColor<V>(options: EnumOption<V>[], value: V): string | undefined {
  const type = getTagTypeByValue(options, value, '');
  return tagColorMap[type];
}

export function getEnumMap<V extends string | number>(options: EnumOption<V>[]): Record<string, string> {
  const map: Record<string, string> = {};
  options.forEach((opt) => {
    map[String(opt.value)] = opt.label;
  });
  return map;
}

export const PROJECT_STATUS_MAP = getEnumMap(PROJECT_STATUS_OPTIONS);
export const EPISODE_STATUS_MAP = getEnumMap(EPISODE_STATUS_OPTIONS);
export const SCRIPT_STATUS_MAP = getEnumMap(SCRIPT_STATUS_OPTIONS);
export const AUDIO_SOURCE_MAP = getEnumMap(AUDIO_SOURCE_OPTIONS);
export const ENABLE_STATUS_MAP = getEnumMap(ENABLE_STATUS_OPTIONS);
export const YES_NO_MAP = getEnumMap(YES_NO_OPTIONS);
export const CURRENT_STEP_MAP = getEnumMap(CURRENT_STEP_OPTIONS);
export const CURRENT_STEP_WITH_MOVIE_MAP = getEnumMap(CURRENT_STEP_WITH_MOVIE_OPTIONS);
