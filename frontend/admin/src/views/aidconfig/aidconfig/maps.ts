/** 分类名称映射 */
export const CATEGORY_NAMES: Record<string, string> = {
  mail: '邮箱配置',
  wxLogin: '微信公众号配置',
  alipay: '支付宝配置',
  wxpay: '微信支付配置',
  security: '安全配置',
  upload: '上传配置',
  sms: '短信配置',
  wx_notify: '微信公众号推送',
  payment: '支付配置',
  storage: '存储配置',
  realAuth: '实名认证配置',
  oss: '文件存储',
  mq: '消息队列配置',
  media: '媒体生成',
  mps: '媒体处理',
  tencent_asr: '腾讯云语音识别',
  agent_model: 'Agent 默认模型',
  voice: '配音设置',
  referenceAudio: '参考音频配置',
  captcha: '行为验证码配置',
  api_crypto: '接口加密配置',
  taskq: '任务队列',
  storyboard: '分镜配置',
  project_gen_config: '项目生成配置',
  image_moderation: '图片内容安全审查',
  image_object_detection: '图像识别 / 视觉服务',
  login_policy: '登录与在线策略',
  account_security: '账号安全',
  admin_entry: '后台登录入口',
  default_avatar: '默认头像',
  admin_brand: '后台品牌图片',
  basic: '基础配置',
  register_bonus: '注册送积分',
  invite: '邀请激励',
  system_upgrade: '项目升级配置',
  official_gateway: '官方统一网关'
};

/**
 * 系统配置「分区」模型：左侧菜单按业务域分区，右侧顶部用 Tab 在同一分区下的多个配置分类间切换。
 * - categories 仅 1 个时：不显示 Tab，直接展示该分类。
 * - categories 多个时：左侧显示分区名，右侧顶部出现 Tab 栏（如「支付管理」下的 支付宝 / 微信支付）。
 * 实际渲染时会按数据库里真实存在的分类做过滤，不存在的分类自动跳过。
 */
export interface ConfigSection {
  key: string;
  name: string;
  categories: string[];
}

export const CONFIG_SECTIONS: ConfigSection[] = [
  { key: 'basic', name: '基础配置', categories: ['basic', 'admin_brand'] },
  { key: 'storage', name: '文件存储', categories: ['oss'] },
  { key: 'payment', name: '支付管理', categories: ['alipay', 'wxpay'] },
  { key: 'notify', name: '消息通知', categories: ['sms', 'mail', 'wx_notify'] },
  {
    key: 'account',
    name: '登录与认证',
    categories: ['account_security', 'login_policy', 'admin_entry', 'default_avatar', 'wxLogin', 'captcha', 'api_crypto', 'security']
  },
  { key: 'task', name: '任务与并发', categories: ['media', 'mps', 'tencent_asr', 'taskq', 'mq'] },
  { key: 'ai', name: 'AI 与生成', categories: ['agent_model', 'project_gen_config', 'storyboard'] },
  { key: 'voice', name: '配音设置', categories: ['voice'] },
  { key: 'moderation', name: '内容安全', categories: ['image_moderation'] },
  { key: 'vision', name: '图像识别', categories: ['image_object_detection'] },
  { key: 'promotion', name: '营销活动', categories: ['register_bonus', 'invite'] }
];

/** 需要「重启应用后才生效」的字段（凭证类 Bean 启动时一次性构建）。在状态区展示「重启生效」提示。 */
export const RESTART_REQUIRED_FIELDS: Record<string, string[]> = {
  oss: ['endpoint', 'accessKeyId', 'accessKeySecret', 'cosRegion', 'cosSecretId', 'cosSecretKey']
};

export function isRestartRequired(category: string | undefined, name: string): boolean {
  if (!category) return false;
  return (RESTART_REQUIRED_FIELDS[category] || []).includes(name);
}

/**
 * 按分类隔离的字段中文标签（优先级高于全局 CONFIG_NAME_MAP / 后端 configDict）。
 * 用于像 captcha 这种存在通用字段名（如 type）的分类，避免与其它分类的同名字段冲突。
 */
export const CATEGORY_FIELD_LABELS: Record<string, Record<string, string>> = {
  account_security: {
    cancel_re_registration_enabled: '限制注销账号再次注册',
    cancel_re_registration_days: '再次注册限制天数'
  },
  // 登录与在线策略（need10）
  login_policy: {
    allow_multi_online: '允许多端同时在线',
    max_online_count: '最大同时在线数'
  },
  // 营销活动：注册送积分
  register_bonus: {
    enabled: '注册送积分总开关',
    amount: '注册赠送积分数量',
    sms_enabled: '手机号注册是否参与',
    email_enabled: '邮箱注册是否参与',
    wechat_enabled: '微信注册是否参与'
  },
  // 营销活动：邀请激励（充值返佣）
  invite: {
    enabled: '邀请激励总开关',
    rebate_ratio: '充值返佣比例(%)',
    rebate_max_per_order: '单笔返佣积分上限(0为不限)'
  },
  // 微信支付（V3）：字段名按 V3 证书体系明确标注，便于运营对照商户平台下载的证书文件
  wxpay: {
    enabled: '启用微信支付',
    appId: '应用ID (AppID)',
    mchId: '商户号 (mchId)',
    apiV3Key: 'APIv3密钥',
    privateKey: '应用私钥 (apiclient_key.pem)',
    serialNo: '证书序列号 (apiclient_cert.pem)',
    publicKeyId: '微信支付公钥ID',
    publicKey: '微信支付公钥 (pub_key.pem)',
    notifyUrl: '异步通知地址'
  },
  // 基础配置：协议/隐私政策/备案号/交流二维码等合规与首屏展示内容
  basic: {
    site_name: '网站名称',
    site_description: '网站描述',
    site_keywords: '网站关键词',
    personal_information_collection_list: '个人信息收集清单',
    app_permissions_description: '应用权限说明',
    third_party_sdk_and_information_sharing_list: '第三方SDK及信息共享清单',
    terms_of_service: '用户协议',
    membership_agreement: '会员协议',
    privacy_policy: '隐私政策',
    record_filing_number: '备案号',
    exchange_image_url: '交流二维码图片地址',
    tutorial_url: '教程链接',
    open_source_git_url: '开源地址(GitHub)',
    open_source_gitee_url: '开源地址(Gitee)'
  },
  // 短信服务：通用策略 + 当前厂商专属参数
  sms: {
    enabled: '启用短信服务',
    providerType: '短信渠道',
    accessKeyId: 'AccessKey ID / SecretId',
    accessKeySecret: 'AccessKey Secret / SecretKey',
    endpoint: '云短信 API 端点',
    signName: '短信签名',
    sdkAppId: '腾讯云短信应用 ID',
    defaultTemplateId: '验证码模板 ID',
    codeParamName: '验证码参数名',
    smsBaoUsername: '短信宝用户名',
    smsBaoApiKey: '短信宝 API Key',
    smsBaoProductId: '短信宝产品 ID（可选）',
    smsBaoContentTemplate: '短信内容模板（含签名）',
    code_length: '验证码长度（位）',
    code_expire_minutes: '验证码有效期（分钟）',
    send_interval_seconds: '同手机号/同IP最小发送间隔（秒）',
    daily_limit: '同手机号/同IP每日发送上限'
  },
  // 后台品牌图片：平台 LOGO / 页签图标（专用 AdminBrandSection 渲染）
  admin_brand: {
    platform_logo_url: '平台LOGO地址',
    favicon_url: '浏览器页签图标地址'
  },
  // 图片内容安全审查（image_moderation）：渲染走专用 ImageModerationSection 区块，
  // 不再走通用字段表，因此不需要在此声明字段标签。
  oss: {
    // ===== 公共字段（页面顶部）=====
    enabled: '启用文件存储',
    uploadMode: '存储模式',
    uploadTypeLimits: '分类型上传限制',
    maxFileSize: '单文件大小上限(字节，未配置分类型限制时兜底)',
    allowedExtensions: '允许的文件类型(未配置分类型限制时兜底)',
    maxBatchCount: '单次最多上传数量',
    imageUrlWhitelist: '图片URL域名白名单',
    resourceAccessDomain: '资源访问地址',
    modelSignedUrlExpireHours: '模型临时链接有效期(小时)',
    // ===== 本地存储（旧 localDomain 仅兼容历史数据，不再展示）=====
    localDomain: '旧站点访问域名',
    // ===== 阿里云 OSS（凭证类改后需重启）=====
    endpoint: 'OSS Endpoint',
    accessKeyId: 'AccessKey ID',
    accessKeySecret: 'AccessKey Secret',
    bucketName: 'Bucket 名称',
    prefix: '路径前缀',
    cdnDomain: '旧公共访问域名',
    // ===== 腾讯云 COS（去掉 COS 前缀；凭证类改后需重启）=====
    cosRegion: '地域 (Region)',
    cosSecretId: 'SecretId',
    cosSecretKey: 'SecretKey',
    cosBucketName: 'Bucket 名称',
    cosPrefix: '路径前缀',
    cosCdnDomain: 'COS 源站域名（可选）',
    // ===== 七牛云 Kodo =====
    qiniuAccessKey: 'AccessKey',
    qiniuSecretKey: 'SecretKey',
    qiniuBucketName: 'Bucket 名称',
    qiniuPrefix: '路径前缀'
  },
  captcha: {
    enabled: '是否启用',
    type: '验证码类型',
    protected_scenes: '受保护场景',
    background_urls: '背景图片',
    token_expire_seconds: '验证令牌有效期(秒)',
    captcha_expire_seconds: '验证码有效期(秒)'
  },
  api_crypto: {
    enabled: '接口加密总开关',
    gzip_enabled: '响应GZIP压缩',
    gzip_threshold: 'GZIP压缩阈值(字节)',
    max_plain_size: '单次加密最大字节数',
    timestamp_window_ms: '防重放时间窗口(毫秒)',
    public_key: 'RSA公钥',
    private_key: 'RSA私钥'
  },
  storyboard: {
    shot_density_floor: '分镜镜头数下限锚点'
  },
  voice: {
    voice_preview_max_seconds: '试听时长上限(秒)'
  },
  project_gen_config: {
    main_character_extract: '角色提取',
    main_scene_extract: '场景提取',
    main_prop_extract: '道具提取',
    main_character_form: '角色形态提取',
    main_scene_form: '场景形态提取',
    main_prop_form: '道具形态提取',
    main_character_image: '生成角色图',
    main_scene_image: '生成场景图',
    main_prop_image: '生成道具图',
    main_character_card_image: '角色设定卡',
    main_storyboard_script: '分镜脚本提取',
    main_storyboard_stylist: '分镜图提示词',
    main_storyboard_video_prompt: '视频提示词-多参数版',
    main_storyboard_video_prompt_image: '视频提示词-图生视频',
    main_storyboard_image: '分镜生图'
  }
};

/**
 * 按分类隔离的下拉选项（仅在对应分类下生效）。
 */
export const CATEGORY_SELECT_OPTIONS: Record<string, Record<string, Array<{ label: string; value: string }>>> = {
  captcha: {
    type: [
      { label: '滑块', value: 'SLIDER' },
      { label: '旋转', value: 'ROTATE' },
      { label: '文字点选', value: 'WORD_IMAGE_CLICK' },
      { label: '滑动还原', value: 'CONCAT' },
      { label: '随机', value: 'RANDOM' }
    ]
  },
  oss: {
    // 腾讯云 COS 地域：固定可选，避免手填出错
    cosRegion: [
      { label: '北京 ap-beijing', value: 'ap-beijing' },
      { label: '上海 ap-shanghai', value: 'ap-shanghai' },
      { label: '广州 ap-guangzhou', value: 'ap-guangzhou' },
      { label: '成都 ap-chengdu', value: 'ap-chengdu' },
      { label: '重庆 ap-chongqing', value: 'ap-chongqing' },
      { label: '南京 ap-nanjing', value: 'ap-nanjing' },
      { label: '香港 ap-hongkong', value: 'ap-hongkong' },
      { label: '新加坡 ap-singapore', value: 'ap-singapore' },
      { label: '硅谷 na-siliconvalley', value: 'na-siliconvalley' },
      { label: '法兰克福 eu-frankfurt', value: 'eu-frankfurt' }
    ]
  },
  image_moderation: {
    // 图片审查走专用 ImageModerationSection 区块（PageCard + Form），
    // 这里保留为空表，避免通用 ValueField 误读到下拉/布尔字段定义。
  }
};

/** 支持一键同步到内存的分类 */
export const SYNCABLE_CATEGORIES = ['sms', 'mail', 'wxLogin', 'alipay', 'wxpay', 'realAuth', 'oss', 'mq'];

/** 配置显示名（优先级最高，覆盖后端 configDict） */
export const CONFIG_NAME_MAP: Record<string, string> = {
  enabled: '是否启用',
  providerType: '服务商类型',
  endpoint: 'API端点',
  accessKeyId: 'AccessKey ID',
  accessKeySecret: 'AccessKey Secret',
  signName: '短信签名',
  sdkAppId: '腾讯云AppId',
  defaultTemplateId: '默认模板ID',
  codeParamName: '验证码参数名',
  host: 'SMTP服务器',
  port: '端口',
  user: '邮箱账号',
  pass: '授权码',
  from: '发件人地址',
  wxLoginAppId: 'AppID',
  wxLoginSecret: 'AppSecret',
  wxLoginToken: 'Token',
  wxLoginEncodingAESKey: 'EncodingAESKey',
  encodingAESKey: 'EncodingAESKey',
  wxLoginSubscribeReply: '关注后自动回复',
  qrcodeExpireSeconds: '二维码有效期(秒)',
  appId: '应用ID',
  privateKey: '应用私钥',
  publicKeyId: '微信支付公钥ID',
  publicKey: '微信支付公钥',
  alipayPublicKey: '支付宝公钥',
  notifyUrl: '异步通知地址',
  returnUrl: '同步返回地址',
  sandbox: '沙箱环境',
  signType: '签名类型',
  charset: '字符集',
  format: '返回格式',
  appCode: '阿里云AppCode',
  authType: '认证类型',
  bucketName: 'Bucket名称',
  prefix: '路径前缀',
  cdnDomain: 'CDN域名',
  resourceAccessDomain: '资源访问地址',
  modelSignedUrlExpireHours: '模型临时链接有效期(小时)',
  maxFileSize: '最大文件大小(字节)',
  allowedExtensions: '允许的文件类型',
  // 腾讯云COS 专属字段（兜底，oss 分类已用 CATEGORY_FIELD_LABELS 覆盖为去前缀短标签）
  cosRegion: '地域 (Region)',
  cosSecretId: 'SecretId',
  cosSecretKey: 'SecretKey',
  cosBucketName: 'Bucket 名称',
  cosPrefix: '路径前缀',
  cosCdnDomain: 'COS 源站域名',
  qiniuAccessKey: '七牛云 AccessKey',
  qiniuSecretKey: '七牛云 SecretKey',
  qiniuBucketName: '七牛云 Bucket 名称',
  qiniuPrefix: '七牛云路径前缀',
  // agent_model 类
  text_economy_model_code: '文字模型 · 经济模式',
  text_performance_model_code: '文字模型 · 性能模式',
  image_economy_model_code: '图片模型 · 经济模式',
  image_performance_model_code: '图片模型 · 性能模式',
  // media 类（媒体生成管线：图片/视频/音频/文本生成）
  ai_billing_global_multiplier: '模型基础倍率（积分/元）',
  media_concurrent_limit_global: '媒体生成 · 全局并发上限',
  media_concurrent_limit_user: '媒体生成 · 单用户并发上限',
  // taskq 类（任务排队 + 多维并发调度 v2.59，作用于通用抽取任务中心）
  taskq_concurrent_limit_global: '任务队列 · 全局并发上限',
  taskq_concurrent_limit_user: '任务队列 · 单用户默认并发上限',
  // mq 类
  mqType: '消息队列类型'
};

/** 下拉选择字段 */
export const SELECT_FIELD_OPTIONS: Record<string, Array<{ label: string; value: string }>> = {
  providerType: [
    { label: '阿里云', value: 'aliyun' },
    { label: '腾讯云', value: 'tencent' },
    { label: '短信宝', value: 'smsbao' }
  ],
  authType: [
    { label: '二要素(姓名+身份证)', value: 'twoFactor' },
    { label: '三要素(姓名+身份证+手机号)', value: 'threeFactor' }
  ],
  mqType: [
    { label: 'RocketMQ', value: 'rocketmq' },
    { label: 'Redis', value: 'redis' }
  ],
  uploadMode: [
    { label: '阿里云OSS', value: 'oss' },
    { label: '腾讯云COS', value: 'cos' },
    { label: '七牛云Kodo', value: 'qiniu' },
    { label: '本地存储', value: 'local' }
  ]
};

/** 敏感字段（脱敏 + 密码框） */
export const SENSITIVE_KEYWORDS = [
  'pass',
  'password',
  'secret',
  'key',
  'token',
  'credential',
  'accessKeySecret',
  'privateKey',
  'alipayPublicKey',
  'appCode',
  'secretKey'
];

export const NON_SENSITIVE_FIELDS = ['publicKeyId'];

/** 服务商相关配置项 */
export const PROVIDER_SPECIFIC_FIELDS: Record<string, { common: string[]; [key: string]: string[] }> = {
  sms: {
    common: ['enabled', 'providerType', 'code_length', 'code_expire_minutes', 'send_interval_seconds', 'daily_limit'],
    aliyun: ['endpoint', 'accessKeyId', 'accessKeySecret', 'signName', 'defaultTemplateId', 'codeParamName'],
    tencent: [
      'endpoint',
      'accessKeyId',
      'accessKeySecret',
      'signName',
      'sdkAppId',
      'defaultTemplateId',
      'codeParamName'
    ],
    smsbao: ['smsBaoUsername', 'smsBaoApiKey', 'smsBaoProductId', 'smsBaoContentTemplate']
  },
  // 文件存储：按"存储模式(uploadMode)"动态显示字段。
  // 公共字段（顶部）：所有模式共用的开关与限制项。
  // 模式字段（底部）：凭证按服务商隔离；云存储共用公共访问域名。
  oss: {
    common: [
      'enabled',
      'uploadMode',
      'resourceAccessDomain',
      'modelSignedUrlExpireHours',
      'uploadTypeLimits',
      'maxFileSize',
      'allowedExtensions',
      'maxBatchCount',
      'imageUrlWhitelist'
    ],
    // 本地存储不需要厂商凭证；资源访问地址已在公共字段配置。
    local: [],
    // 阿里云OSS 模式
    oss: ['endpoint', 'accessKeyId', 'accessKeySecret', 'bucketName', 'prefix'],
    // 腾讯云COS 模式
    cos: ['cosRegion', 'cosSecretId', 'cosSecretKey', 'cosBucketName', 'cosPrefix'],
    // 七牛云 Kodo 模式
    qiniu: ['qiniuAccessKey', 'qiniuSecretKey', 'qiniuBucketName', 'qiniuPrefix']
  }
};

/** 按分类指定"驱动字段过滤"的字段名与默认值（sms 看 providerType，oss 看 uploadMode） */
export const PROVIDER_FILTER_DRIVER: Record<string, { field: string; default: string }> = {
  sms: { field: 'providerType', default: 'aliyun' },
  oss: { field: 'uploadMode', default: 'oss' }
};

/**
 * 明确声明数值型配置，禁止根据当前值猜测类型。
 *
 * 账号、AppId、商户号、模板 ID 等业务标识即使全是数字，本质仍是字符串；
 * 使用显式白名单可以保留前导零，也能保证空值配置仍渲染为正确的数值输入框。
 */
const NUMBER_FIELDS_BY_CATEGORY: Record<string, string[]> = {
  account_security: ['cancel_re_registration_days'],
  admin_entry: ['rate_limit_per_min'],
  api_crypto: ['gzip_threshold', 'max_plain_size', 'timestamp_window_ms'],
  captcha: ['captcha_expire_seconds', 'token_expire_seconds'],
  image_moderation: ['logRetentionDays'],
  invite: ['rebate_max_per_order', 'rebate_ratio'],
  login_policy: ['max_online_count'],
  mail: ['port', 'code_length', 'code_expire_minutes', 'send_interval_seconds', 'daily_limit'],
  media: ['ai_billing_global_multiplier', 'media_concurrent_limit_global', 'media_concurrent_limit_user'],
  mps: ['creditRate', 'profitMultiplier'],
  oss: ['maxBatchCount', 'maxFileSize', 'modelSignedUrlExpireHours'],
  referenceAudio: ['maxDurationSeconds', 'maxPerProject', 'minDurationSeconds'],
  register_bonus: ['amount'],
  sms: ['code_length', 'code_expire_minutes', 'send_interval_seconds', 'daily_limit'],
  system_upgrade: ['keep_backups'],
  taskq: ['taskq_concurrent_limit_global', 'taskq_concurrent_limit_user'],
  tencent_asr: ['maxAttempts', 'sentenceMaxLength', 'speakerDiarization', 'timeoutSeconds'],
  voice: ['voice_preview_max_seconds']
};

/** agent_model 类特殊字段：值是 JSON 串，编辑时需要拆成 modelCode + defaultParams */
export const AGENT_MODEL_JSON_FIELDS = [
  'text_economy_model_code',
  'text_performance_model_code',
  'image_economy_model_code',
  'image_performance_model_code'
];

/** 每个 agent_model 字段对应的模型类型过滤 */
export const AGENT_MODEL_FIELD_TYPE: Record<string, 'text' | 'image' | 'video'> = {
  text_economy_model_code: 'text',
  text_performance_model_code: 'text',
  image_economy_model_code: 'image',
  image_performance_model_code: 'image'
};

/** 图片 / 视频字段是否要求支持"图生图" / "图生视频" */
export const AGENT_MODEL_FIELD_REQUIRE_I2X: Record<string, boolean> = {
  image_economy_model_code: true,
  image_performance_model_code: true
};

/** 是否为 agent_model 的 JSON 字段 */
export function isAgentModelJson(name: string): boolean {
  return AGENT_MODEL_JSON_FIELDS.includes(name);
}

/** 通用 JSON 值判定（以 { 或 [ 开头、以 } 或 ] 结尾） */
export function isJsonLike(v: any): boolean {
  if (v === null || v === undefined) return false;
  const s = String(v).trim();
  return (s.startsWith('{') && s.endsWith('}')) || (s.startsWith('[') && s.endsWith(']'));
}

/** 工具函数 */
export function isSensitive(name: string): boolean {
  if (NON_SENSITIVE_FIELDS.includes(name)) return false;
  const lower = name.toLowerCase();
  return SENSITIVE_KEYWORDS.some((s) => lower.includes(s.toLowerCase()));
}

export function isBooleanLike(v: any): boolean {
  return v === 'true' || v === 'false';
}

export function isNumericField(category: string | undefined, name: string): boolean {
  if (category === 'taskq' && /^taskq_concurrent_limit_user_\d+$/.test(name)) {
    return true;
  }
  return Boolean(category && NUMBER_FIELDS_BY_CATEGORY[category]?.includes(name));
}

export function isLongText(v: any): boolean {
  if (!v) return false;
  const s = String(v);
  return s.length > 100 || s.includes('\n');
}

export function formatConfigLabel(item: any): string {
  // 分类内显式标签优先级最高（覆盖后端可能乱码的 configDict）
  if (item.category && CATEGORY_FIELD_LABELS[item.category]?.[item.configName]) {
    return CATEGORY_FIELD_LABELS[item.category][item.configName];
  }
  // taskq 用户专属并发：taskq_concurrent_limit_user_{userId}
  const m = /^taskq_concurrent_limit_user_(\d+)$/.exec(item.configName || '');
  if (m) return `用户专属并发上限 (用户ID: ${m[1]})`;
  if (CONFIG_NAME_MAP[item.configName]) return CONFIG_NAME_MAP[item.configName];
  if (item.configDict && !item.configDict.includes('(') && !item.configDict.includes('/')) return item.configDict;
  if (item.configDict) return item.configDict.replace(/\([^)]*\)/g, '').trim();
  return item.configName;
}

export function formatCurrentValue(name: string, value: any): string {
  if (value === null || value === undefined || value === '') return '(空)';
  if (SELECT_FIELD_OPTIONS[name]) {
    const hit = SELECT_FIELD_OPTIONS[name].find((o) => o.value === value);
    return hit ? hit.label : String(value);
  }
  // agent_model JSON：显示 modelCode + 关键参数
  if (isAgentModelJson(name) || isJsonLike(value)) {
    try {
      const obj = typeof value === 'string' ? JSON.parse(value) : value;
      if (obj && typeof obj === 'object') {
        const parts: string[] = [];
        if (obj.modelCode) parts.push(obj.modelCode);
        if (obj.defaultParams) {
          const p = obj.defaultParams;
          if (p.size) parts.push(p.size);
          if (p.outputCount) parts.push(`×${p.outputCount}`);
        }
        if (parts.length) return parts.join(' · ');
      }
    } catch {
      /* fallthrough */
    }
  }
  if (isSensitive(name) && String(value).length > 2) {
    return String(value).substring(0, 2) + '****';
  }
  if (value === 'true') return '已开启';
  if (value === 'false') return '已关闭';
  const s = String(value);
  return s.length > 24 ? s.substring(0, 24) + '...' : s;
}
