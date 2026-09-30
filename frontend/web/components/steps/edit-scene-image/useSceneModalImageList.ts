'use client'

import {
userAssetRpsFormImageList
} from '~/utils/businessApi'
import { createSceneModalImagePersistenceOps } from './sceneModalImagePersistenceOps'
import { isSinglePrimaryImageEditor, normalizeImageId } from './sceneModalTaskParsers'
import type { EditSceneImageModalCtx,EditSceneImageModalScene,ModalScopeSnapshot } from './types'

export interface SceneModalImageListApi {
  /** 设置当前从表形态为使用中（列表与 Tab 展示主图） */
  reserveSetRpsForm: (payload: {
    imageId?: number
    formId?: number
    imageType: 'scene' | 'character' | 'prop' | 'form'
  }) => Promise<boolean>
  /** 取消从表形态使用（列表与 Tab 不再展示该主图） */
  reserveUnsetRpsForm: (payload: { imageId?: number; formId?: number }) => Promise<boolean>
  /**
   * 通过 form-image/list 解析当前选中图对应的形态图实例 ID（imgId）。
   * 匹配优先级：rpsImageId > imageUrl > name > 首条可用记录。
   */
  resolveImageIdFromFormImageList: (payload: {
    formId?: number
    imageId?: number
    imageUrl?: string
    imageTitle?: string
  }) => Promise<number | null>
  /** 只把“已设置”的图片同步给父组件（决定外部列表和顶部 tab 展示） */
  buildVisibleImagesForParent: () => any[]
  /** 编辑弹窗内：优先走 form-image 层（create/update）同步 */
  syncImageToRpsApi: (
    imageUrl: string,
    imageTitle: string,
    sourceType: 'upload' | 'official' | 'ai',
    currentImage?: any
  ) => Promise<{ formId?: number; imageId?: number } | null>
  emitSceneTabUpdate: (images: any[], tabIndex?: number) => void
  syncLocalSceneImagesFromSceneIndex: (
    sceneIdx: number,
    opts?: { preservePending?: boolean }
  ) => void
  /** 导入参考图弹窗：当前场景以左侧生成记录为准（含 isUse=0 等未同步到父级的图） */
  scenesForImportModal: () => EditSceneImageModalScene[]
  /** 父级已持久化的场景图 id 写入集合（刷新后依赖 props 恢复「取消添加」态） */
  syncAddedImageIdsFromParentScenes: () => void
  /** 当前图是否在父级列表中且可显示「取消添加」（与 addedImageIds 一致） */
  isCurrentImageCancelAddVisible: () => boolean
  /** 当前场景已设为主图且可在外部展示的图片数量 */
  currentSceneMainImageCount: () => number
  /** 只剩 1 张主图时，禁止取消添加并给出提示 */
  cancelAddDisabledTooltip: () => string
  canDeleteHistoryImage: (img: { rpsImageId?: number } | null | undefined) => boolean
  isHistoryItemMain: (imageIndex: number) => boolean
  canSetMainFromHistory: (imageIndex: number) => boolean
  buildInitFormImageListKey: () => string
  /**
   * 弹窗打开时初始化形态图列表：
   * - imageType === 'form'：按当前 form tab 的 formId 拉取 isUse=1 图片
   * - imageType !== 'form'：按 activeRpsFormIds 拉取形态图全量（isUse=0/1）
   */
  initFormImageListOnOpen: (options?: { focusImageId?: number | null }) => Promise<void>
  /** 对话作图成功后，从 form-image/list 回填可展示 URL（SSE resultData 的 imageUrl 可能未走 @MediaUrl） */
  refreshAfterEditChatGenerate: (
    items: Array<{ imageId: number; imageUrl: string }>,
    modalScope?: ModalScopeSnapshot
  ) => Promise<void>
  /** 任务落库后刷新形态图列表；SSE 略早于 list 可见时短重试 */
  refreshFormImageListAfterTask: (
    focusImageId?: number | null,
    opts?: {
      imageUrl?: string | null
      retryDelaysMs?: number[]
      isCurrent?: () => boolean
    }
  ) => Promise<void>
  appendSettingCardToLocalListIfMissing: (payload: {
    imageId: number | null
    imageUrl: string
    title?: string
  }) => number
}

/** 与列表接口使用同一映射，拆分响应可在列表短暂不可见时立即补齐生成记录。 */
export function mapFormImageRowToLocalImage(row: any, formIdFallback: number, orderIndex: number) {
  const idRaw = Number(row?.id)
  const id = Number.isFinite(idRaw) ? `img-${idRaw}` : `form-${formIdFallback}-${orderIndex}`
  const url = String(row?.imageUrl || '').trim()
  return {
    id,
    url,
    thumbnail: url,
    title: String(row?.name || '').trim() || `形态图${orderIndex + 1}`,
    source: 'server',
    importDate: String(row?.updateTime || row?.createTime || '') || '',
    angles: [],
    rpsFormId: Number(row?.formId ?? formIdFallback),
    rpsImageId: Number(row?.id ?? NaN),
    promptText: typeof row?.promptText === 'string' ? row.promptText : null,
    referenceImages: Array.isArray(row?.referenceImages) ? [...row.referenceImages] : [],
    _serverSourceType: String(row?.sourceType || '').trim() || undefined,
    _isSet: Number(row?.isUse) === 1,
    canSplit: row?.canSplit === true
  }
}

export function useSceneModalImageList(ctx: EditSceneImageModalCtx): SceneModalImageListApi {
  const { buildVisibleImagesForParent, emitSceneTabUpdate, reserveSetRpsForm, reserveUnsetRpsForm, resolveImageIdFromFormImageList, syncImageToRpsApi, syncLocalSceneImagesFromSceneIndex } = createSceneModalImagePersistenceOps(ctx)
  function normalizeMainImageFlags(images: any[], focusImageId?: number | null) {
    if (!isSinglePrimaryImageEditor(ctx.props())) return images
    let mainIndex = -1
    for (let index = 0; index < images.length; index += 1) {
      if (images[index]?._isSet === true) mainIndex = index
    }
    if (focusImageId != null) {
      const focusIndex = images.findIndex(
        (image) => image?._isSet === true && Number(image?.rpsImageId) === Number(focusImageId)
      )
      if (focusIndex >= 0) mainIndex = focusIndex
    }
    return images.map((image, index) =>
      image?._isSet === true && index !== mainIndex ? { ...image, _isSet: false } : image
    )
  }

  function scenesForImportModal() {
    return ctx.props().scenes.map((scene, index) => {
      if (index !== ctx.currentSceneIndex.get()) return scene
      const images = ctx.localSceneImages
        .get()
        .filter((img) => Boolean(img?.url))
        .map((img, i) => ({
          id: img.id,
          url: img.url,
          thumbnail: img.thumbnail || img.url,
          title: img.title || img.name || `场景图${i + 1}`,
          source: img.source,
          importDate: img.importDate
        }))
      return { ...scene, images }
    })
  }

  /** 父级已持久化的场景图 id 写入集合（刷新后依赖 props 恢复「取消添加」态） */
  function syncAddedImageIdsFromParentScenes() {
    const si = ctx.currentSceneIndex.get()
    const sceneImages = ctx.props().scenes[si]?.images || []
    const next = new Set<string>()
    const visibleImages = isSinglePrimaryImageEditor(ctx.props())
      ? sceneImages.slice(-1)
      : sceneImages
    for (const im of visibleImages) {
      const key = normalizeImageId(im?.id)
      if (key && !(im as { _pending?: boolean })?._pending) next.add(key)
    }
    ctx.addedImageIds.set(next)
  }

  /** 当前图是否在父级列表中且可显示「取消添加」（与 addedImageIds 一致） */
  const isCurrentImageCancelAddVisible = () => {
    const img = ctx.currentImg() as { id?: unknown } | null
    const key = normalizeImageId(img?.id)
    return Boolean(key && ctx.addedImageIds.get().has(key))
  }

  /** 当前场景已设为主图且可在外部展示的图片数量 */
  const currentSceneMainImageCount = () => {
    const sceneImages = ctx.props().scenes[ctx.currentSceneIndex.get()]?.images || []
    let count = 0
    for (const im of sceneImages) {
      const key = normalizeImageId((im as { id?: unknown })?.id)
      if (key && ctx.addedImageIds.get().has(key)) count += 1
    }
    return count
  }

  /** 只剩 1 张主图时，禁止取消添加并给出提示 */
  const cancelAddDisabledTooltip = () => {
    if (!isCurrentImageCancelAddVisible()) return ''
    if (currentSceneMainImageCount() <= 1) {
      return '当前只有一张主图，不可取消'
    }
    return ''
  }

  function canDeleteHistoryImage(img: { rpsImageId?: number } | null | undefined): boolean {
    const rpsImageId = Number(img?.rpsImageId)
    return Number.isFinite(rpsImageId)
  }

  function isHistoryItemMain(imageIndex: number): boolean {
    const img = ctx.localSceneImages.get()[imageIndex] as { _isSet?: boolean } | undefined
    return img?._isSet === true
  }

  function canSetMainFromHistory(imageIndex: number): boolean {
    const img = ctx.localSceneImages.get()[imageIndex] as { url?: string } | undefined
    if (!img?.url || ctx.isHistoryItemGenerating(imageIndex) || isHistoryItemMain(imageIndex)) return false
    return true
  }

  function buildInitFormImageListKey() {
    const formIds = ctx.activeRpsFormIds() ?? []
    return `${ctx.props().imageType}|${ctx.currentSceneIndex.get()}|${ctx.activeRpsAssetId() ?? ''}|${Array.isArray(formIds) ? formIds.join(',') : ''}`
  }

  /**
   * 弹窗打开时初始化形态图列表：
   * - imageType === 'form'：按当前 form tab 的 formId 拉取 isUse=1 图片
   * - imageType !== 'form'：按 activeRpsFormIds 拉取形态图全量（isUse=0/1）
   */
  async function initFormImageListOnOpen(options?: { focusImageId?: number | null }) {
    if (!ctx.props().open) return
    const seq = ++ctx.initFormImageListSeq.current
    const expectedSceneIdx = ctx.currentSceneIndex.get()
    const expectedEditorScopeKey = ctx.buildEditorScopeKeyForSceneIndex(expectedSceneIdx)
    const canApply = () =>
      ctx.props().open &&
      ctx.currentSceneIndex.get() === expectedSceneIdx &&
      ctx.buildEditorScopeKeyForSceneIndex(ctx.currentSceneIndex.get()) === expectedEditorScopeKey

    const focusImageId =
      options?.focusImageId != null && Number.isFinite(Number(options.focusImageId))
        ? Number(options.focusImageId)
        : null

    function applyFocusIndex() {
      if (focusImageId == null) return
      const idx = ctx.localSceneImages
        .get()
        .findIndex((img: any) => Number(img?.rpsImageId) === focusImageId)
      if (idx >= 0) ctx.currentImageIndex.set(idx)
    }

    // 兜底：本弹窗需要 formIds 才有意义
    const formIds = ctx.activeRpsFormIds() ?? []
    if (ctx.props().imageType === 'form' && formIds.length === 0) return

    const hasRpsContext = ctx.activeRpsAssetId() != null || (formIds?.length ?? 0) > 0
    if (hasRpsContext) ctx.lockLocalSceneImagesFromRps.current = true

    // 只保留 pending，避免被初始化覆盖
    const pendingOnly = ctx.localSceneImages.get().filter((img: any) => img?._pending)

    if (ctx.props().imageType === 'form') {
      const formId = formIds[ctx.currentSceneIndex.get()]
      const fid = Number(formId)
      if (fid == null || !Number.isFinite(fid)) return
      try {
        const list = await userAssetRpsFormImageList({ formId: fid, isUse: null })
        if (seq !== ctx.initFormImageListSeq.current || !canApply()) return
        const images = normalizeMainImageFlags(
          (Array.isArray(list) ? list : []).map((r: any, i: number) => mapFormImageRowToLocalImage(r, fid, i)),
          focusImageId
        )
        ctx.localSceneImages.set(ctx.finalizeLocalImagesWhileGenerating([...images, ...pendingOnly]))
        if (focusImageId != null) {
          applyFocusIndex()
        } else {
          ctx.currentImageIndex.set(images.length ? 0 : Math.max(0, pendingOnly.length - 1))
        }
      } catch {
        // 初始化失败不阻断，保持当前展示
      }
      return
    }

    try {
      const images: any[] = []

      // formIds 为空时：使用 assetId/assetType 兜底，仍触发 list 初始化
      if (formIds.length === 0) {
        const assetId = ctx.activeRpsAssetId()
        const assetType = ctx.props().imageType === 'scene' ? 'scene' : ctx.props().imageType === 'character' ? 'character' : 'prop'

        const list = await userAssetRpsFormImageList({
          formId: undefined,
          assetId: assetId != null && Number.isFinite(Number(assetId)) ? Number(assetId) : undefined,
          assetType,
          isUse: null
        })

        if (seq !== ctx.initFormImageListSeq.current || !canApply()) return
        const arr = Array.isArray(list) ? list : []
        for (const [idx, r] of arr.entries()) {
          const fidFallback = Number(r?.formId)
          images.push(
            mapFormImageRowToLocalImage(r, Number.isFinite(fidFallback) ? fidFallback : 0, idx)
          )
        }
      } else {
        const results = await Promise.all(
          formIds
            .map((f) => Number(f))
            .filter((n) => Number.isFinite(n))
            .map(async (fid) => {
              const list = await userAssetRpsFormImageList({ formId: fid, isUse: null })
              return [fid, list] as const
            })
        )
        if (seq !== ctx.initFormImageListSeq.current || !canApply()) return

        for (const [fid, list] of results) {
          const arr = Array.isArray(list) ? list : []
          // form-image/list 可能返回 isUse=0/1 全量；
          // 左侧列表初始化需要展示“所有图片”，不要只取 isUse=1 的那一张。
          for (const r of arr) {
            if (!r) continue
            images.push(mapFormImageRowToLocalImage(r, fid, images.length))
          }
        }
      }

      if (!canApply()) return
      ctx.localSceneImages.set(
        ctx.finalizeLocalImagesWhileGenerating([
          ...normalizeMainImageFlags(images, focusImageId),
          ...pendingOnly
        ])
      )
      if (focusImageId != null) {
        applyFocusIndex()
      } else {
        ctx.currentImageIndex.set(images.length ? 0 : Math.max(0, pendingOnly.length - 1))
      }
    } catch {
      // ignore
    }
  }

  /** 对话作图成功后，从 form-image/list 回填可展示 URL（SSE resultData 的 imageUrl 可能未走 @MediaUrl） */
  async function refreshAfterEditChatGenerate(
    items: Array<{ imageId: number; imageUrl: string }>,
    modalScope?: ModalScopeSnapshot
  ) {
    ctx.preserveDialogueComposer.current = true
    if (modalScope && !ctx.isSameModalScope(modalScope)) return
    const focusImageId = items.length ? items[items.length - 1]?.imageId ?? null : null
    await refreshFormImageListAfterTask(focusImageId, {
      isCurrent: modalScope ? () => ctx.isSameModalScope(modalScope) : undefined
    })
    if (modalScope && !ctx.isSameModalScope(modalScope)) return
    emitSceneTabUpdate(buildVisibleImagesForParent())
  }

  /** 任务落库后刷新形态图列表；SSE 略早于 list 可见时短重试 */
  async function refreshFormImageListAfterTask(
    focusImageId?: number | null,
    opts?: {
      imageUrl?: string | null
      retryDelaysMs?: number[]
      isCurrent?: () => boolean
    }
  ) {
    const isCurrent = opts?.isCurrent ?? (() => true)
    const fid =
      focusImageId != null && Number.isFinite(Number(focusImageId)) ? Number(focusImageId) : null
    const imageUrl = String(opts?.imageUrl || '').trim()
    const delays = opts?.retryDelaysMs ?? [450, 900, 1500]

    const isFound = () =>
      ctx.localSceneImages.get().some((img: any) => {
        if (fid != null && Number(img?.rpsImageId) === fid) return true
        if (imageUrl && String(img?.url || '').trim() === imageUrl) return true
        return false
      })

    if (!isCurrent()) return
    ctx.lastInitFormImageListKey.current = ''
    await initFormImageListOnOpen({ focusImageId: fid })
    if (!isCurrent()) return
    if (isFound()) return

    for (const delay of delays) {
      await new Promise((r) => setTimeout(r, delay))
      if (!isCurrent()) return
      await initFormImageListOnOpen({ focusImageId: fid })
      if (!isCurrent()) return
      if (isFound()) return
    }
  }

  function appendSettingCardToLocalListIfMissing(payload: {
    imageId: number | null
    imageUrl: string
    title?: string
  }): number {
    const url = String(payload.imageUrl || '').trim()
    if (!url) return -1

    const existingIdx = ctx.localSceneImages.get().findIndex((x: any) => {
      if (payload.imageId != null && Number(x?.rpsImageId) === payload.imageId) return true
      return String(x?.url || '').trim() === url
    })
    if (existingIdx >= 0) return existingIdx

    const formIds = ctx.activeRpsFormIds() ?? []
    const rawFormId =
      ctx.props().imageType === 'form' ? formIds[ctx.currentSceneIndex.get()] : formIds[ctx.currentSceneIndex.get()]
    const fid = Number(rawFormId)
    const newRow = {
      id:
        payload.imageId != null && Number.isFinite(payload.imageId)
          ? `img-${payload.imageId}`
          : `card-${Date.now()}`,
      url,
      thumbnail: url,
      title: payload.title || '角色设定卡',
      source: 'server',
      importDate: new Date().toISOString(),
      angles: [],
      rpsFormId: Number.isFinite(fid) ? fid : 0,
      rpsImageId: payload.imageId ?? undefined,
      _serverSourceType: 'ai_builder',
      _isSet: true,
      canSplit: false
    }
    const currentImages = isSinglePrimaryImageEditor(ctx.props())
      ? ctx.localSceneImages
          .get()
          .map((image) => (image._isSet ? { ...image, _isSet: false } : image))
      : ctx.localSceneImages.get()
    ctx.localSceneImages.set([...currentImages, newRow])
    return ctx.localSceneImages.get().length - 1
  }

  return {
    reserveSetRpsForm,
    reserveUnsetRpsForm,
    resolveImageIdFromFormImageList,
    buildVisibleImagesForParent,
    syncImageToRpsApi,
    emitSceneTabUpdate,
    syncLocalSceneImagesFromSceneIndex,
    scenesForImportModal,
    syncAddedImageIdsFromParentScenes,
    isCurrentImageCancelAddVisible,
    currentSceneMainImageCount,
    cancelAddDisabledTooltip,
    canDeleteHistoryImage,
    isHistoryItemMain,
    canSetMainFromHistory,
    buildInitFormImageListKey,
    initFormImageListOnOpen,
    refreshAfterEditChatGenerate,
    refreshFormImageListAfterTask,
    appendSettingCardToLocalListIfMissing
  }
}
