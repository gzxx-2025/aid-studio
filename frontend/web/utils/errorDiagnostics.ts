type Post = (path: string, body: unknown) => Promise<unknown>

export interface DiagnosticRequest {
  requestId: string
  requestTime: string
  url: string
  method: string
  body?: unknown
}

const PREFIX = '/api/user/diagnostics/'
const MAX_RESPONSE_BYTES = 4 * 1024 * 1024
const PRIVATE_FIELD = /^(authorization|proxyauthorization|cookie|setcookie|password|passwd|secret|clientsecret|credential|credentials|apikey|apitoken|xapikey|xapitoken|accesskey|secretkey|privatekey|aeskey|token|accesstoken|refreshtoken|captchatoken|xencryptkey|xencryptiv|xencryptts)$/i
const PROMPT_FIELD = /^(systemprompt|systeminstruction|developerprompt|agentprompt|promptcontent)$/i
let post: Post | undefined
let status: { identity: string; until: number; enabled: boolean } | undefined
let statusRequest: Promise<boolean> | undefined
let statusIdentity = ''
let active = 0
let missed = 0
const sent = new Set<string>()
const pending: Array<() => void> = []
const responses = new WeakMap<Response, DiagnosticRequest>()
const requestIdentities = new WeakMap<DiagnosticRequest, string>()

export function configureErrorDiagnostics(transport: Post): void {
  post = transport
}

export function isDiagnosticUrl(url?: string): boolean {
  return String(url || '').split(/[?#]/)[0].includes(PREFIX)
}

export function newDiagnosticRequestId(): string {
  if (typeof globalThis.crypto.randomUUID === 'function') return globalThis.crypto.randomUUID()
  const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64
  bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function identity(): string {
  try { return typeof window === 'undefined' ? '' : localStorage.getItem('token') || '' }
  catch { return '' }
}

async function enabled(): Promise<boolean> {
  const token = identity()
  if (!token || !post) return false
  if (status?.identity === token && status.until > Date.now()) return status.enabled
  if (statusRequest && statusIdentity === token) return statusRequest
  statusIdentity = token
  const pending = post(`${PREFIX}status`, {}).then((value) => {
    const result = value as { code?: number; data?: { enabled?: boolean } }
    const allowed = result.code === 200 && result.data?.enabled === true
    status = { identity: token, enabled: allowed, until: Date.now() + 30_000 }
    return allowed
  }).catch(() => {
    status = { identity: token, enabled: false, until: Date.now() + 30_000 }
    return false
  }).finally(() => {
    if (statusRequest === pending) statusRequest = undefined
  })
  statusRequest = pending
  return pending
}

function cleanText(value: string): string {
  if (/^(https?:\/\/|\/(?:aid|url|api)\/)/i.test(value)) {
    try {
      const absolute = /^https?:/i.test(value)
      const url = new URL(value, 'https://diagnostics.invalid')
      url.username = ''
      url.password = ''
      url.hash = ''
      for (const [key, item] of url.searchParams) {
        if (PRIVATE_FIELD.test(key.replace(/[-_]/g, '')) || /signature|^x-amz-|^q-sign|credential|^(key|sign|ossaccesskeyid|securitytoken)$/i.test(key)) {
          url.searchParams.set(key, '[已过滤]')
        } else if (/^https?:\/\//i.test(item)) {
          url.searchParams.set(key, cleanText(item))
        }
      }
      return absolute ? url.toString() : `${url.pathname}${url.search}${url.hash}`
    } catch { /* Preserve malformed URLs as error evidence. */ }
  }
  const unescaped = value.replace(/\\(["'])/g, '$1')
  if (/["']role["']\s*:\s*["'](?:system|developer)["']/i.test(unescaped)) return '[已过滤包含系统提示词的非结构化内容]'
  if (unescaped !== value && Array.from(unescaped.matchAll(/["']([\w-]+)["']\s*:/g)).some(([, key]) => PRIVATE_FIELD.test(key.replace(/[-_]/g, '')) || PROMPT_FIELD.test(key.replace(/[-_]/g, '')))) return '[已过滤包含转义秘密字段的非结构化内容]'
  return value
    .replace(/^([ \t]*(?:authorization|proxy-authorization|cookie|set-cookie)\s*:\s*)[^\r\n]*/gim, '$1[已过滤]')
    .replace(/\b(Bearer|Basic)\s+[A-Za-z0-9._~+\/-]+=*/gi, '$1 [已过滤]')
    .replace(/([?&](?:token|key|api[_-]?key|signature|sign|credential|x-amz-[^=\s&]+|q-signature)=)[^&\s"']*/gi, '$1[已过滤]')
    .replace(/(["']?(?:password|passwd|secret|client[_-]?secret|credentials?|api[_-]?(?:key|token)|x[_-]?api[_-]?(?:key|token)|access[_-]?key|secret[_-]?key|private[_-]?key|aes[_-]?key|token|access[_-]?token|refresh[_-]?token|captcha[_-]?token|x[_-]?encrypt[_-]?(?:key|iv|ts)|authorization|proxy[_-]?authorization|cookie|set[_-]?cookie|system[_-]?prompt|system[_-]?instruction|developer[_-]?prompt|agent[_-]?prompt|prompt[_-]?content|base64|b64[_-]?json)["']?\s*[:=]\s*)(?:"(?:\\[\s\S]|[^"\\])*(?:"|$)|'(?:\\[\s\S]|[^'\\])*(?:'|$)|[^\r\n]+)/gi, '$1[已过滤]')
    .replace(/data:[^;\s]+;base64,[A-Za-z0-9+/=\r\n]+/gi, '[媒体内容未采集]')
    .replace(/(https?:\/\/)[^/@\s]+:[^/@\s]+@/gi, '$1[已过滤]@')
}

/** Remove secrets and system prompts without shortening diagnostic text. */
export function sanitizeBrowserDiagnostic(value: unknown, seen = new WeakSet<object>()): unknown {
  if (typeof value === 'string') {
    const raw = value.trim()
    if (raw.startsWith('{') || raw.startsWith('[')) {
      try {
        const parsed = JSON.parse(value)
        const sanitized = sanitizeBrowserDiagnostic(parsed, seen)
        return JSON.stringify(sanitized) === JSON.stringify(parsed) ? value : JSON.stringify(sanitized)
      }
      catch { /* Plain/non-JSON errors retain their text. */ }
    }
    return cleanText(value)
  }
  if (value == null || typeof value !== 'object') return value
  if (seen.has(value)) return '[循环引用未采集]'
  seen.add(value)
  try {
    if (value instanceof Error) return { name: value.name, message: cleanText(value.message), stack: cleanText(value.stack || '') }
    if (value instanceof Date) return value.toISOString()
    if (typeof Blob !== 'undefined' && value instanceof Blob) return { omitted: 'media', size: value.size, type: value.type, ...('name' in value ? { name: value.name } : {}) }
    if (typeof FormData !== 'undefined' && value instanceof FormData) {
      return { formFields: Array.from(value.entries()).map(([name, item]) => sanitizeBrowserDiagnostic({ [name]: item }, seen)) }
    }
    if (value instanceof ArrayBuffer || ArrayBuffer.isView(value)) return { omitted: 'binary' }
    if (Array.isArray(value)) return value.map((item) => sanitizeBrowserDiagnostic(item, seen))
    const record = value as Record<string, unknown>
    const system = /^(system|developer)$/i.test(String(record.role || ''))
    return Object.fromEntries(Object.entries(record).map(([key, item]) => {
      const normalized = key.replace(/[-_]/g, '')
      if (PRIVATE_FIELD.test(normalized)) return [key, '[已过滤秘密字段]']
      if (PROMPT_FIELD.test(normalized) || (system && key === 'content')) return [key, '[已省略智能体提示词]']
      if (/^(base64|inlinedata|audiobase64|imagebase64|b64json)$/i.test(normalized)) return [key, { omitted: 'media' }]
      return [key, sanitizeBrowserDiagnostic(item, seen)]
    }))
  } finally { seen.delete(value) }
}

export function createDiagnosticRequest(url: string, method: string, body?: unknown, requestId?: string): DiagnosticRequest {
  const request = { url, method: method.toUpperCase(), body, requestId: requestId || newDiagnosticRequestId(), requestTime: new Date().toISOString() }
  requestIdentities.set(request, identity())
  return request
}

/** Best-effort evidence upload; never retry the original business request. */
export function captureBrowserFailure(request: DiagnosticRequest | undefined, response: unknown, exception?: unknown, serverRequestId?: string): void {
  if (!request || isDiagnosticUrl(request.url) || !identity()) return
  if (requestIdentities.get(request) !== identity()) return
  const requestId = /^[0-9a-f-]{36}$/i.test(serverRequestId || '') ? serverRequestId! : request.requestId
  if (sent.has(requestId)) return
  sent.add(requestId)
  if (sent.size > 1000) sent.delete(sent.values().next().value!)
  const token = identity()
  const send = () => {
    active++
    void (async () => {
      if (identity() !== token || !(await enabled()) || identity() !== token || !post) return
      const diagnostic = sanitizeBrowserDiagnostic({
        source: 'browser', request: { url: request.url, method: request.method, body: request.body },
        response: response ?? { received: false }, exception,
        capture: { source: 'browser', authoritative: false, eventsNotCaptured: missed, filters: ['secrets', 'system_prompts', 'inline_media'] }
      })
      missed = 0
      await post(`${PREFIX}capture`, { eventId: newDiagnosticRequestId(), requestId, requestTime: request.requestTime, diagnostic })
    })().catch(() => { /* Diagnostics must not affect the original operation. */ }).finally(() => {
      active--
      pending.shift()?.()
    })
  }
  if (active < 2) send()
  else if (pending.length < 32) pending.push(send)
  else missed++
}

async function responseEvidence(response: Response): Promise<unknown> {
  const reader = response.body?.getReader()
  if (!reader) return { body: null, received: false }
  const decoder = new TextDecoder()
  let size = 0
  let body = ''
  try {
    while (true) {
      const chunk = await reader.read()
      if (chunk.done) return { body: body + decoder.decode() }
      size += chunk.value.byteLength
      if (size > MAX_RESPONSE_BYTES) return { bodyOmitted: 'browser_capture_capacity', incomplete: true }
      body += decoder.decode(chunk.value, { stream: true })
    }
  } catch (error) {
    return { body, interrupted: true, exception: error }
  } finally {
    void reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

/** Observe explicit fetch call sites without replacing global fetch or consuming their response. */
export async function diagnosticFetch(url: string, init?: RequestInit): Promise<Response> {
  const context = createDiagnosticRequest(url, init?.method || 'GET', init?.body)
  const headers = new Headers(init?.headers)
  if (typeof window !== 'undefined') {
    const resolved = new URL(url, window.location.href)
    if (resolved.origin === window.location.origin && /\/(?:aid\/|url\/)?api\/user\//.test(resolved.pathname)) {
      headers.set('X-AID-Request-ID', context.requestId)
    }
  }
  try {
    const response = await fetch(url, { ...init, headers })
    responses.set(response, context)
    const contentType = response.headers.get('content-type') || ''
    const disabled = status?.identity === identity() && status.until > Date.now() && !status.enabled
    if (!disabled && !isDiagnosticUrl(url) && (!response.ok || contentType.includes('application/json'))) {
      const copy = response.clone()
      void (async () => {
        if (!(await enabled())) {
          void copy.body?.cancel().catch(() => {})
          return
        }
        const evidence = await responseEvidence(copy) as { body?: string }
        let failed = !response.ok
        if (evidence.body && contentType.includes('application/json')) {
          try {
            const payload = JSON.parse(evidence.body) as { code?: number }
            failed ||= payload.code !== undefined && payload.code !== 0 && payload.code !== 200
          } catch { failed = true }
        }
        if (failed) captureBrowserFailure(context, { status: response.status, ...evidence }, undefined, response.headers.get('X-AID-Request-ID') || undefined)
      })().catch(() => {})
    }
    return response
  } catch (error) {
    if (!init?.signal?.aborted) captureBrowserFailure(context, undefined, error)
    throw error
  }
}

export function captureStreamFailure(response: Response | undefined, rawEvent: unknown, error?: unknown): void {
  if (!response) return
  captureBrowserFailure(responses.get(response), { status: response.status, stream: true, rawEvent }, error,
    response.headers.get('X-AID-Request-ID') || undefined)
}
