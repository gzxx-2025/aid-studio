/** 项目与剧集域：用户项目/剧集增删改查、项目级生成配置、创作步骤。 */
import type {
ApiEnvelope,
ApiListEnvelope,
ApiListEnvelopeData,
CreationStepAdvanceRequest,
CreationStepRequest,
CreationStepState,
ProjectGenConfigQueryRequest,
ProjectGenConfigSaveRequest,
ProjectGenConfigSavedItem,
ProjectGenConfigVO,
ProjectOrEpisodeIdRequest,
PublicProjectDetailRow,
PublicProjectVideoListRequest,
PublicProjectVideoRow,
UserEpisodeCreateRequest,
UserEpisodeDeleteRequest,
UserEpisodeDetailRequest,
UserEpisodeRow,
UserEpisodeUpdateRequest,
UserProjectCreateRequest,
UserProjectListRequest,
UserProjectPublishRequest,
UserProjectRow,
UserProjectUpdateRequest
} from '~/types/business-api';
import { request } from '~/utils/api';
import {
API_DEFAULT_PAGE_SIZE,
extractPaginatedResponse,
runListDedupe,
stableRequestKey,
unwrap,
type ListBurstSlot
} from '~/utils/business/shared';

const userProjectListInflight = new Map<string, Promise<{ total: number; rows: UserProjectRow[] }>>()
const userProjectListBurst: ListBurstSlot<{ total: number; rows: UserProjectRow[] }> = { current: null }
const userProjectUpdateInflight = new Map<string, Promise<UserProjectRow>>()

const publicProjectVideoListInflight = new Map<
  string,
  Promise<{ total: number; rows: PublicProjectVideoRow[] }>
>()
const publicProjectVideoListBurst: ListBurstSlot<{ total: number; rows: PublicProjectVideoRow[] }> = {
  current: null
}
const publicProjectDetailInflight = new Map<string, Promise<PublicProjectDetailRow>>()
const publicProjectDetailBurst: {
  current: { key: string; data: PublicProjectDetailRow; at: number } | null
} = { current: null }


/** 用户项目：列表查询（/api/user/project/list） */
export async function userProjectList(body?: UserProjectListRequest): Promise<{ total: number; rows: UserProjectRow[] }> {
  const reqBody = body ?? {}
  const key = stableRequestKey(reqBody)
  return runListDedupe(key, userProjectListInflight, userProjectListBurst, async () => {
    const res = (await request.post('/api/user/project/list', reqBody)) as ApiListEnvelope<UserProjectRow> &
      ApiListEnvelopeData<UserProjectRow> & {
        rows?: UserProjectRow[]
        data?: UserProjectRow[]
        total?: number
      }
    // 后端多为 { total, data: [...] }；旧版可能为根级 rows
    const rows = Array.isArray(res.rows) ? res.rows : Array.isArray(res.data) ? res.data : []
    const total = typeof res.total === 'number' ? res.total : rows.length
    return { total, rows }
  })
}

/** 用户项目：获取详情（/api/user/project/detail） */
export async function userProjectDetail(id: number): Promise<UserProjectRow> {
  const res = await request.post<ApiEnvelope<UserProjectRow>>('/api/user/project/detail', { id })
  return unwrap(res)
}

/** 提交项目审核。 */
export async function userProjectSubmitAudit(body: ProjectOrEpisodeIdRequest): Promise<void> {
  await request.post<ApiEnvelope>('/api/user/project/submit-audit', body)
}

/** 提交剧集审核。 */
export async function userEpisodeSubmitAudit(body: ProjectOrEpisodeIdRequest): Promise<void> {
  await request.post<ApiEnvelope>('/api/user/episode/submit-audit', body)
}

/** 发布项目至案例广场。 */
export async function userProjectPublish(body: UserProjectPublishRequest): Promise<UserProjectRow> {
  const res = await request.post<ApiEnvelope<UserProjectRow>>('/api/user/project/publish', body)
  userProjectListBurst.current = null
  return unwrap(res)
}

/** 将项目从案例广场下架。 */
export async function userProjectUnpublish(body: ProjectOrEpisodeIdRequest): Promise<UserProjectRow> {
  const res = await request.post<ApiEnvelope<UserProjectRow>>('/api/user/project/unpublish', body)
  userProjectListBurst.current = null
  return unwrap(res)
}

/** 用户项目：删除（/api/user/project/delete） */
export async function userProjectDelete(id: number): Promise<void> {
  await request.post<ApiEnvelope>('/api/user/project/delete', { id })
}

/** 用户项目：创建（/api/user/project/create） */
export async function userProjectCreate(body: UserProjectCreateRequest): Promise<{ data: UserProjectRow; msg: string }> {
  const res = await request.post<ApiEnvelope<UserProjectRow>>('/api/user/project/create', body)
  return {
    data: unwrap(res),
    msg: res.msg || '操作成功'
  }
}

/** 用户项目：修改（/api/user/project/update） */
export async function userProjectUpdate(body: UserProjectUpdateRequest): Promise<UserProjectRow> {
  const key = stableRequestKey(body)
  const current = userProjectUpdateInflight.get(key)
  if (current) return current
  const task = (async () => {
    const res = await request.post<ApiEnvelope<UserProjectRow>>('/api/user/project/update', body)
    const data = unwrap(res)
    const staleListRequests = Array.from(userProjectListInflight.values())
    if (staleListRequests.length > 0) await Promise.allSettled(staleListRequests)
    userProjectListBurst.current = null
    return data
  })().finally(() => userProjectUpdateInflight.delete(key))
  userProjectUpdateInflight.set(key, task)
  return task
}

/** 公开案例广场：分页列表（POST /api/public/project/video，无需登录） */
export async function publicProjectVideoList(
  body?: PublicProjectVideoListRequest
): Promise<{ total: number; rows: PublicProjectVideoRow[] }> {
  const reqBody = body ?? {}
  const key = stableRequestKey(reqBody)
  return runListDedupe(key, publicProjectVideoListInflight, publicProjectVideoListBurst, async () => {
    const res = (await request.post('/api/public/project/video', reqBody)) as
      ApiListEnvelope<PublicProjectVideoRow> &
      ApiListEnvelopeData<PublicProjectVideoRow> & {
        rows?: PublicProjectVideoRow[]
        data?: PublicProjectVideoRow[]
        total?: number
      }
    const rows = Array.isArray(res.rows) ? res.rows : Array.isArray(res.data) ? res.data : []
    const total = typeof res.total === 'number' ? res.total : rows.length
    return { total, rows }
  })
}

/** 公开案例广场：项目详情（POST /api/public/project/detail，无需登录） */
export async function publicProjectDetail(id: number): Promise<PublicProjectDetailRow> {
  const key = String(id)
  const burst = publicProjectDetailBurst.current
  if (burst && burst.key === key && Date.now() - burst.at < 450) return burst.data
  const current = publicProjectDetailInflight.get(key)
  if (current) return current

  const task = (async () => {
    const res = await request.post<ApiEnvelope<PublicProjectDetailRow>>(
      '/api/public/project/detail',
      { id }
    )
    const data = unwrap(res)
    publicProjectDetailBurst.current = { key, data, at: Date.now() }
    return data
  })().finally(() => publicProjectDetailInflight.delete(key))
  publicProjectDetailInflight.set(key, task)
  return task
}

/** 用户剧集：分页列表；服务端从查询参数读取 pageNum/pageSize。 */
export async function userEpisodePage(body: {
  projectId: number
  pageNum: number
  pageSize: number
}): Promise<{ total: number; rows: UserEpisodeRow[]; hasMore: boolean }> {
  const { projectId, pageNum, pageSize } = body
  const res = await request.post('/api/user/episode/list', { projectId }, {
    params: { pageNum, pageSize }
  })
  const { total, rows, hasMore } = extractPaginatedResponse<UserEpisodeRow>(res, pageNum, pageSize)
  return { total, rows, hasMore }
}

/** 旧调用需要完整剧集集合（选择剧集、项目配置校验等），逐页读取直到结束。 */
export async function userEpisodeList(body: { projectId: number }): Promise<UserEpisodeRow[]> {
  const rows: UserEpisodeRow[] = []
  for (let pageNum = 1; ; pageNum += 1) {
    const page = await userEpisodePage({ ...body, pageNum, pageSize: API_DEFAULT_PAGE_SIZE })
    rows.push(...page.rows)
    if (!page.hasMore || page.rows.length === 0) return rows
  }
}

/** 用户剧集：创建（/api/user/episode/create） */
export async function userEpisodeCreate(body: UserEpisodeCreateRequest): Promise<UserEpisodeRow> {
  const res = await request.post<ApiEnvelope<UserEpisodeRow>>('/api/user/episode/create', body)
  return unwrap(res)
}

/** 用户剧集：详情（/api/user/episode/detail） */
export async function userEpisodeDetail(body: UserEpisodeDetailRequest): Promise<UserEpisodeRow> {
  const res = await request.post<ApiEnvelope<UserEpisodeRow>>('/api/user/episode/detail', body)
  return unwrap(res)
}

/** 用户剧集：修改（/api/user/episode/update） */
export async function userEpisodeUpdate(body: UserEpisodeUpdateRequest): Promise<UserEpisodeRow> {
  const res = await request.post<ApiEnvelope<UserEpisodeRow>>('/api/user/episode/update', body)
  return unwrap(res)
}

/** 用户剧集：删除（/api/user/episode/delete） */
export async function userEpisodeDelete(body: UserEpisodeDeleteRequest): Promise<void> {
  await request.post<ApiEnvelope>('/api/user/episode/delete', body)
}

/** 创作步骤：查询状态（/api/user/step/status） */
export async function creationStepStatus(body: CreationStepRequest): Promise<CreationStepState> {
  const res = await request.post<ApiEnvelope<CreationStepState>>('/api/user/step/status', body)
  return unwrap(res)
}

/** 创作步骤：手动推进（/api/user/step/advance） */
export async function creationStepAdvance(body: CreationStepAdvanceRequest): Promise<CreationStepState> {
  const res = await request.post<ApiEnvelope<CreationStepState>>('/api/user/step/advance', body)
  return unwrap(res)
}

/** 查询项目级生成配置（懒加载 + aid_config 兜底）：POST /api/user/project/gen-config/get */
export async function userProjectGenConfigGet(
  body: ProjectGenConfigQueryRequest
): Promise<ProjectGenConfigVO[]> {
  const res = await request.post<ApiEnvelope<ProjectGenConfigVO[]>>(
    '/api/user/project/gen-config/get',
    body
  )
  const data = unwrap(res)
  return Array.isArray(data) ? data : []
}

/** 保存项目级生成配置（部分更新）：POST /api/user/project/gen-config/save */
export async function userProjectGenConfigSave(
  body: ProjectGenConfigSaveRequest
): Promise<ProjectGenConfigSavedItem[]> {
  const res = await request.post<ApiEnvelope<ProjectGenConfigSavedItem[]>>(
    '/api/user/project/gen-config/save',
    body
  )
  const data = unwrap(res)
  return Array.isArray(data) ? data : []
}
