/** 与 `components/steps/接口.md` 中「通用响应格式」一致 */

export interface ApiEnvelope<T = unknown> {
  code: number
  msg: string
  data?: T
}

/**
 * 分页列表（旧版）：记录数组在根级 `rows`
 * @deprecated 新接口已改为使用 {@link ApiListEnvelopeData}
 */
export interface ApiListEnvelope<T> {
  code: number
  msg: string
  total: number
  rows: T[]
}

/**
 * 分页列表（新版）：记录数组在 `data` 中，不再使用根级 `rows`
 * 例：`{ code, msg, total, data: [ {...}, ... ] }`
 */
export interface ApiListEnvelopeData<T> {
  code: number
  msg: string
  total: number
  data: T[]
}

/** /auth/login */
export interface LoginRequest {
  loginType: 'password' | 'sms' | 'email'
  account?: string
  password?: string
  code?: string
  /** 邀请码；仅首次注册瞬间生效，无效码静默忽略 */
  inviteCode?: string
}

export interface SocialBindItem {
  platformSource: string
  openid: string
  unionid?: string
}

export interface UserInfoFromApi {
  userId: number
  userName: string
  nickName?: string
  avatar?: string
  phonenumber?: string
  email?: string
  balance?: number
  frozenBalance?: number
  memberLevel?: string
  memberLevelName?: string
  memberExpireTime?: string
  totalRecharge?: number
  totalConsumption?: number
  isReal?: boolean
  realName?: string
  idCard?: string
  wechatNotifyEnabled?: boolean
}

/** POST /api/user/balance — 快捷查询账户积分 */
export interface UserBalanceFromApi {
  balance: number
  frozenBalance: number
  totalRecharge: number
  totalConsumption: number
}

export interface LoginData {
  token: string
  userInfo: UserInfoFromApi
  social?: SocialBindItem[]
}

/** /auth/sendCode */
export interface SendCodeRequest {
  target?: string
  codeType: 'sms' | 'email'
  scene:
    | 'login'
    | 'bind'
    | 'unbind'
    | 'reset'
    | 'set_password'
    | 'rebind_old'
    | 'rebind_new'
    | 'cancel'
  /** 邀请码（登录场景可选）；有则透传，空串不传 */
  inviteCode?: string
}

/** /auth/public-config */
export interface AuthCaptchaPublicConfig {
  enabled: boolean
  type?: 'SLIDER' | 'ROTATE' | 'WORD_IMAGE_CLICK' | 'CONCAT' | null
  reason?: string
  urlCount?: number
  localCount?: number
  applicationOk?: boolean
  imagesReady?: boolean
}

export interface AuthCodePolicyConfig {
  channel: 'sms' | 'email'
  codeLength: number
  codeExpireMinutes: number
  sendIntervalSeconds: number
  dailyLimit: number
}

/** /auth/public-config → login */
export interface AuthLoginPublicConfig {
  smsEnabled: boolean
  emailEnabled: boolean
  wechatEnabled: boolean
}

/** /auth/public-config → crypto */
export interface AuthCryptoPublicConfig {
  enabled: boolean
  publicKey: string | null
  algorithm?: string
}

/** /auth/public-config → basic */
export interface AuthBasicPublicConfig {
  /** 网站名称（浏览器标题 / 品牌文案 / SEO） */
  site_name?: string
  /** 网站描述（搜索摘要 / 分享卡片） */
  site_description?: string
  /** 网站关键词，英文逗号分隔 */
  site_keywords?: string
  record_filing_number?: string
  version_number?: string
  privacy_policy?: string
  terms_of_service?: string
  membership_agreement?: string
  app_permissions_description?: string
  personal_information_collection_list?: string
  third_party_sdk_and_information_sharing_list?: string
  /** 交流/客服二维码图片地址（关于我们、帮助中心） */
  exchange_image_url?: string
  /** 服务邮箱（关于我们「服务邮箱」） */
  service_email?: string
  /** 商务合作邮箱（历史字段；关于我们「商务合作」现用 contact_phone） */
  business_email?: string
  /** 联系电话（关于我们「商务合作」） */
  contact_phone?: string
  company_name?: string
  company_address?: string
  tutorial_url?: string
  open_source_git_url?: string
  open_source_gitee_url?: string
  work_publish_enabled?: string
}

/** /auth/public-config → brand */
export interface AuthBrandPublicConfig {
  /** 平台 LOGO 图片地址 */
  platformLogoUrl?: string | null
  /** 浏览器页签图标地址；C 端可据此动态替换 Favicon */
  faviconUrl?: string | null
}

/** /auth/public-config → promotion.registerBonus */
export interface AuthRegisterBonusPublicConfig {
  enabled?: boolean
  amount?: number
  smsEnabled?: boolean
  emailEnabled?: boolean
  wechatEnabled?: boolean
}

/** /auth/public-config → promotion.invite */
export interface AuthInvitePromotionPublicConfig {
  enabled?: boolean
  rebateRatio?: number
  rebateMaxPerOrder?: number
}

/** /auth/public-config → promotion */
export interface AuthPromotionPublicConfig {
  registerBonus?: AuthRegisterBonusPublicConfig
  invite?: AuthInvitePromotionPublicConfig
}

export interface AuthWechatNotifyPublicConfig {
  enabled: boolean
  rules?: string[]
}

/** /auth/public-config → voicePreview */
export interface AuthVoicePreviewPublicConfig {
  maxSeconds?: number | null
  estimatedMaxChars?: number | null
}

/** /auth/public-config → upload 分类型上传限制 */
export interface AuthUploadTypeLimit {
  name: string
  maxSizeMb: number
  extensions: string[]
}

export interface AuthUploadPublicConfig {
  typeLimits?: AuthUploadTypeLimit[]
  globalMaxSizeMb?: number
  globalAllowedExtensions?: string
}

export interface AuthPaymentPublicConfig {
  alipayEnabled?: boolean
  wxpayEnabled?: boolean
}

export interface AuthPublicConfigData {
  captcha: AuthCaptchaPublicConfig
  smsPolicy: AuthCodePolicyConfig
  emailPolicy: AuthCodePolicyConfig
  login?: AuthLoginPublicConfig
  basic?: AuthBasicPublicConfig
  brand?: AuthBrandPublicConfig
  crypto?: AuthCryptoPublicConfig
  payment?: AuthPaymentPublicConfig
  upload?: AuthUploadPublicConfig
  wechatNotify?: AuthWechatNotifyPublicConfig
  voicePreview?: AuthVoicePreviewPublicConfig
  promotion?: AuthPromotionPublicConfig
  serverTime?: number
}

/** /api/user/wechat-notify/preference | enable | disable */
export interface WechatNotifyPreferenceData {
  systemEnabled: boolean
  userEnabled: boolean
  wechatBound: boolean
  rules: string[]
}

/** /captcha/gen（已开启） */
export interface CaptchaGenData {
  id: string
  type: string
  backgroundImage: string
  templateImage: string
  backgroundImageWidth: number
  backgroundImageHeight: number
  templateImageWidth: number
  templateImageHeight: number
}

/** /captcha/gen（未开启 / 降级） */
export interface CaptchaGenDisabledData {
  enabled: false
}

/** /captcha/check */
export interface CaptchaCheckData {
  token: string
}

/** /auth/resetPassword */
export interface ResetPasswordRequest {
  target: string
  resetType: 'phone' | 'email'
  code: string
  newPassword: string
  confirmPassword: string
}

/** 微信扫码 */
export interface WechatQrcodeData {
  sceneStr: string
  qrCodeUrl: string
  expireSeconds: number
}

export type WechatLoginStatus = 'WAITING' | 'SCANNED' | 'SUCCESS' | 'EXPIRED' | 'FAIL'

/** /auth/wechat/check：非成功状态仍会在 data 中返回 status */
export interface WechatLoginCheckData extends Partial<LoginData> {
  status: WechatLoginStatus
  expireSeconds?: number
}

export type WechatLoginSuccessData = LoginData & { status: 'SUCCESS' }

/** POST /api/user/account/security */
export interface AccountSecurityData {
  passwordSet: boolean
  phoneBound: boolean
  maskedPhone?: string
  emailBound: boolean
  maskedEmail?: string
  wechatBound: boolean
  loginMethods: Array<'sms' | 'email' | 'password' | 'wechat' | string>
  canUnbindPhone: boolean
  canUnbindEmail: boolean
  canUnbindWechat: boolean
  smsAvailable: boolean
  emailAvailable: boolean
  wechatAvailable: boolean
  reRegistrationRestrictionEnabled: boolean
  reRegistrationRestrictionDays: number
  passwordPolicy: { minLength: number; maxLength: number }
}

export type AccountCodeChannel = 'sms' | 'email'

export interface AccountPasswordSetRequest {
  verifyType: AccountCodeChannel
  code: string
  newPassword: string
  confirmPassword: string
}

export interface AccountPasswordChangeRequest {
  oldPassword: string
  newPassword: string
  confirmPassword: string
}

export interface AccountBindRequest {
  bindType: AccountCodeChannel
  target: string
  code: string
}

export interface AccountUnbindRequest {
  unbindType: AccountCodeChannel | 'wechat'
  code: string
}

export interface AccountRebindRequest {
  bindType: AccountCodeChannel
  newTarget: string
  oldCode: string
  newCode: string
}

export interface AccountCancelRequest {
  verifyType: AccountCodeChannel
  code: string
}

export interface AccountLoginHistoryRequest {
  pageNum: number
  pageSize: number
}

export interface AccountLoginHistoryItem {
  id: number
  status: string
  ipaddr?: string
  loginLocation?: string
  browser?: string
  os?: string
  message?: string
  loginTime?: string
}

export interface AccountLoginHistoryData {
  total: number
  pageNum: number
  pageSize: number
  list: AccountLoginHistoryItem[]
}

/** /auth/wechat/bind/check */
export interface WechatBindCheckData {
  status: WechatLoginStatus
  expireSeconds?: number
}

/** /realAuth/verify */
export interface RealAuthVerifyRequest {
  realName: string
  idCard: string
}

/** /realAuth/status */
export interface RealAuthStatusData {
  hasPhone: boolean
  enabled: boolean
  authType: string
  needPhone: boolean
  isReal: boolean
  realName?: string
  idCard?: string
}

/** /recharge/package/list */
export interface RechargePackageItem {
  id: number
  packageName: string
  credits: number
  originalPrice: number
  discount: number
  payPrice: number
  icon?: string
  description?: string
}

/** /recharge/order/create */
export interface RechargeOrderCreateRequest {
  packageId: number
  payType: 'alipay' | 'wxpay'
}

export interface RechargeOrderCreateData {
  orderNo: string | null
  qrCode: string | null
  pendingOrderNo: string | null
  pendingPayPrice: number | null
  pendingProductName: string | null
  pendingRemainSeconds: number | null
}

/** /recharge/order/query */
export interface RechargeOrderQueryData {
  orderNo: string
  productName: string
  credits: number
  payPrice: number
  payStatus: 'pending' | 'paid' | 'failed' | 'closed' | 'refunded'
  payTime?: string
  createTime?: string
  canRepay: boolean
}

export interface RechargeOrderRow {
  orderNo: string
  productName: string
  credits: number
  payPrice: number
  payStatus: string
  payTime?: string
  createTime?: string
  canRepay: boolean
}

/** 充值订单列表 `/recharge/order/list` 响应（记录数组在 `data`，无根级 `rows`） */
export type RechargeOrderListResponse = ApiListEnvelopeData<RechargeOrderRow>

/** `/api/user/credit/consume/list` 单条积分消耗明细 */
export interface CreditConsumeRecordRow {
  bizTraceId: string
  bizType: string
  bizTypeName: string
  bizName: string
  /** 本次实际使用的模型展示名；多模型顿号分隔；非 AI / 历史记录可空 */
  modelName?: string | null
  changeAmount: number
  consumedAmount: number
  frozenAmount: number
  hasRefund: boolean
  refundAmount: number
  extraAmount: number
  createTime: string | null
}

/** 积分消耗明细列表响应（记录数组在 `data`，`total` 在根级） */
export type CreditConsumeListResponse = ApiListEnvelopeData<CreditConsumeRecordRow>

/** /api/user/project/list */
export type UserProjectType = 'movie' | 'series'

/** POST /api/public/project/video */
export interface PublicProjectVideoListRequest {
  projectName?: string
  /** movie 电影 / series 剧集；不传返回全部 */
  projectType?: UserProjectType
  pageNum?: number
  pageSize?: number
}

export type PublicProjectAccessReasonCode =
  | 'PROJECT_UNAVAILABLE'
  | 'NO_APPROVED_SNAPSHOT'
  | 'PROJECT_CONTENT_SHARING_DISABLED'
  | 'PROJECT_TYPE_UNSUPPORTED'
  | 'PROJECT_CONTENT_NOT_VIEWABLE'
  | 'PROJECT_COPY_DISABLED'
  | 'RIGHTS_RESTRICTED'
  | 'PUBLISHED_SNAPSHOT_UNAVAILABLE'
  | (string & {})

export interface PublicProjectVideoRow {
  id: number
  projectName: string
  authorNickname?: string | null
  projectType?: UserProjectType | string | null
  projectDesc?: string | null
  publishTime?: string | null
  episodeCount?: number | null
  coverUrl?: string | null
  finalVideoUrl?: string | null
  previewVideoUrl?: string | null
  mediaWidth?: number | null
  mediaHeight?: number | null
  aspectRatio?: string | null
  ratioSource?: string | null
  allowPreview?: boolean | null
  allowCopy?: boolean | null
  canViewProjectContent?: boolean | null
  viewProjectContentReasonCode?: PublicProjectAccessReasonCode | null
  canCopyProject?: boolean | null
  copyProjectReasonCode?: PublicProjectAccessReasonCode | null
  copyRequiresLogin?: boolean | null
  copyLabel?: string | null
}

export interface PublicProjectEpisodeItem {
  episodeId: number
  episodeNo?: number | null
  title?: string | null
  coverUrl?: string | null
  videoUrl?: string | null
}

/** POST /api/public/project/detail */
export interface PublicProjectDetailRow extends Omit<PublicProjectVideoRow, 'previewVideoUrl'> {
  previewVideoUrl?: string | null
  updateTime?: string | null
  videoStyleType?: string | null
  episodes?: PublicProjectEpisodeItem[] | null
}

export interface UserProjectListRequest {
  projectName?: string
  projectType?: UserProjectType
  status?: 0 | 1 | 2 | 3 | 4 | 5
  pageNum?: number
  pageSize?: number
}

export interface UserProjectRow {
  id: number
  projectName: string
  projectDesc?: string | null
  projectType: UserProjectType
  coverUrl?: string | null
  aspectRatio?: string | null
  scriptType?: string | null
  videoStyleType?: string | null
  videoStyleValue?: string | null
  /** 项目当前风格来源：official 官方 / custom 用户自定义 */
  styleSource?: 'official' | 'custom' | null
  /** 项目当前风格在对应来源表中的资产ID */
  styleAssetId?: number | null
  /** 已存在角色/场景/道具资产时为 true，前端不得切换风格 */
  styleLocked?: boolean | null
  defaultGenMode?: string | null
  defaultStoryboardMode?: string | null
  defaultCreationMode?: string | null
  currentStep?: number | null
  status: 0 | 1 | 2 | 3 | 4 | 5
  statusReason?: string | null
  isPublic?: string | null
  allowPreview?: boolean | null
  allowCopy?: boolean | null
  publishedSnapshotId?: number | null
  sourceProjectId?: number | null
  sourceSnapshotId?: number | null
  copyLabel?: string | null
  copyCount?: number | null
  createTime?: string | null
  updateTime?: string | null
  /** 剧集项目：分集数量（列表接口可能返回，用于作品卡片展示） */
  episodeCount?: number | null
  /** 电影模式：项目级剪辑记录 ID（episode_id=0） */
  episodeEditorId?: number | null
  /** 电影模式：项目级最新成片地址；剧集类型恒为 null */
  finalVideoUrl?: string | null
  /** 待审核新成片地址；非空表示需重新提审，公开侧仍展示 finalVideoUrl */
  pendingVideoUrl?: string | null
  /** 项目级成片导出状态：0 未导出/待重导，1 合成中，2 成功，3 失败 */
  exportStatus?: number | null
}

export interface UserProjectCreateRequest {
  projectName: string
  projectDesc?: string
  projectType: UserProjectType
  coverUrl?: string
  aspectRatio?: string
  scriptType?: 'plot' | 'monologue'
  /** 官方风格为 assetName；历史/自定义可为枚举或占位字符串 */
  videoStyleType?: string
  videoStyleValue?: string
  /** 风格来源，由后端据此读取官方或用户自定义风格并形成项目快照 */
  styleSource: 'official' | 'custom'
  /** 风格在对应来源表中的主键 ID */
  styleAssetId: number
  defaultGenMode?: 'economy' | 'performance'
  defaultStoryboardMode?: 'single' | 'grid'
  defaultCreationMode?: 'i2v' | 'multi' | 'pro' | 'auto_grid'
}

/** /api/user/episode/list、create */
export interface UserEpisodeRow {
  id: number
  projectId: number
  episodeNo?: number
  comicTitle?: string | null
  comicDesc?: string | null
  status?: number
  statusReason?: string | null
  createTime?: string | null
  updateTime?: string | null
  episodeEditorId?: number | null
  finalVideoUrl?: string | null
  pendingVideoUrl?: string | null
  exportStatus?: number | null
}

export interface UserEpisodeDetailRequest {
  id: number
}

export interface UserEpisodeUpdateRequest {
  id: number
  comicTitle?: string
  comicDesc?: string
  comicCoverUrl?: string
  genMode?: 'economy' | 'performance'
  creationMode?: 'i2v' | 'multi' | 'pro' | 'auto_grid'
}

export interface UserEpisodeDeleteRequest {
  id: number
}

export interface UserEpisodeCreateRequest {
  projectId: number
  comicTitle: string
  comicDesc?: string
  comicCoverUrl?: string
  genMode?: 'economy' | 'performance'
  storyboardMode?: 'single' | 'grid'
  creationMode?: 'i2v' | 'multi' | 'pro' | 'auto_grid'
}

/** /api/user/project/update */
export interface UserProjectUpdateRequest {
  id: number
  projectName?: string
  projectDesc?: string
  coverUrl?: string
  aspectRatio?: string
  scriptType?: 'plot' | 'monologue'
  videoStyleType?: string
  videoStyleValue?: string
  /** 仅用户主动切换风格时传入 */
  styleSource?: 'official' | 'custom'
  /** 仅用户主动切换风格时传入 */
  styleAssetId?: number
  defaultGenMode?: 'economy' | 'performance'
  defaultStoryboardMode?: 'single' | 'grid'
  defaultCreationMode?: 'i2v' | 'multi' | 'pro' | 'auto_grid'
  allowPreview?: boolean
  allowCopy?: boolean
}

/** 与官方/个人资产接口 assetType 对齐 */
export type UserAssetApiType =
  | 'style'
  | 'reference_scene'
  | 'reference_character'
  | 'reference_prop'
  | 'scene'
  | 'character'
  | 'prop'
  | 'file'
  | 'pose'
  | 'effect'
  | 'expression'
  | 'mood'
  | 'camera'
  | 'other'

/** @deprecated v2.0 已废弃 /api/user/asset/query */
export type UserAssetQuerySource = 'personal' | 'official' | 'all'

/** @deprecated v2.0 已废弃，请用 official/query 或 rps/list */
export interface UserAssetQueryRequest {
  source?: UserAssetQuerySource
  projectId?: number
  episodeId?: number
  assetType?: UserAssetApiType | string
  assetName?: string
  pageNum?: number
  pageSize?: number
}

/** POST /api/user/asset/official/query */
export interface UserAssetOfficialQueryRequest {
  assetType?: UserAssetApiType | string
  assetName?: string
}

export interface UserAssetOfficialRow {
  id: number
  assetType: string
  assetName: string
  promptText?: string | null
  imageUrl?: string | null
}

/** POST /api/user/asset/custom/type/list */
export interface UserAssetCustomTypeItem {
  code: string
  name: string
  description?: string
}

/** POST /api/user/asset/custom/list */
export interface UserAssetCustomListRequest {
  assetType?: string
  keyword?: string
  pageNum?: number
  pageSize?: number
}

export interface UserAssetCustomRow {
  id: number
  assetType: string
  assetName: string
  personalityDesc?: string | null
  promptText?: string | null
  imageUrl?: string | null
  sourceType?: string | null
  sortOrder?: number | null
  remark?: string | null
  createTime?: string | null
}

/** POST /api/user/asset/custom/create */
export interface UserAssetCustomCreateRequest {
  assetType: string
  assetName: string
  personalityDesc?: string
  promptText?: string
  imageUrl?: string
  remark?: string
}

export interface UserAssetCustomCreateData {
  id: number
}

/** POST /api/user/asset/custom/detail */
export interface UserAssetCustomDetailRequest {
  id: number
}

/** POST /api/user/asset/custom/update */
export interface UserAssetCustomUpdateRequest {
  id: number
  assetName?: string
  personalityDesc?: string
  promptText?: string
  imageUrl?: string
  remark?: string
}

/** POST /api/user/asset/custom/delete */
export interface UserAssetCustomDeleteRequest {
  id: number
}

/** POST /api/user/asset/custom/page — 合并个人+官方参考资产分页 */
export interface MergedAssetPageRequest {
  assetType?: string
  /** 精选风格分类；仅 assetType=style 时使用。 */
  categoryCode?: string
  keyword?: string
  /** custom=个人(可编辑/删除) / official=官方(只读)；兼容 USER/OFFICIAL */
  sourceFlag?: 'custom' | 'official' | 'USER' | 'OFFICIAL' | 'ALL' | string
  pageNum?: number
  pageSize?: number
}

/** POST /api/user/asset/style/category/list */
export interface UserAssetStyleCategoryItem {
  code: string
  label: string
}

/** custom/page 列表项 */
export interface MergedAssetVO {
  id: number
  assetType: string
  assetName: string
  personalityDesc?: string | null
  promptText?: string | null
  imageUrl?: string | null
  /** custom=个人自定义(可编辑) / official=官方素材(只读) */
  sourceFlag: 'custom' | 'official' | 'USER' | 'OFFICIAL' | string
  sourceType?: string | null
  sortOrder?: number | null
  remark?: string | null
  createTime?: string | null
}

/** POST /api/user/asset/center/category/tree — 项目→剧集→分类三层树节点 */
export interface AssetCenterCategoryTreeVO {
  projectId: number
  projectName: string
  projectType?: 'series' | 'movie' | string | null
  episodeId?: number | null
  episodeNo?: number | null
  episodeTitle?: string | null
  categoryCode?: string | null
  categoryName?: string | null
  assetCount?: number | null
  children?: AssetCenterCategoryTreeVO[]
}

/** POST /api/user/asset/center/list — 个人资产中心列表（不含长正文） */
export interface AssetCenterListRequest {
  projectId: number
  episodeId?: number
  categoryCode?: string
  assetType?: UserAssetApiType | string
  keyword?: string
  pageNum?: number
  pageSize?: number
}

export interface AssetCenterItemVO {
  id: number
  projectId: number
  episodeId: number
  /** 分类编码（center/list 出参） */
  categoryCode?: string | null
  categoryName?: string | null
  /** 展示名称（center/list 出参） */
  name?: string | null
  /** 媒体地址：图片/视频/音频 URL（center/list 出参） */
  mediaUrl?: string | null
  /** 兼容旧字段 */
  assetType?: string | null
  assetName?: string | null
  coverUrl?: string | null
  createTime?: string | null
  updateTime?: string | null
}

/** POST /api/user/asset/center/detail — 个人资产明细 */
export interface AssetCenterDetailRequest {
  /** 分类编码（见资产中心统一分类编码表） */
  categoryCode: string
  /** 该分类业务表主键 ID */
  id: number
}

export interface AssetCenterDetailVO {
  id: number
  categoryCode: string
  categoryName?: string | null
  name: string
  projectId: number
  episodeId: number
  imageUrl?: string | null
  videoUrl?: string | null
  audioUrl?: string | null
  coverUrl?: string | null
  content?: Record<string, unknown> | null
  createTime?: string | null
  /** 兼容旧代码读取 */
  assetType?: string | null
  assetName?: string | null
  updateTime?: string | null
}

/** POST /api/user/asset/rps/list */
export interface UserAssetRpsListRequest {
  projectId?: number
  episodeId?: number
  assetType?: UserAssetApiType | string
  /** 0 未使用 1 已使用，不传查全部 */
  isUse?: number
}

/** 角色音色绑定（rps/list 的 voiceBinding、/voice/bind|query 出参） */
export interface RoleVoiceBindingVO {
  bindingId?: number
  assetId?: number
  voiceLibraryId?: number
  voiceCode?: string | null
  voiceName?: string | null
  avatarUrl?: string | null
  sampleUrl?: string | null
  sampleText?: string | null
  language?: string | null
  gender?: string | null
  ageRange?: string | null
  supportsEmotion?: boolean | null
  supportsSpeed?: boolean | null
  supportsPitch?: boolean | null
  defaultSpeed?: number | null
  defaultPitch?: number | null
  overrideSpeed?: number | null
  overridePitch?: number | null
  overrideEmotion?: string | null
  offlineTime?: string | null
  offline?: boolean | null
}

/** 个人资产从表形态 */
export interface UserAssetRpsFormRow {
  id: number
  assetId?: number | null
  assetType?: UserAssetApiType | string | null
  name: string
  imageUrl?: string | null
  promptText?: string | null
  /** 角色形态的完整视觉提示词 */
  descriptions?: string | null
  /** 场景/道具形态的生图提示词 */
  prompt?: string | null
  /** v2.25.0+ 形态层解析字段（scene/prop） */
  summary?: string | null
  introduction?: string | null
  changeReason?: string | null
  /** 视觉描述状态：pending / completed */
  visualDescStatus?: string | null
  /** 创建来源：manual=手动创建，auto=自动提取 */
  createSource?: string | null
  canAutoGenerateImage?: boolean | null
  promptVariantCount?: number | null
  imageCount?: number | null
  currentImageId?: number | null
  /** 0 未使用 1 已使用（列表/Tab 主图展示） */
  isUse?: number | null
  images?: UserAssetRpsFormImageRow[]
}

/** 个人资产主表（含形态列表） */
export interface UserAssetRpsRow {
  id: number
  assetType: string
  assetName: string
  forms?: UserAssetRpsFormRow[]
  createTime?: string | null
  updateTime?: string | null
  /** 创建来源：manual=手动创建，auto=自动提取 */
  createSource?: string | null
  /** 若后端仍返回手动/自动标记（旧字段，1=手动） */
  sourceType?: number | null
  /** v2.25.0+ /rps/list 结构化主表字段（原 profileData 打散；null 字段可能不出现在 JSON） */
  introduction?: string | null
  summary?: string | null
  aliasesName?: string | null
  gender?: string | null
  ageRange?: string | null
  roleLevel?: string | null
  archetype?: string | null
  eraPeriod?: string | null
  occupation?: string | null
  costumeTier?: number | string | null
  socialClass?: string | null
  visualKeywords?: string[] | null
  personalityTags?: string[] | null
  suggestedColors?: string[] | null
  primaryIdentifier?: string | null
  expectedAppearances?: Array<{ id?: number; label?: string; name?: string }> | string | null
  availableSlots?: string[] | string | null
  hasCrowd?: number | null
  crowdDescription?: string | null
  /** 兼容旧版整包 JSON */
  profileData?: string | null
  /** 仅 character：角色已绑定的音色（未绑定时不序列化） */
  voiceBinding?: RoleVoiceBindingVO | null
}

/** POST /api/user/asset/rps/create */
export interface UserAssetRpsCreateRequest {
  projectId: number
  episodeId: number
  name: string
  assetType: 'scene' | 'character' | 'prop' | string
}

/** POST /api/user/asset/rps/form/create */
export interface UserAssetRpsFormCreateRequest {
  projectId: number
  episodeId: number
  assetId: number
  imageUrl: string
  name?: string
  sourceType: 'upload' | 'official' | 'ai'
}

/** POST /api/user/asset/rps/form/list */
export interface UserAssetRpsFormListRequest {
  projectId?: number
  episodeId?: number
  assetType?: UserAssetApiType | string
  assetId?: number
}

/** POST /api/user/asset/rps/form/use */
export interface UserAssetRpsFormUseRequest {
  projectId?: number
  /** 图片实例 ID；主图为单选，接口每次只接受一个图片。 */
  imageId: number
}

/** POST /api/user/asset/rps/form/use 批量出参 data */
export interface UserAssetRpsFormUseBatchData {
  total?: number
  successCount?: number
  failCount?: number
  successIds?: number[]
  failures?: Array<{ id?: number | null; reason?: string }>
}

/** POST /api/user/asset/rps/form/unuse */
export interface UserAssetRpsFormUnuseRequest {
  projectId?: number
  /** v2.18.6+ 推荐：图片实例ID（单个） */
  imageId?: number
  /** v2.63+ 批量图片实例ID列表 */
  imageIds?: number[]
  /** 兼容旧前端：等同 imageId */
  id?: number
}

/** 个人资产形态图片 */
export interface UserAssetRpsFormImageRow {
  id: number
  /** v2.18.6+ 创建接口可能返回 imgId（图片实例ID） */
  imgId?: number
  formId: number
  name?: string | null
  imageUrl?: string | null
  sourceType?: string | null
  /** 列表展示用提示词；仅后端允许展示的来源返回 */
  promptText?: string | null
  isUse?: number | null
  descriptionIndex?: number | null
  referenceImages?: string[] | null
  /** 是否可拆分四宫格（scene 且未拆过、非拆分产物时为 true） */
  canSplit?: boolean | null
}

/** POST /api/user/asset/rps/form-image/create */
export interface UserAssetRpsFormImageCreateRequest {
  formId: number
  imageUrl: string
  name?: string
  sourceType: 'upload' | 'official' | 'ai_auto' | 'ai_manual' | 'migrate' | string
  asInUse?: boolean
}

/** POST /api/user/asset/rps/form-image/update */
export interface UserAssetRpsFormImageUpdateRequest {
  imageId: number
  name?: string
  imageUrl?: string
  descriptionIndex?: number
  referenceImages?: string[]
}

/** POST /api/user/asset/rps/form-image/list */
export interface UserAssetRpsFormImageListRequest {
  formId?: number
  projectId?: number
  episodeId?: number
  assetId?: number
  assetType?: UserAssetApiType | string
  /** 0 未使用 1 已使用；null / 不传查全部（编辑弹窗显式传 null 拉全量） */
  isUse?: number | null
}

/** POST /api/user/asset/rps/form-image/delete */
export interface UserAssetRpsFormImageDeleteRequest {
  imageId: number
}

/** POST /api/user/asset/rps/form-image/scene/split */
export interface UserAssetRpsSceneImageSplitRequest {
  projectId: number
  sourceImageId?: number
  sourceImageIds?: number[]
}

export interface UserAssetRpsSceneImageSplitChildRow {
  id: number
  formId: number
  formName?: string
  assetId?: number
  assetName?: string
  assetType?: string
  projectId?: number
  episodeId?: number
  name?: string
  imageUrl?: string
  sourceType?: string
  isUse?: number
  imageStatus?: string
  sortOrder?: number
}

export interface UserAssetRpsSceneImageSplitData {
  sourceImageId: number
  assetId: number
  formId: number
  children: UserAssetRpsSceneImageSplitChildRow[]
}

/** POST /api/user/asset/rps/form-image/upscale（v2.24+ 异步高清） */
export interface UserAssetRpsFormImageUpscaleRequest {
  imageId: number
  modelCode?: string
  resolution?: string
}

/** upscale 提交后立即返回 */
export interface UserAssetRpsFormImageUpscaleSubmitData {
  taskId: number
  status: string
}

/**
 * POST /api/user/asset/rps/update-main（文档 2.5）
 * id 必填；其余按需传入。支持顶层打散（推荐）或整体 profileData JSON 字符串。
 * 后端兼容驼峰字段名。
 *
 * - createSource=manual：仅 name / aliases / aliasesName 生效（见 pickManualUpdateMainFields）
 * - createSource=auto：走完整校验；expectedAppearances 须为真实数组（非 JSON 字符串）
 */
export interface UserAssetRpsExpectedAppearanceUpdateItem {
  id: number
  name: string
  /** snake_case，与文档 2.5.1 示例一致；亦可传 changeReason */
  change_reason?: string
  changeReason?: string
  /** 列表出参兼容字段，提交前应归一到 name */
  label?: string
}

export interface UserAssetRpsUpdateMainRequest {
  id: number
  name?: string
  profileData?: string

  /** —— 角色 character（2.5.1）—— */
  gender?: string
  aliases?: string
  aliasesName?: string
  ageRange?: string
  archetype?: string
  eraPeriod?: string
  occupation?: string
  roleLevel?: string
  costumeTier?: number
  introduction?: string
  socialClass?: string
  visualKeywords?: string[]
  personalityTags?: string[]
  suggestedColors?: string[]
  primaryIdentifier?: string
  /**
   * 子形象列表：真实 JSON 数组（文档 2.5.1），元素含 id / name / change_reason。
   * 勿再传 JSON 字符串。
   */
  expectedAppearances?: UserAssetRpsExpectedAppearanceUpdateItem[]

  /** —— 场景 scene（2.5.2）—— */
  summary?: string
  /** 是否有人群：0 无 / 1 有 */
  hasCrowd?: number
  crowdDescription?: string
  /**
   * 角色可落位区域：主表须传 **JSON 数组字符串**（如 `'["位置1"]'`）
   * 勿与形态接口的数组形态混淆。
   */
  availableSlots?: string

  /** —— 道具 prop（2.5.3）复用 name / summary / introduction —— */
}

/**
 * POST /api/user/asset/rps/update-form（文档 2.6）
 * id 必填；平铺模式为增量合并。若同时传 promptText 与平铺字段，以 promptText 为准。
 */
export interface UserAssetRpsUpdateFormRequest {
  id: number
  name?: string
  changeReason?: string
  promptVersion?: string
  /** 旧模式：整包 JSON 字符串 */
  promptText?: string

  /** 场景形态（2.6.1） */
  summary?: string
  introduction?: string
  hasCrowd?: number
  crowdDescription?: string
  /** 场景形态：直接传数组，勿 stringify */
  availableSlots?: string[]

  /** 角色形态（2.6.2） */
  appearanceId?: number
  /** 外观完整视觉描述，单条字符串 */
  descriptions?: string

  /** 场景/道具形态的生图提示词 */
  prompt?: string

  /** 道具形态（2.6.3）复用 summary / introduction */

  /**
   * @deprecated 图片能力走 form-image/create|delete；无特殊需求勿传
   */
  imageUrl?: string
}

/** POST /api/user/asset/rps/delete（单个 / 批量同接口） */
export interface UserAssetRpsDeleteRequest {
  /** 主资产 ID（单删；与 ids 至少传一个） */
  id?: number
  /** 形态 ID：传则只删该形态（仅单删可传，与 ids 互斥） */
  formId?: number
  /** 批量主资产 ID 列表（传了本字段即按批量出参返回；单批最多 50） */
  ids?: number[]
}

/** POST /api/user/asset/rps/delete 批量出参 data（结构同 form/use 批量） */
export interface UserAssetRpsDeleteBatchData {
  total?: number
  successCount?: number
  failCount?: number
  successIds?: number[]
  failures?: Array<{ id?: number | null; reason?: string }>
}

/** POST /api/user/asset/rps/create-other */
export interface UserAssetRpsCreateOtherRequest {
  imageUrl: string
  name?: string
}

/** POST /api/user/asset/rps/ai/extract（开发中，字段以后端为准） */
export interface UserAssetRpsAiExtractRequest {
  projectId: number
  episodeId: number
  model: string
  imageSize?: string
  resolution?: string
  refImages?: string[]
  promptText?: string
  genType: 'multicam' | 'triview'
  /** genType=multicam 时必传，JSON 字符串 */
  multicamParams?: string
  /** 可选：提取场景/角色/道具，供后端路由（若未实现则由 promptText 区分） */
  assetType?: 'scene' | 'character' | 'prop'
}

export interface UserAssetListRequest {
  projectId?: number
  episodeId?: number
  assetType?: UserAssetApiType
  assetName?: string
}

export interface UserAssetRow {
  id: number
  projectId?: number | null
  episodeId?: number | null
  assetType: UserAssetApiType | string
  assetName: string
  personalityDesc?: string | null
  refImageUrl?: string | null
  extraImages?: string | null
  sourceType?: number | null
  createTime?: string | null
  updateTime?: string | null
}

/** /api/user/oss/remote/upload、/uploads 单条结果 */
export interface OssRemoteUploadResult {
  url: string
  fileName: string
  originalFileName: string
  fileSize: number
}

/** 点选改图：点击坐标识别 */
export interface TouchEditPointDetectRequest {
  image: string
  prompt: string
}

export interface TouchEditPointDetectData {
  name?: string
  label?: string
  objectName?: string
  data?: {
    name?: string
    label?: string
    objectName?: string
  }
}

/** @deprecated 请使用 UserAssetRpsCreateRequest + /api/user/asset/rps/create */
export interface UserAssetCreateRequest {
  projectId: number
  episodeId: number
  assetType: UserAssetApiType | string
  assetName: string
  personalityDesc?: string
  refImageUrl?: string
  extraImages?: string
  sourceType?: number
}

/** /api/user/step/status & /api/user/step/advance */
export interface CreationStepRequest {
  projectId: number
  episodeId?: number
}

export type CreationStepStatus = 'completed' | 'current' | 'waiting'

export interface CreationStepItem {
  step: number
  name: string
  status: CreationStepStatus
}

export interface CreationStepState {
  currentStep: number
  steps: CreationStepItem[]
}

export interface CreationStepAdvanceRequest extends CreationStepRequest {
  completedStep: number
}

/** /api/user/script/detailByProject */
export interface ScriptDetailByProjectRequest {
  projectId: number
  episodeId: number
}

export interface ScriptDetailRow {
  id: number
  projectId: number
  episodeId: number
  originalText?: string | null
  contentHash?: string | null
  isExtracted?: number
  comicVersion?: number
  status?: number
  createTime?: string | null
  updateTime?: string | null
}

/** /api/user/script/save、/api/user/script/autoSave */
export interface ScriptSaveRequest {
  projectId: number
  episodeId: number
  originalText: string
  /** 当前编辑基线的剧本ID；尚无剧本时传0 */
  baseScriptId?: number
  /** 当前编辑基线的正文哈希 */
  baseContentHash?: string
}

/** /api/user/script/list */
export interface ScriptListRequest extends ScriptDetailByProjectRequest {
  status?: 0 | 1 | 2
  pageNum?: number
  pageSize?: number
}

export interface ScriptListResult {
  rows: ScriptDetailRow[]
  total: number
  pageNum: number
  pageSize: number
  hasMore: boolean
}

/** POST /api/user/script/upload（multipart） */
export interface ScriptUploadRequest {
  file: File
  projectId: number
  /** 电影传 0；剧集单集传集 ID；整篇导入不传或传 0 */
  episodeId?: number
}

/** 通用项目或剧集 ID 请求。 */
export interface ProjectOrEpisodeIdRequest {
  id: number
}

/** POST /api/user/project/publish */
export interface UserProjectPublishRequest {
  id: number
  projectDesc: string
  coverUrl: string
}

/** /api/user/storyboard/list 分镜最终图引用的参考图快照 */
export interface StoryboardReferenceImageSnapshot {
  n?: number
  name?: string
  assetKind?: 'character' | 'scene' | 'prop' | string | null
  assetName?: string | null
  url?: string | null
  type?: 'REFERENCE' | 'DESCRIPTION' | string | null
}

/** /api/user/storyboard/list 分镜工作台：查询分镜列表 */
export interface UserStoryboardListRequest {
  projectId: number
  /** 剧集 ID；电影传 0 或不传（与接口文档一致） */
  episodeId?: number
}

/** 分镜列表 speakerVoices[] 子项（与 speakerRoles 同序同长） */
export interface UserStoryboardSpeakerVoice {
  roleName?: string | null
  assetId?: number | null
  voiceBound?: boolean | null
  voiceLibraryId?: number | null
  voiceCode?: string | null
  voiceName?: string | null
}

export interface UserStoryboardListRow {
  id: number
  projectId: number
  episodeId?: number
  sortOrder?: number
  title?: string
  storyScript?: string | null
  /** 分镜台词配音文本（带角色标记原文，工作台编辑用） */
  dialogueText?: string | null
  /**
   * 字幕展示文本：「人物：说的话」格式（由 dialogueText 清洗格式化）。
   * 成品预览时间轴字幕轨请用本字段；无台词为 null。
   */
  subtitleText?: string | null
  /** 配音类型：narration / dialogue / mixed；无可朗读台词为 null */
  voiceType?: 'narration' | 'dialogue' | 'mixed' | string | null
  /** 发言角色主名列表（按出现顺序去重） */
  speakerRoles?: string[] | null
  /** 发言角色音色绑定列表，与 speakerRoles 同序同长 */
  speakerVoices?: UserStoryboardSpeakerVoice[] | null
  /** 配音状态：SUCCEEDED / PROCESSING / FAILED / NONE */
  audioStatus?: 'SUCCEEDED' | 'PROCESSING' | 'FAILED' | 'NONE' | string | null
  /** 分镜图脚本（图生图 prompt），由 /generate/image-prompt 写入；列表接口一般为 null */
  imagePrompt?: string | null
  /** 多参方向视频提示词，由 /generate/video-prompt 写入；列表接口一般为 null */
  videoPrompt?: string | null
  /** 图生方向视频提示词，由 /generate/video-prompt-image 写入；列表接口一般为 null */
  videoPromptImage?: string | null
  /** 宫格类型（四宫格/九宫格），仅 detail 可能有值 */
  gridType?: string | null
  /**
   * 明细专属：推荐视频时长（秒）。
   * multi/pro/auto_grid 优先 script_params.视频时长建议秒；缺失时回落模式默认视频模型时长。
   * 列表接口一般为 null。
   */
  recommendedDurationSeconds?: number | null
  /** 推荐来源：STORYBOARD_SUGGESTION / MODEL_DEFAULT */
  recommendedDurationSource?: string | null
  /** 推荐秒数说明（切换模型后按档位向上调整等） */
  recommendedDurationDescription?: string | null
  finalImageId?: number | null
  finalVideoId?: number | null
  finalAudioId?: number | null
  /** 主图完整 URL（is_selected=1 权威源，未设置主图时为 null） */
  finalImageUrl?: string | null
  /**
   * 分镜视频完整 URL（**恒为配音前原视频**，原视频轨 is_selected=1；
   * 不含配音合成 compose；配音视频从生成记录列表取）。
   */
  finalVideoUrl?: string | null
  /**
   * 配音视频主视频完整 URL（compose 类 is_selected=1 最新一条）；
   * 成片合成导出优先使用；未设置时为 null。
   */
  finalComposeVideoUrl?: string | null
  /** 最终分镜图引用的参考图快照列表 */
  referenceImages?: StoryboardReferenceImageSnapshot[] | null
  createTime?: string | null
}

/** POST /api/user/storyboard/detail 查询分镜详情 */
export interface UserStoryboardDetailRequest {
  id: number
}

/** /api/user/storyboard/create */
export interface UserStoryboardCreateRequest {
  projectId: number
  episodeId?: number
  title?: string
  /** 复制来源分镜 ID；传入时由服务端原子克隆内容与图片/原视频产物 */
  sourceStoryboardId?: number
}

/** 创建/复制响应与分镜列表单行同结构；复制时会立即返回新记录的内容与最终产物字段。 */
export type UserStoryboardCreateData = UserStoryboardListRow

/** /api/user/storyboard/delete — 单删传 `[id]`，批删传 `[id1, id2, ...]`，单次最多 200 条 */
export interface UserStoryboardDeleteRequest {
  ids: number[]
}

/** /api/user/storyboard/update */
export interface UserStoryboardUpdateRequest {
  id: number
  title?: string
  storyScript?: string
  dialogueText?: string
  sortOrder?: number
}

/** @deprecated 请使用 UserStoryboardUpdateRequest */
export type UserStoryboardSaveRequest = UserStoryboardUpdateRequest

/** /api/user/storyboard/sort */
export interface UserStoryboardSortRequest {
  sortedIds: number[]
}

/** POST /api/user/storyboard/generate/script 批量生成分镜脚本 */
export interface StoryboardGenerateScriptRequest {
  projectId: number
  episodeId: number
  sceneIds?: number[]
  agentCode?: string
  modelCode?: string
  /** StoryboardShotDensityEnum.value，如「标准模式」 */
  mode?: string
  overwrite?: boolean
}

export interface StoryboardGenerateScriptData {
  taskId: number
  status: string
  /** 本任务总批次数（一个场次=一批） */
  totalBatches?: number
  /** 提交时固定为 0，镜头数由执行阶段决定 */
  totalShots?: number
  /**
   * 非阻断提示：标准版/轻量版某场次字数算出的最低镜头数超过单次封顶时返回。
   * 前端建议 toast 展示，任务照常执行。
   */
  warning?: string | null
}

/** POST /api/user/task/resume — 统一续生（按 taskId 识别任务类型） */
export interface UserTaskResumeRequest {
  taskId: number
}

export interface UserTaskResumeData {
  taskId: number
  status: string
  totalBatches?: number
  totalShots?: number
}

/** @deprecated 请使用 UserTaskResumeRequest */
export interface StoryboardGenerateScriptResumeRequest {
  taskId: number
}

export interface StoryboardGenerateScriptResumeData {
  taskId: number
  status: string
  totalBatches?: number
}

/** POST /api/user/storyboard/generate/image-prompt 批量生成分镜图脚本 */
export interface StoryboardGenerateImagePromptRequest {
  projectId: number
  episodeId: number
  storyboardIds: number[]
  agentCode?: string
  modelCode?: string
  overwrite?: boolean
}

export interface StoryboardGenerateImagePromptData {
  taskId: number
  status: string
  totalShots?: number
}

/** POST /api/user/storyboard/generate/image-with-prompt 批量生成分镜图提示词 + 自动出图（任务3） */
export interface StoryboardGenerateImageWithPromptRequest {
  projectId: number
  episodeId: number
  storyboardIds: number[]
  /** 提示词阶段智能体编码，默认分镜画师（biz=main_storyboard_stylist） */
  agentCode?: string
  /** 提示词阶段文本模型编码 */
  modelCode?: string
  /** 是否覆盖已有 image_prompt，默认 false */
  overwrite?: boolean
  /** 出图阶段智能体编码，默认 aid_storyboard_image（biz=main_storyboard_image） */
  genAgentCode?: string
  /** 出图阶段图片模型编码 */
  genModelName?: string
  /** 出图阶段宽高比（如 16:9） */
  genAspectRatio?: string
  /** 出图阶段厂商原生 size（如 1024x1024，与 genAspectRatio 二选一） */
  genSize?: string
  /** 出图阶段业务场景标识 */
  genScenario?: string
  /** 出图阶段负向提示词 */
  genNegativePrompt?: string
}

export type StoryboardGenerateImageWithPromptData = StoryboardGenerateImagePromptData

/** @deprecated 请使用 UserTaskResumeRequest */
export interface StoryboardGenerateImagePromptResumeRequest {
  taskId: number
}

export interface StoryboardGenerateImagePromptResumeData {
  taskId: number
  status: string
  totalShots?: number
}

/** POST /api/user/storyboard/generate/video-prompt 批量生成分镜视频提示词（多参方向，写 video_prompt） */
export interface StoryboardGenerateVideoPromptRequest {
  projectId: number
  episodeId: number
  /**
   * 目标分镜 ID 列表。
   * 不传或为空 → 处理本剧集全部分镜（由 overwrite 区分继续生成/重新生成）；
   * 传了 → 仅处理这些分镜且默认覆盖。
   */
  storyboardIds?: number[]
  /** 智能体编码；默认 aid_visual_director；强校验 biz_category_code=main_storyboard_video_prompt */
  agentCode?: string
  /** 文本模型；走用户传 → 项目配置 → aid_config 三级兜底 */
  modelCode?: string
  /**
   * 仅在不传 storyboardIds（全集）时生效：
   * false=继续生成（跳过已有 video_prompt）；true=重新生成（全部覆盖）。
   */
  overwrite?: boolean
}

export interface StoryboardGenerateVideoPromptData {
  taskId: number
  status: string
  totalShots?: number
}

/** POST /api/user/storyboard/generate/video-with-prompt 批量生成分镜视频提示词 + 自动出片（任务4，按创作模式自动路由） */
export interface StoryboardGenerateVideoWithPromptRequest {
  projectId: number
  episodeId: number
  /**
   * 目标分镜 ID 列表。
   * 不传或为空 → 处理本剧集全部分镜（由 overwrite 区分继续生成/重新生成）；
   * 传了 → 仅处理这些分镜且默认覆盖。
   */
  storyboardIds?: number[]
  /** 提示词阶段智能体编码；通常留空，由后端按 creation_mode 自动解析 */
  agentCode?: string
  /** 提示词阶段文本模型编码；通常留空，由后端按当前创作模式配置兜底 */
  modelCode?: string
  /**
   * 仅在不传 storyboardIds（全集）时生效：
   * false=继续生成（跳过已有视频提示词）；true=重新生成（全部覆盖）。
   */
  overwrite?: boolean
  /** 出片阶段视频模型编码；为空按当前模式对应池兜底 */
  genModelName?: string
  /** 出片阶段宽高比 */
  genAspectRatio?: string
  /** 出片阶段视频时长（秒） */
  genDurationSeconds?: number
  /** 出片阶段清晰度档（如 1K / 720P） */
  genResolution?: string
  /** 出片阶段是否生成音频（仅部分模型支持） */
  genGenerateAudio?: boolean
}

export type StoryboardGenerateVideoWithPromptData = StoryboardGenerateVideoPromptData

/** POST /api/user/storyboard/generate/grid-video-with-prompt 批量生成宫格视频提示词 + 自动出宫格视频（仅 auto_grid） */
export interface StoryboardGenerateGridVideoWithPromptRequest {
  projectId: number
  episodeId: number
  storyboardIds: number[]
  /** 提示词阶段文本模型编码（3 级兜底解析） */
  modelCode?: string
  /** 是否覆盖已有 video_prompt_image，默认 false */
  overwrite?: boolean
  /** 出片阶段视频模型编码 */
  genModelName?: string
  /** 出片阶段宽高比 */
  genAspectRatio?: string
  /** 出片阶段视频时长（秒） */
  genDurationSeconds?: number
  /** 出片阶段清晰度档（如 1K / 720P） */
  genResolution?: string
  /** 出片阶段是否生成音频（仅部分模型支持） */
  genGenerateAudio?: boolean
}

export type StoryboardGenerateGridVideoWithPromptData = StoryboardGenerateVideoPromptData

/** @deprecated 请使用 UserTaskResumeRequest */
export interface StoryboardGenerateVideoPromptResumeRequest {
  taskId: number
}

export interface StoryboardGenerateVideoPromptResumeData {
  taskId: number
  status: string
  totalShots?: number
}

/** POST /api/user/storyboard/save/video-prompt 手动保存分镜视频提示词（v3.0，多参方向） */
export interface StoryboardSaveVideoPromptRequest {
  storyboardId: number
  videoPrompt: string
}

/** POST /api/user/storyboard/generate/video-prompt-image 批量生成图生方向分镜视频提示词（写 video_prompt_image） */
export interface StoryboardGenerateVideoPromptImageRequest {
  projectId: number
  episodeId: number
  storyboardIds?: number[]
  /** 默认 aid_visual_director_image；强校验 biz_category_code=main_storyboard_video_prompt_image */
  agentCode?: string
  /** 文本模型；走用户传 → 项目配置 → aid_config 三级兜底 */
  modelCode?: string
  /**
   * 仅在不传 storyboardIds（全集）时生效：
   * false=继续生成（跳过已有 video_prompt_image）；true=重新生成（全部覆盖）。
   */
  overwrite?: boolean
}

export type StoryboardGenerateVideoPromptImageData = StoryboardGenerateVideoPromptData

/** POST /api/user/storyboard/generate/video-prompt-grid 批量生成宫格方向分镜视频提示词（写 video_prompt_image，仅 auto_grid） */
export interface StoryboardGenerateVideoPromptGridRequest {
  projectId: number
  episodeId: number
  storyboardIds?: number[]
  /** 默认 aid_visual_director_grid；强校验 biz_category_code=main_storyboard_video_prompt_grid */
  agentCode?: string
  modelCode?: string
  overwrite?: boolean
}

export type StoryboardGenerateVideoPromptGridData = StoryboardGenerateVideoPromptData

/** POST /api/user/storyboard/setFinal 设置分镜最终产物（image/video/audio） */
export interface StoryboardSetFinalRequest {
  storyboardId: number
  recordId: number
  recordType: 'image' | 'video' | 'audio'
}

/** POST /api/user/storyboard/image-prompt/resolve 解析 image_prompt 中的 @图片N[name] / @音频N[...] */
export interface StoryboardImagePromptResolveRequest {
  projectId: number
  episodeId: number
  imagePrompt?: string | null
  /** 用户显式选择的系统配音记录 ID（aid_audio_record） */
  referenceAudioRecordIds?: number[] | null
  /** 用户显式选择的上传参考音频 ID（aid_reference_audio） */
  referenceAudioIds?: number[] | null
}

/** image-prompt/resolve 出参 references[] 子项 */
export interface StoryboardImagePromptReferenceItem {
  n?: number | null
  name?: string | null
  imageId?: number | null
  url?: string | null
}

/** image-prompt/resolve 出参 audioReferences[] 子项 */
export type StoryboardAudioReferenceSourceType = 'VOICE_SAMPLE' | 'AUDIO_RECORD' | 'UPLOAD'

export interface StoryboardAudioReferenceItem {
  index?: number | null
  name?: string | null
  sourceType?: StoryboardAudioReferenceSourceType | string | null
  assetId?: number | null
  bindingId?: number | null
  voiceLibraryId?: number | null
  audioRecordId?: number | null
  referenceAudioId?: number | null
  voiceName?: string | null
  sampleUrl?: string | null
  format?: string | null
  durationMs?: number | null
}

export interface StoryboardImagePromptResolveData {
  referenceImageIds: number[]
  referenceImageUrls: string[]
  unresolvedNames: string[]
  /** 解析成功的参考图明细（按名称认图）；优先于平行数组 */
  references?: StoryboardImagePromptReferenceItem[] | null
  referenceAudioUrls?: string[] | null
  audioReferences?: StoryboardAudioReferenceItem[] | null
  unresolvedAudioNames?: string[] | null
  unresolvedAudioRecordIds?: number[] | null
  unresolvedReferenceAudioIds?: number[] | null
}

/** POST /api/user/reference-audio/upload 登记用户上传参考音频 */
export interface ReferenceAudioUploadRequest {
  projectId: number
  /** 剧集项目必传；电影不传（服务端归一 0） */
  episodeId?: number
  audioName: string
  /** oss/upload 返回的本站相对路径，禁止外链 */
  audioUrl: string
}

export interface ReferenceAudioVO {
  id: number
  userId?: number
  projectId?: number
  episodeId?: number
  audioName: string
  audioUrl: string
  durationMs?: number | null
  audioFormat?: string | null
  fileSize?: number | null
  status?: string | null
  createTime?: string | null
}

export interface ReferenceAudioDeleteRequest {
  id: number
}

/** /api/user/storyboard/generate/media */
export type StoryboardGenerateMediaType = 'image' | 'grid' | 'i2v' | 'multi' | 'edge'

/** list-by-storyboard 出参 genType（含 upload_video / compose 配音合成视频） */
export type StoryboardRecordGenType =
  | StoryboardGenerateMediaType
  | 'upload_video'
  | 'compose'

export interface StoryboardGenerateMediaParams {
  sceneIds?: string
  characterIds?: string
  propIds?: string
  poseIds?: string
  expressionIds?: string
  effectIds?: string
  sketchIds?: string
  shotSize?: string
  cameraAngle?: string
  focalLength?: string
  colorTone?: string
  lighting?: string
  exposureBlur?: string
  imagePrompt?: string
  cameraMovement?: string
  shootingTechnique?: string
  videoPrompt?: string
}

export interface StoryboardGenerateMediaRequest {
  storyboardId: number
  genType: StoryboardGenerateMediaType
  modelId: number
  userInputText?: string
  promptId?: number
  baseImageId?: number
  firstImageId?: number
  lastImageId?: number
  videoDuration?: number
  soundDesc?: string
  genParams?: StoryboardGenerateMediaParams
}

export interface StoryboardGenerateMediaData {
  id: number
  storyboardId: number
  genType: StoryboardGenerateMediaType
  fileUrl?: string | null
  modelId: number
  userInputText?: string | null
  baseImageId?: number | null
  firstImageId?: number | null
  lastImageId?: number | null
  videoDuration?: number | null
  soundDesc?: string | null
  costCredits?: number | null
  isSelected?: number | null
  taskId?: number | null
  status?: 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | string
  createTime?: string | null
}

/** 分镜视频出片批量受理项 */
export interface StoryboardVideoGenerateItemResult {
  storyboardId: number
  accepted: boolean
  reason?: string | null
}

/** POST /api/user/storyboard/generate/video（v2.58+ 多参生视频，支持批量） */
export interface StoryboardVideoGenerateRequest {
  storyboardIds: number[]
  modelName?: string
  videoPrompt?: string | null
  baseImageRecordId?: number | null
  /** 单镜头临时参考图：key = @图片N[name] 的 name，value = 外部图片 URL */
  referenceOverrides?: Record<string, string> | null
  aspectRatio?: string
  durationSeconds?: number
  /** 清晰度档（如 1K / 2K / 720P）；须命中模型 capability.sizeOptions */
  resolution?: string
  count?: number
  generateAudio?: boolean
  /** 单镜头：系统配音记录 ID（本功能不用） */
  referenceAudioRecordIds?: number[] | null
  /** 单镜头：用户上传参考音频 ID（aid_reference_audio） */
  referenceAudioIds?: number[] | null
  /** 单镜头：参考视频生成记录 ID（aid_gen_record），按用户选择顺序提交 */
  referenceVideoRecordIds?: number[] | null
  userInputText?: string | null
}

export interface StoryboardVideoGenerateData {
  taskId: number
  status: 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'PARTIAL_FAILED' | 'FAILED' | 'CANCELLED' | string
  modelName?: string
  totalShots?: number
  countPerShot?: number
  totalSubtasks?: number
  items?: StoryboardVideoGenerateItemResult[]
  /** @deprecated 旧版单镜头字段，批量接口不再返回 */
  storyboardId?: number
  count?: number
}

/** POST /api/user/storyboard/generate/video/image（图生方向，支持批量） */
export interface StoryboardVideoImageGenerateRequest {
  storyboardIds: number[]
  /** 仅单镜头生效；不传/多镜头各镜头回落 final_image_id */
  images?: string[]
  videoPrompt?: string | null
  modelName?: string
  baseImageRecordId?: number | null
  aspectRatio?: string
  durationSeconds?: number
  /** 清晰度档（如 1K / 2K / 720P）；须命中模型 capability.sizeOptions */
  resolution?: string
  count?: number
  generateAudio?: boolean
  referenceAudioRecordIds?: number[] | null
  referenceAudioIds?: number[] | null
  userInputText?: string | null
}

export type StoryboardVideoImageGenerateData = StoryboardVideoGenerateData

/** POST /api/user/storyboard/generate/video/edge（首尾帧方向，支持批量） */
export interface StoryboardVideoEdgeFrameItem {
  storyboardId: number
  firstImageUrl?: string
  firstImageRecordId?: number
  lastImageUrl?: string
  lastImageRecordId?: number
  audios?: Array<{ audioUrl: string; durationSeconds: number }>
  /** 该分镜上传参考音频 ID */
  referenceAudioIds?: number[] | null
}

export interface StoryboardVideoEdgeGenerateRequest {
  storyboardIds: number[]
  items: StoryboardVideoEdgeFrameItem[]
  modelName?: string
  videoPrompt?: string | null
  aspectRatio?: string
  durationSeconds?: number
  /** 清晰度档（如 1K / 2K / 720P）；须命中模型 capability.sizeOptions */
  resolution?: string
  count?: number
  generateAudio?: boolean
  userInputText?: string | null
}

export type StoryboardVideoEdgeGenerateData = StoryboardVideoGenerateData

/** POST /api/user/storyboard/generate/video/grid（宫格方向，仅 auto_grid 创作模式） */
export interface StoryboardVideoGridGenerateRequest {
  storyboardIds: number[]
  videoPrompt?: string | null
  modelName?: string
  aspectRatio?: string
  durationSeconds?: number
  /** 清晰度档（如 1K / 2K / 720P）；须命中模型 capability.sizeOptions */
  resolution?: string
  count?: number
  generateAudio?: boolean
  referenceAudioRecordIds?: number[] | null
  referenceAudioIds?: number[] | null
  userInputText?: string | null
}

export type StoryboardVideoGridGenerateData = StoryboardVideoGenerateData

/** @deprecated 请使用 UserTaskResumeRequest */
export interface StoryboardVideoResumeRequest {
  taskId: number
}

/** @deprecated 请使用 UserTaskResumeRequest */
export interface StoryboardGenerateImageResumeRequest {
  taskId: number
}

/** 分镜图出图批量受理项 */
export interface StoryboardImageGenerateItemResult {
  storyboardId: number
  accepted: boolean
  reason?: string | null
}

/** POST /api/user/storyboard/generate/image（v2.63+ 批量父任务 + 智能体标准链路） */
export interface StoryboardGenerateImageRequest {
  storyboardIds: number[]
  agentCode?: string
  /** 仅单镜头生效；多镜头各镜头回落库内 image_prompt */
  imagePrompt?: string
  modelName?: string
  aspectRatio?: string
  size?: string
  /** 仅单镜头（storyboardIds 长度==1）生效，范围 [1,8]；多镜头恒 1 */
  count?: number
  scenario?: string
  negativePrompt?: string
  userInputText?: string
}

export interface StoryboardGenerateImageData {
  taskId: number
  status: 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'PARTIAL_FAILED' | 'FAILED' | 'CANCELLED' | string
  modelName?: string
  totalShots?: number
  countPerShot?: number
  totalSubtasks?: number
  items?: StoryboardImageGenerateItemResult[]
  /** @deprecated 旧版单镜头字段，批量接口不再返回 */
  storyboardId?: number
  imageUrl?: string | null
  providerTaskId?: string | null
  recordId?: number | null
}

/** GET /api/user/media/task/{taskId} */
export interface MediaTaskDetail {
  id: number
  status: 'PENDING' | 'QUEUED' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | string
  ossUrl?: string | null
  imageUrl?: string | null
  originUrl?: string | null
  errorMessage?: string | null
  mediaType?: string | null
  bizTaskType?: string | null
  eta?: TaskEtaData
}

/** 项目内生成内容列表 type：image 含 image/grid；video 含 i2v/multi/edge */
export type StoryboardRecordListType = 'image' | 'video' | 'compose'

/** @deprecated 请使用 StoryboardRecordListType */
export type StoryboardRecordListGenType = StoryboardRecordListType

/** POST /api/user/storyboard/record/list-by-storyboard 入参（v2.57.7） */
export interface StoryboardRecordListByStoryboardRequest {
  projectId: number
  /** 电影项目固定 0；剧集项目必须 > 0 */
  episodeId: number
  type: StoryboardRecordListType
}

/** POST /api/user/storyboard/record/list-by-storyboard 出参行 */
export interface StoryboardRecordListByStoryboardRow {
  id: number
  /** 展示名：分镜{镜号}-图片{序号} / 分镜{镜号}-视频{序号} */
  displayName?: string | null
  storyboardId: number
  genType: StoryboardRecordGenType | string
  fileUrl?: string | null
  /** 是否被选为最终分镜产物：1=是、0=否（同分镜+同 genType 大类内互斥） */
  isSelected?: number | null
  createTime?: string | null
  /** 旧 /record/list 可能返回；list-by-storyboard v2.57.7 不返回 */
  status?: 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | string
  taskId?: number | null
}

/** 生成记录行（与 list-by-storyboard 出参一致） */
export type StoryboardRecordRow = StoryboardRecordListByStoryboardRow

/** POST /api/user/storyboard/setFinalImage 设置分镜最终图片 */
export interface StoryboardSetFinalImageItem {
  storyboardId: number
  recordId: number
}

export interface StoryboardSetFinalImageRequest {
  projectId?: number
  episodeId?: number
  storyboardId?: number
  recordId?: number
  items?: StoryboardSetFinalImageItem[]
}

/** POST /api/user/storyboard/setFinalImage 批量出参 data */
export interface StoryboardSetFinalImageBatchData {
  total?: number
  successCount?: number
  failCount?: number
  successIds?: number[]
  failures?: Array<{ id?: number | null; reason?: string }>
}

/** POST /api/user/storyboard/unSetFinalImage 取消分镜最终图片（v2.58） */
export type StoryboardUnSetFinalImageRequest = StoryboardSetFinalImageRequest

/** POST /api/user/storyboard/setFinalVideo 设置分镜最终视频（v2.62） */
export type StoryboardSetFinalVideoRequest = StoryboardSetFinalImageRequest

/** POST /api/user/storyboard/unSetFinalVideo 取消分镜最终视频（v2.62） */
export type StoryboardUnSetFinalVideoRequest = StoryboardSetFinalVideoRequest

/** POST /api/user/storyboard/unSetFinalAudio 取消分镜最终配音（与 unSetFinalVideo 对称） */
export type StoryboardUnSetFinalAudioRequest = StoryboardSetFinalImageRequest

/** POST /api/user/storyboard/record/delete 物理删除分镜生成记录（分镜图 / 分镜视频） */
export type StoryboardRecordDeleteRequest = StoryboardSetFinalImageRequest

/** POST /api/user/storyboard/upload 用户自行上传分镜媒体（图片 / 视频） */
export type StoryboardUploadMediaType = 'image' | 'video'

export interface StoryboardUploadRequest {
  projectId: number
  episodeId: number
  storyboardId: number
  /** 已上传到 OSS 的图片或视频 URL（字段名按接口约定为 imageUrl） */
  imageUrl: string
  /** 不传默认 image；image→gen_type=image，video→gen_type=upload_video */
  mediaType?: StoryboardUploadMediaType
  /** 视频时长（秒）；mediaType=video 时必填，由浏览器读取媒体元数据后传入 */
  videoDuration?: number
}

/** POST /api/user/storyboard/upload 出参 data */
export interface StoryboardUploadData {
  id: number
  storyboardId: number
  /** image 或 upload_video */
  genType: string
  fileUrl?: string | null
  modelId?: number | null
  userInputText?: string | null
  costCredits?: number | null
  isSelected?: number | null
  status?: string | null
  videoDuration?: number | null
  createTime?: string | null
}

/** @deprecated 请使用 StoryboardUploadRequest（mediaType=image, imageUrl） */
export type StoryboardUploadImageRequest = Omit<StoryboardUploadRequest, 'mediaType'> & {
  mediaType?: 'image'
}

/** @deprecated 请使用 StoryboardUploadData */
export type StoryboardUploadImageData = StoryboardUploadData

/** @deprecated 请使用 StoryboardUploadRequest（mediaType=video, imageUrl）；videoUrl 仅作兼容别名 */
export type StoryboardUploadVideoRequest = Omit<StoryboardUploadRequest, 'imageUrl' | 'mediaType'> & {
  imageUrl?: string
  videoUrl?: string
  mediaType?: 'video'
}

/** @deprecated 请使用 StoryboardUploadData */
export type StoryboardUploadVideoData = StoryboardUploadData

/** @deprecated 新版 upload 接口仅支持 image/video，不再使用 genType/fileUrl */
export type StoryboardUploadGenType = StoryboardUploadMediaType

/** POST /api/user/storyboard/generate/audio */
export interface StoryboardGenerateAudioRequest {
  storyboardId: number
  ttsText: string
  voiceLibraryId?: number
  voiceModelId?: number
  timbreCode?: string
  emotion?: string
  emotionScale?: number
  speechRate?: number
  loudnessRate?: number
  pitch?: number
  audioFormat?: string
  sampleRate?: number
}

/** 对口型任务状态（派生字段） */
export type StoryboardLipSyncStatus =
  | 'PROCESSING'
  | 'SUCCEEDED'
  | 'FAILED'
  | string
  | null

/** GET /api/user/storyboard/audio/{taskId}、generate/audio 共用 AudioTaskVO（单个 /lipSync 已改为返回 taskId） */
export interface StoryboardAudioTaskVO {
  id: number
  storyboardId?: number
  audioSource?: number
  /** OSS/CDN URL；PROCESSING/FAILED 时可能为 null */
  audioUrl?: string | null
  /** 音频时长（毫秒）；MiniMax 系有值，豆包系可能为 null */
  durationMs?: number | null
  /** 落库配音文本（服务端清洗后的正文） */
  ttsText?: string
  voiceModelId?: number
  timbreCode?: string
  voiceLibraryId?: number | null
  enableLipSync?: number
  /** 配音（TTS）本身状态，非对口型状态 */
  status?: string
  errorMessage?: string | null
  /** 对口型合成视频 URL；仅 lipSyncStatus=SUCCEEDED 后回填 */
  syncVideoUrl?: string | null
  /** 对口型任务状态：null=未发起 / PROCESSING / SUCCEEDED / FAILED */
  lipSyncStatus?: StoryboardLipSyncStatus
  createTime?: string | null
}

/** POST /api/user/storyboard/lipSync */
export interface StoryboardLipSyncRequest {
  storyboardId: number
  voiceLibraryId?: number
  voiceModelId?: number
  timbreCode?: string
  emotion?: string
  emotionScale?: number
  speechRate?: number
  loudnessRate?: number
  pitch?: number
}

/** POST /api/user/storyboard/lipSync/batch */
export interface StoryboardLipSyncBatchRequest {
  projectId: number
  episodeId: number
  storyboardIds?: number[]
  voiceLibraryId?: number
  voiceModelId?: number
  timbreCode?: string
  overwrite?: boolean
  emotion?: string
  emotionScale?: number
  speechRate?: number
  loudnessRate?: number
  pitch?: number
}

/** POST /api/user/storyboard/lipSync 与 /lipSync/batch 受理返回（AssetExtractTaskVO） */
export interface StoryboardLipSyncBatchAcceptVO {
  taskId: number
  status?: string
  totalCount?: number
}

/** 单个对口型受理返回（与批量同形） */
export type StoryboardLipSyncAcceptVO = StoryboardLipSyncBatchAcceptVO

/** SSE 终态 items[] 子项（单个 / 批量对口型） */
export interface StoryboardLipSyncBatchResultItem {
  storyboardId: number
  speakerRoles?: string[] | null
  voiceLibraryId?: number | null
  audioRecordId?: number | null
  audioUrl?: string | null
  durationMs?: number | null
  sourceVideoRecordId?: number | null
  lipSyncVideoRecordId?: number | null
  lipSyncVideoUrl?: string | null
  status?: string
  errorMessage?: string | null
}

/** SSE complete / partial_failed data（批量对口型） */
export interface StoryboardLipSyncBatchResultData {
  totalCount?: number
  successCount?: number
  failCount?: number
  items?: StoryboardLipSyncBatchResultItem[]
}

/** POST /api/user/storyboard/generate/audio/batch */
export interface StoryboardAudioBatchRequest {
  projectId: number
  episodeId: number
  storyboardIds?: number[]
  voiceLibraryId?: number
  voiceModelId?: number
  timbreCode?: string
  overwrite?: boolean
  emotion?: string
  emotionScale?: number
  speechRate?: number
  loudnessRate?: number
  pitch?: number
  resolution?: 'SD' | 'HD' | 'FHD' | '2K' | '4K' | string
}

/** POST /api/user/storyboard/generate/audio/batch 受理返回 */
export interface StoryboardAudioBatchAcceptVO {
  taskId: number
  status?: string
  totalCount?: number
}

/** SSE 终态 items[] 子项 */
export interface StoryboardAudioBatchResultItem {
  storyboardId: number
  /** 发言角色主名（台词解析，展示用） */
  speakerRoles?: string[] | null
  /** 该分镜实际使用的音色库 ID */
  voiceLibraryId?: number | null
  audioRecordId?: number | null
  originalVideoRecordId?: number | null
  dubbedVideoRecordId?: number | null
  dubbedVideoUrl?: string | null
  status?: string
  errorMessage?: string | null
}

/** SSE complete / partial_failed data */
export interface StoryboardAudioBatchResultData {
  totalCount?: number
  successCount?: number
  failCount?: number
  items?: StoryboardAudioBatchResultItem[]
}

/** POST /api/user/storyboard/generate/dubbing — 分镜音画同步（异步，SSE + dubbing/{taskId} 查询） */
export interface StoryboardGenerateDubbingRequest {
  storyboardId: number
  ttsText: string
  voiceLibraryId?: number
  voiceModelId?: number
  timbreCode?: string
  emotion?: string
  emotionScale?: number
  enableLipSync?: number
  sourceVideoUrl?: string
}

/** GET /api/user/storyboard/dubbing/{taskId} 与 generate/dubbing 返回 */
export interface StoryboardDubbingTaskVO {
  id: number
  taskId?: number
  storyboardId?: number
  status?: string
  errorMessage?: string | null
  syncVideoUrl?: string | null
  videoUrl?: string | null
  audioUrl?: string | null
}

/** POST /api/user/compose/voiceover — voiceover 子对象 */
export interface VoiceoverParam {
  voiceModelId?: number
  voiceLibraryId?: number
  timbreCode?: string
  ttsTexts?: string[]
  /** 逐段音色，下标与 storyboardIds 对齐 */
  voiceLibraryIds?: Array<number | null>
}

/** POST /api/user/compose/voiceover */
export interface StoryboardComposeRequest {
  storyboardIds: number[]
  voiceover: VoiceoverParam
  resolution?: 'SD' | 'HD' | 'FHD' | '2K' | '4K' | string
  projectId?: number
  episodeId?: number
}

/** POST /api/user/compose/voiceover 出参 data */
export interface ComposeAcceptResult {
  composeBatchId: string
  audioRecordIds: number[]
  status: string
}

/** POST /api/user/compose/status 入参 */
export interface ComposeStatusRequest {
  composeBatchId: string
}

/** POST /api/user/compose/status 出参 data */
export interface ComposeStatusResult {
  composeBatchId: string
  status: 'VOICING' | 'COMPOSING' | 'SUCCEEDED' | 'FAILED' | string
  audioTotal: number
  audioSucceeded: number
  audioFailed: number
  videoUrl: string | null
  videoDuration: number | null
  errorMessage: string | null
}

/** POST /api/user/episode/export — groups 元素 */
export interface EpisodeExportComposeGroup {
  storyboardId: number
  videoUrls: string[]
  videoDurations?: number[]
  audioUrls?: string[]
  audioDurations?: number[]
  subtitle?: string | null
  subtitleCues?: TimedSubtitleCue[] | null
  subtitleSourceMediaFingerprint?: string | null
  bgmUrl?: string | null
}

/** POST /api/user/episode/export */
export interface EpisodeExportRequest {
  episodeEditorId?: number | null
  projectId?: number
  episodeId?: number
  globalBgmUrl?: string
  groups: EpisodeExportComposeGroup[]
  resolution?: 'SD' | 'HD' | 'FHD' | '2K' | '4K' | string
  timelineJson?: string
  forceRecompose?: boolean
}

/** POST /api/user/episode/export 出参 data */
export interface EpisodeExportResult {
  episodeEditorId: number
  /** 字幕同步阶段尚未创建 MPS 任务时为 null；仅用于排查，不作为前端轮询主键 */
  exportTaskId?: string | null
  exportStatus: number
  reused?: boolean
  finalVideoUrl?: string | null
}

/** POST /api/user/episode/export/status */
export interface EpisodeExportStatusRequest {
  episodeEditorId?: number | null
  projectId?: number
  episodeId?: number
}

/** POST /api/user/episode/export/status 出参 data */
export interface EpisodeExportStatusResult {
  episodeEditorId: number
  projectId?: number
  episodeId?: number
  exportStatus: number
  exportProgress?: number | null
  finalVideoUrl?: string | null
  coverUrl?: string | null
  errorMsg?: string | null
  exportTaskId?: string | null
  pendingVideoUrl?: string | null
}

/** POST /api/user/episode/timeline/get | save */
export interface EpisodeTimelineGetRequest {
  episodeEditorId?: number | null
  projectId?: number
  episodeId?: number
  rebuild?: boolean
}

export interface TimelineVideoItem {
  genRecordId?: number | null
  url?: string | null
  durationSeconds?: number
  volume?: number
  muted?: boolean
}

export interface TimelineVoiceItem {
  audioRecordId?: number | null
  url?: string | null
  durationSeconds?: number
  volume?: number
  muted?: boolean
  ttsText?: string | null
  voiceLibraryId?: number | null
  voiceModelId?: number | null
  timbreCode?: string | null
  voiceName?: string | null
  emotion?: string | null
  speed?: number | null
  pitch?: number | null
}

export interface TimedSubtitleCue {
  startSeconds?: number | null
  endSeconds?: number | null
  speaker?: string | null
  text?: string | null
  source?: string | null
}

export interface TimelineSubtitleItem {
  text?: string | null
  fontSize?: number
  fontColor?: string
  fontFamily?: string | null
  position?: string
  show?: boolean
  cues?: TimedSubtitleCue[] | null
  sourceMediaFingerprint?: string | null
  sourceDialogueFingerprint?: string | null
  recognitionStatus?: 'PROCESSING' | 'COMPLETED' | 'TEXT_FALLBACK' | 'FAILED' | string | null
  recognitionProvider?: string | null
  recognitionUpdatedAt?: string | null
  recognitionError?: string | null
}

export interface TimelineBgm {
  url?: string | null
  name?: string | null
  volume?: number
  loop?: boolean
  fade?: boolean
}

export interface TimelineSegment {
  storyboardId?: number | null
  sortOrder?: number
  video: TimelineVideoItem
  voice: TimelineVoiceItem
  subtitle: TimelineSubtitleItem
}

export interface TimelineData {
  version?: number
  resolution?: string
  totalDurationSeconds?: number
  segments: TimelineSegment[]
  bgm: TimelineBgm
  extraJson?: string | null
}

export interface EpisodeTimelineResult {
  episodeEditorId: number
  projectId?: number
  episodeId?: number
  exportStatus?: number
  exportProgress?: number
  finalVideoUrl?: string | null
  errorMsg?: string | null
  timeline: TimelineData
}

export interface EpisodeTimelineSaveRequest {
  episodeEditorId?: number | null
  projectId?: number
  episodeId?: number
  timeline: TimelineData
}

/** POST /api/user/episode/export/segments */
export interface EpisodeSegmentVideosRequest {
  projectId: number
  episodeId: number
}

/** POST /api/user/episode/export/segments/zip（二进制 zip 流，不走信封加密） */
export interface EpisodeSegmentZipDownloadRequest {
  projectId: number
  episodeId: number
  includeImages?: boolean
  includeVideos?: boolean
  includeAudios?: boolean
  includeSubtitles?: boolean
}

/** POST /api/user/episode/export/download（成片 mp4 附件流；二选一） */
export interface EpisodeFinalVideoDownloadRequest {
  episodeEditorId?: number | null
  projectId?: number
  /** 电影固定传 0 */
  episodeId?: number
}

/** POST /api/user/script/split/preview | confirm */
export interface ScriptSplitPreviewRequest {
  projectId: number
  scriptText: string
  episodeKeyword?: string | null
}

export interface ScriptSplitPreviewItem {
  episodeNo: number
  title: string
  description?: string | null
  charCount?: number | null
}

export interface ScriptSplitPreviewVO {
  totalEpisodes: number
  totalCharCount?: number | null
  episodeKeyword?: string | null
  items: ScriptSplitPreviewItem[]
}

export interface ScriptSplitConfirmEpisodeItem {
  episodeId: number
  episodeNo: number
  title: string
  description?: string | null
  scriptId?: number | null
}

export interface ScriptSplitConfirmVO {
  totalEpisodes: number
  episodes: ScriptSplitConfirmEpisodeItem[]
}

export interface EpisodeSegmentVideoItem {
  storyboardId: number
  sortOrder?: number | null
  title?: string | null
  genRecordId?: number | null
  videoUrl?: string | null
  videoDurationSeconds?: number | null
  audioRecordId?: number | null
  audioUrl?: string | null
  audioDurationMs?: number | null
  lipSyncVideoUrl?: string | null
  subtitle?: string | null
  hasDubbing?: boolean
}

export interface EpisodeSegmentVideosResult {
  projectId: number
  episodeId: number
  totalSegments?: number
  videoReadyCount?: number
  dubbedCount?: number
  items: EpisodeSegmentVideoItem[]
}

/** POST /api/user/invite/check */
export interface InviteCodeCheckRequest {
  inviteCode: string
}

export interface InviteCodeCheckVO {
  valid: boolean
  reason?: string | null
  inviterNickName?: string | null
  inviterAvatar?: string | null
}

/** POST /api/user/invite/info */
export interface InviteInfoVO {
  enabled: boolean
  inviteCode?: string
  rebateRatio?: number
  rebateMaxPerOrder?: number
  invitedCount?: number
  totalRebate?: number
}

/** POST /api/user/invite/users */
export interface InviteUsersRequest {
  pageNum?: number
  pageSize?: number
}

export interface InvitedUserVO {
  nickName?: string
  avatar?: string
  totalRebate?: number
  registerTime?: string
}

/** POST /api/user/invite/rebates */
export interface InviteRebatesRequest {
  pageNum?: number
  pageSize?: number
}

export interface InviteRebateItemVO {
  nickName?: string
  orderCredits?: number
  rebateRatio?: number
  rebateAmount?: number
  status?: 'granted' | 'revoked' | string
  statusName?: string
  createTime?: string
}

/** preview_video 明细 content 中与导出相关的字段 */
export interface PreviewVideoEditorContent {
  timelineJson?: string | null
  exportStatus?: number | null
  exportProgress?: number | null
  errorMsg?: string | null
}

/** POST /api/user/voice/preview */
export interface VoicePreviewRequest {
  text: string
  voiceModelId: number
  timbreCode?: string
}

/** POST /api/user/voice/preview 出参 data */
export interface VoicePreviewResult {
  audioUrl?: string | null
  audioBase64?: string | null
  durationMs?: number | null
}

/** /api/user/model/list */
export type AiModelType = 'text' | 'image' | 'video' | 'audio'

export interface UserModelListRequest {
  modelType?: AiModelType
  /** 生成模式细分，如 image_to_video */
  generateMode?: string
}

export interface UserModelCapability {
  aspectRatioOptions?: string[]
  defaultAspectRatio?: string
  sizeOptions?: string[]
  defaultSize?: string
  defaultOutputCount?: number
  durationOptions?: number[]
  defaultDurationSeconds?: number
  /** 提示词最大字符数；服务端按 Java String.length() 校验。 */
  maxPromptCharacters?: number
  /** 提示词包含中日韩文字时的最大字符数；缺失时回退 maxPromptCharacters。 */
  maxPromptCharactersCjk?: number
  /** 是否支持音画同出；视频模型必返，未配置为 false */
  supportsAudio?: boolean
  /** 是否支持外部参考音频（音色克隆参考）；视频模型必返，缺失视为 false */
  supportsReferenceAudio?: boolean
  /** 单次最多参考音频数量；不支持时为 0 */
  maxReferenceAudios?: number
  referenceAudioMinDurationSeconds?: number
  referenceAudioMaxDurationSeconds?: number
  referenceAudioMaxTotalDurationSeconds?: number
  /** 支持格式白名单，如 ["wav","mp3"]；不支持时为 [] */
  referenceAudioFormats?: string[]
  /** 是否支持参考视频输入；视频模型未配置时视为 false */
  supportsVideoInput?: boolean
  /** 单次最大参考视频数；-1 表示不限制，0 表示不支持 */
  maxReferenceVideos?: number
  referenceVideoMinDurationSeconds?: number
  referenceVideoMaxDurationSeconds?: number
  referenceVideoMaxTotalDurationSeconds?: number
  referenceVideoFormats?: string[]
  /** 部分动态模型以输入模态列表补充声明视频能力 */
  allowedInputs?: string[]
  allowCustomWH?: boolean
  sceneRules?: Record<string, unknown>
}

export interface UserModelListItem {
  id: number
  modelCode: string
  capabilityCode?: string | null
  parameterSchema?: import('./modelParameters').ModelParameter[] | null
  parameterRules?: import('./modelParameters').ModelParameterRule[] | null
  legacyModelCodes?: string[]
  legacyModelIds?: number[]
  modelName: string
  modelType: AiModelType
  costCredits?: number | null
  /** 模型级免费状态；true 时实际任务费用为 0，原价格仍保留展示 */
  isFree?: boolean
  priority?: number | null
  providerName?: string | null
  /** 服务商 LOGO 图标 URL（厂家品牌图标，无配置时为 null） */
  providerLogo?: string | null
  generateMode?: string | null
  maxOutputCount?: number | null
  defaultOutputCount?: number | null
  supportsAspectRatio?: boolean | null
  supportsDuration?: boolean | null
  supportsMultiImageInput?: boolean | null
  defaultAspectRatio?: string | null
  defaultSizeCode?: string | null
  defaultDurationSeconds?: number | null
  /** 部分接口会在顶层冗余返回 sizeOptions（权威仍以 capability.sizeOptions 为准） */
  sizeOptions?: string[] | null
  capability?: UserModelCapability | null
  /** 模型计费明细（与 /api/public/billing/detail 单模型结构一致） */
  billing?: ModelBillingDetailVO | null
}

/** POST /api/user/model/listByFunc 批量入参 */
export interface UserModelListByFuncRequest {
  /** 单个功能编码（兼容旧调用） */
  funcCode?: string
  /** 多个功能编码，一次拉取各池模型列表 */
  funcCodes?: string[]
  /** 传了则按项目创作模式重映射出片池（如 pro 下 main_storyboard_video → multi_pro） */
  projectId?: number
  /** 剧集类项目传剧集 ID；电影固定 0 */
  episodeId?: number
}

/** POST /api/user/model/listByFunc 批量出参分组 */
export interface UserModelListByFuncGroupVO {
  funcCode?: string | null
  funcName?: string | null
  modelType?: AiModelType | string | null
  generateMode?: string | null
  models?: UserModelListItem[]
}

/** /api/user/asset/extract/estimate */
export type AssetExtractType = 'character' | 'scene' | 'prop'

export interface UserAssetExtractEstimateRequest {
  projectId: number
  episodeId?: number
  extractTypes?: AssetExtractType[]
}

export interface UserAssetExtractEstimateData {
  projectType?: 'movie' | 'series' | string
  extractTypes?: AssetExtractType[]
  characterTotalChars?: number
  episodeCount?: number
  characterGroupCount?: number
  characterGroupName?: string
  existingCharacterCount?: number
  scenePropTotalChars?: number
  scenePropTypes?: AssetExtractType[]
}

/** C 端：POST /aid/agent/list（按 bizCategoryCodes 分组返回） */
export interface AidAgentListRequest {
  bizCategoryCodes?: string[]
  /** 传了则按项目创作模式裁剪候选智能体 */
  projectId?: number
  /** 剧集类项目传剧集 ID；电影固定 0 */
  episodeId?: number
}

export interface AgentInfoVO {
  id?: number
  agentCode?: string
  name?: string
  subTitle?: string
  introduction?: string
  /** 智能体图标：POST /aid/agent/list */
  iconUrl?: string | null
  /** 智能体图标：gen-config/get 的 agentOptions / 场景级字段 */
  agentIconUrl?: string | null
  modelCode?: string | null
  temperature?: number | null
  topP?: number | null
  maxTokens?: number | null
  bizCategoryCode?: string
  status?: number
}

export interface AgentListGroupVO {
  bizCategoryCode?: string | null
  agents?: AgentInfoVO[]
}

/** 项目级生成配置 sceneCode（固定 16 个，见接口文档 Project Generation Config） */
export type ProjectGenConfigSceneCode =
  | 'main_character_extract'
  | 'main_scene_extract'
  | 'main_prop_extract'
  | 'main_character_form'
  | 'main_scene_form'
  | 'main_prop_form'
  | 'main_character_image'
  | 'main_scene_image'
  | 'main_prop_image'
  | 'main_character_card_image'
  | 'main_storyboard_script'
  | 'main_storyboard_stylist'
  | 'main_storyboard_video_prompt'
  | 'main_storyboard_video_prompt_image'
  | 'main_storyboard_video_prompt_grid'
  | 'main_storyboard_image'

export type ProjectGenConfigSource = 'project' | 'default' | 'none'

/** POST /api/user/project/gen-config/get — 单场景配置 */
export interface ProjectGenConfigVO {
  sceneCode: ProjectGenConfigSceneCode | string
  agentCode?: string | null
  /** 当前已选智能体图标（场景级；agentOptions 单项也可能带同名字段） */
  agentIconUrl?: string | null
  modelCode?: string | null
  resolution?: string | null
  aspectRatio?: string | null
  source: ProjectGenConfigSource
  mode?: 'economy' | 'performance' | string | null
  availableModels: UserModelListItem[]
  /** 该场景可选智能体池（agentCode 列表），主要用于后端校验 */
  agentPool?: string[]
  /** 该场景可选智能体下拉项（已按创作模式过滤），前端渲染智能体选择器的唯一数据源 */
  agentOptions?: AgentInfoVO[]
  /** 该场景是否适用于当前创作模式（当前接口仅返回适用场景，恒为 true） */
  applicable?: boolean
}

/** POST /api/user/project/gen-config/get */
export interface ProjectGenConfigQueryRequest {
  projectId: number
  /** 剧集类项目必传：按该剧集 creation_mode 解析分镜场景 */
  episodeId?: number
}

/** POST /api/user/project/gen-config/save — 单场景保存项 */
export interface ProjectGenConfigSaveItem {
  sceneCode: ProjectGenConfigSceneCode | string
  agentCode: string
  modelCode: string
  resolution?: string
  aspectRatio?: string
}

/** POST /api/user/project/gen-config/save */
export interface ProjectGenConfigSaveRequest {
  projectId: number
  configs: ProjectGenConfigSaveItem[]
}

/** POST /api/user/project/gen-config/save — 出参单项 */
export interface ProjectGenConfigSavedItem {
  sceneCode: string
  agentCode: string
  modelCode: string
  resolution?: string | null
  aspectRatio?: string | null
  source: 'project'
}

/** /api/user/asset/extract/parallel（v2026-06 modelCodes 替代 modelCode） */
export interface UserAssetExtractParallelRequest {
  projectId: number
  episodeId?: number
  extractTypes: AssetExtractType[]
  /** key=extractType，value=agentCode（如 aid_prop_extractor） */
  agentCodes: Partial<Record<AssetExtractType, string>> & Record<string, string>
  /** key=extractType，value=modelCode；未传的 type 走后端 3 级兜底（项目配置 → aid_config） */
  modelCodes?: Partial<Record<AssetExtractType, string>> & Record<string, string>
  overwrite?: boolean
}

/** POST /api/user/asset/extract/cancel — 停止提取/形态父任务等（见接口文档 v2.25+ / v2.28+） */
export interface UserAssetExtractCancelRequest {
  taskId: number
}

/** @deprecated 请使用 UserTaskResumeRequest */
export interface UserAssetExtractResumeRequest {
  taskId: number
}

export interface UserAssetExtractResumeData {
  taskId: number
  status: string
}

/** 提取/形态父任务同步返回（form_generate_batch / form_image_batch） */
export interface AssetExtractTaskSyncVO {
  taskId?: number
  status?: string
  totalCount?: number
  assets?: unknown | null
  resultData?: string | null
  errorMessage?: string | null
}

/** /api/user/asset/extract/form/generate（v2.28+ 父任务；三类资产均必填 agentCode） */
export interface UserAssetExtractFormGenerateRequest {
  assetIds: number[]
  /** 必填；biz_category_code：character→main_character_form / scene→main_scene_form / prop→main_prop_form */
  agentCode: string
  /** 文本模型；不传用智能体默认 modelCode */
  modelCode?: string
}

/** @deprecated v2.28+ 同步仅返回父任务，见 AssetExtractTaskSyncVO */
export interface UserAssetExtractFormGenerateTaskItem {
  assetId: number
  taskId: number
  status?: string
}

/** @deprecated 失败项在 SSE complete / resultData，不在同步响应 */
export interface UserAssetExtractFormGenerateFailedAsset {
  assetId: number
  message: string
}

export type UserAssetExtractFormGenerateData = AssetExtractTaskSyncVO & {
  taskIds?: number[]
  tasks?: UserAssetExtractFormGenerateTaskItem[]
  failedAssets?: UserAssetExtractFormGenerateFailedAsset[]
}

/** /api/user/asset/extract/form/generate-image（v2026-06 新增 resolution / aspectRatio，走 3 级兜底） */
export interface UserAssetExtractFormGenerateImageRequest {
  formIds: number[]
  agentCode: string
  /** 不传走项目配置兜底；传则须为 image 类型模型 */
  modelCode?: string
  /** 清晰度档位（如 1K / 2K / 4K）；不传走项目配置兜底 */
  resolution?: string
  /** 图片比例（如 16:9 / 1:1）；不传走项目配置兜底 */
  aspectRatio?: string
}

export interface UserAssetExtractFormGenerateImageTaskItem {
  formId: number
  taskId: number
  status?: string
}

export interface UserAssetExtractFormGenerateImageFailedForm {
  formId: number
  message: string
}

export type UserAssetExtractFormGenerateImageData = AssetExtractTaskSyncVO & {
  taskIds?: number[]
  tasks?: UserAssetExtractFormGenerateImageTaskItem[]
  failedForms?: UserAssetExtractFormGenerateImageFailedForm[]
}

/** POST /api/user/asset/extract/form/generate-card-image（v2026-06 纯批量 imageIds） */
export interface UserAssetExtractFormGenerateCardImageRequest {
  /** 参考底图 aid_role_prop_scene_form_image.id 列表（平台生成含 ai_edit_chat 等，或 upload） */
  imageIds: number[]
  /** 智能体编码，biz_category=main_character_card_image */
  agentCode: string
  /** 图片模型；不传走项目配置兜底 */
  modelCode?: string
  /** 清晰度档位（如 1K / 2K / 4K）；不传走项目配置兜底 */
  resolution?: string
  /** 图片比例（设定卡默认 21:9）；不传走项目配置兜底 */
  aspectRatio?: string
}

export type UserAssetExtractFormGenerateCardImageData = AssetExtractTaskSyncVO

/** 通用任务（/api/user/task/list|status 与提交任务返回） */
export type UserTaskStatus = 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | string

export interface UserTaskRow {
  id: number
  projectId?: number
  episodeId?: number
  userId?: number
  taskType?: string
  status: UserTaskStatus
  inputSnapshot?: string | null
  resultData?: string | null
  errorMessage?: string | null
  totalCount?: number
  modelCode?: string | null
  createTime?: string | null
  updateTime?: string | null
}

export interface UserTaskListRequest {
  projectId?: number
  taskType?: string
  status?: UserTaskStatus
  pageNum?: number
  pageSize?: number
}

/** 分页列表通用出参 */
export interface PaginatedListResult<T> {
  rows: T[]
  total: number
  pageNum: number
  pageSize: number
  hasMore: boolean
}

/** /api/user/task/detail */
export interface UserTaskDetailRequest {
  taskId: number
}

/** 停止/取消进行中的任务：POST /api/user/task/cancel（与后端约定，未上线时可能 404） */
export interface UserTaskCancelRequest {
  taskId: number
}

/** 批量取消 PENDING 独立任务：POST /api/user/task/cancel-batch */
export interface UserTaskCancelBatchRequest {
  taskIds: number[]
}

export interface UserTaskCancelBatchData {
  cancelCount: number
}

/** 图片、视频、音频任务的统一预计进度与剩余时间。 */
export interface TaskEtaData {
  phase?: string
  displayProgress?: number
  progressSource?: string
  remainingSecondsP50?: number
  remainingSecondsP90?: number
  estimatedStartAt?: number
  estimatedFinishAtP50?: number
  estimatedFinishAtP90?: number
  confidence?: string
  sampleCount?: number
  calculatedAt?: number
  totalCount?: number
  completedCount?: number
  runningCount?: number
  queuedCount?: number
  delayed?: boolean
  predictionVersion?: string
}

export interface UserTaskDetailData {
  taskId: number
  projectId?: number
  episodeId?: number
  taskType?: string
  status: UserTaskStatus
  inputSnapshot?: string | null
  resultData?: string | null
  errorMessage?: string | null
  totalCount?: number
  modelCode?: string | null
  queuePosition?: number | null
  createTime?: string | null
  updateTime?: string | null
  eta?: TaskEtaData
}

/** 官方只读参数词库：分类列表 /api/user/prompt/official/category/list */
export interface OfficialPromptCategoryItem {
  categoryCode: string
  categoryName: string
  itemCount?: number
  sortOrder?: number
}

/** 官方只读参数词库：词条列表 /api/user/prompt/official/item/list */
export interface OfficialPromptItemListRequest {
  categoryCode?: string
  categoryCodes?: string[]
  keyword?: string
  pageNum?: number
  pageSize?: number
}

export interface OfficialPromptItem {
  id: number
  categoryCode: string
  categoryName?: string
  itemName?: string
  promptText?: string
  promptTextEn?: string
  coverUrl?: string
  sortOrder?: number
  remark?: string
}

/** 官方只读参数词库：词条详情 /api/user/prompt/official/item/detail */
export interface OfficialPromptItemDetailRequest {
  id: number
}

/** 枚举字典：查询枚举 /api/user/dict/enum/list */
export interface EnumDictListRequest {
  enumTypes: string[]
}

export interface EnumDictListItem {
  value: string
  desc: string
}

export interface EnumDictGroup {
  enumType: string
  items: EnumDictListItem[]
}

/** POST /api/user/asset/extract/form/generate-multi-view-image（v2.35.0 多机位形态生图） */
export interface UserAssetExtractFormGenerateMultiViewImageRequest {
  formId: number
  imageUrl: string
  anglePrompt: string
  modelCode: string
  aspectRatio?: string
}

export interface UserAssetExtractFormGenerateMultiViewImageData {
  taskId: number
  status: string
}

/** @deprecated v2.62 已迁移至 UserStoryboardGenerateMultiViewGridImageRequest */
export interface UserAssetExtractStoryboardGenerateMultiViewGridImageRequest {
  formId: number
  imageUrl: string
  /** 必须 1 或 9 个元素，每个非空 */
  angles: string[]
  modelCode: string
  aspectRatio?: string
}

/** @deprecated v2.62 已迁移至 UserStoryboardGenerateMultiViewGridImageData */
export interface UserAssetExtractStoryboardGenerateMultiViewGridImageData {
  taskId: number
  status: string
}

/** POST /api/user/storyboard/generate/multi-view-grid-image（v2.62 分镜机位生图，angles 长度 1 或 9） */
export interface UserStoryboardGenerateMultiViewGridImageRequest {
  storyboardId: number
  imageUrl: string
  /** 必须 1 或 9 个元素，每个非空 */
  angles: string[]
  modelCode: string
  aspectRatio?: string
}

export interface UserStoryboardGenerateMultiViewGridImageData {
  taskId: number
  status: string
}

/** storyboard_multi_view_image / storyboard_multi_grid_image SSE complete / resultData */
export interface StoryboardMultiViewGridCompleteData {
  storyboardId?: number
  recordId?: number
  imageUrl?: string
  genType?: string
  anglesCount?: number
  aspectRatio?: string
}

/** 形态图片创作模式：edit=编辑图片（必传参考图）；chat=对话作图（参考图可选） */
export type FormCreationImageGenMode = 'edit' | 'chat'

/** POST /api/user/asset/extract/form/generate-creation-image（编辑图片 / 对话作图） */
export interface UserAssetExtractFormGenerateCreationImageRequest {
  formId: number
  genMode: FormCreationImageGenMode
  /** edit 必传 ≥1 张；chat 可省略或空数组（纯文生图） */
  referenceImages?: string[]
  prompt: string
  modelCode: string
  aspectRatio: string
  size: string
  imageCount: number
}

export interface UserAssetExtractFormGenerateCreationImageData {
  taskId: number
  status: string
}

/** @deprecated 请改用 UserAssetExtractFormGenerateCreationImageRequest */
export type UserAssetExtractFormGenerateEditImageRequest =
  UserAssetExtractFormGenerateCreationImageRequest

/** @deprecated 请改用 UserAssetExtractFormGenerateCreationImageData */
export type UserAssetExtractFormGenerateEditImageData =
  UserAssetExtractFormGenerateCreationImageData

/** generate-creation-image SSE complete 事件 data 结构 */
export interface EditImageCompleteData {
  formId?: number
  storyboardId?: number
  imageCount?: number
  imageIds?: number[]
  recordIds?: number[]
  items?: Array<{ imageId: number; imageUrl: string; recordId?: number }>
  aspectRatio?: string
  size?: string
  failCount?: number
  failedItems?: Array<{ index: number; message: string }>
}

/** POST /api/user/storyboard/generate/edit-image（v2.57 分镜编辑图/对话作图） */
export interface UserStoryboardGenerateEditImageRequest {
  storyboardId: number
  referenceImage: string
  prompt: string
  modelCode: string
  aspectRatio: string
  size: string
  imageCount: number
}

export interface UserStoryboardGenerateEditImageData {
  taskId: number
  status: string
}

/** POST /api/user/storyboard/generate/upscale（v2.57 分镜图高清） */
export interface UserStoryboardGenerateUpscaleRequest {
  genRecordId: number
  modelCode: string
  resolution?: string
}

export interface UserStoryboardGenerateUpscaleData {
  taskId: number
  status: string
}

/** storyboard_image_upscale SSE complete / resultData */
export interface StoryboardUpscaleCompleteData {
  genRecordId?: number
  recordId?: number
  imageUrl?: string
  imageId?: number
}

/** @deprecated v2.0 起后端已下线，前端仅做兼容映射保留 */
export interface UserPromptGetDataRequest {
  category?: string
  officialOnly?: boolean
  status?: string
}

/** @deprecated v2.0 起后端已下线，前端仅做兼容映射保留 */
export interface PromptLibItem {
  id: number
  promptType: string
  promptName?: string
  promptContent?: string
  coverUrl?: string
  sortOrder?: number
  status?: string
}

/** @deprecated v2.0 起后端已下线，前端仅做兼容映射保留 */
export interface PromptEnumItem {
  enumType: string
  value: string
  desc: string
  category?: string
}

/** @deprecated v2.0 起后端已下线，前端仅做兼容映射保留 */
export interface UserPromptGetDataData {
  promptLibList?: PromptLibItem[]
  enumList?: PromptEnumItem[]
}

/** POST /api/user/voice-library/tags — C 端一次性拉取音色筛选字典 */
export interface VoiceTagItem {
  tagCode: string
  tagName: string
}

export interface VoiceEnumItem {
  code: string
  name: string
}

export interface VoiceTagBundleData {
  characterTypes?: VoiceTagItem[]
  voiceStyles?: VoiceTagItem[]
  toneTags?: VoiceTagItem[]
  emotionTags?: VoiceTagItem[]
  enums?: Record<string, VoiceEnumItem[]>
}

/** POST /api/public/billing/detail */
export interface PublicBillingDetailRequest {
  modelType?: AiModelType | string
  modelName?: string
}

export interface BillingColumnVO {
  key: string
  label: string
  unit?: string | null
  type?: 'number' | 'text' | string
}

export interface BillingRuleItemVO {
  skuCode?: string | null
  skuName?: string | null
  /** 本档位真实计费口径；MIXED 模型按各档位分别返回 */
  meterType?: string | null
  meterTypeName?: string | null
  /** 服务端规则命中条件原样返回，前端仅展示，不参与计价 */
  matchConditions?: Record<string, unknown> | null
  /** 规范计费单位：TOKEN / IMAGE / SECOND / CALL / CHAR */
  unit?: string | null
  unitName?: string | null
  /** true 表示最终金额依赖任务完成后的上游真实用量 */
  estimated?: boolean | null
  resolution?: string | null
  generateMode?: string | null
  durationMin?: number | null
  durationMax?: number | null
  inputTokensMin?: number | null
  inputTokensMax?: number | null
  referenceImageCountMin?: number | null
  referenceImageCountMax?: number | null
  inputVideoCountMin?: number | null
  inputVideoCountMax?: number | null
  audioMode?: string | null
  unitPrice?: number | null
  pricePerSecond?: number | null
  packagePrice?: number | null
  inputPricePerMillion?: number | null
  outputPricePerMillion?: number | null
  /** 输入图片单价 Credits/张（档位覆盖值；null=用模型级 inputPricing） */
  inputImagePrice?: number | null
  inputImageFreeCount?: number | null
  /** 输入视频单价 Credits/秒（档位覆盖值；null=用模型级 inputPricing） */
  inputVideoPricePerSecond?: number | null
}

/** 输入媒体计费说明（图片/视频作为输入时的附加费） */
export interface BillingInputPricingVO {
  imageSupported?: boolean | null
  /** 模型级 Credits/张；null 时查看 rules[].inputImagePrice */
  imageUnitPrice?: number | null
  imageFreeCount?: number | null
  imageMaxCount?: number | null
  videoSupported?: boolean | null
  /** 模型级 Credits/秒；null 时查看 rules[].inputVideoPricePerSecond */
  videoUnitPrice?: number | null
  videoMaxSeconds?: number | null
  videoMaxCount?: number | null
}

export interface ModelBillingDetailVO {
  id: number
  modelCode: string
  modelName: string
  providerName?: string | null
  providerLogo?: string | null
  modelType?: string | null
  modelTypeName?: string | null
  generateMode?: string | null
  billingMode?: string | null
  /** true 表示本次模型调用免费；价格规则仍保留展示（兼容升级前接口可缺省） */
  isFree?: boolean
  meterType?: string | null
  meterTypeName?: string | null
  billingDesc?: string | null
  creditUnit?: string | null
  columns: BillingColumnVO[]
  rules: BillingRuleItemVO[]
  /** 输入媒体计费；文本模型未配置时为 null */
  inputPricing?: BillingInputPricingVO | null
}

export interface PublicBillingDetailData {
  creditUnit: string
  llm: ModelBillingDetailVO[]
  image: ModelBillingDetailVO[]
  video: ModelBillingDetailVO[]
  voice: ModelBillingDetailVO[]
}

/** POST /api/user/billing/quote 的权威报价类型。 */
export type BillingQuoteKnownType =
  | 'MEDIA_IMAGE'
  | 'MEDIA_IMAGE_PRICING'
  | 'MEDIA_VIDEO'
  | 'MEDIA_TEXT'
  | 'MEDIA_AUDIO'
  | 'ASSET_EXTRACT'
  | 'FORM_IMAGE'
  | 'FORM_GENERATE'
  | 'FORM_CARD_IMAGE'
  | 'FORM_IMAGE_UPSCALE'
  | 'FORM_EDIT_CHAT_IMAGE'
  | 'FORM_MULTI_VIEW_IMAGE'
  | 'STORYBOARD_SCRIPT'
  | 'STORYBOARD_IMAGE'
  | 'STORYBOARD_IMAGE_PROMPT'
  | 'STORYBOARD_IMAGE_WITH_PROMPT'
  | 'STORYBOARD_EDIT_IMAGE'
  | 'STORYBOARD_IMAGE_UPSCALE'
  | 'STORYBOARD_MULTI_VIEW_IMAGE'
  | 'STORYBOARD_MULTI_GRID_IMAGE'
  | 'STORYBOARD_VIDEO'
  | 'STORYBOARD_VIDEO_IMAGE'
  | 'STORYBOARD_VIDEO_GRID'
  | 'STORYBOARD_VIDEO_EDGE'
  | 'STORYBOARD_VIDEO_PROMPT'
  | 'STORYBOARD_VIDEO_PROMPT_IMAGE'
  | 'STORYBOARD_VIDEO_PROMPT_GRID'
  | 'STORYBOARD_VIDEO_WITH_PROMPT'
  | 'STORYBOARD_AUDIO'
  | 'STORYBOARD_AUDIO_BATCH'
  | 'STORYBOARD_LIP_SYNC'
  | 'STORYBOARD_LIP_SYNC_BATCH'
  | 'TASK_RESUME'

/** 允许服务端平滑新增业务报价类型，已知值仍保留自动补全。 */
export type BillingQuoteType = BillingQuoteKnownType | (string & {})

export interface BillingQuoteRequest {
  quoteType: BillingQuoteType
  /** 与对应生成接口相同的业务请求体；报价接口无副作用。 */
  payload: Record<string, unknown>
  /** 仅 MEDIA_* 可大于 1；业务报价固定为 1。 */
  quantity?: number
}

export interface BillingQuoteVO {
  quoteType?: string | null
  modelCode?: string | null
  isFree?: boolean | null
  matched?: boolean | null
  skuCode?: string | null
  skuName?: string | null
  meterType?: string | null
  unit?: string | null
  unitName?: string | null
  matchConditions?: Record<string, unknown> | null
  quantity?: number | null
  amount?: number | null
  preHoldAmount?: number | null
  determined?: boolean | null
  estimated?: boolean | null
  /** 服务端统一生成“免费 / 预计扣除 / 预扣约”文案。 */
  displayText?: string | null
  items?: BillingQuoteVO[] | null
}

/** POST /api/user/faq/list */
export interface UserFaqListRequest {
  pageNum?: number
  pageSize?: number
  category?: string
  keyword?: string
}

export interface UserFaqListItem {
  id: number
  title: string
  category?: string | null
  sortOrder?: number
  viewCount?: number
  publishTime?: string | null
}

/** POST /api/user/faq/detail */
export interface UserFaqDetailRequest {
  id: number
}

export interface UserFaqDetail {
  id: number
  title: string
  category?: string | null
  content?: string | null
  viewCount?: number
  publishTime?: string | null
}
