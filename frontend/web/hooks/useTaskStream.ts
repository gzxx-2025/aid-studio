import { captureStreamFailure, diagnosticFetch } from '~/utils/errorDiagnostics'
import { buildUserApiAuthHeaders, redirectToLogin, resolveClientApiUrl } from '~/utils/api'
import { claimTaskStreamConnectSlot } from '~/utils/taskStreamConnectGuard'
import {
  advanceTaskEtaProgress,
  parseTaskSseProgressPayload,
  withTaskSseDisplayTiming,
  type TaskEtaProgress,
  type TaskSseProgressInput
} from '~/utils/taskSseProgressText'
import { isBenignTaskSseDisconnectMessage } from '~/utils/taskSseSilentDisconnect'
import { scheduleUserBalanceRefresh } from '~/utils/userBalanceRefresh'
import type { TaskPartialFailedData } from '~/utils/taskPartialFailed'
import { parseTaskPartialFailedData } from '~/utils/taskPartialFailed'

const MAX_SSE_BUFFER_CHARS = 1024 * 1024

/** SSE progress / queued 事件数据（与 {@link TaskSseProgressInput} 一致） */
export type TaskProgressEventData = TaskSseProgressInput

/** 结构化错误协议 */
export interface TaskErrorEventData {
  taskStatus?: string
  errorCode?: string
  errorType?: string
  errorSource?: string
  userMessage?: string
  rawMessage?: string
  needRecharge?: boolean
  rechargeOwner?: string
  retryable?: boolean
  billingStatus?: string
  refundStatus?: string
  /** 向后兼容旧字段 */
  errorMessage?: string
}

export type TaskStreamResult =
  | { type: 'complete'; data: unknown }
  | { type: 'partial_failed'; data: TaskPartialFailedData | null }
  | { type: 'error'; errorMessage: string; errorData?: TaskErrorEventData }
  | { type: 'cancelled'; message: string }

/** 任务 SSE 连接句柄（框架无关；React 组件经由 hooks 包装消费） */
export interface TaskStreamHandle {
  isConnected(): boolean
  isClosed(): boolean
  getLastProgress(): TaskProgressEventData | null
  /** 订阅进度更新；订阅时若已有进度会立即回调一次（对齐原 watch immediate 语义） */
  subscribeProgress(cb: (p: TaskProgressEventData) => void): () => void
  close(): void
  done: Promise<TaskStreamResult>
}

function safeParseJson(raw: string): unknown {
  try {
    return JSON.parse(raw)
  } catch {
    return raw
  }
}

/** 解析一个 SSE 事件块（以空行分隔），得到 event 名与合并后的 data 文本 */
export function parseTaskSseEventBlock(block: string): { event: string; data: string } | null {
  const lines = block.split('\n')
  let eventName = ''
  const dataLines: string[] = []
  for (const raw of lines) {
    const line = raw.replace(/\r$/, '')
    if (!line) continue
    // SSE 注释帧（心跳等），跳过
    if (line.startsWith(':')) continue
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      // 规范：data: 后可跟一个可选空格
      dataLines.push(line.slice(5).replace(/^\s/, ''))
    }
  }
  const data = dataLines.join('\n')
  if (!eventName && !data) return null
  return { event: eventName || 'message', data }
}

function resolveErrorFromPayload(dataRaw: string): {
  errorMessage: string
  errorData?: TaskErrorEventData
} {
  const rawData = String(dataRaw || '').trim()
  if (!rawData) return { errorMessage: 'Task SSE error' }
  const parsed = safeParseJson(rawData)
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    const o = parsed as Record<string, unknown>
    const errorData: TaskErrorEventData = {
      taskStatus: typeof o.taskStatus === 'string' ? o.taskStatus : undefined,
      errorCode: typeof o.errorCode === 'string' ? o.errorCode : undefined,
      errorType: typeof o.errorType === 'string' ? o.errorType : undefined,
      errorSource: typeof o.errorSource === 'string' ? o.errorSource : undefined,
      userMessage: typeof o.userMessage === 'string' ? o.userMessage : undefined,
      rawMessage: typeof o.rawMessage === 'string' ? o.rawMessage : undefined,
      needRecharge: typeof o.needRecharge === 'boolean' ? o.needRecharge : undefined,
      rechargeOwner: typeof o.rechargeOwner === 'string' ? o.rechargeOwner : undefined,
      retryable: typeof o.retryable === 'boolean' ? o.retryable : undefined,
      billingStatus: typeof o.billingStatus === 'string' ? o.billingStatus : undefined,
      refundStatus: typeof o.refundStatus === 'string' ? o.refundStatus : undefined,
      errorMessage: typeof o.errorMessage === 'string' ? o.errorMessage : undefined
    }
    // 优先使用 userMessage，兼容旧 errorMessage
    const msg =
      errorData.userMessage ||
      errorData.errorMessage ||
      (typeof o.message === 'string' ? o.message : '') ||
      'Task SSE error'
    return { errorMessage: msg, errorData }
  }
  if (typeof parsed === 'string' && parsed) return { errorMessage: parsed }
  return { errorMessage: 'Task SSE error' }
}

function resolveCancelledMessage(dataRaw: string): string {
  const rawData = String(dataRaw || '').trim()
  if (!rawData) return '任务已取消'
  const parsed = safeParseJson(rawData)
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    const o = parsed as Record<string, unknown>
    if (typeof o.message === 'string' && o.message) return o.message
  }
  if (typeof parsed === 'string' && parsed) return parsed
  return '任务已取消'
}

/**
 * 连接任务 SSE（`text/event-stream`，与后端 `SseEmitter` 一致）。
 *
 * 原生 `EventSource` **无法**设置 `Authorization`，因此使用 `fetch` + `ReadableStream`
 * 解析事件流，请求头与 axios 一致（{@link buildUserApiAuthHeaders}）。
 *
 * 事件：`queued` | `progress` | `complete` | `partial_failed` | `error` | `cancelled` | `warning`
 * 未命名 `data` 时 event 视为 `message`，按 progress 兼容处理。
 */
export function createTaskStream(
  taskId: number,
  options?: { initialProgress?: TaskProgressEventData | null }
): TaskStreamHandle {
  let connected = false
  let closed = false
  let lastProgress: TaskProgressEventData | null = null
  const progressListeners = new Set<(p: TaskProgressEventData) => void>()
  let etaTicker: ReturnType<typeof setInterval> | null = null
  let etaAnchor: TaskEtaProgress | null = null

  let abortController: AbortController | null = null
  let streamResponse: Response | undefined
  let settled = false

  const setLastProgress = (p: TaskProgressEventData, fromEtaTicker = false) => {
    // 详情接口恢复出的 ETA 是当前任务的计时锚点。SSE 首包可能只有 connected/message，
    // 不应因此清掉 ETA，否则进度会长期停在服务端最后一次返回的百分比。
    const nextProgress = !p.eta && lastProgress?.eta ? { ...p, eta: lastProgress.eta } : p
    lastProgress = nextProgress
    const displayProgress = withTaskSseDisplayTiming(nextProgress)
    for (const cb of progressListeners) cb(displayProgress)
    if (!fromEtaTicker && p.eta) {
      etaAnchor = p.eta
    }
    const etaCompleted =
      nextProgress.eta?.phase === 'COMPLETED' || nextProgress.eta?.displayProgress === 100
    if (etaCompleted && etaTicker) {
      clearInterval(etaTicker)
      etaTicker = null
    } else if (!fromEtaTicker && p.eta && !etaTicker) {
      etaTicker = setInterval(() => {
        if (closed || !lastProgress?.eta || !etaAnchor) return
        const eta = advanceTaskEtaProgress(etaAnchor)
        if (!eta) return
        setLastProgress({ ...lastProgress, eta }, true)
      }, 15_000)
    }
  }

  if (typeof window !== 'undefined' && options?.initialProgress) {
    setLastProgress(options.initialProgress)
  }

  const close = () => {
    if (closed) return
    closed = true
    if (etaTicker) {
      clearInterval(etaTicker)
      etaTicker = null
    }
    try {
      abortController?.abort()
    } catch {
      // ignore
    } finally {
      abortController = null
    }
  }

  const done = new Promise<TaskStreamResult>((resolve, reject) => {
    if (typeof window === 'undefined') {
      reject(new Error('Task SSE only available on client'))
      return
    }

    const settleOnce = (fn: () => void) => {
      if (settled) return
      settled = true
      fn()
      close()
    }

    const applyProgressPayload = (raw: string, eventName: string) => {
      const parsed = parseTaskSseProgressPayload(raw, taskId)
      if (!parsed) return
      if (eventName === 'queued') {
        parsed.status = parsed.status || 'QUEUED'
      }
      setLastProgress(parsed)
    }

    const dispatchEvent = (eventName: string, dataRaw: string) => {
      const name = eventName || 'message'

      // queued / progress / message — 非终态，更新进度（queued 与 progress 字段一致）
      if (name === 'queued' || name === 'progress' || name === 'message') {
        if (dataRaw) applyProgressPayload(dataRaw, name)
        return
      }

      // warning — 非终态，仅更新进度文案（不关闭连接）
      if (name === 'warning') {
        if (dataRaw) {
          const parsed = parseTaskSseProgressPayload(dataRaw, taskId)
          const msg = parsed?.message || ''
          if (msg) {
            setLastProgress({ ...(lastProgress || {}), message: msg, stepTitle: msg })
          }
        }
        return
      }

      // complete — 终态：任务成功
      if (name === 'complete') {
        settleOnce(() => {
          scheduleUserBalanceRefresh()
          resolve({ type: 'complete', data: safeParseJson(dataRaw) })
        })
        return
      }

      // partial_failed — 终态：部分成功部分失败（可续生）
      if (name === 'partial_failed') {
        captureStreamFailure(streamResponse, { event: name, data: dataRaw })
        settleOnce(() => {
          scheduleUserBalanceRefresh()
          resolve({
            type: 'partial_failed',
            data: parseTaskPartialFailedData(safeParseJson(dataRaw))
          })
        })
        return
      }

      // error — 终态：任务失败（结构化错误）
      if (name === 'error') {
        const { errorMessage, errorData } = resolveErrorFromPayload(dataRaw)
        if (!isBenignTaskSseDisconnectMessage(errorMessage)) {
          captureStreamFailure(streamResponse, { event: name, data: dataRaw })
        }
        settleOnce(() => {
          // 良性断连由上层重连；此处不刷积分，避免空窗误触发
          if (!isBenignTaskSseDisconnectMessage(errorMessage)) {
            scheduleUserBalanceRefresh()
          }
          resolve({ type: 'error', errorMessage, errorData })
        })
        return
      }

      // cancelled — 终态：任务取消
      if (name === 'cancelled') {
        settleOnce(() => {
          scheduleUserBalanceRefresh()
          resolve({ type: 'cancelled', message: resolveCancelledMessage(dataRaw) })
        })
        return
      }
    }

    void (async () => {
      const auth = buildUserApiAuthHeaders()
      if (!auth.Authorization) {
        redirectToLogin()
        settleOnce(() => reject(new Error('AUTH_REDIRECT')))
        return
      }

      // 同一 taskId 短窗内建连过多：直接拒绝，避免调用方 tight-loop 打爆浏览器/网关
      if (!claimTaskStreamConnectSlot(taskId)) {
        settleOnce(() => reject(new Error('Task SSE connect rate limited')))
        return
      }

      const url = resolveClientApiUrl(`/api/user/task/stream/${taskId}`)
      abortController = new AbortController()

      let res: Response
      try {
        res = await diagnosticFetch(url, {
          method: 'GET',
          headers: {
            Accept: 'text/event-stream',
            ...auth
          },
          signal: abortController.signal,
          cache: 'no-store'
        })
        streamResponse = res
      } catch (e: unknown) {
        if (closed) {
          settleOnce(() => reject(new Error('Task SSE aborted')))
          return
        }
        if (e instanceof DOMException && e.name === 'AbortError') return
        settleOnce(() => reject(e instanceof Error ? e : new Error('Task SSE fetch failed')))
        return
      }

      if (res.status === 401) {
        redirectToLogin()
        settleOnce(() => reject(new Error('Unauthorized')))
        return
      }

      if (!res.ok) {
        settleOnce(() => reject(new Error(`SSE HTTP ${res.status}`)))
        return
      }

      connected = true

      const reader = res.body?.getReader()
      if (!reader) {
        captureStreamFailure(res, null, new Error('SSE: no response body'))
        settleOnce(() => reject(new Error('SSE: no response body')))
        return
      }

      const decoder = new TextDecoder()
      let buffer = ''

      try {
        while (!settled) {
          const { value, done: readDone } = await reader.read()
          if (readDone) break
          if (value) {
            buffer += decoder.decode(value, { stream: true })
            if (buffer.length > MAX_SSE_BUFFER_CHARS) {
              throw new Error('SSE event exceeds safety limit')
            }
          }
          buffer = buffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n')

          // 按空行切分完整 SSE 事件块
          let sep: number
          while ((sep = buffer.indexOf('\n\n')) >= 0) {
            const rawBlock = buffer.slice(0, sep)
            buffer = buffer.slice(sep + 2)
            const parsed = parseTaskSseEventBlock(rawBlock)
            if (parsed) dispatchEvent(parsed.event, parsed.data)
            if (settled) return
          }
        }

        if (!settled) {
          const tail = buffer.trim()
          if (tail) {
            const parsed = parseTaskSseEventBlock(tail)
            if (parsed) dispatchEvent(parsed.event, parsed.data)
          }
        }

        // 主动 close（切作品/中断跟进）时 reader 可能以 done 结束而非抛 AbortError
        if (!settled && closed) {
          settleOnce(() => reject(new Error('Task SSE aborted')))
        } else if (!settled && !closed) {
          captureStreamFailure(res, buffer, new Error('Task SSE ended unexpectedly'))
          settleOnce(() => reject(new Error('Task SSE ended unexpectedly')))
        }
      } catch (e: unknown) {
        if (closed) {
          settleOnce(() => reject(new Error('Task SSE aborted')))
          return
        }
        if (e instanceof DOMException && e.name === 'AbortError') return
        captureStreamFailure(res, buffer, e)
        settleOnce(() => reject(e instanceof Error ? e : new Error('Task SSE read failed')))
      }
    })()
  })

  return {
    isConnected: () => connected,
    isClosed: () => closed,
    getLastProgress: () => (lastProgress ? withTaskSseDisplayTiming(lastProgress) : null),
    subscribeProgress(cb) {
      progressListeners.add(cb)
      if (lastProgress) cb(withTaskSseDisplayTiming(lastProgress))
      return () => {
        progressListeners.delete(cb)
      }
    },
    close,
    done
  }
}

/**
 * SSE 等待终态：连接异常断连时转为 error 结果，避免 Promise 永久挂起导致 UI loading 无法清除。
 */
export function taskStreamDoneForRace(stream: TaskStreamHandle): Promise<TaskStreamResult> {
  return stream.done.catch((err: unknown) => {
    const msg =
      err instanceof Error && String(err.message || '').trim()
        ? String(err.message).trim()
        : '任务连接中断'
    return { type: 'error' as const, errorMessage: msg }
  })
}
