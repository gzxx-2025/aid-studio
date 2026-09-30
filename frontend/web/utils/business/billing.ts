/** 用户侧权威计费报价；只读、无冻结和扣费副作用。 */
import axios, { type AxiosRequestConfig } from 'axios'
import type { ApiEnvelope, BillingQuoteRequest, BillingQuoteVO } from '~/types/business-api'
import { request } from '~/utils/api'
import { unwrap } from '~/utils/business/shared'
import { useUserStore } from '~/stores/user'

const pending = new Map<string, Promise<BillingQuoteVO>>()
const settled = new Map<string, { at: number; value: BillingQuoteVO }>()
const CACHE_MS = 750
const CACHE_LIMIT = 64
let authScope = ''

function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical)
  if (!value || typeof value !== 'object') return value
  return Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b))
    .map(([key, item]) => [key, canonical(item)]))
}

/** 取消单个订阅不取消共享网络请求，避免切换选项后相同请求键再次发送。 */
function subscribe(quote: Promise<BillingQuoteVO>, signal?: AxiosRequestConfig['signal']): Promise<BillingQuoteVO> {
  if (!signal) return quote.then((value) => structuredClone(value))
  return new Promise((resolve, reject) => {
    const cancel = () => reject(new axios.CanceledError('报价请求已取消'))
    if (signal.aborted) cancel()
    else signal.addEventListener?.('abort', cancel, { once: true })
    quote.then((value) => {
      if (!signal.aborted) resolve(structuredClone(value))
    }, reject).finally(() => signal.removeEventListener?.('abort', cancel))
  })
}

export async function userBillingQuote(
  body: BillingQuoteRequest,
  config?: Pick<AxiosRequestConfig, 'signal'> & { force?: boolean }
): Promise<BillingQuoteVO> {
  if (config?.signal?.aborted) throw new axios.CanceledError('报价请求已取消')
  const token = useUserStore.getState().token
  if (token !== authScope) { settled.clear(); authScope = token }
  const key = JSON.stringify([token, canonical(body)])
  // force 只绕过已完成结果缓存；进行中的同键报价始终合并。
  const running = pending.get(key)
  if (running) return subscribe(running, config?.signal)
  const hit = settled.get(key)
  if (!config?.force && hit && Date.now() - hit.at < CACHE_MS) {
    return subscribe(Promise.resolve(hit.value), config?.signal)
  }
  const promise = request.post<ApiEnvelope<BillingQuoteVO>>('/api/user/billing/quote', body)
    .then((res) => {
      const value = unwrap(res)
      if (authScope === token) {
        const now = Date.now()
        for (const [cachedKey, entry] of settled) {
          if (now - entry.at >= CACHE_MS) settled.delete(cachedKey)
        }
        settled.delete(key)
        settled.set(key, { at: now, value })
        while (settled.size > CACHE_LIMIT) settled.delete(settled.keys().next().value!)
      }
      return value
    }).finally(() => {
      if (pending.get(key) === promise) pending.delete(key)
    })
  pending.set(key, promise)
  return subscribe(promise, config?.signal)
}
