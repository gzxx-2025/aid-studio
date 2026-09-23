import type { RouteLikeLocation, RouteLikeNavigator } from '~/types/routeLike'
import type { AidAgentListRequest, UserProjectType } from '~/types/business-api'
import type { useCreationStore } from '~/stores/creation'
import { fetchCreationStepStatusOnce } from '~/utils/creationStepStatusOnce'
import {
  CREATE_FLOW_STEP_ORDER,
  CREATE_SERIES_EPISODE_LIST_PATH,
  creationStepToRoutePath,
  isCreateFlowEmbeddedLibraryPanel,
  withCreateFlowFromQuery
} from '~/utils/createFlowRoutes'

type CreationStore = ReturnType<typeof useCreationStore>

/** 当前流程上下文中的作品 ID（store 优先，与 stepRequestParams 一致） */
export function resolveActiveFlowProjectId(
  store: Pick<CreationStore, 'currentProjectId'>,
  route: RouteLikeLocation
): number | null {
  const storePid = Number(store.currentProjectId)
  if (Number.isFinite(storePid) && storePid > 0) return storePid
  const routePid = Number(route.query.projectId ?? route.query.id ?? route.query.workId)
  if (Number.isFinite(routePid) && routePid > 0) return routePid
  return null
}

/** 后端返回「项目不存在 / 无权限」类错误 */
export function isProjectMissingApiError(err: unknown): boolean {
  const msg = String(
    (err as { msg?: string; message?: string })?.msg ??
      (err as { message?: string })?.message ??
      ''
  ).trim()
  if (!msg) return false
  return (
    msg.includes('项目不存在') ||
    msg.includes('无权限访问') ||
    msg.includes('不存在或无权限')
  )
}

/** 内嵌「我的作品 / 资产库」面板：不应再拉当前作品的 detail / step/status */
export function shouldSkipFlowProjectScopedApis(route: RouteLikeLocation): boolean {
  return isCreateFlowEmbeddedLibraryPanel(route.query)
}

/** 从路由 query 解析 episodeId；电影作品固定为 0，忽略 URL 中残留的剧集 episodeId */
export function resolveFlowEpisodeIdFromRoute(
  route: RouteLikeLocation,
  projectType: UserProjectType | null | undefined
): number | null {
  if (projectType === 'movie') return 0
  const raw = route.query.episodeId
  if (raw === undefined || raw === '') return null
  const n = Number(Array.isArray(raw) ? raw[0] : raw)
  return Number.isFinite(n) && n >= 0 ? n : null
}

/**
 * 打开作品时构造干净的流程 query，避免上一作品的 episodeId / panel 等污染新作品。
 * 电影不带 episodeId；剧集仅在明确指定单集时带上 episodeId。
 */
export function buildOpenProjectFlowQuery(
  projectId: number,
  options: {
    embedded: boolean
    projectType: UserProjectType | null | undefined
    episodeId?: number | null
  }
): Record<string, string> {
  const base: Record<string, string> = {
    projectId: String(projectId),
    id: String(projectId)
  }
  if (
    options.projectType === 'series' &&
    options.episodeId != null &&
    Number(options.episodeId) > 0
  ) {
    base.episodeId = String(options.episodeId)
  }
  return withCreateFlowFromQuery(base, options.embedded)
}

/** 内嵌/独立打开作品时：按类型解析应进入的流程路由（避免走 /create 卸载壳层） */
export async function resolveCreateFlowEntryPath(
  projectId: number,
  projectType: UserProjectType | null | undefined
): Promise<string> {
  if (projectType === 'series') {
    return CREATE_SERIES_EPISODE_LIST_PATH
  }
  try {
    const status = await fetchCreationStepStatusOnce({ projectId, episodeId: 0 })
    const stepNumber = Number(status?.currentStep)
    const stepIndex = Number.isFinite(stepNumber)
      ? Math.min(Math.max(Math.floor(stepNumber) - 1, 0), CREATE_FLOW_STEP_ORDER.length - 1)
      : 1
    const stepKey = CREATE_FLOW_STEP_ORDER[stepIndex] ?? 'story-script'
    return creationStepToRoutePath(stepKey)
  } catch {
    return creationStepToRoutePath('story-script')
  }
}

/** POST /aid/agent/list：传 projectId（+ episodeId）以按创作模式裁剪智能体池 */
export function buildAidAgentListScopeParams(
  store: Pick<CreationStore, 'currentProjectId' | 'currentEpisodeId' | 'currentProjectType'>
): Pick<AidAgentListRequest, 'projectId' | 'episodeId'> {
  const projectId = Number(store.currentProjectId)
  if (!Number.isFinite(projectId) || projectId <= 0) return {}

  const out: Pick<AidAgentListRequest, 'projectId' | 'episodeId'> = { projectId }

  if (store.currentProjectType === 'movie') {
    out.episodeId = 0
    return out
  }

  const episodeId = Number(store.currentEpisodeId)
  if (Number.isFinite(episodeId) && episodeId >= 0) {
    out.episodeId = episodeId
  }

  return out
}

/** step/status 等接口入参：剧集必须有有效 episodeId，电影固定 0 */
export function buildFlowStepRequestParams(options: {
  projectId: number
  projectType: UserProjectType | null | undefined
  storeEpisodeId: number | null | undefined
  routeEpisodeId: number | null
}): { projectId: number; episodeId?: number } | null {
  const { projectId, projectType, storeEpisodeId, routeEpisodeId } = options
  if (projectType === 'movie') {
    return { projectId, episodeId: 0 }
  }
  if (projectType === 'series') {
    const e =
      storeEpisodeId != null && storeEpisodeId > 0
        ? storeEpisodeId
        : routeEpisodeId != null && routeEpisodeId > 0
          ? routeEpisodeId
          : null
    if (e == null) return null
    return { projectId, episodeId: e }
  }
  const e = storeEpisodeId ?? routeEpisodeId
  if (e != null && e >= 0) return { projectId, episodeId: e }
  return { projectId }
}

const FLOW_PROJECT_QUERY_KEYS = new Set([
  'projectId',
  'id',
  'workId',
  'episodeId',
  'stepInitAdvance'
])

/**
 * 作品已删除或 detail/status 报不存在时：清 Pinia 上下文与 URL 上的 project 参数。
 * 默认保留 panel=works|assets，避免打断内嵌库视图。
 */
export async function clearStaleCreateFlowProjectContext(options: {
  router: RouteLikeNavigator
  route: RouteLikeLocation
  store: CreationStore
  keepEmbeddedPanel?: boolean
}): Promise<void> {
  const { router, route, store, keepEmbeddedPanel = true } = options
  // 保留短时失败缓存：页面与壳层可能先后收到同一个不存在项目的错误，
  // 此处清空缓存会让稍后的回显再次请求相同 projectId。
  store.setCurrentProjectContext({ projectId: null, episodeId: null })

  const nextQuery: Record<string, string> = {}
  for (const [key, value] of Object.entries(route.query)) {
    if (value == null) continue
    if (FLOW_PROJECT_QUERY_KEYS.has(key)) continue
    if (!keepEmbeddedPanel && key === 'panel') continue
    nextQuery[key] = Array.isArray(value) ? String(value[0] ?? '') : String(value)
  }

  const currentQuery: Record<string, string> = {}
  for (const [key, value] of Object.entries(route.query)) {
    if (value == null) continue
    currentQuery[key] = Array.isArray(value) ? String(value[0] ?? '') : String(value)
  }

  const unchanged =
    Object.keys(nextQuery).length === Object.keys(currentQuery).length &&
    Object.keys(nextQuery).every((k) => nextQuery[k] === currentQuery[k])
  if (unchanged) return

  await router.replace({ path: route.path, query: nextQuery })
}

export function isDeletedFlowProject(
  deletedProjectId: number,
  store: Pick<CreationStore, 'currentProjectId'>,
  route: RouteLikeLocation
): boolean {
  const active = resolveActiveFlowProjectId(store, route)
  return active != null && active === deletedProjectId
}
