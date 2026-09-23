import { diagnosticFetch } from '~/utils/errorDiagnostics'
import {
isRejectedMediaContentType,
isUsableMediaBlob
} from '~/utils/mediaBlobGuard'
import { resolveSameOriginApiUrl } from '~/utils/sameOriginApiUrl'

function isSameOriginUrl(url: string): boolean {
  if (typeof window === 'undefined') return true
  try {
    return new URL(url, window.location.href).origin === window.location.origin
  } catch {
    return false
  }
}

function buildMediaProxyUrl(remoteUrl: string): string {
  const params = new URLSearchParams({ url: remoteUrl })
  return `${resolveSameOriginApiUrl('/media/proxy')}?${params.toString()}`
}

/**
 * 将跨域媒体地址转换为同源代理地址，供 video/audio 直接播放。
 * 预览与 Canvas 截帧必须使用相同地址，避免媒体可播放却无法读取画面的情况。
 */
export function resolveMediaPlaybackUrl(url: string): string {
  const remote = String(url || '').trim()
  if (
    !remote ||
    remote.startsWith('blob:') ||
    remote.startsWith('data:') ||
    isSameOriginUrl(remote)
  ) {
    return remote
  }
  return buildMediaProxyUrl(remote)
}

async function tryFetchMediaBlob(href: string, init?: RequestInit): Promise<Blob | null> {
  try {
    const res = await diagnosticFetch(href, init)
    if (!res.ok) return null
    const contentType = res.headers.get('content-type')
    // 静态站 SPA 回退常把 /media/proxy 指到 index.html（仍是 200）
    if (isRejectedMediaContentType(contentType)) return null
    const blob = await res.blob()
    if (!(await isUsableMediaBlob(blob))) return null
    return blob
  } catch {
    return null
  }
}

/**
 * 拉取媒体 Blob。跨域 CDN 优先走同源 `/media/proxy`；
 * 代理不可用（如纯静态部署不运行 Next Proxy）时回退直连 CORS，避免把 HTML 当 MP4 喂给 WebAV。
 */
export async function fetchMediaBlob(url: string): Promise<Blob | null> {
  const remote = String(url || '').trim()
  if (!remote) return null

  if (remote.startsWith('blob:') || remote.startsWith('data:') || isSameOriginUrl(remote)) {
    return tryFetchMediaBlob(remote, { credentials: 'same-origin' })
  }

  const proxied = await tryFetchMediaBlob(buildMediaProxyUrl(remote), {
    credentials: 'same-origin'
  })
  if (proxied) return proxied

  // 回退：CDN CORS + 防盗链通常要求带站点 Referer；勿用 no-referrer（空 Referer 会被 COS 403）
  return tryFetchMediaBlob(remote, {
    mode: 'cors',
    credentials: 'omit'
  })
}

/** 供 WebAV MP4Clip / AudioClip 使用的可读流 */
export async function fetchMediaStream(url: string): Promise<ReadableStream<Uint8Array> | null> {
  const blob = await fetchMediaBlob(url)
  if (!blob) return null
  return blob.stream() as ReadableStream<Uint8Array>
}
