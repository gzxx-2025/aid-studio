'use client'

/**
 * 原 pages/create/series-episode-list.vue（definePageMeta layout: 'create'）。
 *
 * 创作壳已接线（app/create/layout.tsx → CreateFlowShell）：
 * - 侧栏 / 顶栏（剧集 chrome）由壳层提供，本页仅渲染剧集列表主体；
 * - html class `app-shell-create layout-create-flow` 已移交 app/create/layout.tsx 挂载；
 * - 本页未使用 createFlowShellContext（utils/createFlowInjection.ts），无 Context 降级需求。
 */

import { PlusOutlined } from '@ant-design/icons'
import { Modal,message } from 'antd'
import { useRouter } from 'next/navigation'
import { Suspense,useEffect,useMemo,useRef,useState } from 'react'
import tvIconRaw from '~/assets/img/icon/tv.svg'
import SeriesProjectAssetCard from '~/components/create/SeriesProjectAssetCard'
import { InfiniteScrollLoadFooter } from '~/components/common/InfiniteScrollLoadFooter'
import { useInfiniteScrollPagination } from '~/hooks/useInfiniteScrollPagination'
import { useRouteLike } from '~/hooks/useRouteLike'
import { useCreationStore } from '~/stores/creation'
import type { UserAssetApiType,UserAssetRow,UserEpisodeRow } from '~/types/business-api'
import { assetUrl } from '~/utils/assetUrl'
import {
rpsRowToUserAssetRow,
userAssetRpsDelete,
userAssetRpsList,
userEpisodeCreate,
userEpisodeDelete,
userEpisodeList,
userEpisodePage,
userScriptDetailByProject
} from '~/utils/businessApi'
import { emptyImageIconUrl as assetCoverPlaceholderRaw } from '~/utils/emptyImageIcon'
import {
htmlPlainTextLength,
resolveStoryScriptEditorHtmlAfterApiLoad,
storyScriptOriginalTextForApi
} from '~/utils/htmlPlain'
import './series-episode-list.css'

const tvIconUrl = assetUrl(tvIconRaw)

const tabs = [
  { key: 'episodes' as const, label: '剧集管理' },
  { key: 'characters' as const, label: '角色' },
  { key: 'props' as const, label: '道具' },
  { key: 'scenes' as const, label: '场景' }
]

type TabKey = (typeof tabs)[number]['key']

/** 无封面 / 副图时与「我的作品」卡片一致的本地占位（不依赖外网生成图） */
const PLACEHOLDER_ASSET_COVER = assetUrl(assetCoverPlaceholderRaw)

const tabKeyToApiType: Record<'characters' | 'props' | 'scenes', UserAssetApiType> = {
  characters: 'character',
  props: 'prop',
  scenes: 'scene'
}

function sortAssetRows(rows: UserAssetRow[]): UserAssetRow[] {
  return [...rows].sort((a, b) => {
    const ta = new Date(a.updateTime || a.createTime || 0).getTime()
    const tb = new Date(b.updateTime || b.createTime || 0).getTime()
    return tb - ta
  })
}

function assetCoverUrl(row: UserAssetRow): string {
  let thumb = row.refImageUrl?.trim() || ''
  if (!thumb && row.extraImages) {
    const first = row.extraImages
      .split(';')
      .map((s) => s.trim())
      .find(Boolean)
    thumb = first || ''
  }
  return thumb || PLACEHOLDER_ASSET_COVER
}

function formatDateTime(raw?: string | null): string {
  if (!raw) return '—'
  const d = new Date(raw)
  if (Number.isNaN(d.getTime())) return raw
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const h = String(d.getHours()).padStart(2, '0')
  const min = String(d.getMinutes()).padStart(2, '0')
  return `${y}/${m}/${day} ${h}:${min}`
}

function SeriesEpisodeListClient() {
  const router = useRouter()
  const route = useRouteLike()
  const currentProjectId = useCreationStore((s) => s.currentProjectId)

  const [activeTab, setActiveTab] = useState<TabKey>('episodes')
  const activeTabRef = useRef<TabKey>('episodes')
  const [adding, setAdding] = useState(false)
  const [generatingEpisodeId, setGeneratingEpisodeId] = useState<number | null>(null)
  const generatingEpisodeIdRef = useRef<number | null>(null)
  const [deletingEpisodeId, setDeletingEpisodeId] = useState<number | null>(null)
  const deletingEpisodeIdRef = useRef<number | null>(null)
  const episodesRef = useRef<UserEpisodeRow[]>([])
  const episodeScrollRootRef = useRef<HTMLDivElement>(null)

  const [projectAssets, setProjectAssets] = useState<UserAssetRow[]>([])
  const [projectAssetsLoading, setProjectAssetsLoading] = useState(false)
  const projectAssetsFetchTokenRef = useRef(0)

  /** 已加载/加载中的 projectId：effect 因 store 水合后置触发时，同项目不重复拉列表 */
  const episodesLoadedProjectIdRef = useRef<number | null>(null)

  function setGenerating(id: number | null) {
    generatingEpisodeIdRef.current = id
    setGeneratingEpisodeId(id)
  }

  function setDeleting(id: number | null) {
    deletingEpisodeIdRef.current = id
    setDeletingEpisodeId(id)
  }

  const emptyAssetTips = (() => {
    if (activeTab === 'characters') return '暂无角色'
    if (activeTab === 'props') return '暂无道具'
    return '暂无场景'
  })()

  const sortedProjectAssets = useMemo(() => sortAssetRows(projectAssets), [projectAssets])

  function projectIdFromRoute(): number | null {
    const routePid = Number(route.query.projectId ?? route.query.id ?? route.query.workId)
    const pid =
      useCreationStore.getState().currentProjectId ??
      (Number.isFinite(routePid) && routePid > 0 ? routePid : null)
    return pid
  }

  const fetchEpisodePage = async (pageNum: number, pageSize: number) => {
    const pid = projectIdFromRoute()
    if (!pid) {
      useCreationStore.getState().setSeriesEpisodeListTotal(0)
      return { rows: [], hasMore: false }
    }
    const page = await userEpisodePage({ projectId: pid, pageNum, pageSize })
    if (projectIdFromRoute() === pid) {
      useCreationStore.getState().setSeriesEpisodeListTotal(page.total)
    }
    return { rows: page.rows, hasMore: page.hasMore }
  }
  const {
    items: episodes,
    loading: episodesLoading,
    loadingMore: episodesLoadingMore,
    initialLoaded: episodesInitialLoaded,
    loadError: episodesLoadError,
    hasMore: episodesHasMore,
    appendTick: episodesAppendTick,
    reload: reloadEpisodes,
    loadNextPage: loadNextEpisodePage,
    setLoadMoreTrigger: setEpisodeLoadMoreTrigger
  } = useInfiniteScrollPagination<UserEpisodeRow>(episodeScrollRootRef, fetchEpisodePage, {
    pageSize: 20,
    onError: (error, { reset }) => {
      if (reset) {
        episodesLoadedProjectIdRef.current = null
        useCreationStore.getState().setSeriesEpisodeListTotal(0)
      }
      const err = error as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || (reset ? '加载分集失败' : '加载更多分集失败'))
    }
  })
  const loading = episodesLoading || !episodesInitialLoaded
  useEffect(() => {
    episodesRef.current = episodes
  }, [episodes])
  const sortedEpisodes = useMemo(
    () => [...episodes].sort((a, b) => (a.episodeNo ?? 0) - (b.episodeNo ?? 0)),
    [episodes]
  )
  const previousEpisodeCountRef = useRef(0)
  const [appendedEpisodeFromIndex, setAppendedEpisodeFromIndex] = useState<number | null>(null)
  useEffect(() => {
    if (episodesAppendTick > 0 && episodes.length > previousEpisodeCountRef.current) {
      setAppendedEpisodeFromIndex(previousEpisodeCountRef.current)
    } else if (episodes.length === 0) {
      setAppendedEpisodeFromIndex(null)
    }
    previousEpisodeCountRef.current = episodes.length
  }, [episodesAppendTick, episodes.length])

  function sortedEpisodesFromRef(): UserEpisodeRow[] {
    return [...episodesRef.current].sort((a, b) => (a.episodeNo ?? 0) - (b.episodeNo ?? 0))
  }

  function resolveEpisodeIdForAssets(): number | null {
    const fromStore = useCreationStore.getState().currentEpisodeId
    if (fromStore != null && fromStore > 0) return fromStore
    const fromRoute = Number(route.query.episodeId)
    if (Number.isFinite(fromRoute) && fromRoute > 0) return fromRoute
    const first = sortedEpisodesFromRef()[0]?.id
    return first ?? null
  }

  function buildQuery(episodeId: number) {
    const q: Record<string, string> = {}
    for (const [k, v] of Object.entries(route.query)) {
      if (v === undefined || v === null) continue
      q[k] = Array.isArray(v) ? String(v[0] ?? '') : String(v)
    }
    const pid = projectIdFromRoute()
    if (pid) {
      q.projectId = String(pid)
      q.id = String(pid)
    }
    q.episodeId = String(episodeId)
    return q
  }

  function pushRoute(path: string, query: Record<string, string>) {
    const qs = new URLSearchParams(query).toString()
    router.push(qs ? `${path}?${qs}` : path)
  }

  /** 角色/道具/场景：POST /api/user/asset/rps/list（projectId + episodeId + assetType） */
  async function loadProjectAssets() {
    const tab = activeTabRef.current
    if (tab !== 'characters' && tab !== 'props' && tab !== 'scenes') return
    const pid = projectIdFromRoute()
    if (!pid) {
      setProjectAssets([])
      return
    }
    const fetchToken = ++projectAssetsFetchTokenRef.current
    setProjectAssetsLoading(true)
    try {
      const ep = resolveEpisodeIdForAssets()
      const { rows } = await userAssetRpsList({
        projectId: pid,
        ...(ep != null && ep > 0 ? { episodeId: ep } : {}),
        assetType: tabKeyToApiType[tab]
      })
      if (fetchToken !== projectAssetsFetchTokenRef.current) return
      setProjectAssets(rows.map(rpsRowToUserAssetRow))
    } catch (e: unknown) {
      if (fetchToken !== projectAssetsFetchTokenRef.current) return
      setProjectAssets([])
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '加载资产失败')
    } finally {
      if (fetchToken === projectAssetsFetchTokenRef.current) setProjectAssetsLoading(false)
    }
  }

  function openSceneCharacterForAssets() {
    const pid = projectIdFromRoute()
    const eid = resolveEpisodeIdForAssets()
    if (!pid || !eid) {
      message.warning('请先新增至少一集后再编辑资产')
      return
    }
    const store = useCreationStore.getState()
    store.setCurrentProjectContext({ projectId: pid, episodeId: eid })
    store.setSeriesFlowEnteredStoryScript(true)
    pushRoute('/create/scene-character', buildQuery(eid))
  }

  function onDeleteProjectAsset(row: UserAssetRow) {
    Modal.confirm({
      title: '删除资产',
      content: `确认删除「${row.assetName || '未命名'}」吗？`,
      okText: '删除',
      cancelText: '取消',
      okType: 'danger',
      async onOk() {
        try {
          await userAssetRpsDelete({ id: row.id })
          message.success('删除成功')
          await loadProjectAssets()
        } catch (e: unknown) {
          const err = e as { msg?: string; message?: string }
          message.error(err?.msg || err?.message || '删除失败')
        }
      }
    })
  }

  async function loadEpisodes(opts?: { force?: boolean }) {
    const pid = projectIdFromRoute()
    if (!pid) {
      episodesLoadedProjectIdRef.current = null
      await reloadEpisodes()
      return
    }
    if (!opts?.force && episodesLoadedProjectIdRef.current === pid) return
    episodesLoadedProjectIdRef.current = pid
    if (episodeScrollRootRef.current) episodeScrollRootRef.current.scrollTop = 0
    await reloadEpisodes()
  }

  function onTabClick(key: TabKey) {
    activeTabRef.current = key
    setActiveTab(key)
    if (key === 'characters' || key === 'props' || key === 'scenes') {
      const pid = projectIdFromRoute()
      const eid = resolveEpisodeIdForAssets()
      if (pid && eid) {
        useCreationStore.getState().setCurrentProjectContext({ projectId: pid, episodeId: eid })
      }
      void loadProjectAssets()
    }
  }

  async function onAddEpisode() {
    const pid = projectIdFromRoute()
    if (!pid) {
      message.warning('缺少项目信息')
      return
    }
    setAdding(true)
    try {
      const sorted = await userEpisodeList({ projectId: pid })
      const nextNo =
        sorted.length > 0 ? Math.max(...sorted.map((e) => e.episodeNo ?? 0)) + 1 : 1
      await userEpisodeCreate({
        projectId: pid,
        comicTitle: `第${nextNo}集`
      })
      message.success('已新增一集')
      await loadEpisodes({ force: true })
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '新增失败')
    } finally {
      setAdding(false)
    }
  }

  function deleteEpisode(ep: UserEpisodeRow) {
    if (deletingEpisodeIdRef.current !== null || generatingEpisodeIdRef.current !== null) return
    const title = ep.comicTitle || `第${ep.episodeNo ?? ''}集`
    Modal.confirm({
      title: '删除剧集',
      content: `确认删除「${title}」吗？删除后不可恢复。`,
      okText: '删除',
      cancelText: '取消',
      okType: 'danger',
      async onOk() {
        setDeleting(ep.id)
        try {
          await userEpisodeDelete({ id: ep.id })
          message.success('删除成功')
          const store = useCreationStore.getState()
          if (store.currentEpisodeId === ep.id) {
            store.setCurrentProjectContext({ episodeId: null })
          }
          await loadEpisodes({ force: true })
        } catch (e: unknown) {
          const err = e as { msg?: string; message?: string }
          message.error(err?.msg || err?.message || '删除失败')
          throw e
        } finally {
          setDeleting(null)
        }
      }
    })
  }

  async function generateEpisode(ep: UserEpisodeRow) {
    if (generatingEpisodeIdRef.current !== null) return
    const projectId = projectIdFromRoute()
    if (!projectId) {
      message.warning('缺少项目信息')
      return
    }

    setGenerating(ep.id)
    try {
      const script = await userScriptDetailByProject({ projectId, episodeId: ep.id })
      const rawScript = String(script?.originalText || '').trim()
      const hasScript = htmlPlainTextLength(rawScript) > 0

      const store = useCreationStore.getState()
      store.setCurrentProjectContext({ projectId, episodeId: ep.id })
      store.setSeriesFlowEnteredStoryScript(true)
      store.updateFormData({
        storyScript: {
          content: hasScript ? resolveStoryScriptEditorHtmlAfterApiLoad(rawScript, '') : ''
        }
      })
      store.setScriptServerHtmlBaseline(
        storyScriptOriginalTextForApi(
          useCreationStore.getState().formData.storyScript.content || ''
        )
      )

      try {
        pushRoute(hasScript ? '/create/scene-character' : '/create/story-script', {
          ...buildQuery(ep.id),
          stepInitAdvance: '1',
          ...(hasScript ? { stepInitTarget: 'scene-character' } : {})
        })
      } catch (navErr: unknown) {
        // 快速连点不同剧集时，并发导航可能被路由打断
        if (process.env.NODE_ENV === 'development') {
          console.warn('[series-episode-list] generateEpisode navigation skipped', navErr)
        }
      }
    } catch (e: unknown) {
      const err = e as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '读取剧本失败，请稍后重试')
    } finally {
      setGenerating(null)
    }
  }

  // 原 onMounted + watch([route.query.projectId, route.query.id, route.query.workId, currentProjectId])
  useEffect(() => {
    void loadEpisodes()
    const tab = activeTabRef.current
    if (tab === 'characters' || tab === 'props' || tab === 'scenes') {
      void loadProjectAssets()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [route.query.projectId, route.query.id, route.query.workId, currentProjectId])

  // 原 onUnmounted
  useEffect(() => {
    return () => {
      useCreationStore.getState().setSeriesEpisodeListTotal(null)
    }
  }, [])

  return (
    <div className="series-ep-list">
      <div className="series-ep-list__toolbar">
        {/* 与 EditStoryboardImageModal 右侧 config-tabs--three / config-tab 一致 */}
        <div className="series-ep-list__segment-wrap">
          <div className="series-ep-list__segment-tabs" role="tablist">
            {tabs.map((tab) => (
              <button
                key={tab.key}
                type="button"
                role="tab"
                className={`series-ep-list__segment-tab${activeTab === tab.key ? ' active' : ''}`}
                aria-selected={activeTab === tab.key}
                onClick={() => onTabClick(tab.key)}
              >
                {tab.label}
              </button>
            ))}
          </div>
        </div>
        {activeTab === 'episodes' ? (
          <button
            type="button"
            className="series-ep-list__add"
            disabled={adding}
            onClick={onAddEpisode}
          >
            <PlusOutlined />
            新增一集
          </button>
        ) : null}
      </div>

      {activeTab === 'episodes' ? (
        <div className="series-ep-list__body" ref={episodeScrollRootRef}>
          {loading ? (
            <div className="series-ep-list__loading">加载中…</div>
          ) : episodesLoadError && episodes.length === 0 ? (
            <div className="series-ep-list__empty">
              加载分集失败，<button type="button" onClick={() => void loadEpisodes({ force: true })}>点击重试</button>
            </div>
          ) : sortedEpisodes.length === 0 ? (
            <div className="series-ep-list__empty">暂无分集，点击「新增一集」开始</div>
          ) : (
            <div
              className="series-ep-list__table"
              role="table"
              aria-label="剧集列表"
            >
              <div className="series-ep-list__thead" role="row">
                <span
                  role="columnheader"
                  className="series-ep-list__th-col series-ep-list__th-col--episode"
                >
                  集数
                </span>
                <span
                  role="columnheader"
                  className="series-ep-list__th-col series-ep-list__th-col--time"
                >
                  创建时间
                </span>
                <span
                  role="columnheader"
                  className="series-ep-list__th-col series-ep-list__th-col--actions"
                >
                  操作
                </span>
              </div>
              {sortedEpisodes.map((ep, index) => (
                <div
                  key={ep.id}
                  className={`series-ep-list__row${appendedEpisodeFromIndex !== null && index >= appendedEpisodeFromIndex ? ' series-ep-list__row--appended' : ''}`}
                  role="row"
                >
                  <div className="series-ep-list__cell series-ep-list__cell--ep" role="cell">
                    <span className="series-ep-list__ep-inner">
                      <span className="series-ep-list__num">{ep.episodeNo ?? '—'}</span>
                      <img src={tvIconUrl} className="series-ep-list__doc-ico" alt="" />
                      <span className="series-ep-list__ep-title">
                        {ep.comicTitle || `第${ep.episodeNo ?? ''}集`}
                      </span>
                    </span>
                  </div>
                  <div className="series-ep-list__cell series-ep-list__cell--time" role="cell">
                    {formatDateTime(ep.createTime)}
                  </div>
                  <div className="series-ep-list__cell series-ep-list__cell--actions" role="cell">
                    <button
                      type="button"
                      className="series-ep-list__btn series-ep-list__btn--primary"
                      disabled={generatingEpisodeId !== null || deletingEpisodeId !== null}
                      onClick={() => void generateEpisode(ep)}
                    >
                      {generatingEpisodeId === ep.id ? '进入中…' : '生成剧集'}
                    </button>
                    <button
                      type="button"
                      className="series-ep-list__btn series-ep-list__btn--ghost"
                      disabled={deletingEpisodeId !== null || generatingEpisodeId !== null}
                      onClick={() => deleteEpisode(ep)}
                    >
                      {deletingEpisodeId === ep.id ? '删除中…' : '删除剧集'}
                    </button>
                  </div>
                </div>
              ))}
              <div ref={setEpisodeLoadMoreTrigger} className="series-ep-list__footer">
                {episodesLoadError && episodesHasMore ? (
                  <button type="button" onClick={() => void loadNextEpisodePage()}>加载失败，点击重试</button>
                ) : (
                  <InfiniteScrollLoadFooter
                    loading={episodesLoadingMore}
                    hasMore={episodesHasMore}
                    hasItems
                    loadingText="正在加载更多剧集…"
                    endText="已加载全部剧集"
                  />
                )}
              </div>
            </div>
          )}
        </div>
      ) : activeTab === 'characters' || activeTab === 'props' || activeTab === 'scenes' ? (
        <div className="series-ep-list__asset-body">
          {projectAssetsLoading ? (
            <div className="series-ep-list__loading">加载资产中…</div>
          ) : sortedProjectAssets.length > 0 ? (
            <div className="series-ep-list__asset-grid-wrap assets-library-figma">
              <div className="works-lib-grid series-ep-list__works-grid">
                {sortedProjectAssets.map((row) => (
                  <SeriesProjectAssetCard
                    key={row.id}
                    title={row.assetName || '未命名'}
                    coverImage={assetCoverUrl(row)}
                    dateLabel={row.createTime || row.updateTime || ''}
                    onDelete={() => onDeleteProjectAsset(row)}
                    onEdit={openSceneCharacterForAssets}
                  />
                ))}
              </div>
            </div>
          ) : (
            <div className="series-ep-list__empty">{emptyAssetTips}</div>
          )}
        </div>
      ) : null}
    </div>
  )
}

export default function SeriesEpisodeListPage() {
  return (
    <Suspense fallback={null}>
      <SeriesEpisodeListClient />
    </Suspense>
  )
}
