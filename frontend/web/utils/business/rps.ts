/** 角色/道具/场景（RPS）域：个人资产主表与形态、形态图、使用状态、删除、音色绑定。 */
import type {
ApiEnvelope,
RoleVoiceBindingVO,
UserAssetRow,
UserAssetRpsAiExtractRequest,
UserAssetRpsCreateOtherRequest,
UserAssetRpsCreateRequest,
UserAssetRpsDeleteBatchData,
UserAssetRpsDeleteRequest,
UserAssetRpsFormCreateRequest,
UserAssetRpsFormImageCreateRequest,
UserAssetRpsFormImageDeleteRequest,
UserAssetRpsFormImageListRequest,
UserAssetRpsFormImageRow,
UserAssetRpsFormImageUpdateRequest,
UserAssetRpsFormImageUpscaleRequest,
UserAssetRpsFormImageUpscaleSubmitData,
UserAssetRpsFormListRequest,
UserAssetRpsFormRow,
UserAssetRpsFormUnuseRequest,
UserAssetRpsFormUseBatchData,
UserAssetRpsFormUseRequest,
UserAssetRpsListRequest,
UserAssetRpsRow,
UserAssetRpsSceneImageSplitData,
UserAssetRpsSceneImageSplitRequest,
UserAssetRpsUpdateFormRequest,
UserAssetRpsUpdateMainRequest
} from '~/types/business-api'
import { request } from '~/utils/api'
import {
extractDataArray,
runListDedupe,
stableRequestKey,
unwrap,
type ListBurstSlot
} from '~/utils/business/shared'
import { chunkRpsDeleteIds,mergeRpsDeleteBatchResults } from '~/utils/rpsDeleteBatch'
import { normalizeUpdateMainRequest } from '~/utils/rpsUpdateMainPayload'

const rpsListInflight = new Map<string, Promise<{ rows: UserAssetRpsRow[]; total: number }>>()
const rpsListBurst: ListBurstSlot<{ rows: UserAssetRpsRow[]; total: number }> = { current: null }

const rpsFormListInflight = new Map<string, Promise<UserAssetRpsFormRow[]>>()
const rpsFormListBurst: ListBurstSlot<UserAssetRpsFormRow[]> = { current: null }

const rpsFormImageListInflight = new Map<string, Promise<UserAssetRpsFormImageRow[]>>()
const rpsFormImageListBurst: ListBurstSlot<UserAssetRpsFormImageRow[]> = { current: null }

/** 个人 RPS 主表行 → 旧版列表行（主图+其余形态进 extraImages） */
export function rpsRowToUserAssetRow(r: UserAssetRpsRow): UserAssetRow {
  const forms = r.forms ?? []
  const first = forms[0]
  const rest = forms.slice(1)
  const extraImages =
    rest
      .map((f) => (f.imageUrl || '').trim())
      .filter(Boolean)
      .join(';') || null
  const intro =
    (typeof r.introduction === 'string' && r.introduction.trim() ? r.introduction.trim() : '') ||
    (typeof first?.introduction === 'string' && first.introduction.trim() ? first.introduction.trim() : '') ||
    (typeof first?.promptText === 'string' && first.promptText.trim() ? first.promptText.trim() : '') ||
    undefined
  return {
    id: r.id,
    assetType: r.assetType,
    assetName: r.assetName,
    personalityDesc: intro,
    refImageUrl: first?.imageUrl ?? null,
    extraImages,
    sourceType: r.sourceType ?? undefined,
    createTime: r.createTime ?? undefined,
    updateTime: r.updateTime ?? undefined
  }
}

export function sortUserAssetRpsRows(rows: UserAssetRpsRow[]): UserAssetRpsRow[] {
  return [...rows].sort((a, b) => {
    const ta = a.createTime || ''
    const tb = b.createTime || ''
    if (ta && tb) return ta.localeCompare(tb)
    return (a.id ?? 0) - (b.id ?? 0)
  })
}

/** 个人资产（角色/场景/道具等）：POST /api/user/asset/rps/list */
export async function userAssetRpsList(
  body?: UserAssetRpsListRequest
): Promise<{ rows: UserAssetRpsRow[]; total: number }> {
  const reqBody = body ?? {}
  const key = stableRequestKey(reqBody)
  return runListDedupe(key, rpsListInflight, rpsListBurst, async () => {
    const res = await request.post('/api/user/asset/rps/list', reqBody)
    const rows = extractDataArray<UserAssetRpsRow>(res)
    return { rows, total: rows.length }
  })
}

/** 个人资产形态列表：POST /api/user/asset/rps/form/list */
export async function userAssetRpsFormList(
  body: UserAssetRpsFormListRequest = {}
): Promise<UserAssetRpsFormRow[]> {
  const key = stableRequestKey(body)
  return runListDedupe(key, rpsFormListInflight, rpsFormListBurst, async () => {
    const res = await request.post<ApiEnvelope<UserAssetRpsFormRow[]>>('/api/user/asset/rps/form/list', body)
    const data = unwrap(res)
    return Array.isArray(data) ? data : []
  })
}

/** 形态增删改后调用，避免 450ms 列表缓存导致「已生成形态」仍被当作待生成而隐藏主列表 */
export function invalidateUserAssetRpsFormListCache() {
  rpsFormListBurst.current = null
  rpsFormListInflight.clear()
}

/** 个人资产主表创建：POST /api/user/asset/rps/create */
export async function userAssetRpsCreate(body: UserAssetRpsCreateRequest): Promise<UserAssetRpsRow> {
  const res = await request.post<ApiEnvelope<UserAssetRpsRow>>('/api/user/asset/rps/create', body)
  return unwrap(res)
}

/** 个人资产形态创建：POST /api/user/asset/rps/form/create */
export async function userAssetRpsFormCreate(body: UserAssetRpsFormCreateRequest): Promise<UserAssetRpsRow> {
  const res = await request.post<ApiEnvelope<UserAssetRpsRow>>('/api/user/asset/rps/form/create', body)
  return unwrap(res)
}

/** 个人资产形态图创建：POST /api/user/asset/rps/form-image/create */
export async function userAssetRpsFormImageCreate(
  body: UserAssetRpsFormImageCreateRequest
): Promise<UserAssetRpsFormImageRow> {
  const res = await request.post<ApiEnvelope<UserAssetRpsFormImageRow>>('/api/user/asset/rps/form-image/create', body)
  return unwrap(res)
}

/** 个人资产形态图更新：POST /api/user/asset/rps/form-image/update */
export async function userAssetRpsFormImageUpdate(
  body: UserAssetRpsFormImageUpdateRequest
): Promise<UserAssetRpsFormImageRow> {
  const res = await request.post<ApiEnvelope<UserAssetRpsFormImageRow>>('/api/user/asset/rps/form-image/update', body)
  return unwrap(res)
}

/** 个人资产形态图列表：POST /api/user/asset/rps/form-image/list */
export async function userAssetRpsFormImageList(
  body: UserAssetRpsFormImageListRequest
): Promise<UserAssetRpsFormImageRow[]> {
  const key = stableRequestKey(body)
  return runListDedupe(key, rpsFormImageListInflight, rpsFormImageListBurst, async () => {
    const res = await request.post<ApiEnvelope<UserAssetRpsFormImageRow[]>>('/api/user/asset/rps/form-image/list', body)
    const data = unwrap(res)
    return Array.isArray(data) ? data : []
  })
}

/** 个人资产形态图删除：POST /api/user/asset/rps/form-image/delete */
export async function userAssetRpsFormImageDelete(body: UserAssetRpsFormImageDeleteRequest): Promise<void> {
  await request.post<ApiEnvelope<unknown>>('/api/user/asset/rps/form-image/delete', body)
}

/** 场景形态图拆分四宫格：POST /api/user/asset/rps/form-image/scene/split */
export async function userAssetRpsFormImageSceneSplit(
  body: UserAssetRpsSceneImageSplitRequest
): Promise<UserAssetRpsSceneImageSplitData> {
  const res = await request.post<ApiEnvelope<UserAssetRpsSceneImageSplitData>>(
    '/api/user/asset/rps/form-image/scene/split',
    body
  )
  const data = unwrap(res)
  if (data.summary?.successCount > 0) {
    // A successful split changes the same form-image list that the editor reads next.
    // Detach earlier in-flight reads as well as the short burst cache.
    rpsFormImageListInflight.clear()
    rpsFormImageListBurst.current = null
  }
  return data
}

/** 形态图高清（异步）：POST /api/user/asset/rps/form-image/upscale（v2.24+） */
export async function userAssetRpsFormImageUpscale(
  body: UserAssetRpsFormImageUpscaleRequest
): Promise<UserAssetRpsFormImageUpscaleSubmitData> {
  const res = await request.post<ApiEnvelope<UserAssetRpsFormImageUpscaleSubmitData>>(
    '/api/user/asset/rps/form-image/upscale',
    body
  )
  return unwrap(res)
}

/** 从表形态设为使用中：POST /api/user/asset/rps/form/use（主图单选，每次一个 imageId） */
export async function userAssetRpsFormUse(
  body: UserAssetRpsFormUseRequest
): Promise<UserAssetRpsFormUseBatchData | null> {
  const res = await request.post<ApiEnvelope<UserAssetRpsFormUseBatchData>>(
    '/api/user/asset/rps/form/use',
    body
  )
  return unwrap(res) ?? null
}

/** 取消从表形态使用：POST /api/user/asset/rps/form/unuse（支持单个 / 批量，出参同 use） */
export async function userAssetRpsFormUnuse(
  body: UserAssetRpsFormUnuseRequest
): Promise<UserAssetRpsFormUseBatchData | null> {
  const res = await request.post<ApiEnvelope<UserAssetRpsFormUseBatchData>>(
    '/api/user/asset/rps/form/unuse',
    body
  )
  return unwrap(res) ?? null
}

/** 个人资产编辑：更新主表 POST /api/user/asset/rps/update-main（文档 2.5） */
export async function userAssetRpsUpdateMain(
  body: UserAssetRpsUpdateMainRequest,
  options?: { createSource?: string | null; isManual?: boolean }
): Promise<UserAssetRpsRow> {
  const payload = normalizeUpdateMainRequest(body, options)
  const res = await request.post<ApiEnvelope<UserAssetRpsRow>>('/api/user/asset/rps/update-main', payload)
  return unwrap(res)
}

/** 个人资产编辑：仅更新从表形态 POST /api/user/asset/rps/update-form（文档 2.6，出参为单条 RpsFormVO） */
export async function userAssetRpsUpdateForm(body: UserAssetRpsUpdateFormRequest): Promise<UserAssetRpsFormRow> {
  const res = await request.post<ApiEnvelope<UserAssetRpsFormRow>>('/api/user/asset/rps/update-form', body)
  return unwrap(res)
}

/**
 * 个人资产删除（单个 / 批量同接口）：POST /api/user/asset/rps/delete
 * - 单删传 `id`（可带 `formId`）：出参无 data，返回 null
 * - 批量传 `ids`：返回统一批量结果（成功 / 失败明细）
 */
export async function userAssetRpsDelete(
  body: UserAssetRpsDeleteRequest
): Promise<UserAssetRpsDeleteBatchData | null> {
  const res = await request.post<ApiEnvelope<UserAssetRpsDeleteBatchData>>(
    '/api/user/asset/rps/delete',
    body
  )
  return unwrap(res) ?? null
}

/**
 * 批量删除主资产（传 ids；超出 50 自动分片；单条失败不阻断其它分片）。
 */
export async function userAssetRpsDeleteBatchByIds(
  assetIds: number[]
): Promise<{
  successIds: number[]
  failCount: number
  failures: Array<{ id?: number | null; reason?: string }>
}> {
  const chunks = chunkRpsDeleteIds(assetIds)
  if (!chunks.length) {
    return { successIds: [], failCount: 0, failures: [] }
  }

  const parts: Array<UserAssetRpsDeleteBatchData | null> = []
  for (const chunk of chunks) {
    try {
      parts.push(await userAssetRpsDelete({ ids: chunk }))
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      const reason = String(err?.msg || err?.message || '删除失败').trim() || '删除失败'
      parts.push({
        total: chunk.length,
        successCount: 0,
        failCount: chunk.length,
        successIds: [],
        failures: chunk.map((id) => ({ id, reason }))
      })
    }
  }

  return mergeRpsDeleteBatchResults(parts)
}

/** 其他类型个人资产：POST /api/user/asset/rps/create-other */
export async function userAssetRpsCreateOther(body: UserAssetRpsCreateOtherRequest): Promise<UserAssetRpsRow> {
  const res = await request.post<ApiEnvelope<UserAssetRpsRow>>('/api/user/asset/rps/create-other', body)
  return unwrap(res)
}

/** AI 提取个人资产（开发中）：POST /api/user/asset/rps/ai/extract */
export async function userAssetRpsAiExtract(body: UserAssetRpsAiExtractRequest): Promise<unknown> {
  const res = await request.post<ApiEnvelope<unknown>>('/api/user/asset/rps/ai/extract', body)
  return unwrap(res)
}

/** 角色音色绑定：POST /api/user/asset/rps/voice/bind */
export async function userRoleVoiceBind(body: {
  assetId: number
  voiceLibraryId: number
  overrideSpeed?: number
  overridePitch?: number
  overrideEmotion?: string
}): Promise<RoleVoiceBindingVO> {
  const res = await request.post<ApiEnvelope<RoleVoiceBindingVO>>('/api/user/asset/rps/voice/bind', body)
  return unwrap(res)
}

/** 查询角色音色绑定：POST /api/user/asset/rps/voice/query */
export async function userRoleVoiceQuery(body: { assetId: number }): Promise<RoleVoiceBindingVO | null> {
  const res = await request.post<ApiEnvelope<RoleVoiceBindingVO | null>>(
    '/api/user/asset/rps/voice/query',
    body
  )
  return unwrap(res)
}

/** 解除角色音色绑定：POST /api/user/asset/rps/voice/unbind */
export async function userRoleVoiceUnbind(body: { assetId: number }): Promise<void> {
  await request.post<ApiEnvelope<unknown>>('/api/user/asset/rps/voice/unbind', body)
}
