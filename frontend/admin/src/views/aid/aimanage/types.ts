export interface Provider {
  integrationType?: 'NATIVE' | 'NEW_API';
  newApiSystemTokenEnabled?: boolean;
  newApiAccessToken?: string;
  newApiUserId?: number;
  newApiGroup?: string;
  newApiTokenId?: number;
  providerCategory?: 'AGGREGATOR' | 'OFFICIAL';
  displayOrder?: number;
  id: number;
  providerName: string;
  providerCode: string;
  /** 服务商LOGO图标URL（厂家品牌图标；入参剥域名存相对路径，出参拼完整URL） */
  logoUrl?: string | null;
  baseUrl?: string;
  /** 异步任务查询相对路径模板，必须且仅包含一个 %s 任务号占位符 */
  taskQuerySuffix?: string | null;
  apiKey?: string;
  apiSecret?: string;
  status: string;
  remark?: string;
  supportsCallback?: boolean;
  scheduleStrategyJson?: string;
  // OpenAI 兼容协议扩展字段
  /** 鉴权 header 名（默认 Authorization；Azure 用 api-key） */
  authHeader?: string | null;
  /** 鉴权前缀（默认 'Bearer '；空字符串表示无前缀） */
  authPrefix?: string | null;
  /** 自定义 header（JSON 对象字符串） */
  extraHeaders?: string | null;
  /** 厂商级请求体附加参数（JSON 对象字符串，如思考模式关闭） */
  extraBody?: string | null;
  /** 自定义 query string（JSON 对象字符串） */
  extraQuery?: string | null;
  /** API Key 申请页直链 */
  apiKeyApplyUrl?: string | null;
  /** 官方接口文档首页 */
  officialDocUrl?: string | null;
  [key: string]: any;
}

export interface ProviderOperationCapabilities {
  balance?: boolean;
  upstreamTasks?: boolean;
  taskStatuses?: string[];
  productTypes?: string[];
  taskSearchTypes?: string[];
  balanceDelayNotice?: string;
  balanceKind?: 'money' | 'credits' | 'resourcePackages';
  balanceUnit?: string;
  supportsTimeRange?: boolean;
  recentDays?: number;
}

export interface Model {
  businessBindings?: import('./ModelBusinessBindingEditor').BusinessModelBinding[];
  capabilities?: import('./modelDefinition').ModelCapabilityDefinition[];
  id?: number;
  providerId?: number;
  modelCode: string;
  /** 真实上游模型名（发给厂商的模型名，可重复）；为空时后端自动回退 modelCode */
  realModelCode?: string;
  modelName: string;
  /** 模型专属LOGO；为空时客户端展示所属服务商LOGO */
  logoUrl?: string | null;
  modelType: string;
  generateMode?: string;
  costCredits?: number | null;
  billingMultiplier?: number | null;
  apiVersion?: string;
  apiSuffix?: string;
  priority?: number;
  status?: string;
  remark?: string;
  billingMode?: 'FIXED' | 'SKU';
  billingRuleJson?: string | null;
  billingVersion?: number;
  /** 模型配置乐观锁版本；更新时原样提交，由服务端 CAS 递增。 */
  configVersion?: number;
  /** 是否免费；缺省为正常计费 */
  isFree?: boolean;
  /** 是否将该模型的上游图片输入按代理 URL 模板拼接 */
  imageUrlProxyEnabled?: boolean;
  /** 图片代理 URL 模板；启用时必须且只能包含一个 {url} */
  imageUrlProxyTemplate?: string | null;
  meterType?: string;
  imageRefine?: number | null;
  supportsTextInput?: boolean;
  supportsSystemPrompt?: boolean;
  supportsImageInput?: boolean;
  supportsMultiImageInput?: boolean;
  maxOutputCount?: number;
  defaultOutputCount?: number;
  supportsAspectRatio?: boolean;
  supportsSizePreset?: boolean;
  supportsDuration?: boolean;
  supportsFirstFrame?: boolean;
  supportsLastFrame?: boolean;
  defaultSizeCode?: string | null;
  defaultAspectRatio?: string | null;
  defaultDurationSeconds?: number | null;
  capabilityJson?: string | null;
  paramMappingJson?: string | null;
  /** 模型协议（openai-compatible-text / gemini-text 等） */
  protocol?: string | null;
  /** 模型级请求体附加参数（JSON 对象字符串，覆盖厂商级 extra_body） */
  extraBody?: string | null;
  /** 调度策略 JSON（含 maxConcurrency 模型并发上限），与服务商同字段约定 */
  scheduleStrategyJson?: string | null;
  /** 输入要求标签（后端推导，只读）：text_only / image_optional / image_required / video_required */
  inputRequirement?: string | null;
  [key: string]: any;
}

/** 输入图片计费配置（积分/张；unitPrice 空或 0 = 不计费） */
export interface InputPricingImage {
  unitPrice?: number | null;
  /** 前 N 张不计费；0 表示从首张开始计费。 */
  freeCount?: number | null;
  /** 计费张数上限（空 = 不限） */
  maxCount?: number | null;
}

/** 输入视频计费配置（积分/秒；unitPrice 空或 0 = 不计费） */
export interface InputPricingVideo {
  unitPrice?: number | null;
  /** 计费秒数上限（时长未知时按此值预扣） */
  maxSeconds?: number | null;
  /** 输入视频段数上限（展示用） */
  maxCount?: number | null;
}

/** 输入媒体计费（规则级默认，SKU 级可覆盖；官方阶梯输入价统一拍平为固定单价） */
export interface InputPricing {
  image?: InputPricingImage | null;
  video?: InputPricingVideo | null;
}

export interface Sku {
  /** 编辑器不展示的原始 SKU 字段；保存时作为无损构建基底。 */
  preservedSku?: Record<string, unknown>;
  skuCode: string;
  skuName: string;
  /** SKU 级计费口径；为空时继承模型级 meterType */
  meterType?: string | null;
  enabled: boolean;
  priority: number;
  match: Record<string, unknown>;
  price?: number | null;
  /** 每个价格单位覆盖的输出像素；只适用于按图片计费，留空即固定单张价。 */
  outputPixelsPerUnit?: number | null;
  /** 每秒单价（PER_SECOND 口径专用；缺省时后端用 price ÷ match.durationMax 反推） */
  pricePerSecond?: number | null;
  /** 每字符单价（PER_CHAR 口径专用，TTS 配音） */
  pricePerChar?: number | null;
  /** 每次固定附加成本，可与按字符等用量价格叠加。 */
  fixedSurcharge?: number | null;
  inputPricePerMillion?: number | null;
  outputPricePerMillion?: number | null;
  cachedInputPricePerMillion?: number | null;
  cacheWritePricePerMillion?: number | null;
  reasoningPricePerMillion?: number | null;
  /** SKU 级输入媒体计费覆盖（如视频输入单价随分辨率变化）；空 = 用规则级 */
  inputPricing?: InputPricing | null;
  remark?: string;
}

export interface SkuEditData {
  /** 原计费配置无法解析；禁止结构化编辑器静默重建为空规则。 */
  parseError?: boolean;
  /** 编辑器不展示的原始计费规则；保存时作为无损构建基底。 */
  preservedBillingRule?: Record<string, unknown>;
  charToTokenRatio: number;
  usagePricingMode?: 'AGGREGATE' | 'BUCKETED';
  /** 实际用量超过预冻结时是否允许补扣；缺省为 false。 */
  allowExtraCharge?: boolean;
  skuList: Sku[];
  /** 规则级输入媒体计费（图片/视频输入附加费默认值） */
  inputPricing?: InputPricing | null;
}

export interface SceneRule {
  supportsAspectRatio?: boolean;
  supportsSizePreset?: boolean;
  supportsDuration?: boolean;
  aspectRatioFollowInput?: boolean;
  requiredInputs?: string[];
  requiredAnyOf?: string[];
  allowedInputs?: string[];
}

export interface CapabilityModel {
  /** 原能力配置无法解析；禁止结构化编辑器静默覆盖。 */
  parseError?: boolean;
  /** 原始完整能力对象，作为无损构建基线；不直接展示给管理员。 */
  sourceCapability?: Record<string, any>;
  /** 图形编辑器不管理的原始能力字段；保存时无损合并回 capabilityJson。 */
  preservedCapability?: Record<string, any>;
  supportsReasoning?: boolean;
  supportsReasoningDisable?: boolean;
  returnsReasoningContent?: boolean;
  supportsReasoningBudget?: boolean;
  defaultReasoningEnabled?: boolean;
  reasoningApiStyle?: string;
  supportsStreaming?: boolean;
  supportsToolCalling?: boolean;
  supportsChatPrefix?: boolean;
  supportsStructuredOutput?: boolean;
  supportsContextCaching?: boolean;
  supportsBuiltinTools?: boolean;
  outputTokenApiField?: string;
  allowedReasoningLevels?: string[];
  defaultReasoningLevel?: string;
  defaultReasoningBudgetTokens?: number | null;
  maxReasoningBudgetTokens?: number | null;
  inputModalities?: string[];
  outputModalities?: string[];
  maxInputImages?: number | null;
  maxInputVideos?: number | null;
  maxInputAudios?: number | null;
  maxInputDocuments?: number | null;
  inputImageFormats?: string[];
  inputVideoFormats?: string[];
  inputAudioFormats?: string[];
  inputDocumentFormats?: string[];
  maxInputImageFileSizeMb?: number | null;
  maxInputVideoFileSizeMb?: number | null;
  maxInputAudioFileSizeMb?: number | null;
  maxInputDocumentFileSizeMb?: number | null;
  /** All input media combined, in MB. This is independent from each file limit. */
  maxInputMediaTotalFileSizeMb?: number | null;
  inputMediaMaxUrlLength?: number | null;
  minInputVideoDurationSeconds?: number | null;
  maxInputVideoDurationSeconds?: number | null;
  maxInputVideoTotalDurationSeconds?: number | null;
  minInputAudioDurationSeconds?: number | null;
  maxInputAudioDurationSeconds?: number | null;
  maxInputAudioTotalDurationSeconds?: number | null;
  maxInputDocumentPages?: number | null;
  inputImageMinDimensionPixels?: number | null;
  inputImageMaxDimensionPixels?: number | null;
  inputImageMinPixels?: number | null;
  inputImageMaxPixels?: number | null;
  inputImageMinAspectRatio?: number | null;
  inputImageMaxAspectRatio?: number | null;
  inputVideoMinDimensionPixels?: number | null;
  inputVideoMaxDimensionPixels?: number | null;
  inputVideoMinPixels?: number | null;
  inputVideoMaxPixels?: number | null;
  inputVideoMinAspectRatio?: number | null;
  inputVideoMaxAspectRatio?: number | null;
  inputVideoMinFps?: number | null;
  inputVideoMaxFps?: number | null;
  inputImageHighCountThreshold?: number | null;
  inputImageHighCountMaxDimensionPixels?: number | null;
  inputMediaAllowedMessageRoles?: string[];
  contextWindowTokens?: number | null;
  maxOutputTokens?: number | null;
  /** Generated image pixel range (width * height). */
  minOutputPixels?: number | null;
  maxOutputPixels?: number | null;
  /** Generated image width / height range. */
  minOutputAspectRatio?: number | null;
  maxOutputAspectRatio?: number | null;
  /** 非中日韩提示词最大字符数。 */
  maxPromptCharacters?: number | null;
  /** 包含中日韩文字时的提示词最大字符数；留空时沿用通用上限。 */
  maxPromptCharactersCjk?: number | null;
  durationMin?: number | null;
  durationMax?: number | null;
  strictSceneRules?: boolean;
  sizeOptions: string[];
  aspectRatioOptions: string[];
  durationOptions: number[];
  /** 官方声明的输出容器/文件格式及默认值。 */
  outputFormatOptions: string[];
  defaultOutputFormat?: string | null;
  /** 视频输出帧率枚举及默认值，与参考视频输入帧率限制分开维护。 */
  outputFpsOptions: number[];
  defaultOutputFps?: number | null;
  /** 视频音频模式枚举，例如 off/native/original。 */
  audioModeOptions: string[];
  /** supportsAudio=true 时未显式选择的默认开关；null 表示沿用历史兼容语义。 */
  defaultAudio?: boolean | null;
  supportsBgm?: boolean;
  supportsVoiceId?: boolean;
  audioTypes: string[];
  supportsVoiceControl?: boolean;
  /** 音频模型的真实业务操作，不与视频音画同出混用。 */
  audioOperation?: 'synthesis' | 'design' | 'clone' | string | null;
  ttsTextRequired?: boolean;
  ttsVoiceRequired?: boolean;
  builtInVoiceOptions: string[];
  audioFormatOptions: string[];
  defaultAudioFormat?: string | null;
  audioSampleRateOptions: number[];
  defaultAudioSampleRate?: number | null;
  supportsAudioStreaming?: boolean;
  supportsTimestamp?: boolean;
  speechRateMin?: number | null;
  speechRateMax?: number | null;
  loudnessRateMin?: number | null;
  loudnessRateMax?: number | null;
  pitchMin?: number | null;
  pitchMax?: number | null;
  emotionOptions: string[];
  supportsEmotionScale?: boolean;
  voiceSampleRequired?: boolean;
  voiceSampleFormats: string[];
  voiceSampleMaxFileSizeMb?: number | null;
  supportsElements?: boolean;
  maxElements?: number | null;
  elementTypeRequired?: boolean;
  /** 已接入视频协议使用的结构化场景标识。 */
  klingScenario?: string | null;
  videoScenario?: string | null;
  /** Seedance Generations 允许显式下发的任务类型。空数组表示该模型不支持该参数。 */
  seedanceTaskTypeOptions: string[];
  /** 允许生成的结构化业务场景。 */
  allowedScenes: string[];
  allowCustomWH: boolean;
  /**
   * 单次最多参考图张数（capabilityJson.maxReferenceImages）。
   * null = 未配置并回退厂商默认；-1 = 不限；0 = 禁止参考图；N>=1 = 最多 N 张。
   * 仅 image / video 类型有意义。
   */
  maxReferenceImages?: number | null;
  /**
   * 最少参考图张数（capabilityJson.minReferenceImages）。
   * null / 0 = 不要求带图；N>=1 = 必须至少带 N 张图（图生图/图生视频/首尾帧等），
   * 后端在建任务/扣费前按此值拦截缺图请求。仅 image / video 类型有意义。
   */
  minReferenceImages?: number | null;
  /** 参考图片格式、文件和画面硬约束。 */
  referenceImageFormats?: string[];
  referenceImageMaxFileSizeMb?: number | null;
  referenceImageMinDimensionPixels?: number | null;
  referenceImageMaxDimensionPixels?: number | null;
  /** Total pixels of one image (width * height), not an edge length. */
  referenceImageMinPixels?: number | null;
  referenceImageMaxPixels?: number | null;
  referenceImageMinAspectRatio?: number | null;
  referenceImageMaxAspectRatio?: number | null;
  /**
   * 官方接口是否支持 Base64 传图（能力位，依官方文档配置）。
   * false 时"启用 Base64 传图"开关不可选（该接口只允许 URL 传图）。
   */
  supportsBase64Image?: boolean;
  /**
   * 是否启用 Base64 传图（运营开关，仅 supportsBase64Image=true 时可开）。
   * 开启后参考图下载转 Base64 内联下发，用于上游网关无法回源业务 CDN 的场景。
   */
  base64ImageEnabled?: boolean;
  /**
   * 是否支持「音画同出」用户开关（仅 video）。
   * true：C 端模型列表 capability.supportsAudio=true，允许用户选择生成声音/不生成声音；
   * false：不展示开关，后端拒绝 generateAudio=true。
   */
  supportsAudio?: boolean;
  /** 可配置异步视频协议的上游音频开关字段；none 表示上游隐式处理。 */
  upstreamAudioField?: 'generate_audio' | 'audio' | 'none';
  /** 可配置异步视频协议的业务规格到上游分辨率参数映射。 */
  upstreamResolutionMap?: Record<string, string>;
  /** 是否支持外部参考音频输入（仅 video，缺省 false）。 */
  supportsReferenceAudio?: boolean;
  /** 单次最少参考音频数量。 */
  minReferenceAudios?: number | null;
  /** 参考音频下发前是否必须开启音画同出（缺省 true）。 */
  referenceAudioRequiresGeneratedAudio?: boolean;
  /** 参考音频是否必须同时携带图片或视频素材。 */
  referenceAudioRequiresVisualInput?: boolean;
  /** 单次最多参考音频数量。 */
  maxReferenceAudios?: number | null;
  /** 单段参考音频最短时长（秒）。 */
  referenceAudioMinDurationSeconds?: number | null;
  /** 单段参考音频最长时长（秒）。 */
  referenceAudioMaxDurationSeconds?: number | null;
  /** 单次参考音频总时长上限（秒）。 */
  referenceAudioMaxTotalDurationSeconds?: number | null;
  /** 单个参考音频最大文件大小。 */
  referenceAudioMaxFileSizeMb?: number | null;
  /** 支持的参考音频格式。 */
  referenceAudioFormats: string[];
  /** 是否允许参考视频输入。 */
  supportsVideoInput?: boolean;
  /** 单次最少/最多参考视频数量。 */
  minReferenceVideos?: number | null;
  maxReferenceVideos?: number | null;
  /** 参考视频格式、文件、时长和画面硬约束。 */
  referenceVideoFormats?: string[];
  referenceVideoMaxFileSizeMb?: number | null;
  referenceVideoMinDurationSeconds?: number | null;
  referenceVideoMaxDurationSeconds?: number | null;
  referenceVideoMaxTotalDurationSeconds?: number | null;
  referenceVideoMinDimensionPixels?: number | null;
  referenceVideoMaxDimensionPixels?: number | null;
  /** 单个参考视频总像素范围（宽×高）。 */
  referenceVideoMinPixels?: number | null;
  referenceVideoMaxPixels?: number | null;
  referenceVideoMinAspectRatio?: number | null;
  referenceVideoMaxAspectRatio?: number | null;
  referenceVideoMinFps?: number | null;
  referenceVideoMaxFps?: number | null;
  /** 图、视频、音频合计数量，以及输入输出视频合计时长。 */
  maxReferenceMaterials?: number | null;
  maxInputOutputVideoDurationSeconds?: number | null;
  sceneRules: {
    textOnly: SceneRule;
    textToImage: SceneRule;
    imageToImage: SceneRule;
    textToVideo: SceneRule;
    imageToVideo: SceneRule;
    startEndToVideo: SceneRule;
    referenceToVideo: SceneRule;
    videoToVideo: SceneRule;
  };
}

export interface ParamMapping {
  paramName: string;
  provider: string;
  providerParamName: string;
}

export interface ScheduleStrategy {
  dispatchMode: string;
  supportsCallback: boolean;
  /** 厂商接收任务状态通知的完整公网地址；MiniMax H3 可留空并回退轮询。 */
  callbackBaseUrl?: string | null;
  firstPollDelaySeconds: number;
  baseIntervalSeconds: number;
  maxIntervalSeconds: number;
  backoffFactor: number;
  maxRetryCount: number;
  /** 最大存活（秒）：从上游受理起算的绝对天花板，仅防上游永远回报处理中，不代表预期出片耗时 */
  maxLifeSeconds: number;
  /** 无进展超时（秒）：从最近一次观测到上游推进起算，是判定上游生死的依据；缺省回落最大存活 */
  progressTimeoutSeconds?: number | null;
  /** 每秒成片对应的最大存活预算；缺省使用后端视频默认值，0 表示关闭按作业规模抬高 */
  lifeSecondsPerVideoSecond?: number | null;
  /**
   * 并发上限（唯一键名，供应商行与模型行同名，各表示所在层级的上限）。
   * 供应商行 = 该供应商下所有模型同时在途的上游请求总数上限；
   * 模型行 = 该模型同时在途的上游请求数上限。<=0/缺省 = 不限（仅受上层约束）。
   */
  maxConcurrency?: number | null;
}

export interface PreviewResult {
  matched?: boolean;
  skuCode?: string;
  skuName?: string;
  amount?: number;
  errorMessage?: string;
  snapshot?: {
    baseAmount?: number;
    inputPricePerMillion?: number;
    outputPricePerMillion?: number;
    modelBillingMultiplier?: number;
    globalBillingMultiplier?: number;
    finalBillingMultiplier?: number;
    requestParams?: Record<string, any>;
  };
}
