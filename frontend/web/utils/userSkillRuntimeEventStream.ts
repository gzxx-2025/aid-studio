import { captureStreamFailure, diagnosticFetch } from '~/utils/errorDiagnostics'
import type {
  UserSkillRuntimeEventView,
  UserSkillRuntimeOutputDelta,
  UserSkillRuntimeRunHandle
} from '~/types/user-skill'
import { buildUserApiAuthHeaders, redirectToLogin, resolveClientApiUrl } from '~/utils/api'
import { parseSseEventBlock } from '~/utils/sseEventBlock'

const MAX_EVENT_BUFFER_CHARS = 512 * 1024

type UnknownRecord = Record<string, unknown>

export interface UserSkillRuntimeSseHandlers {
  onMilestone: (event: UserSkillRuntimeEventView) => void
  onOutputDelta?: (delta: UserSkillRuntimeOutputDelta, seq: number) => void
  onReasoningDelta?: (delta: UserSkillRuntimeOutputDelta, seq: number) => void
  onSnapshot?: (snapshot: UserSkillRuntimeRunHandle) => void
  onReconnectRequired?: () => void
}

function record(value: unknown): UnknownRecord | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as UnknownRecord
    : null
}

function parseRecord(raw: string): UnknownRecord | null {
  try {
    return record(JSON.parse(raw))
  } catch {
    return null
  }
}

function parseMilestone(data: UnknownRecord, eventType: string): UserSkillRuntimeEventView | null {
  const seq = Number(data.seq)
  if (!Number.isFinite(seq) || seq <= 0) return null
  return {
    seq,
    eventType: String(data.eventType || eventType || ''),
    stage: data.stage == null ? null : String(data.stage),
    stepId: data.stepId == null ? null : Number(data.stepId),
    mediaTaskId: data.mediaTaskId == null ? null : Number(data.mediaTaskId),
    payloadJson: data.payloadJson == null ? null : String(data.payloadJson),
    createTime: data.createTime == null ? null : String(data.createTime)
  }
}

function parseRunSnapshot(data: UnknownRecord): UserSkillRuntimeRunHandle | null {
  const runId = Number(data.runId)
  if (!Number.isFinite(runId) || runId <= 0) return null
  return data as unknown as UserSkillRuntimeRunHandle
}

export function parseUserSkillRuntimeOutputDelta(
  data: UnknownRecord
): UserSkillRuntimeOutputDelta | null {
  if (!data.payloadJson) return null
  const payload = typeof data.payloadJson === 'string'
    ? parseRecord(data.payloadJson)
    : record(data.payloadJson)
  const content = String(payload?.content || '')
  const artifactType = String(payload?.artifactType || '').trim()
  const stepExecutionId = String(payload?.stepExecutionId || '').trim()
  if (!content || !artifactType || !stepExecutionId || typeof payload?.reset !== 'boolean') return null
  return {
    content,
    artifactType,
    stepExecutionId,
    reset: payload.reset
  }
}

function normalizeLineEndings(value: string, flush = false): string {
  const holdTrailingCarriageReturn = !flush && value.endsWith('\r')
  const complete = holdTrailingCarriageReturn ? value.slice(0, -1) : value
  const normalized = complete.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  return holdTrailingCarriageReturn ? `${normalized}\r` : normalized
}

/** Pure SSE event router — kept testable without a live fetch stream. */
export function dispatchUserSkillRuntimeSseEvent(
  eventType: string,
  raw: string,
  handlers: UserSkillRuntimeSseHandlers
): void {
  const data = parseRecord(raw)
  if (!data) return
  if (eventType === 'snapshot') {
    const snapshot = parseRunSnapshot(data)
    if (snapshot) handlers.onSnapshot?.(snapshot)
    return
  }
  if (eventType === 'output_delta' || eventType === 'reasoning_delta') {
    const delta = parseUserSkillRuntimeOutputDelta(data)
    const seq = Number(data.seq)
    if (!delta || !Number.isFinite(seq) || seq <= 0) return
    if (eventType === 'reasoning_delta') handlers.onReasoningDelta?.(delta, seq)
    else handlers.onOutputDelta?.(delta, seq)
    return
  }
  if (eventType === 'reconnect_required') {
    handlers.onReconnectRequired?.()
    return
  }
  const milestone = parseMilestone(data, eventType)
  if (milestone) handlers.onMilestone(milestone)
}

export async function streamUserSkillRuntimeEvents(input: {
  runId: number
  afterSeq: number
  signal: AbortSignal
} & UserSkillRuntimeSseHandlers): Promise<void> {
  if (typeof window === 'undefined') throw new Error('Skill 事件流仅支持浏览器环境')
  const auth = buildUserApiAuthHeaders()
  if (!auth.Authorization) {
    redirectToLogin()
    throw new Error('AUTH_REDIRECT')
  }
  const response = await diagnosticFetch(resolveClientApiUrl('/api/user/skill/execution/run/events/stream'), {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Accept-Encoding': 'identity',
      'Content-Type': 'application/json',
      ...auth
    },
    body: JSON.stringify({ runId: input.runId, afterSeq: input.afterSeq }),
    cache: 'no-store',
    credentials: 'omit',
    signal: input.signal
  })
  if (response.status === 401) {
    redirectToLogin()
    throw new Error('登录状态已失效')
  }
  if (!response.ok || !String(response.headers.get('content-type') || '').toLowerCase().includes('text/event-stream')) {
    const raw = await response.text()
    const parsed = parseRecord(raw)
    throw new Error(String(parsed?.msg || parsed?.message || `Skill 事件连接失败（HTTP ${response.status}）`))
  }
  const reader = response.body?.getReader()
  if (!reader) {
    captureStreamFailure(response, null, new Error('Skill 事件响应为空'))
    throw new Error('Skill 事件响应为空')
  }

  const handlers: UserSkillRuntimeSseHandlers = {
    onMilestone: input.onMilestone,
    onOutputDelta: input.onOutputDelta,
    onReasoningDelta: input.onReasoningDelta,
    onSnapshot: input.onSnapshot,
    onReconnectRequired: input.onReconnectRequired
  }

  const decoder = new TextDecoder()
  let buffer = ''
  let completed = false
  const dispatchObserved = (event: string, raw: string) => {
    const data = parseRecord(raw)
    const payload = typeof data?.payloadJson === 'string' ? parseRecord(data.payloadJson) : record(data?.payloadJson)
    const status = String(payload?.status || data?.status || '').toUpperCase()
    if (event === 'terminal' || event === 'reconnect_required' || event === 'input_required'
      || (event === 'snapshot' && ['SUCCEEDED', 'FAILED', 'CANCELED', 'CANCELLED'].includes(status))) completed = true
    if (event === 'error' || status === 'FAILED') captureStreamFailure(response, { event, data: raw })
    dispatchUserSkillRuntimeSseEvent(event, raw, handlers)
  }
  try {
    while (!input.signal.aborted) {
      const chunk = await reader.read()
      if (chunk.done) {
        buffer += decoder.decode()
        buffer = normalizeLineEndings(buffer, true)
        break
      }
      if (chunk.value) buffer += decoder.decode(chunk.value, { stream: true })
      if (buffer.length > MAX_EVENT_BUFFER_CHARS) throw new Error('Skill 事件超过安全限制')
      buffer = normalizeLineEndings(buffer)
      let separator = buffer.indexOf('\n\n')
      while (separator >= 0) {
        const block = buffer.slice(0, separator)
        buffer = buffer.slice(separator + 2)
        const parsed = parseSseEventBlock(block)
        if (parsed) dispatchObserved(parsed.event, parsed.data)
        separator = buffer.indexOf('\n\n')
      }
    }
    if (buffer.trim()) {
      const parsed = parseSseEventBlock(buffer.trim())
      if (parsed) dispatchObserved(parsed.event, parsed.data)
    }
    if (!input.signal.aborted && !completed) captureStreamFailure(response, buffer, new Error('Skill 事件流在终态前结束'))
  } catch (error) {
    if (!input.signal.aborted) captureStreamFailure(response, buffer, error)
    throw error
  } finally {
    try {
      await reader.cancel()
    } catch {
      // The route, pause action, or terminal event may already have closed the reader.
    }
  }
}
