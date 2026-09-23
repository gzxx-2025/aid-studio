import type {
AxiosInstance,
AxiosRequestConfig,
AxiosResponse,
InternalAxiosRequestConfig
} from 'axios'
import axios from 'axios'
import {
applyEncryptedPayloadToAxiosConfig,
ensureApiCryptoConfigReady,
isApiCryptoExemptPath,
isApiCryptoIncludedPath,
maybeDecryptApiPayload,
prepareEncryptedRequest,
refreshApiCryptoConfig,
shouldEncryptApiPath,
takeAxiosRequestAesKey
} from '~/utils/apiCrypto'
import { redirectToLogin } from '~/utils/authLoginNavigation'
import {
  captureBrowserFailure,
  configureErrorDiagnostics,
  createDiagnosticRequest,
  isDiagnosticUrl,
  type DiagnosticRequest
} from '~/utils/errorDiagnostics'
import {
clearPendingCaptchaToken,
isCaptchaProtectedAuthPath,
takePendingCaptchaToken
} from '~/utils/captchaToken'
import {
isInsufficientBalanceMessage,
shouldOpenUserRechargeFromSseError,
type SseRechargeErrorData
} from '~/utils/insufficientBalanceRecharge'

export { redirectToLogin } from '~/utils/authLoginNavigation'

type ApiCryptoRetryConfig = InternalAxiosRequestConfig & {
  __diagnosticRequest?: DiagnosticRequest
  __apiCryptoPlainData?: unknown
  __apiCryptoOriginalTransformRequest?: InternalAxiosRequestConfig['transformRequest']
  __apiCryptoRetryPrepared?: boolean
  __apiCryptoExpiredRetried?: boolean
}

const API_CRYPTO_NO_RETRY = Symbol('API_CRYPTO_NO_RETRY')

/** 开发环境请求前缀（与 nuxt.config 里 /url 代理一致，按需改） */
const API_BASE_DEVELOPMENT = '/url'

/**
 * 浏览器端与 axios `baseURL` 一致的 API 根路径（用于 EventSource 等无法走 axios 拦截器的请求）。
 * - 开发：`/url` + `/api/...` → Nitro 代理到后端
 * - 生产客户端：与 {@link resolveApiBaseURL} 一致（同源 + `aid` 前缀）
 */
export function resolveClientApiUrl(path: string): string {
  const p = path.startsWith('/') ? path : `/${path}`
  if ((process.env.NODE_ENV === 'development')) {
    return `${API_BASE_DEVELOPMENT.replace(/\/$/, '')}${p}`
  }
  if ((typeof window !== 'undefined')) {
    const base = `${window.location.protocol}//${window.location.host}/aid`
    return `${base.replace(/\/$/, '')}${p}`
  }
  return `${API_BASE_DEVELOPMENT.replace(/\/$/, '')}${p}`
}

/**
 * 生产环境请求前缀（同源代理仍用 /url；若直连 API 则改为 https://xxx）
 * SSR/预渲染阶段不能访问 window，统一回退到相对路径。
 */
function resolveApiBaseURL(): string {
  if ((process.env.NODE_ENV === 'development')) return API_BASE_DEVELOPMENT
  if ((typeof window !== 'undefined')) {
    return `${window.location.protocol}//${window.location.host}/aid`
  }
  return API_BASE_DEVELOPMENT
}

const resolvedApiBaseURL = resolveApiBaseURL()

const ANONYMOUS_USER_API_PATHS = new Set([
  '/api/user/asset/custom/page',
  '/api/user/asset/style/category/list',
  '/api/user/skill/execution/catalog'
])

function normalizeApiPath(url?: string): string {
  const raw = String(url || '').trim()
  if (!raw) return ''
  const withoutOrigin = raw.replace(/^https?:\/\/[^/]+/i, '')
  const path = withoutOrigin.split(/[?#]/, 1)[0] || ''
  return path.replace(/^\/(?:url|aid)(?=\/)/, '')
}

function isLoginRequiredApi(url?: string): boolean {
  const path = normalizeApiPath(url)
  if (!path || ANONYMOUS_USER_API_PATHS.has(path)) return false
  // 其余 /api/user/** 仍然必须登录；匿名白名单只允许精确匹配公开目录接口。
  return path.startsWith('/api/user/')
}

function extractApiMessage(data: unknown): string {
  if (!data || typeof data !== 'object') return ''
  const d = data as { msg?: string; message?: string }
  return String(d.msg ?? d.message ?? '')
}

function isApiCryptoExpiredResponse(data: unknown): boolean {
  if (!data || typeof data !== 'object') return false
  const payload = data as { code?: unknown; msg?: unknown; message?: unknown }
  const code = Number(payload.code)
  const message = String(payload.msg ?? payload.message ?? '').trim()
  return code === 400 && message === '请求已过期'
}

function removeApiCryptoHeaders(config: InternalAxiosRequestConfig) {
  const headers = config.headers as InternalAxiosRequestConfig['headers'] & {
    delete?: (name: string) => unknown
  }
  for (const name of ['X-Encrypt-Key', 'X-Encrypt-Iv', 'X-Encrypt-Ts']) {
    if (typeof headers.delete === 'function') headers.delete(name)
    else {
      delete (headers as Record<string, unknown>)[name]
      delete (headers as Record<string, unknown>)[name.toLowerCase()]
    }
  }
}

/** 判断接口返回文案是否表示需要重新登录 */
export function isLoginRequiredMessage(msg: string): boolean {
  const s = String(msg ?? '').trim()
  if (!s) return false
  return /请(?:先)?登录|未登录|登录已过期|登录失效|登录状态.*失效|重新登录|token.*无效/i.test(s)
}

function handleLoginRequiredResponse(data?: unknown): boolean {
  if (!isLoginRequiredMessage(extractApiMessage(data))) return false
  redirectToLogin()
  return true
}

/**
 * 与 axios 请求拦截器一致：供 `fetch` / SSE 等无法走 axios 的场景携带鉴权与其它通用头。
 * - `Authorization: Bearer <token>`（localStorage `token`）
 * - `X-Requested-With: XMLHttpRequest`
 */
export function buildUserApiAuthHeaders(): Record<string, string> {
  if (!(typeof window !== 'undefined')) {
    return { 'X-Requested-With': 'XMLHttpRequest' }
  }
  const token = localStorage.getItem('token') || ''
  const h: Record<string, string> = {
    'X-Requested-With': 'XMLHttpRequest'
  }
  if (token) {
    h.Authorization = `Bearer ${token}`
  }
  return h
}

/** 供业务代码在 SSE / 非 axios 场景按需唤起充值弹窗 */
export function openRechargeModalFromInsufficientBalance(message: string) {
  if (!isInsufficientBalanceMessage(message)) return
  emitRechargeEvent()
}

/**
 * v2.38.0+ 结构化错误协议：根据 SSE error 事件的 errorData 判断是否弹充值弹窗。
 * 优先使用 needRecharge + rechargeOwner 字段，兼容旧文案匹配。
 * - rechargeOwner=USER → 弹用户充值中心
 * - rechargeOwner=MERCHANT → 不弹（模型/商户额度，仅 toast 文案即可）
 */
export function handleSseErrorRecharge(
  errorData?: SseRechargeErrorData,
  fallbackMessage?: string
) {
  if (shouldOpenUserRechargeFromSseError(errorData, fallbackMessage)) {
    emitRechargeEvent()
  }
}

function shouldOpenRecharge(data: any): boolean {
  return isInsufficientBalanceMessage(String(data?.msg ?? data?.message ?? ''))
}

function emitRechargeEvent() {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent('open-recharge-modal'))
}

function readCaptchaTokenFromHeaders(headers: InternalAxiosRequestConfig['headers']): string | undefined {
  if (!headers) return undefined
  const h = headers as Record<string, unknown> & { get?: (key: string) => unknown }
  const fromGet = typeof h.get === 'function' ? h.get('captcha-token') : undefined
  const raw = fromGet ?? h['captcha-token'] ?? h['Captcha-Token']
  const token = String(raw ?? '').trim()
  return token || undefined
}

function applyCaptchaTokenHeader(config: InternalAxiosRequestConfig, captchaToken: string) {
  const token = String(captchaToken || '').trim()
  if (!token) return
  const headers = config.headers
  if (headers && typeof (headers as { set?: (k: string, v: string) => void }).set === 'function') {
    ;(headers as { set: (k: string, v: string) => void }).set('captcha-token', token)
    return
  }
  config.headers['captcha-token'] = token
}

function attachCaptchaTokenIfNeeded(config: InternalAxiosRequestConfig) {
  if (!(typeof window !== 'undefined') || !isCaptchaProtectedAuthPath(config.url)) return
  const existing = readCaptchaTokenFromHeaders(config.headers)
  const captchaToken = existing || takePendingCaptchaToken()
  if (existing) clearPendingCaptchaToken()
  if (captchaToken) applyCaptchaTokenHeader(config, captchaToken)
}

const api: AxiosInstance = axios.create({
  baseURL: resolvedApiBaseURL,
  timeout: 60000, // 请求超时时间
  headers: {
    'Content-Type': 'application/json'
  }
})

async function retryExpiredEncryptedRequest(
  config: InternalAxiosRequestConfig,
  data: unknown,
  encryptedRequest: boolean
): Promise<unknown | typeof API_CRYPTO_NO_RETRY> {
  const retryConfig = config as ApiCryptoRetryConfig
  if (
    !encryptedRequest ||
    retryConfig.__apiCryptoExpiredRetried ||
    !isApiCryptoExpiredResponse(data)
  ) {
    return API_CRYPTO_NO_RETRY
  }

  retryConfig.__apiCryptoExpiredRetried = true
  const refreshed = await refreshApiCryptoConfig()
  if (!refreshed) return API_CRYPTO_NO_RETRY

  retryConfig.data = retryConfig.__apiCryptoPlainData
  retryConfig.transformRequest = retryConfig.__apiCryptoOriginalTransformRequest
  removeApiCryptoHeaders(retryConfig)
  return api.request(retryConfig)
}

// 请求拦截器
api.interceptors.request.use(
  async (config) => {
    // 添加认证信息
    const token = (typeof window !== 'undefined') ? localStorage.getItem('token') : ''
    const loginRequired = isLoginRequiredApi(config.url)
    if (!token && loginRequired) {
      if (!isDiagnosticUrl(config.url)) redirectToLogin()
      return Promise.reject(new axios.CanceledError('AUTH_REDIRECT'))
    }
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }

    // multipart 需由浏览器自动带 boundary，不能沿用默认 application/json
    if (typeof FormData !== 'undefined' && config.data instanceof FormData) {
      const h = config.headers as Record<string, unknown>
      delete h['Content-Type']
    }

    // 处理请求参数
    if (config.method === 'get' && config.params) {
      // 可以在这里对 GET 请求参数进行处理
    }

    // 添加其他通用请求头
    config.headers['X-Requested-With'] = 'XMLHttpRequest'

    if (typeof window !== 'undefined' && !isDiagnosticUrl(config.url)) {
      const tracked = config as ApiCryptoRetryConfig
      tracked.__diagnosticRequest ??= createDiagnosticRequest(config.url || '', config.method || 'GET', config.data ?? config.params)
      config.headers['X-AID-Request-ID'] = tracked.__diagnosticRequest.requestId
    }

    const isFormData = typeof FormData !== 'undefined' && config.data instanceof FormData
    const isApiCryptoCandidate =
      (typeof window !== 'undefined') &&
      isApiCryptoIncludedPath(config.url) &&
      !isApiCryptoExemptPath(config.url) &&
      !isFormData

    // 首批业务请求先等待公开配置，避免加密开关未知时按明文抢跑。
    if (isApiCryptoCandidate) {
      await ensureApiCryptoConfigReady()
    }

    // 信封加密（由 /auth/public-config 的 crypto.enabled 控制）
    if (isApiCryptoCandidate && shouldEncryptApiPath(config.url)) {
      const method = (config.method || 'get').toLowerCase()
      const isGet = method === 'get' || method === 'head'
      const retryConfig = config as ApiCryptoRetryConfig
      if (!retryConfig.__apiCryptoRetryPrepared) {
        retryConfig.__apiCryptoPlainData = config.data
        retryConfig.__apiCryptoOriginalTransformRequest = config.transformRequest
        retryConfig.__apiCryptoRetryPrepared = true
      }
      const enc = await prepareEncryptedRequest({
        body: isGet ? undefined : config.data ?? {},
        skipBody: isGet
      })
      applyEncryptedPayloadToAxiosConfig(config, enc, { skipBody: isGet })
    }

    // 行为验证码：/auth/login、/auth/sendCode 携带 /captcha/check 返回的一次性 token（放在加密之后，避免被覆盖）
    attachCaptchaTokenIfNeeded(config)

    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// 响应拦截器
api.interceptors.response.use(
  async (response: AxiosResponse) => {
    const aesKey = takeAxiosRequestAesKey(response.config as InternalAxiosRequestConfig)
    let data: Record<string, unknown>
    try {
      data = await maybeDecryptApiPayload<Record<string, unknown>>(response.data, aesKey)
    } catch (error) {
      captureBrowserFailure((response.config as ApiCryptoRetryConfig).__diagnosticRequest,
        { status: response.status, body: response.data, encrypted: Boolean(aesKey) }, error,
        String(response.headers['x-aid-request-id'] || ''))
      throw error
    }
    response.data = data

    if (isDiagnosticUrl(response.config.url)) return data as unknown as AxiosResponse

    const retryResult = await retryExpiredEncryptedRequest(
      response.config as InternalAxiosRequestConfig,
      data,
      Boolean(aesKey)
    )
    if (retryResult !== API_CRYPTO_NO_RETRY) return retryResult as AxiosResponse

    // 可以根据后端返回的状态码进行统一处理
    if (data && typeof data === 'object' && data.code !== undefined) {
      // 后端部分接口成功为 200，部分为 0（如充值订单列表）
      const ok = data.code === 200 || data.code === 0
      if (!ok) {
        captureBrowserFailure((response.config as ApiCryptoRetryConfig).__diagnosticRequest,
          { status: response.status, body: data }, undefined,
          String(response.headers['x-aid-request-id'] || data.requestId || ''))
        if (handleLoginRequiredResponse(data)) {
          return Promise.reject(data)
        }
        if (shouldOpenRecharge(data)) {
          emitRechargeEvent()
        }
        // 处理业务错误（与后端文档字段 msg 一致）
        console.log('API 错误:', extractApiMessage(data))
        return Promise.reject(data)
      }
    }

    // 与历史行为一致：拦截器向上返回业务 JSON，而非 AxiosResponse 包装
    return data as unknown as AxiosResponse
  },
  async (error) => {
    if (axios.isCancel(error)) {
      return Promise.reject(error)
    }
    if (isDiagnosticUrl(error.config?.url)) return Promise.reject(error)
    captureBrowserFailure((error.config as ApiCryptoRetryConfig | undefined)?.__diagnosticRequest,
      error.response ? { status: error.response.status, body: error.response.data } : undefined,
      { name: error.name, message: error.message, code: error.code },
      String(error.response?.headers?.['x-aid-request-id'] || ''))
    // 统一处理错误
    if (error.response) {
      // 服务器返回错误
      const { status, data } = error.response
      const config = error.config as InternalAxiosRequestConfig | undefined
      if (config) {
        const aesKey = takeAxiosRequestAesKey(config)
        const retryResult = await retryExpiredEncryptedRequest(config, data, Boolean(aesKey))
        if (retryResult !== API_CRYPTO_NO_RETRY) return retryResult
      }

      if (status === 401) {
        console.error('未授权，请重新登录')
        redirectToLogin()
        return Promise.reject(error)
      }

      if (handleLoginRequiredResponse(data)) {
        return Promise.reject(error)
      }

      switch (status) {
        case 403:
          console.error('拒绝访问')
          break
        case 404:
          console.error('请求地址不存在')
          break
        case 500:
          console.error('服务器内部错误')
          break
        default:
          if (shouldOpenRecharge(data)) {
            emitRechargeEvent()
          }
          console.error('请求失败:', extractApiMessage(data) || '未知错误')
      }
    } else if (error.request) {
      // 请求已发出但没有收到响应
      console.error('网络错误，请检查网络连接')
    } else {
      // 请求配置出错
      console.error('请求配置错误:', error.message)
    }

    return Promise.reject(error)
  }
)

// 封装 HTTP 请求方法
/** 响应拦截器已把 AxiosResponse 解包为业务 JSON；Axios 的静态类型无法表达该运行时转换。 */
function asPayloadPromise<T>(promise: Promise<unknown>): Promise<T> {
  return promise as Promise<T>
}

export const request = {
  // GET 请求
  get<T = any>(url: string, params?: any, config?: AxiosRequestConfig): Promise<T> {
    return asPayloadPromise<T>(api.get(url, {
      params,
      ...config
    }))
  },

  // POST 请求
  post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    return asPayloadPromise<T>(api.post(url, data, config))
  },

  // PUT 请求
  put<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    return asPayloadPromise<T>(api.put(url, data, config))
  },

  // DELETE 请求
  delete<T = any>(url: string, params?: any, config?: AxiosRequestConfig): Promise<T> {
    return asPayloadPromise<T>(api.delete(url, {
      params,
      ...config
    }))
  },

  // PATCH 请求
  patch<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T> {
    return asPayloadPromise<T>(api.patch(url, data, config))
  }
}

configureErrorDiagnostics((url, body) => request.post(url, body, { timeout: 10000 }))

export default api
