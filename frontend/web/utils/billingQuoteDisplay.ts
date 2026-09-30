import type { BillingQuoteVO } from '~/types/business-api'

const quoteAmountFormatter = new Intl.NumberFormat('zh-CN', {
  useGrouping: false,
  minimumFractionDigits: 2,
  maximumFractionDigits: 2
})

/** 报价金额仅在展示时保留两位小数，原始金额继续用于计费与余额检查。 */
export function billingQuoteAmountForDisplay(quote: BillingQuoteVO | null | undefined): string | null {
  if (!quote) return null
  const raw = quote.preHoldAmount ?? quote.amount
  const amount = raw == null
    ? Number(String(quote.displayText || '').match(/(\d+(?:\.\d+)?)(?=\s*积分)/)?.[1] ?? NaN)
    : Number(raw)
  return Number.isFinite(amount) ? quoteAmountFormatter.format(amount) : null
}

/** 保留服务端预估/预扣语义，并统一金额文字、Tooltip 与无障碍提示的展示精度。 */
export function billingQuoteTextForDisplay(quote: BillingQuoteVO | null | undefined): string {
  if (!quote) return ''
  if (quote.isFree) return '免费'
  const text = String(quote.displayText || '').trim()
  const amount = billingQuoteAmountForDisplay(quote)
  if (amount == null) return text
  return text
    ? text.replace(/\d+(?:\.\d+)?(?=\s*积分)/, amount)
    : `${quote.estimated ? '预扣约 ' : ''}${amount} 积分`
}

/** 仅用于前端展示的整数积分；不改动报价原始数值。 */
export function billingQuoteCreditsForDisplay(quote: BillingQuoteVO | null | undefined): number | null {
  if (!quote) return null
  if (quote.isFree) return 0

  const raw = quote.preHoldAmount ?? quote.amount
  if (raw != null && Number.isFinite(Number(raw))) {
    return Math.round(Number(raw))
  }

  const text = String(quote.displayText || '')
  const matched = text.match(/(\d+(?:\.\d+)?)/)
  if (!matched) return null
  const parsed = Number(matched[1])
  return Number.isFinite(parsed) ? Math.round(parsed) : null
}
