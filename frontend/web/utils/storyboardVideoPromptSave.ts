import { EDIT_ASSET_PROMPT_MAX_CHARS } from './htmlPlain'

function validateRequiredVideoPrompt(
  value: string,
  maxLength = EDIT_ASSET_PROMPT_MAX_CHARS
): { ok: true } | { ok: false; message: string } {
  const text = String(value ?? '')
  if (!text.trim()) {
    return { ok: false, message: '提示词不能为空' }
  }
  if (text.length > maxLength) {
    return { ok: false, message: '提示词过长' }
  }
  return { ok: true }
}

/** 多参方向提示词：前端仅校验非空与统一长度边界。 */
export function validateMultiParamVideoPromptPlain(
  plain: string,
  maxLength = EDIT_ASSET_PROMPT_MAX_CHARS
): { ok: true } | { ok: false; message: string } {
  return validateRequiredVideoPrompt(plain, maxLength)
}

/** 图生方向提示词：格式规则由服务端统一校验。 */
export function validateImageToVideoPromptPlain(
  plain: string,
  maxLength = EDIT_ASSET_PROMPT_MAX_CHARS
): { ok: true } | { ok: false; message: string } {
  return validateRequiredVideoPrompt(plain, maxLength)
}

/** 宫格方向提示词：格式规则由服务端统一校验。 */
export function validateGridVideoPromptPlain(
  plain: string,
  maxLength = EDIT_ASSET_PROMPT_MAX_CHARS
): { ok: true } | { ok: false; message: string } {
  return validateRequiredVideoPrompt(plain, maxLength)
}

/** 首尾帧方向：提示词可空，非空时仅校验统一长度边界。 */
export function validateEdgeVideoPromptPlain(
  plain: string,
  maxLength = EDIT_ASSET_PROMPT_MAX_CHARS
): { ok: true } | { ok: false; message: string } {
  const text = String(plain ?? '')
  if (!text.trim()) return { ok: true }
  if (text.length > maxLength) {
    return { ok: false, message: '提示词过长' }
  }
  return { ok: true }
}

/** @deprecated 请使用 validateMultiParamVideoPromptPlain 或 validateImageToVideoPromptPlain */
export function validateStoryboardVideoPromptPlain(
  plain: string,
  maxLength = EDIT_ASSET_PROMPT_MAX_CHARS
): { ok: true } | { ok: false; message: string } {
  return validateImageToVideoPromptPlain(plain, maxLength)
}
