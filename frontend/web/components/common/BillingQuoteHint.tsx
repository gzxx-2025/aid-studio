'use client'

import { LoadingOutlined } from '@ant-design/icons'
import type { BillingQuoteVO } from '~/types/business-api'
import { billingQuoteTextForDisplay } from '~/utils/billingQuoteDisplay'
import './BillingQuoteHint.css'

interface Props {
  quote?: BillingQuoteVO | null
  loading?: boolean
  error?: string
  className?: string
  /** 未形成完整报价请求时不显示占位。 */
  active?: boolean
  /** 已选模型但业务必填参数未齐时的简短提示。 */
  idleText?: string
}

/** 提交区附近的只读权威报价提示；不控制、也不禁用提交按钮。 */
export function BillingQuoteHint({
  quote,
  loading = false,
  error = '',
  className = '',
  active = true,
  idleText = ''
}: Props) {
  if (!active) return null
  const text = billingQuoteTextForDisplay(quote)
  const classes = ['billing-quote-hint', className].filter(Boolean).join(' ')

  if (loading) {
    return (
      <div className={`${classes} is-loading`} role="status" aria-live="polite">
        <LoadingOutlined spin aria-hidden="true" />
        <span>正在获取预计费用…</span>
      </div>
    )
  }

  if (text) {
    return (
      <div
        className={`${classes}${quote?.isFree ? ' is-free' : ''}${quote?.estimated ? ' is-estimated' : ''}`}
        role="status"
        aria-live="polite"
        title={quote?.skuName || undefined}
      >
        <span className="billing-quote-hint__dot" aria-hidden="true" />
        <span>{text}</span>
        {quote?.skuName ? <span className="billing-quote-hint__sku">{quote.skuName}</span> : null}
      </div>
    )
  }

  if (error) {
    return (
      <div className={`${classes} is-unavailable`} title={error}>
        报价暂不可用，不影响提交
      </div>
    )
  }

  if (idleText) {
    return <div className={`${classes} is-idle`}>{idleText}</div>
  }

  return null
}

export default BillingQuoteHint
