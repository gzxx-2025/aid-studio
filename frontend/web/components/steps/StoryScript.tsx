'use client'

import { forwardRef, useCallback, useContext, useEffect, useImperativeHandle, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import {
  CommentOutlined,
  CopyOutlined,
  DeleteOutlined,
  FileTextOutlined,
  HistoryOutlined,
  RedoOutlined,
  UndoOutlined,
  UploadOutlined
} from '@ant-design/icons'
import { Button, Drawer, message, Modal, Spin } from 'antd'
import agentIconRaw from '~/assets/img/home/agent-icon.svg'
import RichTextEditor, { type RichTextEditorHandle } from '~/components/common/RichTextEditor'
import { getRouteLikeSnapshot } from '~/hooks/useRouteLike'
import { useStoryScriptAutoSave } from '~/hooks/useStoryScriptAutoSave'
import { useCreationStore } from '~/stores/creation'
import type { ScriptDetailRow } from '~/types/business-api'
import { assetUrl } from '~/utils/assetUrl'
import { userScriptDelete, userScriptList } from '~/utils/businessApi'
import {
  htmlPlainTextLength,
  htmlToPlainText,
  htmlPureTextCharCount,
  isHtmlContentEmpty,
  resolveStoryScriptEditorHtmlAfterApiLoad,
  storyScriptOriginalTextForApi,
  STORY_SCRIPT_MAX_CHARS_MOVIE,
  STORY_SCRIPT_MAX_CHARS_SERIES
} from '~/utils/htmlPlain'
import {
  formatEditorSelectionLocation,
  type EditorTextReplacement,
  type EditorTextSelection,
  type EditorTextSelectionChange
} from '~/utils/quill/editorTextSelection'
import { resolveStoryScriptSaveContext } from '~/utils/storyScriptSaveContext'
import {
  flushStoryScriptAutoSave,
  queueStoryScriptAutoSave,
  type StoryScriptFlushResult
} from '~/utils/storyScriptPersistence'
import { saveStoryScriptAsNewVersion } from '~/utils/storyScriptVersionSave'
import { formatStoryScriptAutosaveHint } from '~/utils/storyScriptAutosaveHint'
import { createFlowShellContext } from '~/utils/createFlowInjection'
import ImportScriptModal from './ImportScriptModal'
import './story-script.css'

const agentIconUrl = assetUrl(agentIconRaw)

interface Props {
  value: string
  description?: string
  onChange: (value: string) => void
  agentOpen?: boolean
  onAgentToggle?: () => void
  onAgentReference?: (selection: EditorTextSelection) => void
}

export interface StoryScriptHandle {
  applyAgentSelectionEdit: (
    selection: EditorTextSelection,
    replacement: string
  ) => 'applied' | 'stale' | 'empty' | 'limit' | 'unavailable'
  applyAgentSelectionEdits: (
    replacements: readonly EditorTextReplacement[]
  ) => 'applied' | 'stale' | 'empty' | 'limit' | 'unavailable'
}

const MAX_UNDO_HISTORY_SIZE = 50
const HISTORY_PAGE_SIZE = 20

function sortScriptVersions(rows: ScriptDetailRow[]): ScriptDetailRow[] {
  const statusRank = (status?: number) => (status === 1 ? 0 : status === 0 ? 1 : 2)
  return [...rows].sort((a, b) => {
    const rankDiff = statusRank(a.status) - statusRank(b.status)
    if (rankDiff !== 0) return rankDiff
    return Number(b.comicVersion || 0) - Number(a.comicVersion || 0)
  })
}

function confirmAction(title: string, content: string, okText: string): Promise<boolean> {
  return new Promise((resolve) => {
    Modal.confirm({
      title,
      content,
      okText,
      cancelText: '取消',
      onOk: () => resolve(true),
      onCancel: () => resolve(false)
    })
  })
}

export const StoryScript = forwardRef<StoryScriptHandle, Props>(function StoryScript(
  {
    value = '',
    description: _description = '撰写或导入剧本内容',
    onChange,
    agentOpen = false,
    onAgentToggle,
    onAgentReference
  },
  forwardedRef
) {
  const currentProjectType = useCreationStore((s) => s.currentProjectType)
  const createFlowShell = useContext(createFlowShellContext)
  const editorRef = useRef<RichTextEditorHandle | null>(null)

  /** 电影 1 万字 / 剧集 10 万字（纯文字，不含空格与标点） */
  const scriptMaxLength =
    currentProjectType === 'series' ? STORY_SCRIPT_MAX_CHARS_SERIES : STORY_SCRIPT_MAX_CHARS_MOVIE

  const [localContent, setLocalContent] = useState(value)
  const [lastExternalValue, setLastExternalValue] = useState(value)
  const [showHistoryPanel, setShowHistoryPanel] = useState(false)
  const [showImportModal, setShowImportModal] = useState(false)
  const [textSelection, setTextSelection] = useState<EditorTextSelectionChange | null>(null)

  useImperativeHandle(
    forwardedRef,
    () => ({
      applyAgentSelectionEdit: (selection, replacement) =>
        editorRef.current?.replaceTextSelection(selection, replacement) ?? 'unavailable',
      applyAgentSelectionEdits: (replacements) =>
        editorRef.current?.replaceTextSelections(replacements) ?? 'unavailable'
    }),
    []
  )

  const [expandedHistoryId, setExpandedHistoryId] = useState<number | null>(null)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyLoadingMore, setHistoryLoadingMore] = useState(false)
  const [historyPage, setHistoryPage] = useState(1)
  const [historyTotal, setHistoryTotal] = useState(0)
  const [historyVersions, setHistoryVersions] = useState<ScriptDetailRow[]>([])
  const [history, setHistory] = useState<{ stack: string[]; index: number }>({
    stack: [],
    index: -1
  })

  const canUndo = history.index > 0
  const canRedo = history.index < history.stack.length - 1
  const hasEditorText = !isHtmlContentEmpty(localContent)

  if (lastExternalValue !== value) {
    setLastExternalValue(value)
    if (textSelection) setTextSelection(null)
    if (localContent !== value) {
      setLocalContent(value)
      setHistory((current) =>
        current.stack.length === 0 ? { stack: [value], index: 0 } : current
      )
    }
  }

  const saveToHistory = (content: string) => {
    setHistory((current) => {
      if (current.stack[current.index] === content) return current
      const stack = current.stack.slice(0, current.index + 1)
      stack.push(content)
      if (stack.length > MAX_UNDO_HISTORY_SIZE) stack.shift()
      return { stack, index: stack.length - 1 }
    })
  }

  const applyEditorContent = (html: string) => {
    setLocalContent(html)
    onChange(html)
    saveToHistory(html)
  }

  const { saveState } = useStoryScriptAutoSave(localContent, applyEditorContent)
  const flushLatestEditorContent = useCallback(async (): Promise<StoryScriptFlushResult> => {
    const ctx = await resolveStoryScriptSaveContext(
      useCreationStore.getState(),
      getRouteLikeSnapshot()
    )
    if (!ctx) return 'error'
    queueStoryScriptAutoSave(ctx, storyScriptOriginalTextForApi(localContent))
    return flushStoryScriptAutoSave(ctx)
  }, [localContent])

  useEffect(() => {
    if (!createFlowShell?.registerStoryScriptFlush) return
    createFlowShell.registerStoryScriptFlush(flushLatestEditorContent)
    return () => createFlowShell.registerStoryScriptFlush?.(null)
  }, [createFlowShell, flushLatestEditorContent])

  const handleContentChange = (html: string) => {
    setLocalContent(html)
    onChange(html)
    if (history.stack.length === 0 || history.stack[history.index] !== html) {
      saveToHistory(html)
    }
  }

  const handleUndo = () => {
    if (!canUndo) return
    const newIndex = history.index - 1
    const content = history.stack[newIndex]
    setHistory({ stack: history.stack, index: newIndex })
    setLocalContent(content)
    onChange(content)
  }

  const handleRedo = () => {
    if (!canRedo) return
    const newIndex = history.index + 1
    const content = history.stack[newIndex]
    setHistory({ stack: history.stack, index: newIndex })
    setLocalContent(content)
    onChange(content)
  }

  const handleCopy = async () => {
    if (isHtmlContentEmpty(localContent)) {
      message.warning('没有内容可复制')
      return
    }
    try {
      await navigator.clipboard.writeText(htmlToPlainText(localContent))
      message.success('已复制到剪贴板')
    } catch {
      message.error('复制失败')
    }
  }

  const handleClear = () => {
    if (isHtmlContentEmpty(localContent)) return
    Modal.confirm({
      title: '确认清空',
      content: '确定要清空所有内容吗？此操作不可撤销。',
      onOk: () => {
        setLocalContent('')
        onChange('')
        setHistory({ stack: [''], index: 0 })
        message.success('已清空')
      }
    })
  }

  function beforeScriptImport(): Promise<boolean> {
    if (isHtmlContentEmpty(localContent)) return Promise.resolve(true)
    return confirmAction('覆盖确认', '确定要覆盖当前剧本内容吗？', '确定')
  }

  const handleImport = (content: string) => {
    const pureLen = htmlPureTextCharCount(content)
    if (pureLen > scriptMaxLength) {
      message.warning(
        `导入内容已超过字数上限（${scriptMaxLength.toLocaleString('zh-CN')}字），请删减后再导入`
      )
      return
    }
    applyEditorContent(content)
    message.success('导入成功')
    setShowImportModal(false)
  }

  async function resolveSaveContext() {
    return resolveStoryScriptSaveContext(useCreationStore.getState(), getRouteLikeSnapshot())
  }

  async function loadHistory(pageNum: number, append: boolean) {
    const ctx = await resolveSaveContext()
    if (!ctx) {
      message.warning('缺少项目信息')
      return
    }
    if (append) setHistoryLoadingMore(true)
    else setHistoryLoading(true)
    try {
      const result = await userScriptList({
        ...ctx,
        pageNum,
        pageSize: HISTORY_PAGE_SIZE
      })
      setHistoryVersions((current) => {
        const source = append ? [...current, ...result.rows] : result.rows
        const deduped = Array.from(new Map(source.map((row) => [row.id, row])).values())
        return sortScriptVersions(deduped)
      })
      setHistoryPage(pageNum)
      setHistoryTotal(result.total)
    } catch (error: unknown) {
      const err = error as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '加载历史版本失败')
    } finally {
      setHistoryLoading(false)
      setHistoryLoadingMore(false)
    }
  }

  const openHistoryPanel = async () => {
    setShowHistoryPanel(true)
    const result = await flushLatestEditorContent()
    if (result === 'conflict') message.warning('请先处理剧本内容冲突')
    await loadHistory(1, false)
  }

  const restoreVersion = async (version: ScriptDetailRow) => {
    if (version.status !== 2) return
    const flushResult = await flushLatestEditorContent()
    if (flushResult === 'conflict') {
      message.warning('请先处理剧本内容冲突')
      return
    }
    if (flushResult === 'error') {
      message.error('当前修改尚未同步，暂不能恢复版本')
      return
    }
    const confirmed = await confirmAction(
      '恢复历史版本',
      '恢复后会生成一个新的当前版本，原历史版本仍会保留。',
      '确认恢复'
    )
    if (!confirmed) return

    const ctx = await resolveSaveContext()
    if (!ctx) return
    try {
      const originalText = String(version.originalText ?? '')
      if (htmlPlainTextLength(originalText) === 0) {
        message.warning('空剧本不能保存为新版本')
        return
      }
      const row = await saveStoryScriptAsNewVersion(ctx, originalText, (server) => {
        applyEditorContent(
          resolveStoryScriptEditorHtmlAfterApiLoad(String(server?.originalText ?? ''), '')
        )
      })
      if (!row) return
      applyEditorContent(
        resolveStoryScriptEditorHtmlAfterApiLoad(String(row.originalText ?? originalText), '')
      )
      message.success('历史版本已恢复为新版本')
      await loadHistory(1, false)
    } catch (error: unknown) {
      const err = error as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '恢复历史版本失败')
    }
  }

  const deleteVersion = async (version: ScriptDetailRow) => {
    if (version.status !== 2) return
    const confirmed = await confirmAction(
      '删除历史版本',
      `确定删除版本 ${Number(version.comicVersion) || '-'} 吗？`,
      '删除'
    )
    if (!confirmed) return
    try {
      await userScriptDelete(Number(version.id))
      if (expandedHistoryId === version.id) setExpandedHistoryId(null)
      message.success('历史版本已删除')
      await loadHistory(1, false)
    } catch (error: unknown) {
      const err = error as { msg?: string; message?: string }
      message.error(err?.msg || err?.message || '删除历史版本失败')
    }
  }

  const formatTime = (time?: string | null) => {
    if (!time) return '-'
    const date = new Date(time.replace(' ', 'T'))
    if (Number.isNaN(date.getTime())) return time
    return date.toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    })
  }

  const historyTitle = (version: ScriptDetailRow) => {
    if (version.status === 1) return `当前工作副本 · 版本 ${Number(version.comicVersion) || 1}`
    if (version.status === 0) return `草稿 · 版本 ${Number(version.comicVersion) || '-'}`
    return `历史版本 ${Number(version.comicVersion) || '-'}`
  }

  const autosaveHintText = formatStoryScriptAutosaveHint(saveState.status, saveState.lastSavedAt)
  const autosaveHintClassName =
    saveState.status === 'error'
      ? ' is-error'
      : saveState.status === 'conflict'
        ? ' is-conflict'
        : ''
  const scriptCharCount = htmlPureTextCharCount(localContent)
  const scriptCharCountAtLimit = scriptCharCount >= scriptMaxLength

  return (
    <div className="story-script create-step-story-script">
      <div className="toolbar">
        <div className={`toolbar-left${hasEditorText ? ' toolbar-left--has-text' : ''}`}>
          <button className="toolbar-btn" onClick={handleUndo} disabled={!canUndo} title="撤销">
            <UndoOutlined />
            <span>撤销</span>
          </button>
          <button className="toolbar-btn" onClick={handleRedo} disabled={!canRedo} title="重做">
            <RedoOutlined />
            <span>重做</span>
          </button>
          <button className="toolbar-btn" onClick={handleCopy} title="复制">
            <CopyOutlined />
            <span>复制</span>
          </button>
          <button className="toolbar-btn" onClick={handleClear} title="清空">
            <DeleteOutlined />
            <span>清空</span>
          </button>
        </div>
        <div className="toolbar-right">
          {onAgentToggle ? (
            <div className={`agent-btn-slot${agentOpen ? ' is-collapsed' : ''}`}>
              <Button
                onClick={onAgentToggle}
                className="agent-btn"
                aria-label="打开 AI写剧本"
                aria-expanded={agentOpen}
                aria-hidden={agentOpen}
                tabIndex={agentOpen ? -1 : 0}
                icon={<img src={agentIconUrl} alt="" className="agent-btn__icon" />}
              >
                AI写剧本
              </Button>
            </div>
          ) : null}
          <Button
            onClick={() => setShowImportModal(true)}
            className="import-btn"
            icon={<UploadOutlined />}
          >
            导入剧本(单集)
          </Button>
          <Button onClick={openHistoryPanel} className="history-btn" icon={<HistoryOutlined />}>
            历史版本
          </Button>
        </div>
      </div>

      <div className="editor-container">
        {onAgentReference &&
        textSelection &&
        typeof document !== 'undefined' &&
        textSelection.anchor.bottom >= 0 &&
        textSelection.anchor.top <= window.innerHeight
          ? createPortal(
              <button
                type="button"
                className="story-script-selection-action"
                aria-label="针对选段批注"
                title={formatEditorSelectionLocation(textSelection.selection)}
                style={{
                  left: Math.min(
                    Math.max(12, textSelection.anchor.left + 8),
                    Math.max(12, window.innerWidth - 144)
                  ),
                  top:
                    textSelection.anchor.bottom + 44 <= window.innerHeight
                      ? textSelection.anchor.bottom + 8
                      : Math.max(8, textSelection.anchor.top - 40)
                }}
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => {
                  onAgentReference(textSelection.selection)
                  setTextSelection(null)
                }}
              >
                <CommentOutlined />
                针对选段批注
              </button>,
              document.body
            )
          : null}
        <RichTextEditor
          ref={editorRef}
          value={localContent}
          onChange={handleContentChange}
          className="script-editor"
          placeholder={`请输入本集剧本内容，或点击右上角「导入剧本(单集)」

提示:若为全集/多集内容,请按集拆分后分别创建作品导入`}
          minHeight="500px"
          maxLength={scriptMaxLength}
          countPureTextOnly
          showCount={false}
          onTextSelectionChange={setTextSelection}
        />
        <div className="story-script-editor-footer" aria-live="polite">
          <span
            className={`story-script-autosave-hint${autosaveHintClassName}`}
          >
            {autosaveHintText}
          </span>
          <span
            className={`story-script-char-count${scriptCharCountAtLimit ? ' is-limit' : ''}`}
          >
            {scriptCharCount}/{scriptMaxLength}
          </span>
        </div>
      </div>

      <Drawer
        open={showHistoryPanel}
        onClose={() => setShowHistoryPanel(false)}
        destroyOnHidden
        styles={{ mask: { pointerEvents: showHistoryPanel ? 'auto' : 'none' } }}
        placement="right"
        size={440}
        rootClassName="create-theme-drawer"
        className="history-drawer"
        title={
          <div className="drawer-title">
            <HistoryOutlined />
            <span>历史版本</span>
          </div>
        }
      >
        {historyLoading ? (
          <div className="history-loading"><Spin /></div>
        ) : historyVersions.length === 0 ? (
          <div className="empty-history">
            <FileTextOutlined className="empty-icon" />
            <p>暂无历史记录</p>
          </div>
        ) : (
          <div className="history-list">
            {historyVersions.map((version) => {
              const expanded = expandedHistoryId === version.id
              const content = String(version.originalText ?? '')
              return (
                <div
                  key={version.id}
                  className={`history-item${expanded ? ' active' : ''}`}
                  onClick={() => setExpandedHistoryId(expanded ? null : Number(version.id))}
                >
                  <div className="history-header">
                    <span className="history-title">{historyTitle(version)}</span>
                    <span className="history-time">
                      {formatTime(version.updateTime || version.createTime)}
                    </span>
                  </div>
                  <div className="history-preview">{content || '空内容'}</div>
                  {expanded && <pre className="history-full-content">{content || '空内容'}</pre>}
                  <div className="history-actions">
                    {version.status === 2 ? (
                      <>
                        <Button
                          size="small"
                          type="link"
                          className="story-script-history-action story-script-history-action--restore"
                          onClick={(event) => {
                            event.stopPropagation()
                            void restoreVersion(version)
                          }}
                        >
                          恢复此版本
                        </Button>
                        <Button
                          size="small"
                          type="link"
                          className="story-script-history-action story-script-history-action--delete"
                          onClick={(event) => {
                            event.stopPropagation()
                            void deleteVersion(version)
                          }}
                        >
                          删除
                        </Button>
                      </>
                    ) : (
                      <span className="history-readonly">只读</span>
                    )}
                  </div>
                </div>
              )
            })}
            {historyVersions.length < historyTotal && (
              <Button
                className="history-load-more"
                loading={historyLoadingMore}
                onClick={() => void loadHistory(historyPage + 1, true)}
              >
                加载更多
              </Button>
            )}
          </div>
        )}
      </Drawer>

      <ImportScriptModal
        open={showImportModal}
        acceptAssetType="script"
        onOpenChange={setShowImportModal}
        beforeScriptImport={beforeScriptImport}
        onImport={handleImport}
      />
    </div>
  )
})

export default StoryScript
