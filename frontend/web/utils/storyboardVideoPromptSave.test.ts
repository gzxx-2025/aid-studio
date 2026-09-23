import { describe, expect, it } from 'vitest'
import { EDIT_ASSET_PROMPT_MAX_CHARS } from './htmlPlain'
import {
  validateEdgeVideoPromptPlain,
  validateGridVideoPromptPlain,
  validateImageToVideoPromptPlain,
  validateMultiParamVideoPromptPlain
} from './storyboardVideoPromptSave'

describe('storyboard video prompt validation', () => {
  const requiredValidators = [
    validateImageToVideoPromptPlain,
    validateGridVideoPromptPlain,
    validateMultiParamVideoPromptPlain
  ]

  it('leaves prompt format validation to the server', () => {
    const freeform = '  自由格式提示词\n保留换行和空格  '

    for (const validate of requiredValidators) {
      expect(validate(freeform)).toEqual({ ok: true })
    }
  })

  it('uses the shared 100,000-character boundary for every video mode', () => {
    const atLimit = '字'.repeat(EDIT_ASSET_PROMPT_MAX_CHARS)
    const overLimit = `${atLimit}字`

    for (const validate of [...requiredValidators, validateEdgeVideoPromptPlain]) {
      expect(validate(atLimit)).toEqual({ ok: true })
      expect(validate(overLimit)).toEqual({ ok: false, message: '提示词过长' })
    }
  })

  it('uses the selected model character limit when it is supplied', () => {
    expect(validateImageToVideoPromptPlain('abc', 3)).toEqual({ ok: true })
    expect(validateImageToVideoPromptPlain('abcd', 3)).toEqual({
      ok: false,
      message: '提示词过长'
    })
  })

  it('keeps required prompts non-empty while allowing an empty edge prompt', () => {
    for (const validate of requiredValidators) {
      expect(validate(' \n ')).toEqual({
        ok: false,
        message: '提示词不能为空'
      })
    }
    expect(validateEdgeVideoPromptPlain(' \n ')).toEqual({ ok: true })
  })
})
