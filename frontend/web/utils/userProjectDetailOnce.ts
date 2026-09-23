import type { UserProjectRow } from '~/types/business-api';
import { userProjectDetail } from '~/utils/businessApi';

const inflightByProjectId = new Map<number, Promise<UserProjectRow>>()
const settledByProjectId = new Map<number, { at: number; promise: Promise<UserProjectRow> }>()
const CACHE_MS = 5000

/** 同一 projectId 并发只发起一次 /api/user/project/detail；短缓存避免作品库进入后壳层重复拉取 */
export function fetchUserProjectDetailOnce(
  projectId: number,
  options?: { force?: boolean }
): Promise<UserProjectRow> {
  // force 只跳过已完成结果的短缓存；相同 projectId 的进行中请求始终复用。
  const inflight = inflightByProjectId.get(projectId)
  if (inflight) return inflight

  if (!options?.force) {
    const hit = settledByProjectId.get(projectId)
    if (hit && Date.now() - hit.at < CACHE_MS) {
      return hit.promise
    }
    if (hit) settledByProjectId.delete(projectId)
  }

  const promise = userProjectDetail(projectId)
    .finally(() => {
      if (inflightByProjectId.get(projectId) === promise) {
        inflightByProjectId.delete(projectId)
        // 成功和明确失败都短时复用，避免壳层、页面挂载和 React 重渲染连续提交同一请求。
        settledByProjectId.set(projectId, { at: Date.now(), promise })
      }
    })
  inflightByProjectId.set(projectId, promise)
  return promise
}

export function invalidateUserProjectDetailCache(projectId?: number): void {
  if (projectId == null) {
    inflightByProjectId.clear()
    settledByProjectId.clear()
    return
  }
  inflightByProjectId.delete(projectId)
  settledByProjectId.delete(projectId)
}
