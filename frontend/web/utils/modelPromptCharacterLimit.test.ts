import { describe, expect, it } from 'vitest'
import type { UserModelListItem } from '~/types/business-api'
import { resolveModelPromptCharacterLimit } from './modelCapability'

function model(capability: unknown): UserModelListItem {
  return {
    id: 1,
    modelCode: 'demo',
    modelName: 'Demo',
    modelType: 'image',
    capability: capability as UserModelListItem['capability']
  }
}

describe('resolveModelPromptCharacterLimit', () => {
  it('uses the generic model limit for prompts without CJK text', () => {
    expect(resolveModelPromptCharacterLimit(model({ maxPromptCharacters: 2500 }), 'camera pan', 100_000)).toBe(2500)
  })

  it('uses the CJK limit and falls back to the generic field when needed', () => {
    const configured = model({ maxPromptCharacters: 2500, maxPromptCharactersCjk: 500 })
    expect(resolveModelPromptCharacterLimit(configured, '镜头平移', 100_000)).toBe(500)
    expect(resolveModelPromptCharacterLimit(model({ maxPromptCharacters: 2500 }), '镜头平移', 100_000)).toBe(2500)
  })

  it('uses the caller default when the model does not return a usable limit', () => {
    expect(resolveModelPromptCharacterLimit(model({}), '提示词', 100_000)).toBe(100_000)
    expect(resolveModelPromptCharacterLimit(null, 'prompt', 10_000)).toBe(10_000)
  })
})
