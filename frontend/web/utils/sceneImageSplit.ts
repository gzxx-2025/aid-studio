import { diagnosticFetch } from '~/utils/errorDiagnostics'
import { withAppBasePath } from '~/utils/appBasePath'

/** 场景四宫格切图顺序：主视、反打、左立面、右立面（左上、右上、左下、右下） */
export const SCENE_SPLIT_QUADRANT_LABELS = ['主视', '反打', '左立面', '右立面'] as const

export type SplitSceneImageOptions = {
  /** 画布预览区已加载的同源/CORS 图片，可跳过重复拉取 */
  sourceImg?: HTMLImageElement | null
}

function resolveAbsoluteImageUrl(imageUrl: string): string {
  const url = imageUrl.trim()
  if (!url) return url
  if (/^(blob:|data:)/i.test(url)) return url
  if (/^https?:\/\//i.test(url)) return url
  if (typeof window !== 'undefined') {
    return new URL(url, window.location.origin).href
  }
  return url
}

function normalizeUrlForCompare(url: string): string {
  const abs = resolveAbsoluteImageUrl(url)
  if (/^(blob:|data:)/i.test(abs)) return abs
  try {
    const u = new URL(abs)
    return `${u.origin}${u.pathname}`.replace(/\/+$/, '')
  } catch {
    return abs.split('?')[0]?.split('#')[0]?.replace(/\/+$/, '') ?? abs
  }
}

function isBlobOrDataUrl(url: string): boolean {
  return /^(blob:|data:)/i.test(url.trim())
}

function isCrossOriginUrl(url: string): boolean {
  if (typeof window === 'undefined') return false
  if (isBlobOrDataUrl(url)) return false
  try {
    return new URL(resolveAbsoluteImageUrl(url)).origin !== window.location.origin
  } catch {
    return true
  }
}

/** 解析同源 API 路径（兼容部署在 /aid/ 等子路径） */
function resolveSameOriginApiUrl(path: string): string {
  const p = path.startsWith('/') ? path : `/${path}`
  if (typeof window === 'undefined') return p
  return withAppBasePath(p)
}

/** 读取场景编辑弹窗画布预览区已展示的图片元素 */
export function resolveCanvasImageElement(expectedUrl?: string): HTMLImageElement | null {
  if (typeof document === 'undefined') return null
  const root = document.querySelector('.canvas-image')
  if (!root) return null
  const img =
    root instanceof HTMLImageElement
      ? root
      : (root.querySelector('img.ant-image-img') ?? root.querySelector('img'))
  if (!(img instanceof HTMLImageElement)) return null
  if (img.naturalWidth < 2 || img.naturalHeight < 2) return null
  if (expectedUrl) {
    const current = String(img.currentSrc || img.src || '').trim()
    if (current && normalizeUrlForCompare(current) !== normalizeUrlForCompare(expectedUrl)) {
      return null
    }
  }
  return img
}

async function decodeBlobToImage(blob: Blob): Promise<HTMLImageElement> {
  const { createTrackedObjectUrl, revokeObjectUrl } = await import('~/utils/objectUrl')
  const objectUrl = createTrackedObjectUrl(blob)
  try {
    const img = new Image()
    await new Promise<void>((resolve, reject) => {
      img.onload = () => resolve()
      img.onerror = () => reject(new Error('图片解析失败'))
      img.src = objectUrl
    })
    if (img.naturalWidth < 2 || img.naturalHeight < 2) {
      throw new Error('图片尺寸无效')
    }
    return img
  } finally {
    revokeObjectUrl(objectUrl)
  }
}

async function loadImageFromUrl(url: string): Promise<HTMLImageElement> {
  const img = new Image()
  await new Promise<void>((resolve, reject) => {
    img.onload = () => resolve()
    img.onerror = () => reject(new Error('图片解析失败'))
    img.src = url
  })
  if (img.naturalWidth < 2 || img.naturalHeight < 2) {
    throw new Error('图片尺寸无效')
  }
  return img
}

async function canExportImageToCanvas(img: HTMLImageElement): Promise<boolean> {
  if (img.naturalWidth < 2 || img.naturalHeight < 2) return false
  try {
    const canvas = document.createElement('canvas')
    canvas.width = 2
    canvas.height = 2
    const ctx = canvas.getContext('2d')
    if (!ctx) return false
    ctx.drawImage(img, 0, 0, 2, 2)
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png'))
    return blob != null && blob.size > 0
  } catch {
    return false
  }
}

async function loadImageWithCrossOrigin(imageUrl: string): Promise<HTMLImageElement> {
  const url = resolveAbsoluteImageUrl(imageUrl)
  const img = new Image()
  img.crossOrigin = 'anonymous'
  img.referrerPolicy = 'no-referrer'
  await new Promise<void>((resolve, reject) => {
    img.onload = () => resolve()
    img.onerror = () => reject(new Error('图片加载失败'))
    img.src = url
  })
  if (img.naturalWidth < 2 || img.naturalHeight < 2) {
    throw new Error('图片尺寸无效')
  }
  if (!(await canExportImageToCanvas(img))) {
    throw new Error('图片跨域限制，无法用于切图')
  }
  return img
}

/**
 * 直接拉取图片 Blob（同源或 OSS/CDN 已配置 CORS 时可用）。
 */
async function fetchImageBlobDirect(imageUrl: string): Promise<Blob> {
  const url = resolveAbsoluteImageUrl(imageUrl)
  if (!url) throw new Error('图片地址为空')

  const sameOrigin =
    typeof window !== 'undefined' && new URL(url).origin === window.location.origin

  const res = await diagnosticFetch(url, {
    mode: sameOrigin ? 'same-origin' : 'cors',
    credentials: 'omit',
    referrerPolicy: 'no-referrer'
  })
  if (!res.ok) throw new Error(`图片加载失败（${res.status}）`)
  const blob = await res.blob()
  if (blob.size === 0) throw new Error('图片内容为空')
  return blob
}

/**
 * 同源 Nitro 代理拉取 OSS/CDN 图片，避免跨域污染 canvas。
 * 开发/生产均可用，不依赖 OSS CORS 配置。
 */
async function fetchImageBlobViaSameOriginProxy(imageUrl: string): Promise<Blob> {
  const params = new URLSearchParams({ url: resolveAbsoluteImageUrl(imageUrl) })
  const proxyUrl = `${resolveSameOriginApiUrl('/api/image-proxy')}?${params.toString()}`
  const res = await diagnosticFetch(proxyUrl, {
    credentials: 'same-origin',
    referrerPolicy: 'no-referrer'
  })
  if (!res.ok) {
    const errText = await res.text().catch(() => '')
    throw new Error(errText || `图片代理拉取失败（${res.status}）`)
  }
  const blob = await res.blob()
  if (blob.size === 0) throw new Error('图片内容为空')
  return blob
}

async function resolveImageForCanvas(
  imageUrl: string,
  options?: SplitSceneImageOptions
): Promise<HTMLImageElement> {
  const url = imageUrl.trim()
  if (!url) throw new Error('图片地址为空')

  // blob / data URL：本地预览图，直接解码
  if (isBlobOrDataUrl(url)) {
    if (options?.sourceImg instanceof HTMLImageElement && (await canExportImageToCanvas(options.sourceImg))) {
      return options.sourceImg
    }
    const domImg = resolveCanvasImageElement(url)
    if (domImg && (await canExportImageToCanvas(domImg))) return domImg
    return loadImageFromUrl(url)
  }

  const candidates: HTMLImageElement[] = []
  if (options?.sourceImg instanceof HTMLImageElement) {
    candidates.push(options.sourceImg)
  }
  const domImg = resolveCanvasImageElement(url)
  if (domImg && !candidates.includes(domImg)) {
    candidates.push(domImg)
  }

  for (const img of candidates) {
    if (await canExportImageToCanvas(img)) return img
  }

  const strategies: Array<() => Promise<HTMLImageElement>> = []

  // 同源：优先直连，减少代理开销
  if (!isCrossOriginUrl(url)) {
    strategies.push(async () => decodeBlobToImage(await fetchImageBlobDirect(url)))
  }

  // 跨域 OSS/CDN：优先同源代理（线上通常无 CORS，代理最可靠）
  strategies.push(async () => decodeBlobToImage(await fetchImageBlobViaSameOriginProxy(url)))

  strategies.push(
    async () => decodeBlobToImage(await fetchImageBlobDirect(url)),
    () => loadImageWithCrossOrigin(url)
  )

  const errors: string[] = []
  for (const strategy of strategies) {
    try {
      return await strategy()
    } catch (e: unknown) {
      errors.push(String((e as Error)?.message || '未知错误'))
    }
  }

  throw new Error(errors[errors.length - 1] || '图片加载失败，请稍后重试')
}

function canvasToPngFile(canvas: HTMLCanvasElement, name: string): Promise<File> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (!blob) {
          reject(new Error('图片导出失败'))
          return
        }
        resolve(new File([blob], name, { type: 'image/png' }))
      },
      'image/png',
      0.92
    )
  })
}

/**
 * 将已加载的图片元素按 2×2 切成 4 张 PNG 文件。
 * 顺序：[主视, 反打, 左立面, 右立面] = [左上, 右上, 左下, 右下]
 */
export async function splitLoadedImageIntoFourFiles(
  img: HTMLImageElement,
  baseName = 'scene-split'
): Promise<File[]> {
  const w = img.naturalWidth
  const h = img.naturalHeight
  if (w < 2 || h < 2) throw new Error('图片尺寸过小，无法拆分')

  const hw = Math.floor(w / 2)
  const hh = Math.floor(h / 2)
  const regions: [number, number, number, number][] = [
    [0, 0, hw, hh],
    [hw, 0, w - hw, hh],
    [0, hh, hw, h - hh],
    [hw, hh, w - hw, h - hh]
  ]

  const safeBase =
    baseName.replace(/[^\w\u4e00-\u9fa5-]+/g, '_').slice(0, 48) || 'scene-split'
  const files: File[] = []

  for (let i = 0; i < 4; i++) {
    const [sx, sy, sw, sh] = regions[i]!
    const canvas = document.createElement('canvas')
    canvas.width = sw
    canvas.height = sh
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('无法创建画布')
    ctx.drawImage(img, sx, sy, sw, sh, 0, 0, sw, sh)
    const label = SCENE_SPLIT_QUADRANT_LABELS[i] ?? `part${i + 1}`
    files.push(await canvasToPngFile(canvas, `${safeBase}_${label}.png`))
  }

  return files
}

/**
 * 将场景形态图按宽高中线切成 4 张子图文件（纯前端 Canvas 切图）。
 * 顺序：[主视, 反打, 左立面, 右立面] = [左上, 右上, 左下, 右下]
 */
export async function splitSceneImageIntoFourFiles(
  imageUrl: string,
  baseName = 'scene-split',
  options?: SplitSceneImageOptions
): Promise<File[]> {
  const img = await resolveImageForCanvas(imageUrl, options)
  return splitLoadedImageIntoFourFiles(img, baseName)
}
