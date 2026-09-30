'use client'

import axios from 'axios'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useUserStore } from '~/stores/user'
import type { BillingQuoteRequest, BillingQuoteVO } from '~/types/business-api'
import { userBillingQuote } from '~/utils/businessApi'

const DEFAULT_DEBOUNCE_MS = 320

export interface UseBillingQuoteOptions {
  enabled?: boolean
  debounceMs?: number
}

export interface UseBillingQuoteResult {
  quote: BillingQuoteVO | null
  loading: boolean
  error: string
  refresh: () => void
  clear: () => void
}

function stableValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stableValue)
  if (!value || typeof value !== 'object') return value
  const source = value as Record<string, unknown>
  const sorted: Record<string, unknown> = {}
  for (const key of Object.keys(source).sort()) sorted[key] = stableValue(source[key])
  return sorted
}

function requestKey(request: BillingQuoteRequest | null): string {
  return request ? JSON.stringify(stableValue(request)) : ''
}

function extractErrorMessage(error: unknown): string {
  if (!error || typeof error !== 'object') return '报价暂不可用'
  const value = error as {
    msg?: string
    message?: string
    response?: { data?: { msg?: string; message?: string } }
  }
  return String(
    value.response?.data?.msg ||
      value.response?.data?.message ||
      value.msg ||
      value.message ||
      '报价暂不可用'
  )
}

/**
 * 对生成请求做防抖权威报价。参数变化时清除旧报价并取消当前订阅，网络请求按业务键合并。
 * 请求序号防止竞态回写；报价失败只返回提示状态，不改变调用方提交按钮状态。
 */
export function useBillingQuote(
  request: BillingQuoteRequest | null,
  options: UseBillingQuoteOptions = {}
): UseBillingQuoteResult {
  const enabled = options.enabled !== false
  const debounceMs = options.debounceMs ?? DEFAULT_DEBOUNCE_MS
  const authToken = useUserStore((state) => state.token)
  const serializedRequest = useMemo(() => requestKey(request), [request])
  const stableRequest = useMemo<BillingQuoteRequest | null>(
    () => (serializedRequest ? (JSON.parse(serializedRequest) as BillingQuoteRequest) : null),
    [serializedRequest]
  )
  const sequenceRef = useRef(0)
  const forceNextRef = useRef(false)
  const controllerRef = useRef<AbortController | null>(null)
  const timerRef = useRef<number | null>(null)
  const [refreshToken, setRefreshToken] = useState(0)
  const [quote, setQuote] = useState<BillingQuoteVO | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const cancelPending = useCallback(() => {
    if (timerRef.current != null) {
      window.clearTimeout(timerRef.current)
      timerRef.current = null
    }
    controllerRef.current?.abort()
    controllerRef.current = null
  }, [])

  const clear = useCallback(() => {
    cancelPending()
    sequenceRef.current += 1
    setQuote(null)
    setLoading(false)
    setError('')
  }, [cancelPending])

  const refresh = useCallback(() => {
    forceNextRef.current = true
    cancelPending()
    sequenceRef.current += 1
    setQuote(null)
    setLoading(false)
    setError('')
    setRefreshToken((value) => value + 1)
  }, [cancelPending])

  useEffect(() => {
    cancelPending()
    const current = stableRequest
    const sequence = ++sequenceRef.current
    setQuote(null)
    setError('')

    if (!enabled || !authToken || !serializedRequest || !current) {
      setLoading(false)
      return
    }

    const controller = new AbortController()
    controllerRef.current = controller
    setLoading(true)
    timerRef.current = window.setTimeout(() => {
      timerRef.current = null
      const force = forceNextRef.current
      forceNextRef.current = false
      void userBillingQuote(current, { signal: controller.signal, force })
        .then((value) => {
          if (sequence !== sequenceRef.current || controller.signal.aborted) return
          setQuote(value)
          setError('')
        })
        .catch((reason: unknown) => {
          if (sequence !== sequenceRef.current || controller.signal.aborted || axios.isCancel(reason)) return
          setQuote(null)
          setError(extractErrorMessage(reason))
        })
        .finally(() => {
          if (controllerRef.current === controller) controllerRef.current = null
          if (sequence === sequenceRef.current && !controller.signal.aborted) setLoading(false)
        })
    }, Math.max(0, debounceMs))

    return () => {
      cancelPending()
    }
  }, [authToken, cancelPending, debounceMs, enabled, refreshToken, serializedRequest, stableRequest])

  return { quote, loading, error, refresh, clear }
}

export default useBillingQuote
