import { diagnosticFetch } from '~/utils/errorDiagnostics'
/** 合成与导出域：一键配音合成、导出进度、剪辑时间轴、分段素材/成片下载与浏览器保存。 */
import type {
ApiEnvelope,
ComposeAcceptResult,
ComposeStatusRequest,
ComposeStatusResult,
EpisodeExportRequest,
EpisodeExportResult,
EpisodeExportStatusRequest,
EpisodeExportStatusResult,
EpisodeFinalVideoDownloadRequest,
EpisodeSegmentVideosRequest,
EpisodeSegmentVideosResult,
EpisodeSegmentZipDownloadRequest,
EpisodeTimelineGetRequest,
EpisodeTimelineResult,
EpisodeTimelineSaveRequest,
StoryboardComposeRequest
} from '~/types/business-api'
import {
buildUserApiAuthHeaders,
openRechargeModalFromInsufficientBalance,
request,
resolveClientApiUrl
} from '~/utils/api'
import { unwrap } from '~/utils/business/shared'
import { isInsufficientBalanceMessage } from '~/utils/insufficientBalanceRecharge'
import { createTrackedObjectUrl,revokeObjectUrl } from '~/utils/objectUrl'

/** 分镜一键配音 + 合成（异步受理）POST /api/user/compose/voiceover */
export async function userComposeVoiceover(
  body: StoryboardComposeRequest
): Promise<ComposeAcceptResult> {
  const res = await request.post<ApiEnvelope<ComposeAcceptResult>>(
    '/api/user/compose/voiceover',
    body
  )
  return unwrap(res)
}

/** 合成进度查询（纯轮询）POST /api/user/compose/status */
export async function userComposeStatus(
  body: ComposeStatusRequest
): Promise<ComposeStatusResult> {
  const res = await request.post<ApiEnvelope<ComposeStatusResult>>(
    '/api/user/compose/status',
    body
  )
  return unwrap(res)
}

/** 前端剪辑器拼接合成（异步受理）POST /api/user/episode/export */
export async function userEpisodeExport(body: EpisodeExportRequest): Promise<EpisodeExportResult> {
  const res = await request.post<ApiEnvelope<EpisodeExportResult>>('/api/user/episode/export', body)
  return unwrap(res)
}

/** 导出进度查询 POST /api/user/episode/export/status */
export async function userEpisodeExportStatus(
  body: EpisodeExportStatusRequest
): Promise<EpisodeExportStatusResult> {
  const res = await request.post<ApiEnvelope<EpisodeExportStatusResult>>(
    '/api/user/episode/export/status',
    body
  )
  return unwrap(res)
}

/** 剪辑时间轴读取（带自动初始化）POST /api/user/episode/timeline/get */
export async function userEpisodeTimelineGet(
  body: EpisodeTimelineGetRequest
): Promise<EpisodeTimelineResult> {
  const res = await request.post<ApiEnvelope<EpisodeTimelineResult>>(
    '/api/user/episode/timeline/get',
    body
  )
  return unwrap(res)
}

/** 剪辑时间轴保存（整份覆盖）POST /api/user/episode/timeline/save */
export async function userEpisodeTimelineSave(
  body: EpisodeTimelineSaveRequest
): Promise<EpisodeTimelineResult> {
  const res = await request.post<ApiEnvelope<EpisodeTimelineResult>>(
    '/api/user/episode/timeline/save',
    body
  )
  return unwrap(res)
}

/** 分段素材批量导出清单 POST /api/user/episode/export/segments */
export async function userEpisodeExportSegments(
  body: EpisodeSegmentVideosRequest
): Promise<EpisodeSegmentVideosResult> {
  const res = await request.post<ApiEnvelope<EpisodeSegmentVideosResult>>(
    '/api/user/episode/export/segments',
    body
  )
  return unwrap(res)
}

/**
 * 分段素材打包下载 POST /api/user/episode/export/segments/zip
 * 返回二进制 zip 流（非 JSON），不走信封加密；用 fetch + blob 接收。
 */
export async function userEpisodeExportSegmentsZip(
  body: EpisodeSegmentZipDownloadRequest
): Promise<{ blob: Blob; filename: string }> {
  const url = resolveClientApiUrl('/api/user/episode/export/segments/zip')
  const res = await diagnosticFetch(url, {
    method: 'POST',
    headers: {
      ...buildUserApiAuthHeaders(),
      'Content-Type': 'application/json',
      Accept: 'application/zip, application/json'
    },
    body: JSON.stringify({
      projectId: body.projectId,
      episodeId: body.episodeId,
      includeImages: body.includeImages ?? true,
      includeVideos: body.includeVideos ?? true,
      includeAudios: body.includeAudios ?? true,
      includeSubtitles: body.includeSubtitles ?? true
    })
  })

  const contentType = String(res.headers.get('content-type') || '').toLowerCase()
  if (!res.ok || contentType.includes('application/json')) {
    let msg = '分段素材打包失败'
    try {
      const data = (await res.json()) as { msg?: string; message?: string; code?: number }
      msg = String(data?.msg || data?.message || msg)
      if (isInsufficientBalanceMessage(msg)) {
        openRechargeModalFromInsufficientBalance(msg)
      }
    } catch {
      /* ignore parse */
    }
    throw new Error(msg)
  }

  const blob = await res.blob()
  if (!blob || blob.size <= 0) {
    throw new Error('暂无可导出素材')
  }

  const headerName =
    String(res.headers.get('download-filename') || '').trim() ||
    parseContentDispositionFilename(res.headers.get('content-disposition'))
  const filename =
    headerName ||
    `分镜素材_${body.projectId}_${body.episodeId}.zip`

  return { blob, filename }
}

/**
 * 成片 mp4 附件流下载 POST /api/user/episode/export/download
 * 响应为二进制视频流（非 JSON），不走信封加密；用 fetch + blob 接收。
 */
export async function userEpisodeExportDownload(
  body: EpisodeFinalVideoDownloadRequest
): Promise<{ blob: Blob; filename: string }> {
  const url = resolveClientApiUrl('/api/user/episode/export/download')
  const payload: Record<string, number> = {}
  const editorId = Number(body.episodeEditorId)
  if (Number.isFinite(editorId) && editorId > 0) {
    payload.episodeEditorId = editorId
  } else {
    const projectId = Number(body.projectId)
    const episodeId = Number(body.episodeId)
    if (!Number.isFinite(projectId) || projectId <= 0) {
      throw new Error('参数有误')
    }
    payload.projectId = projectId
    payload.episodeId = Number.isFinite(episodeId) && episodeId >= 0 ? episodeId : 0
  }

  const res = await diagnosticFetch(url, {
    method: 'POST',
    headers: {
      ...buildUserApiAuthHeaders(),
      'Content-Type': 'application/json',
      Accept: 'video/mp4, application/json'
    },
    body: JSON.stringify(payload)
  })

  const contentType = String(res.headers.get('content-type') || '').toLowerCase()
  if (!res.ok || contentType.includes('application/json')) {
    let msg = '成片下载失败'
    try {
      const data = (await res.json()) as { msg?: string; message?: string; code?: number }
      msg = String(data?.msg || data?.message || msg)
      if (isInsufficientBalanceMessage(msg)) {
        openRechargeModalFromInsufficientBalance(msg)
      }
    } catch {
      /* ignore parse */
    }
    throw new Error(msg)
  }

  const blob = await res.blob()
  if (!blob || blob.size <= 0) {
    throw new Error('暂无成片')
  }

  const headerName =
    String(res.headers.get('download-filename') || '').trim() ||
    parseContentDispositionFilename(res.headers.get('content-disposition'))
  const filename = headerName || `成片_${payload.projectId ?? payload.episodeEditorId}.mp4`

  return { blob, filename }
}

function parseContentDispositionFilename(raw: string | null): string {
  const header = String(raw || '')
  if (!header) return ''
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (utf8?.[1]) {
    try {
      return decodeURIComponent(utf8[1].trim().replace(/^"|"$/g, ''))
    } catch {
      return utf8[1].trim()
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header)
  return plain?.[1]?.trim() || ''
}

/** 触发浏览器保存 blob 文件 */
export function triggerBrowserBlobDownload(blob: Blob, filename: string) {
  if (typeof document === 'undefined') return
  const href = createTrackedObjectUrl(blob)
  const a = document.createElement('a')
  a.href = href
  a.download = filename || 'download.zip'
  a.rel = 'noopener'
  a.style.display = 'none'
  document.body.appendChild(a)
  a.click()
  window.setTimeout(() => {
    try {
      a.remove()
      revokeObjectUrl(href)
    } catch {
      /* ignore */
    }
  }, 2_000)
}
