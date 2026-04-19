import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
import { invoke } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { FitAddon } from '@xterm/addon-fit'
import { Terminal } from '@xterm/xterm'
import { Icon, NerdIcon, glyphForFile } from './icons'
import './App.css'

type ConnectReply = {
  sessionId: string
  rootPath: string
}

type DirEntry = {
  name: string
  path: string
  size: number
  mtime: number
  isDir: boolean
  isLink: boolean
  mode: number
}

type SavedServer = {
  id: string
  name: string
  host: string
  port: number
  user: string
  password: string
  initScripts: InitScript[]
}

type InitScript = {
  id: string
  name: string
  workingDir: string
  content: string
}

type SessionTab = {
  sessionId: string
  serverId: string | null
  scriptId: string | null
  title: string
  view: 'terminal' | 'files'
  rootPath: string
  cwd: string
  entries: DirEntry[]
  fileChildren: Record<string, DirEntry[]>
  expandedDirs: string[]
  loadingDirs: string[]
  filesLoaded: boolean
  filesStatus: string
  filesBusy: boolean
  selectedPath: string | null
  status: string
  connected: boolean
}

type PreviewState =
  | { sessionId: string; kind: 'text'; name: string; content: string }
  | { sessionId: string; kind: 'image'; name: string; objectUrl: string }
  | { sessionId: string; kind: 'binary'; name: string; message: string }
  | null

type FileInfoState =
  | {
      title: string
      rows: Array<{ label: string; value: string }>
    }
  | null

type TextInputRequest = {
  title: string
  label: string
  confirmText?: string
  value: string
}

type SshOutputEvent = {
  sessionId: string
  bytes: number[]
}

type SessionMessageEvent = {
  sessionId: string
  message: string
}

type ServerDraft = {
  id?: string
  name: string
  host: string
  port: string
  user: string
  password: string
  initScripts: InitScript[]
}

type ThemePalette = {
  name: string
  displayName: string
  primary: string
  onPrimary: string
  primaryContainer: string
  onPrimaryContainer: string
  darkBackground: string
  darkSurface: string
  darkSurfaceVariant: string
  custom?: boolean
}

type CustomPaletteDraft = {
  id?: string
  displayName: string
  primary: string
  primaryContainer: string
  darkBackground: string
}

const STORAGE_KEY = 'simpssh.desktop.savedServers.v1'
const THEME_KEY = 'simpssh.desktop.theme.v1'
const LAST_CONN_KEY = 'simpssh.desktop.lastConnected.v1'
const CUSTOM_PALETTES_KEY = 'simpssh.desktop.customPalettes.v1'
const SIDEBAR_WIDTH_KEY = 'simpssh.desktop.sidebarWidth.v1'
const SIDEBAR_COLLAPSED_KEY = 'simpssh.desktop.sidebarCollapsed.v1'
const PREVIEW_MAX_BYTES = 2 * 1024 * 1024
const DOWNLOAD_MAX_BYTES = 64 * 1024 * 1024
const TERM_COLS = 120
const TERM_ROWS = 32

const PALETTES: ThemePalette[] = [
  {
    name: 'default',
    displayName: '普鲁士蓝',
    primary: '#4A8FD9',
    onPrimary: '#FFFFFF',
    primaryContainer: '#1A3A66',
    onPrimaryContainer: '#D7E3FF',
    darkBackground: '#0B1A33',
    darkSurface: '#11244A',
    darkSurfaceVariant: '#1A3661',
  },
  {
    name: 'dracula',
    displayName: '德古拉紫',
    primary: '#BD93F9',
    onPrimary: '#282A36',
    primaryContainer: '#44475A',
    onPrimaryContainer: '#F8F8F2',
    darkBackground: '#282A36',
    darkSurface: '#1E1F29',
    darkSurfaceVariant: '#44475A',
  },
  {
    name: 'monokai',
    displayName: '莫诺凯绿',
    primary: '#A6E22E',
    onPrimary: '#272822',
    primaryContainer: '#49483E',
    onPrimaryContainer: '#F8F8F2',
    darkBackground: '#272822',
    darkSurface: '#1E1F1C',
    darkSurfaceVariant: '#49483E',
  },
  {
    name: 'morninglight',
    displayName: '晨曦白',
    primary: '#0969DA',
    onPrimary: '#FFFFFF',
    primaryContainer: '#DDF4FF',
    onPrimaryContainer: '#0A3069',
    darkBackground: '#FAFBFC',
    darkSurface: '#F6F8FA',
    darkSurfaceVariant: '#EAEEF2',
  },
  {
    name: 'warmcream',
    displayName: '米色暖光',
    primary: '#859900',
    onPrimary: '#002B36',
    primaryContainer: '#EEE8D5',
    onPrimaryContainer: '#586E75',
    darkBackground: '#FDF6E3',
    darkSurface: '#F7F0D8',
    darkSurfaceVariant: '#EEE8D5',
  },
  {
    name: 'lightmist',
    displayName: '轻雾灰',
    primary: '#5C6BC0',
    onPrimary: '#FFFFFF',
    primaryContainer: '#E8EAF6',
    onPrimaryContainer: '#1A237E',
    darkBackground: '#F5F5F7',
    darkSurface: '#EBEDF0',
    darkSurfaceVariant: '#DFE2E8',
  },
]

const EMPTY_DRAFT: ServerDraft = {
  name: '',
  host: '',
  port: '22',
  user: '',
  password: '',
  initScripts: [],
}

const EMPTY_CUSTOM_PALETTE_DRAFT: CustomPaletteDraft = {
  displayName: '',
  primary: '#4A8FD9',
  primaryContainer: '#1A3A66',
  darkBackground: '#0B1A33',
}

function App() {
  const [savedServers, setSavedServers] = useState<SavedServer[]>(() => loadSavedServers())
  const [sessions, setSessions] = useState<SessionTab[]>([])
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null)
  const [globalStatus, setGlobalStatus] = useState('准备连接')
  const [busyServerId, setBusyServerId] = useState<string | null>(null)
  const [preview, setPreview] = useState<PreviewState>(null)
  const [fileInfo, setFileInfo] = useState<FileInfoState>(null)
  const [textInputRequest, setTextInputRequest] = useState<TextInputRequest | null>(null)
  const [guideOpen, setGuideOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [editorOpen, setEditorOpen] = useState(false)
  const [connectPickerServer, setConnectPickerServer] = useState<SavedServer | null>(null)
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({})
  const [tabMenu, setTabMenu] = useState<{ sessionId: string; x: number; y: number } | null>(null)
  const [sidebarWidth, setSidebarWidth] = useState(() => {
    const raw = window.localStorage.getItem(SIDEBAR_WIDTH_KEY)
    const value = raw ? Number(raw) : 260
    return Number.isFinite(value) ? Math.min(420, Math.max(220, value)) : 260
  })
  const [sidebarCollapsed, setSidebarCollapsed] = useState(
    () => window.localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1',
  )
  const [editorDraft, setEditorDraft] = useState<ServerDraft>(EMPTY_DRAFT)
  const [customPaletteEditorOpen, setCustomPaletteEditorOpen] = useState(false)
  const [customPaletteDraft, setCustomPaletteDraft] = useState<CustomPaletteDraft>(
    EMPTY_CUSTOM_PALETTE_DRAFT,
  )
  const [customPalettes, setCustomPalettes] = useState<ThemePalette[]>(() => loadCustomPalettes())
  const [themeName, setThemeName] = useState(() => loadThemeName())
  const [lastConnMap, setLastConnMap] = useState<Record<string, number>>(() => loadLastConnMap())
  const [uploadTargetDir, setUploadTargetDir] = useState<string | null>(null)

  const uploadInputRef = useRef<HTMLInputElement | null>(null)
  const textInputResolverRef = useRef<((value: string | null) => void) | null>(null)
  const sessionsRef = useRef<SessionTab[]>([])
  const closingSessionIdsRef = useRef(new Set<string>())
  const reconnectSessionTabRef = useRef<(tab: SessionTab) => Promise<void>>(async () => {})

  useEffect(() => {
    sessionsRef.current = sessions
  }, [sessions])

  useEffect(() => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(savedServers))
  }, [savedServers])

  useEffect(() => {
    window.localStorage.setItem(THEME_KEY, themeName)
  }, [themeName])

  useEffect(() => {
    window.localStorage.setItem(CUSTOM_PALETTES_KEY, JSON.stringify(customPalettes))
  }, [customPalettes])

  useEffect(() => {
    window.localStorage.setItem(LAST_CONN_KEY, JSON.stringify(lastConnMap))
  }, [lastConnMap])

  useEffect(() => {
    window.localStorage.setItem(SIDEBAR_WIDTH_KEY, String(sidebarWidth))
  }, [sidebarWidth])

  useEffect(() => {
    window.localStorage.setItem(SIDEBAR_COLLAPSED_KEY, sidebarCollapsed ? '1' : '0')
  }, [sidebarCollapsed])

  useEffect(() => {
    return () => {
      if (preview?.kind === 'image') {
        URL.revokeObjectURL(preview.objectUrl)
      }
    }
  }, [preview])

  useEffect(() => {
    let cancelled = false

    async function bindEvents() {
      const unlistenClosed = await listen<SessionMessageEvent>('session-closed', (event) => {
        if (closingSessionIdsRef.current.has(event.payload.sessionId)) {
          closingSessionIdsRef.current.delete(event.payload.sessionId)
          return
        }

        const closedSession = sessionsRef.current.find(
          (session) => session.sessionId === event.payload.sessionId,
        )
        if (!closedSession) return
        void reconnectSessionTabRef.current(closedSession)
      })

      const unlistenError = await listen<SessionMessageEvent>('session-error', (event) => {
        setSessions((current) => {
          if (!current.some((session) => session.sessionId === event.payload.sessionId)) {
            return current
          }
          return current.map((session) =>
            session.sessionId === event.payload.sessionId
              ? { ...session, status: `会话错误: ${event.payload.message}` }
              : session,
          )
        })
      })

      if (cancelled) {
        unlistenClosed()
        unlistenError()
        return
      }

      return () => {
        unlistenClosed()
        unlistenError()
      }
    }

    const cleanupPromise = bindEvents()

    return () => {
      cancelled = true
      void cleanupPromise.then((cleanup) => cleanup?.())
    }
  }, [])

  useEffect(() => {
    const blockNativeContextMenu = (event: MouseEvent) => {
      event.preventDefault()
    }

    window.addEventListener('contextmenu', blockNativeContextMenu, true)
    return () => {
      window.removeEventListener('contextmenu', blockNativeContextMenu, true)
    }
  }, [])

  useEffect(() => {
    if (!tabMenu) return

    const closeMenu = () => setTabMenu(null)
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setTabMenu(null)
    }

    window.addEventListener('click', closeMenu)
    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.removeEventListener('click', closeMenu)
      window.removeEventListener('keydown', onKeyDown)
    }
  }, [tabMenu])

  const palette = useMemo(
    () =>
      PALETTES.find((item) => item.name === themeName) ??
      customPalettes.find((item) => item.name === themeName) ??
      PALETTES[0],
    [customPalettes, themeName],
  )

  const themeVars = useMemo(() => {
    const lightPalette = isLightColor(palette.darkBackground)
    const accentSoft = isLightColor(palette.primaryContainer)
      ? palette.primaryContainer
      : rgbaFromHex(palette.primary, lightPalette ? 0.18 : 0.2)
    const bg = lightPalette ? palette.darkBackground : darkenHex(palette.darkBackground, 0.08)
    const bgElevated = lightPalette
      ? lightenHex(palette.darkSurface, 0.18)
      : lightenHex(palette.darkSurface, 0.04)
    const bgPanel = lightPalette
      ? palette.darkSurface
      : lightenHex(palette.darkSurface, 0.02)
    const bgSunken = lightPalette
      ? darkenHex(palette.darkSurfaceVariant, 0.02)
      : darkenHex(palette.darkBackground, 0.04)
    const bgHover = lightPalette
      ? darkenHex(palette.darkSurfaceVariant, 0.05)
      : lightenHex(palette.darkSurfaceVariant, 0.04)
    const bgActive = lightPalette
      ? darkenHex(palette.primaryContainer, 0.04)
      : lightenHex(palette.darkSurfaceVariant, 0.1)
    const fg = lightPalette ? '#0A0A0A' : '#F8F8F2'
    const fgSecondary = lightPalette ? '#545454' : '#D7DBE2'
    const fgTertiary = lightPalette ? '#8A8A85' : '#9EA7B3'
    const fgQuaternary = lightPalette ? '#B5B4AF' : '#6C7480'
    const border = lightPalette
      ? darkenHex(palette.darkSurfaceVariant, 0.08)
      : lightenHex(palette.darkSurfaceVariant, 0.06)
    const borderStrong = lightPalette
      ? darkenHex(palette.darkSurfaceVariant, 0.16)
      : lightenHex(palette.darkSurfaceVariant, 0.14)

    return {
      '--bg': bg,
      '--bg-elevated': bgElevated,
      '--bg-panel': bgPanel,
      '--bg-sunken': bgSunken,
      '--bg-hover': bgHover,
      '--bg-active': bgActive,
      '--fg': fg,
      '--fg-secondary': fgSecondary,
      '--fg-tertiary': fgTertiary,
      '--fg-quaternary': fgQuaternary,
      '--border': border,
      '--border-strong': borderStrong,
      '--border-focus': palette.primary,
      '--accent': palette.primary,
      '--accent-soft': accentSoft,
      '--term-bg': palette.darkBackground,
      '--term-fg': lightPalette ? '#17202f' : '#E8E6E1',
    } as CSSProperties
  }, [palette])

  const terminalTheme = useMemo(
    () => ({
      background: palette.darkBackground,
      foreground: isLightColor(palette.darkBackground) ? '#17202f' : '#E8E6E1',
      cursor: palette.primary,
      selectionBackground: rgbaFromHex(palette.primary, 0.28),
    }),
    [palette],
  )

  const activeSession = useMemo(
    () => sessions.find((session) => session.sessionId === activeSessionId) ?? null,
    [activeSessionId, sessions],
  )

  const updateSession = useCallback(
    (sessionId: string, updater: (session: SessionTab) => SessionTab) => {
      setSessions((current) =>
        current.map((session) => (session.sessionId === sessionId ? updater(session) : session)),
      )
    },
    [],
  )

  const reconnectSessionTab = useCallback(
    async (tab: SessionTab) => {
      const server = savedServers.find((item) => item.id === tab.serverId)
      if (!server) {
        updateSession(tab.sessionId, (session) => ({
          ...session,
          connected: false,
          status: '连接已关闭',
        }))
        return
      }

      const script = tab.scriptId
        ? server.initScripts.find((item) => item.id === tab.scriptId) ?? null
        : null

      updateSession(tab.sessionId, (session) => ({
        ...session,
        connected: false,
        status: '连接已断开，正在重连…',
      }))
      setGlobalStatus(`正在重连 ${tab.title}...`)

      try {
        const reply = await invoke<ConnectReply>('connect_session', {
          request: {
            host: server.host,
            port: server.port,
            user: server.user,
            password: server.password,
            cols: TERM_COLS,
            rows: TERM_ROWS,
            initScript: script
              ? {
                  workingDir: script.workingDir,
                  content: script.content,
                }
              : null,
          },
        })

        setSessions((current) =>
          current.map((session) =>
            session.sessionId === tab.sessionId
              ? {
                  ...session,
                  sessionId: reply.sessionId,
                  view: 'terminal',
                  rootPath: reply.rootPath,
                  cwd: reply.rootPath,
                  entries: [],
                  fileChildren: {},
                  expandedDirs: [],
                  loadingDirs: [],
                  filesLoaded: false,
                  filesStatus: '点击文件按钮后加载目录',
                  filesBusy: false,
                  selectedPath: null,
                  status: `已重连 ${session.title}`,
                  connected: true,
                }
              : session,
          ),
        )
        setActiveSessionId((current) => (current === tab.sessionId ? reply.sessionId : current))
        setServerErrors((current) => {
          if (!(server.id in current)) return current
          const next = { ...current }
          delete next[server.id]
          return next
        })
        setLastConnMap((current) => ({ ...current, [server.id]: Date.now() }))
        setGlobalStatus(`已重连 ${tab.title}`)
      } catch (error) {
        const message = `重连失败: ${formatError(error)}`
        updateSession(tab.sessionId, (session) => ({
          ...session,
          connected: false,
          status: message,
        }))
        setServerErrors((current) => ({ ...current, [server.id]: message }))
        setGlobalStatus(message)
      }
    },
    [savedServers, updateSession],
  )

  useEffect(() => {
    reconnectSessionTabRef.current = reconnectSessionTab
  }, [reconnectSessionTab])

  const requestTextInput = useCallback((request: TextInputRequest) => {
    setTextInputRequest(request)
    return new Promise<string | null>((resolve) => {
      textInputResolverRef.current = resolve
    })
  }, [])

  const loadDirectory = useCallback(
    async (sessionId: string, path: string, options?: { asRoot?: boolean }) => {
      const asRoot = options?.asRoot ?? true
      updateSession(sessionId, (session) => ({
        ...session,
        filesBusy: true,
        filesStatus: `正在载入 ${path}`,
        loadingDirs: session.loadingDirs.includes(path)
          ? session.loadingDirs
          : [...session.loadingDirs, path],
      }))
      try {
        const list = await invoke<DirEntry[]>('list_dir', { sessionId, path })
        updateSession(sessionId, (session) => ({
          ...session,
          cwd: asRoot ? path : session.cwd,
          entries: asRoot ? list : session.entries,
          fileChildren: { ...session.fileChildren, [path]: list },
          expandedDirs: asRoot
            ? []
            : session.expandedDirs.includes(path)
              ? session.expandedDirs
              : [...session.expandedDirs, path],
          loadingDirs: session.loadingDirs.filter((item) => item !== path),
          filesLoaded: true,
          filesBusy: false,
          filesStatus: `已载入 ${path}`,
          selectedPath: asRoot ? null : session.selectedPath,
          status: `已载入 ${path}`,
        }))
      } catch (error) {
        const message = `文件列表加载失败: ${formatError(error)}`
        updateSession(sessionId, (session) => ({
          ...session,
          loadingDirs: session.loadingDirs.filter((item) => item !== path),
          filesBusy: false,
          filesStatus: message,
          status: message,
        }))
      }
    },
    [updateSession],
  )

  const refreshDirectory = useCallback(
    async (sessionId: string, path: string) => {
      const session = sessions.find((item) => item.sessionId === sessionId)
      if (!session) return
      await loadDirectory(sessionId, path, { asRoot: path === session.cwd })
    },
    [loadDirectory, sessions],
  )

  const connectServer = useCallback(
    async (server: SavedServer, script: InitScript | null = null) => {
      setBusyServerId(server.id)
      setServerErrors((current) => {
        if (!(server.id in current)) return current
        const next = { ...current }
        delete next[server.id]
        return next
      })
      setGlobalStatus(
        `连接 ${server.user}@${server.host}${script ? ` · ${script.name || '未命名脚本'}` : ''}...`,
      )
      try {
        const reply = await invoke<ConnectReply>('connect_session', {
          request: {
            host: server.host,
            port: server.port,
            user: server.user,
            password: server.password,
            cols: TERM_COLS,
            rows: TERM_ROWS,
            initScript: script
              ? {
                  workingDir: script.workingDir,
                  content: script.content,
                }
              : null,
          },
        })
        const title = server.name.trim() || `${server.user}@${server.host}`
        const tab: SessionTab = {
          sessionId: reply.sessionId,
          serverId: server.id,
          scriptId: script?.id ?? null,
          title,
          view: 'terminal',
          rootPath: reply.rootPath,
          cwd: reply.rootPath,
          entries: [],
          fileChildren: {},
          expandedDirs: [],
          loadingDirs: [],
          filesLoaded: false,
          filesStatus: '点击文件按钮后加载目录',
          filesBusy: false,
          selectedPath: null,
          status: `已连接 ${title}`,
          connected: true,
        }
        setSessions((current) => [...current, tab])
        setActiveSessionId(reply.sessionId)
        setGlobalStatus(`已连接 ${title}`)
        setLastConnMap((current) => ({ ...current, [server.id]: Date.now() }))
      } catch (error) {
        console.error('connect_session failed:', error)
        const message = `连接失败: ${formatError(error)}`
        setServerErrors((current) => ({ ...current, [server.id]: message }))
        setGlobalStatus(message)
      } finally {
        setBusyServerId(null)
      }
    },
    [],
  )

  const closeSession = useCallback(
    async (sessionId: string) => {
      closingSessionIdsRef.current.add(sessionId)
      try {
        await invoke('disconnect_session', { sessionId })
      } catch (error) {
        console.error(error)
      }
      setSessions((current) => {
        const remaining = current.filter((session) => session.sessionId !== sessionId)
        if (activeSessionId === sessionId) {
          setActiveSessionId(remaining.at(-1)?.sessionId ?? null)
        }
        return remaining
      })
      window.setTimeout(() => {
        closingSessionIdsRef.current.delete(sessionId)
      }, 500)
    },
    [activeSessionId],
  )

  const saveServerDraft = useCallback(() => {
    const cleanedHost = editorDraft.host.trim()
    const cleanedUser = editorDraft.user.trim()
    if (!cleanedHost || !cleanedUser) return
      const saved: SavedServer = {
      id: editorDraft.id ?? crypto.randomUUID(),
      name: editorDraft.name.trim() || `${cleanedUser}@${cleanedHost}`,
      host: cleanedHost,
      port: Number(editorDraft.port) || 22,
      user: cleanedUser,
      password: editorDraft.password,
      initScripts: editorDraft.initScripts,
    }
    setSavedServers((current) => {
      const existing = current.find((server) => server.id === saved.id)
      if (existing) {
        return current.map((server) => (server.id === saved.id ? saved : server))
      }
      return [...current, saved]
    })
    setEditorOpen(false)
    setEditorDraft(EMPTY_DRAFT)
  }, [editorDraft])

  const previewEntry = useCallback(
    async (entry: DirEntry) => {
      if (entry.isDir || !activeSessionId) return
      updateSession(activeSessionId, (session) => ({
        ...session,
        filesBusy: true,
        filesStatus: `正在预览 ${entry.name}`,
      }))
      try {
        const maxBytes = Math.min(entry.size || PREVIEW_MAX_BYTES, PREVIEW_MAX_BYTES)
        const bytes = await invoke<number[]>('read_file', {
          sessionId: activeSessionId,
          path: entry.path,
          maxBytes,
        })
        const uint8 = new Uint8Array(bytes)
        const ext = entry.name.split('.').at(-1)?.toLowerCase() ?? ''
        if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp'].includes(ext)) {
          const objectUrl = URL.createObjectURL(new Blob([uint8]))
          setPreview((current) => {
            if (current?.kind === 'image') URL.revokeObjectURL(current.objectUrl)
            return { sessionId: activeSessionId, kind: 'image', name: entry.name, objectUrl }
          })
        } else {
          const text = new TextDecoder().decode(uint8)
          const badChars = [...text].filter((char) => char === '\uFFFD').length
          if (badChars > Math.max(8, Math.floor(text.length / 40))) {
            setPreview({
              sessionId: activeSessionId,
              kind: 'binary',
              name: entry.name,
              message: `该文件看起来不是文本文件，当前预览上限 ${formatBytes(maxBytes)}。`,
            })
          } else {
            setPreview({ sessionId: activeSessionId, kind: 'text', name: entry.name, content: text })
          }
        }
        updateSession(activeSessionId, (session) => ({
          ...session,
          filesBusy: false,
          filesStatus: `已预览 ${entry.name}`,
        }))
      } catch (error) {
        updateSession(activeSessionId, (session) => ({
          ...session,
          filesBusy: false,
          filesStatus: `预览失败: ${formatError(error)}`,
        }))
      }
    },
    [activeSessionId, updateSession],
  )

  const downloadEntry = useCallback(
    async (entry: DirEntry) => {
      if (!activeSessionId || entry.isDir) return
      if (entry.size > DOWNLOAD_MAX_BYTES) {
        alert(`当前下载功能只做了内存直读，先限制在 ${formatBytes(DOWNLOAD_MAX_BYTES)} 以内。`)
        return
      }
      updateSession(activeSessionId, (session) => ({
        ...session,
        filesBusy: true,
        filesStatus: `正在下载 ${entry.name}`,
      }))
      try {
        const bytes = await invoke<number[]>('read_file', {
          sessionId: activeSessionId,
          path: entry.path,
          maxBytes: entry.size,
        })
        const url = URL.createObjectURL(new Blob([new Uint8Array(bytes)]))
        const anchor = document.createElement('a')
        anchor.href = url
        anchor.download = entry.name
        anchor.click()
        URL.revokeObjectURL(url)
        updateSession(activeSessionId, (session) => ({
          ...session,
          filesBusy: false,
          filesStatus: `已下载 ${entry.name}`,
        }))
      } catch (error) {
        updateSession(activeSessionId, (session) => ({
          ...session,
          filesBusy: false,
          filesStatus: `下载失败: ${formatError(error)}`,
        }))
      }
    },
    [activeSessionId, updateSession],
  )

  const createDirectory = useCallback(async (targetDir?: string) => {
    if (!activeSession) return
    const name = await requestTextInput({
      title: '新建目录',
      label: '目录名称',
      confirmText: '确定',
      value: '',
    })
    if (!name) return
    const baseDir = targetDir ?? activeSession.cwd
    updateSession(activeSession.sessionId, (session) => ({
      ...session,
      filesBusy: true,
      filesStatus: `正在新建目录 ${name}`,
    }))
    try {
      await invoke('mkdir', {
        sessionId: activeSession.sessionId,
        path: joinPath(baseDir, name),
      })
      await refreshDirectory(activeSession.sessionId, baseDir)
    } catch (error) {
      updateSession(activeSession.sessionId, (session) => ({
        ...session,
        filesBusy: false,
        filesStatus: `新建目录失败: ${formatError(error)}`,
      }))
    }
  }, [activeSession, refreshDirectory, requestTextInput, updateSession])

  const renameEntry = useCallback(
    async (entry: DirEntry) => {
      if (!activeSession) return
      const nextName = await requestTextInput({
        title: '重命名',
        label: '新名称',
        confirmText: '确定',
        value: entry.name,
      })
      if (!nextName || nextName === entry.name) return
      const parentPath = parentOf(entry.path)
      updateSession(activeSession.sessionId, (session) => ({
        ...session,
        filesBusy: true,
        filesStatus: `正在重命名 ${entry.name}`,
      }))
      try {
        await invoke('rename_path', {
          sessionId: activeSession.sessionId,
          from: entry.path,
          to: joinPath(parentPath, nextName),
        })
        await refreshDirectory(activeSession.sessionId, parentPath)
      } catch (error) {
        updateSession(activeSession.sessionId, (session) => ({
          ...session,
          filesBusy: false,
          filesStatus: `重命名失败: ${formatError(error)}`,
        }))
      }
    },
    [activeSession, refreshDirectory, requestTextInput, updateSession],
  )

  const deleteEntry = useCallback(
    async (entry: DirEntry) => {
      if (!activeSession) return
      if (!window.confirm(`确认删除 ${entry.name}？`)) return
      const parentPath = parentOf(entry.path)
      updateSession(activeSession.sessionId, (session) => ({
        ...session,
        filesBusy: true,
        filesStatus: `正在删除 ${entry.name}`,
      }))
      try {
        await invoke('delete_path', {
          sessionId: activeSession.sessionId,
          path: entry.path,
          isDir: entry.isDir,
        })
        await refreshDirectory(activeSession.sessionId, parentPath)
      } catch (error) {
        updateSession(activeSession.sessionId, (session) => ({
          ...session,
          filesBusy: false,
          filesStatus: `删除失败: ${formatError(error)}`,
        }))
      }
    },
    [activeSession, refreshDirectory, updateSession],
  )

  const triggerUpload = useCallback((targetDir?: string) => {
    setUploadTargetDir(targetDir ?? activeSession?.cwd ?? null)
    uploadInputRef.current?.click()
  }, [activeSession?.cwd])

  const uploadFile = useCallback(
    async (file: File | null) => {
      if (!file || !activeSession) return
      const targetDir = uploadTargetDir ?? activeSession.cwd
      updateSession(activeSession.sessionId, (session) => ({
        ...session,
        filesBusy: true,
        filesStatus: `正在上传 ${file.name}`,
      }))
      try {
        const bytes = Array.from(new Uint8Array(await file.arrayBuffer()))
        await invoke('write_file', {
          sessionId: activeSession.sessionId,
          path: joinPath(targetDir, file.name),
          bytes,
        })
        await refreshDirectory(activeSession.sessionId, targetDir)
      } catch (error) {
        updateSession(activeSession.sessionId, (session) => ({
          ...session,
          filesBusy: false,
          filesStatus: `上传失败: ${formatError(error)}`,
        }))
      } finally {
        setUploadTargetDir(null)
      }
    },
    [activeSession, refreshDirectory, updateSession, uploadTargetDir],
  )

  const switchRootDirectory = useCallback(async () => {
    if (!activeSession) return
    const nextRoot = await requestTextInput({
      title: '切换根路径',
      label: '目录地址',
      confirmText: '确定',
      value: activeSession.cwd || activeSession.rootPath,
    })
    if (!nextRoot) return
    await loadDirectory(activeSession.sessionId, nextRoot.trim(), { asRoot: true })
  }, [activeSession, loadDirectory, requestTextInput])

  const copyPathToClipboard = useCallback(
    async (path: string) => {
      if (!activeSession) return
      try {
        await navigator.clipboard.writeText(path)
        updateSession(activeSession.sessionId, (session) => ({
          ...session,
          filesStatus: `已复制路径 ${path}`,
        }))
      } catch (error) {
        updateSession(activeSession.sessionId, (session) => ({
          ...session,
          filesStatus: `复制路径失败: ${formatError(error)}`,
        }))
      }
    },
    [activeSession, updateSession],
  )

  const showFileInfo = useCallback((entry: DirEntry | null, rootPath: string) => {
    if (!entry) {
      setFileInfo({
        title: '目录信息',
        rows: [
          { label: '路径', value: rootPath },
          { label: '类型', value: '目录根路径' },
        ],
      })
      return
    }

    setFileInfo({
      title: entry.name,
      rows: [
        { label: '路径', value: entry.path },
        { label: '类型', value: entry.isDir ? '目录' : entry.isLink ? '符号链接' : '文件' },
        { label: '大小', value: entry.isDir ? '-' : formatBytes(entry.size) },
        { label: '修改时间', value: entry.mtime ? new Date(entry.mtime * 1000).toLocaleString() : '-' },
        { label: '权限', value: entry.mode ? `0${entry.mode.toString(8)}` : '-' },
      ],
    })
  }, [])

  const deleteServer = useCallback((id: string) => {
    setSavedServers((current) => current.filter((server) => server.id !== id))
    setLastConnMap((current) => {
      const { [id]: _omit, ...rest } = current
      return rest
    })
  }, [])

  const openEditorFor = useCallback((server: SavedServer | null) => {
    setEditorDraft(
      server
        ? {
            id: server.id,
            name: server.name,
            host: server.host,
            port: String(server.port),
            user: server.user,
            password: server.password,
            initScripts: server.initScripts,
          }
        : EMPTY_DRAFT,
    )
    setEditorOpen(true)
  }, [])

  const openCustomPaletteEditor = useCallback((paletteToEdit: ThemePalette | null) => {
    setCustomPaletteDraft(
      paletteToEdit
        ? {
            id: paletteToEdit.name,
            displayName: paletteToEdit.displayName,
            primary: paletteToEdit.primary,
            primaryContainer: paletteToEdit.primaryContainer,
            darkBackground: paletteToEdit.darkBackground,
          }
        : EMPTY_CUSTOM_PALETTE_DRAFT,
    )
    setCustomPaletteEditorOpen(true)
  }, [])

  const saveCustomPalette = useCallback(() => {
    const displayName = customPaletteDraft.displayName.trim()
    if (!displayName) return
    const nextPalette = buildCustomPalette({
      id: customPaletteDraft.id,
      displayName,
      primary: customPaletteDraft.primary,
      primaryContainer: customPaletteDraft.primaryContainer,
      darkBackground: customPaletteDraft.darkBackground,
    })
    setCustomPalettes((current) => {
      const idx = current.findIndex((item) => item.name === nextPalette.name)
      if (idx >= 0) {
        return current.map((item) => (item.name === nextPalette.name ? nextPalette : item))
      }
      return [...current, nextPalette]
    })
    setThemeName(nextPalette.name)
    setCustomPaletteEditorOpen(false)
    setCustomPaletteDraft(EMPTY_CUSTOM_PALETTE_DRAFT)
  }, [customPaletteDraft])

  const deleteCustomPalette = useCallback(
    (paletteToDelete: ThemePalette) => {
      if (!window.confirm(`确认删除配色“${paletteToDelete.displayName}”？`)) return
      setCustomPalettes((current) => current.filter((item) => item.name !== paletteToDelete.name))
      if (themeName === paletteToDelete.name) {
        setThemeName('default')
      }
    },
    [themeName],
  )

  const activeServerId = activeSession?.serverId ?? null

  const handleOpenServer = useCallback(
    (server: SavedServer) => {
      if (server.initScripts.length > 0) {
        setConnectPickerServer(server)
        return
      }
      void connectServer(server)
    },
    [connectServer],
  )

  const handleConnectWithScript = useCallback(
    (server: SavedServer, script: InitScript | null) => {
      setConnectPickerServer(null)
      void connectServer(server, script)
    },
    [connectServer],
  )

  const renameSession = useCallback(
    (sessionId: string) => {
      const session = sessions.find((item) => item.sessionId === sessionId)
      if (!session) return
      const nextTitle = window.prompt('修改会话名', session.title)
      if (!nextTitle) return
      const trimmed = nextTitle.trim()
      if (!trimmed) return
      updateSession(sessionId, (current) => ({ ...current, title: trimmed }))
    },
    [sessions, updateSession],
  )

  const setSessionView = useCallback(
    async (sessionId: string, view: SessionTab['view']) => {
      const session = sessions.find((item) => item.sessionId === sessionId)
      if (!session) return
      updateSession(sessionId, (current) => ({ ...current, view }))
      if (view === 'files' && !session.filesLoaded) {
        await loadDirectory(sessionId, session.cwd || session.rootPath, { asRoot: true })
      }
    },
    [loadDirectory, sessions, updateSession],
  )

  const toggleDirectory = useCallback(
    async (sessionId: string, entry: DirEntry) => {
      const session = sessions.find((item) => item.sessionId === sessionId)
      if (!session || !entry.isDir) return

      updateSession(sessionId, (current) => ({
        ...current,
        selectedPath: entry.path,
      }))

      if (session.expandedDirs.includes(entry.path)) {
        updateSession(sessionId, (current) => ({
          ...current,
          expandedDirs: current.expandedDirs.filter((path) => path !== entry.path),
        }))
        return
      }

      if (session.fileChildren[entry.path]) {
        updateSession(sessionId, (current) => ({
          ...current,
          expandedDirs: current.expandedDirs.includes(entry.path)
            ? current.expandedDirs
            : [...current.expandedDirs, entry.path],
        }))
        return
      }

      await loadDirectory(sessionId, entry.path, { asRoot: false })
    },
    [loadDirectory, sessions, updateSession],
  )

  const toggleSidebar = useCallback(() => {
    setSidebarCollapsed((current) => !current)
  }, [])

  const startSidebarResize = useCallback(() => {
    if (sidebarCollapsed) return

    const onMouseMove = (event: MouseEvent) => {
      const nextWidth = Math.min(420, Math.max(220, event.clientX))
      setSidebarWidth(nextWidth)
    }

    const stopResize = () => {
      window.removeEventListener('mousemove', onMouseMove)
      window.removeEventListener('mouseup', stopResize)
    }

    window.addEventListener('mousemove', onMouseMove)
    window.addEventListener('mouseup', stopResize)
  }, [sidebarCollapsed])

  return (
    <div className="app" style={themeVars}>
      <div className="titlebar">
        <div className="brand">
          <strong>simpssh</strong>
        </div>
        <div className="titlebar-spacer" />
        <div className="titlebar-actions">
          <button
            type="button"
            className="titlebar-btn"
            title="操作指南"
            onClick={() => setGuideOpen(true)}
          >
            <Icon.Help size={14} />
          </button>
          <button
            type="button"
            className="titlebar-btn"
            title="设置"
            onClick={() => setSettingsOpen(true)}
          >
            <Icon.Settings size={14} />
          </button>
        </div>
      </div>

      <div className="main">
        <div
          className={`sidebar-frame${sidebarCollapsed ? ' collapsed' : ''}`}
          style={{ width: sidebarCollapsed ? 0 : sidebarWidth }}
        >
          <Sidebar
            servers={savedServers}
            activeServerId={activeServerId}
            serverErrors={serverErrors}
            busyServerId={busyServerId}
            onOpenHost={handleOpenServer}
            onNewHost={() => openEditorFor(null)}
            onToggleCollapse={toggleSidebar}
          />
        </div>

        <div
          className={`sidebar-divider${sidebarCollapsed ? ' collapsed' : ''}`}
          onMouseDown={() => startSidebarResize()}
        >
          {sidebarCollapsed && (
            <button
              type="button"
              className="sidebar-toggle"
              title="展开侧栏"
              onClick={(event) => {
                event.stopPropagation()
                toggleSidebar()
              }}
            >
              <span className="sidebar-toggle-icon collapsed">
                <Icon.ChevronRight size={11} />
              </span>
            </button>
          )}
        </div>

        <div className="workspace">
          <div className="tabbar">
            {sessions.map((session) => (
              <div
                key={session.sessionId}
                className={`tab${session.sessionId === activeSessionId ? ' active' : ''}`}
                onClick={() => setActiveSessionId(session.sessionId)}
                onContextMenu={(event) => {
                  event.preventDefault()
                  setTabMenu({ sessionId: session.sessionId, x: event.clientX, y: event.clientY })
                }}
              >
                <span className="tab-title">{session.title}</span>
              </div>
            ))}
            <div className="tabbar-spacer" />
          </div>

          {tabMenu && (
            <div
              className="tab-context-menu"
              style={{ left: tabMenu.x, top: tabMenu.y }}
              onClick={(event) => event.stopPropagation()}
            >
              <button
                type="button"
                onClick={() => {
                  renameSession(tabMenu.sessionId)
                  setTabMenu(null)
                }}
              >
                修改会话名
              </button>
              <button
                type="button"
                onClick={() => {
                  void closeSession(tabMenu.sessionId)
                  setTabMenu(null)
                }}
              >
                关闭
              </button>
            </div>
          )}

          <div className="view">
            {activeSession ? (
              <SessionView
                session={activeSession}
                preview={preview?.sessionId === activeSession.sessionId ? preview : null}
                terminalTheme={terminalTheme}
                onSetView={(view) => void setSessionView(activeSession.sessionId, view)}
                onToggleDirectory={(entry) => void toggleDirectory(activeSession.sessionId, entry)}
                onSelectEntry={(path) =>
                  updateSession(activeSession.sessionId, (session) => ({
                    ...session,
                    selectedPath: path,
                  }))
                }
                onCreateDirectory={(dir) => void createDirectory(dir)}
                onTriggerUpload={triggerUpload}
                onPreviewEntry={(entry) => void previewEntry(entry)}
                onDownloadEntry={(entry) => void downloadEntry(entry)}
                onRenameEntry={(entry) => void renameEntry(entry)}
                onDeleteEntry={(entry) => void deleteEntry(entry)}
                onRefreshDirectory={(path) => void refreshDirectory(activeSession.sessionId, path)}
                onSwitchRoot={() => void switchRootDirectory()}
                onCopyPath={(path) => void copyPathToClipboard(path)}
                onShowInfo={(entry) => showFileInfo(entry, activeSession.cwd)}
                onClosePreview={() => {
                  if (preview?.kind === 'image') URL.revokeObjectURL(preview.objectUrl)
                  setPreview(null)
                }}
                allSessions={sessions}
              />
              ) : (
                <WelcomeView
                  servers={savedServers}
                  lastConnMap={lastConnMap}
                  sessionServerIds={new Set(sessions.filter((s) => s.connected).map((s) => s.serverId ?? ''))}
                  onOpen={handleOpenServer}
                  onOpenConnectPicker={setConnectPickerServer}
                  onNew={() => openEditorFor(null)}
                  onEdit={(server) => openEditorFor(server)}
                  onDelete={deleteServer}
                  busyServerId={busyServerId}
                />
            )}
          </div>
        </div>
      </div>

      <div className="statusbar">
        <div className="item ok">
          <span>●</span>
          <span>{globalStatus}</span>
        </div>
        <div className="sep" />
        <div className="item">
          {sessions.filter((s) => s.connected).length} 个会话运行中
        </div>
        <div className="right">
          <div className="item">
            <span className="swatch" />
            <span>{palette.displayName}</span>
          </div>
          <div className="sep" />
          <div className="item">v0.6.13</div>
        </div>
      </div>

      <input
        ref={uploadInputRef}
        className="hidden-input"
        type="file"
        onChange={(event) => {
          void uploadFile(event.target.files?.[0] ?? null)
          event.currentTarget.value = ''
        }}
      />

        {editorOpen && (
          <ServerEditorModal
            draft={editorDraft}
            onChange={setEditorDraft}
          onClose={() => {
            setEditorOpen(false)
            setEditorDraft(EMPTY_DRAFT)
          }}
          onSave={saveServerDraft}
        />
      )}

      {guideOpen && <GuideModal onClose={() => setGuideOpen(false)} />}

        {settingsOpen && (
          <SettingsModal
            paletteName={themeName}
            customPalettes={customPalettes}
            onPickPalette={setThemeName}
            onAddCustom={() => openCustomPaletteEditor(null)}
            onEditCustom={openCustomPaletteEditor}
            onDeleteCustom={deleteCustomPalette}
            onClose={() => setSettingsOpen(false)}
          />
        )}

        {customPaletteEditorOpen && (
          <CustomPaletteEditorModal
            draft={customPaletteDraft}
            onChange={setCustomPaletteDraft}
            onClose={() => {
              setCustomPaletteEditorOpen(false)
              setCustomPaletteDraft(EMPTY_CUSTOM_PALETTE_DRAFT)
            }}
            onSave={saveCustomPalette}
          />
        )}

      {fileInfo && <FileInfoModal info={fileInfo} onClose={() => setFileInfo(null)} />}

      {textInputRequest && (
        <TextInputModal
          request={textInputRequest}
          onClose={() => {
            textInputResolverRef.current?.(null)
            textInputResolverRef.current = null
            setTextInputRequest(null)
          }}
          onSubmit={(value) => {
            textInputResolverRef.current?.(value)
            textInputResolverRef.current = null
            setTextInputRequest(null)
          }}
        />
      )}

      {connectPickerServer && (
        <ConnectScriptModal
          server={connectPickerServer}
          onClose={() => setConnectPickerServer(null)}
          onConnect={(script) => handleConnectWithScript(connectPickerServer, script)}
        />
      )}
    </div>
  )
}

function Sidebar({
  servers,
  activeServerId,
  serverErrors,
  busyServerId,
  onOpenHost,
  onNewHost,
  onToggleCollapse,
}: {
  servers: SavedServer[]
  activeServerId: string | null
  serverErrors: Record<string, string>
  busyServerId: string | null
  onOpenHost: (server: SavedServer) => void
  onNewHost: () => void
  onToggleCollapse: () => void
}) {
  return (
    <aside className="sidebar">
        <div className="sidebar-header">
          主机
          <div className="actions">
            <button type="button" className="icon-btn" title="收起侧栏" onClick={onToggleCollapse}>
              <span className="sidebar-toggle-icon">
                <Icon.ChevronRight size={14} />
              </span>
            </button>
            <button type="button" className="icon-btn" title="新建主机" onClick={onNewHost}>
              <Icon.Plus size={14} />
            </button>
          </div>
        </div>
        <div className="sidebar-scroll">
          {servers.length === 0 ? (
            <div className="sidebar-empty">
              还没有主机。右上角「+」添加第一台。
            </div>
          ) : (
            servers.map((server) => {
              const selected = server.id === activeServerId
              const busy = busyServerId === server.id
              return (
                <div key={server.id} className="host-entry">
                  <button
                    type="button"
                    className={`host-row${selected ? ' selected' : ''}`}
                    onClick={() => onOpenHost(server)}
                    title={`${server.user}@${server.host}`}
                  >
                    <div className="lines">
                      <div className="name">{server.name || server.host}</div>
                      <div
                        className={`addr${serverErrors[server.id] ? ' error' : ''}${busy ? ' busy' : ''}`}
                      >
                        {busy
                          ? '连接中…'
                          : serverErrors[server.id] ?? `${server.user}@${server.host}`}
                      </div>
                    </div>
                  </button>
                </div>
              )
            })
          )}
      </div>
    </aside>
  )
}

function WelcomeView({
  servers,
  lastConnMap,
  sessionServerIds,
  onOpen,
  onOpenConnectPicker,
  onNew,
  onEdit,
  onDelete,
  busyServerId,
}: {
  servers: SavedServer[]
  lastConnMap: Record<string, number>
  sessionServerIds: Set<string>
  onOpen: (server: SavedServer) => void
  onOpenConnectPicker: (server: SavedServer) => void
  onNew: () => void
  onEdit: (server: SavedServer) => void
  onDelete: (id: string) => void
  busyServerId: string | null
}) {
  return (
    <div className="welcome">
      <div className="welcome-main">
        {servers.length === 0 ? (
          <div className="empty-welcome">
            <div className="big">
              <Icon.Server size={20} />
            </div>
            <strong>还没有主机</strong>
            <span>添加第一台 SSH 主机开始。</span>
            <div style={{ marginTop: 12 }}>
              <button type="button" className="btn primary" onClick={onNew}>
                <Icon.Plus size={12} /> 新建主机
              </button>
            </div>
          </div>
        ) : (
          <div className="host-grid">
            {servers.map((server) => (
              <HostCard
                key={server.id}
                server={server}
                online={sessionServerIds.has(server.id)}
                busy={busyServerId === server.id}
                lastConn={lastConnMap[server.id]}
                onOpen={() => onOpen(server)}
                onOpenConnectPicker={() => onOpenConnectPicker(server)}
                onEdit={() => onEdit(server)}
                onDelete={() => onDelete(server.id)}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

function HostCard({
  server,
  online,
  busy,
  lastConn,
  onOpen,
  onOpenConnectPicker,
  onEdit,
  onDelete,
}: {
  server: SavedServer
  online: boolean
  busy: boolean
  lastConn: number | undefined
  onOpen: () => void
  onOpenConnectPicker: () => void
  onEdit: () => void
  onDelete: () => void
}) {
  return (
    <div className="host-card">
      <button type="button" className="host-card-main" onClick={onOpen}>
        <div className="row1">
          <div className="host-lines">
            <div className="name">{server.name || server.host}</div>
            <div className="addr">{server.user}@{server.host}</div>
          </div>
          <div
            className="row-actions"
            onClick={(event) => event.stopPropagation()}
          >
            {server.initScripts.length > 0 && (
              <button
                type="button"
                className="icon-btn"
                title="连接方式"
                onClick={onOpenConnectPicker}
              >
                <Icon.Terminal size={12} />
              </button>
            )}
            <button
              type="button"
              className="icon-btn"
              title="编辑"
              onClick={onEdit}
            >
              <Icon.Edit size={12} />
            </button>
            <button
              type="button"
              className="icon-btn"
              title="删除"
              onClick={() => {
                if (window.confirm(`删除「${server.name || server.host}」？`)) onDelete()
              }}
            >
              <Icon.Trash size={12} />
            </button>
          </div>
        </div>
        <div className="footer">
          {busy ? (
            <span style={{ color: 'var(--accent)' }}>正在连接…</span>
          ) : online ? (
            <span className="chip accent">在线</span>
          ) : lastConn ? (
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
              <Icon.Clock size={10} /> 最近 {formatTimeAgo(lastConn)}
            </span>
          ) : (
            <span style={{ color: 'var(--fg-quaternary)' }}>未连接过</span>
          )}
        </div>
      </button>
    </div>
  )
}

function ConnectScriptModal({
  server,
  onClose,
  onConnect,
}: {
  server: SavedServer
  onClose: () => void
  onConnect: (script: InitScript | null) => void
}) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(event) => event.stopPropagation()}>
        <div className="modal-header">
          <h2>连接方式</h2>
          <p>{server.name || server.host}</p>
        </div>
        <div className="modal-body">
          <div className="connect-script-list">
            <button type="button" className="host-script-item" onClick={() => onConnect(null)}>
              <span>默认</span>
              <small>~</small>
            </button>
            {server.initScripts.map((script) => (
              <button
                type="button"
                key={script.id}
                className="host-script-item"
                onClick={() => onConnect(script)}
              >
                <span>{script.name || '未命名脚本'}</span>
                <small>{script.workingDir || '主目录'}</small>
              </button>
            ))}
          </div>
        </div>
        <div className="modal-footer">
          <button type="button" className="btn" onClick={onClose}>
            取消
          </button>
        </div>
      </div>
    </div>
  )
}

function SessionView({
  session,
  preview,
  terminalTheme,
  onSetView,
  onToggleDirectory,
  onSelectEntry,
  onCreateDirectory,
  onTriggerUpload,
  onPreviewEntry,
  onDownloadEntry,
  onRenameEntry,
  onDeleteEntry,
  onRefreshDirectory,
  onSwitchRoot,
  onCopyPath,
  onShowInfo,
  onClosePreview,
  allSessions,
}: {
  session: SessionTab
  preview: Exclude<PreviewState, null> | null
  terminalTheme: {
    background: string
    foreground: string
    cursor: string
    selectionBackground: string
  }
  onSetView: (view: SessionTab['view']) => void
  onToggleDirectory: (entry: DirEntry) => void
  onSelectEntry: (path: string) => void
  onCreateDirectory: (dir?: string) => void
  onTriggerUpload: (dir?: string) => void
  onPreviewEntry: (entry: DirEntry) => void
  onDownloadEntry: (entry: DirEntry) => void
  onRenameEntry: (entry: DirEntry) => void
  onDeleteEntry: (entry: DirEntry) => void
  onRefreshDirectory: (path: string) => void
  onSwitchRoot: () => void
  onCopyPath: (path: string) => void
  onShowInfo: (entry: DirEntry | null) => void
  onClosePreview: () => void
  allSessions: SessionTab[]
}) {
  const fileRows = useMemo(() => flattenFileTree(session), [session])
  const [fileMenu, setFileMenu] = useState<{ entry: DirEntry | null; x: number; y: number } | null>(null)

  useEffect(() => {
    if (!fileMenu) return

    const closeMenu = () => setFileMenu(null)
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setFileMenu(null)
    }

    window.addEventListener('click', closeMenu)
    window.addEventListener('keydown', onKeyDown)
    return () => {
      window.removeEventListener('click', closeMenu)
      window.removeEventListener('keydown', onKeyDown)
    }
  }, [fileMenu])

  return (
    <div className="session-view">
      <div className="session-toolbar">
        <div className="session-view-switch">
          <button
            type="button"
            className={`session-view-btn${session.view === 'terminal' ? ' active' : ''}`}
            onClick={() => onSetView('terminal')}
            title="终端"
          >
            <Icon.Terminal size={13} />
          </button>
          <button
            type="button"
            className={`session-view-btn${session.view === 'files' ? ' active' : ''}`}
            onClick={() => onSetView('files')}
            title="文件"
          >
            <Icon.Folder size={13} />
          </button>
        </div>
        <span className="session-toolbar-path" title={session.cwd}>
          {session.cwd}
        </span>
        <div className={`session-conn-state${session.connected ? '' : ' offline'}`}>
          {session.connected ? '已连接' : '已断开'}
        </div>
      </div>

      <div className="session-content">
        <div className={`session-term${session.view === 'terminal' ? '' : ' hidden'}`}>
          <div className="session-term-body">
            {allSessions.map((s) => (
              <SessionTerminal
                key={s.sessionId}
                sessionId={s.sessionId}
                active={s.sessionId === session.sessionId && session.view === 'terminal'}
                theme={terminalTheme}
              />
            ))}
          </div>
        </div>

        <section className={`files-view${session.view === 'files' ? '' : ' hidden'}`}>
          <div className="files-header">
            <span className="path" title={session.cwd}>
              {session.cwd}
            </span>
            <span className={`files-status-inline${session.filesBusy ? ' busy' : ''}`}>
              {session.filesStatus}
            </span>
          </div>

          {preview ? (
            <FilePreviewPane preview={preview} onClose={onClosePreview} />
          ) : (
            <div
              className="file-list"
              onContextMenu={(event) => {
                if (event.target !== event.currentTarget) return
                event.preventDefault()
                setFileMenu({ entry: null, x: event.clientX, y: event.clientY })
              }}
            >
              {fileRows.length === 0 ? (
                <div className="file-empty">
                  {session.filesLoaded ? '目录为空。' : '点击上方文件按钮后首次加载。'}
                </div>
              ) : (
                fileRows.map((row) => (
                  <button
                    type="button"
                    key={row.entry.path}
                    className={`file-row${row.entry.isDir ? ' dir' : ''}${
                      session.selectedPath === row.entry.path ? ' selected' : ''
                    }`}
                    onContextMenu={(event) => {
                      event.preventDefault()
                      setFileMenu({ entry: row.entry, x: event.clientX, y: event.clientY })
                    }}
                    onClick={() => {
                      onSelectEntry(row.entry.path)
                      if (row.entry.isDir) {
                        onToggleDirectory(row.entry)
                      }
                    }}
                    style={{ ['--tree-depth' as string]: row.depth }}
                  >
                    <span className={`twist${row.entry.isDir ? '' : ' placeholder'}${row.expanded ? ' expanded' : ''}`}>
                      {row.entry.isDir ? (
                        row.loading ? <span className="twist-loading">…</span> : <Icon.ChevronRight size={10} />
                      ) : null}
                    </span>
                    <span className="icon">
                      <NerdIcon
                        glyph={row.entry.isDir ? '\uF07B' : glyphForFile(row.entry.name)}
                        size={13}
                      />
                    </span>
                    <span className="name">{row.entry.name}</span>
                    <span className="size">
                      {row.entry.isDir ? '' : formatBytes(row.entry.size)}
                    </span>
                  </button>
                ))
              )}
            </div>
          )}

          {fileMenu && (
            <div
              className="tab-context-menu file-context-menu"
              style={{ left: fileMenu.x, top: fileMenu.y }}
              onClick={(event) => event.stopPropagation()}
            >
              {fileMenu.entry ? (
                <>
                  {!fileMenu.entry.isDir && (
                    <>
                      <button
                        type="button"
                        onClick={() => {
                          onPreviewEntry(fileMenu.entry!)
                          setFileMenu(null)
                        }}
                      >
                        预览
                      </button>
                      <button
                        type="button"
                        onClick={() => {
                          onDownloadEntry(fileMenu.entry!)
                          setFileMenu(null)
                        }}
                      >
                        下载
                      </button>
                    </>
                  )}
                  {fileMenu.entry.isDir && (
                    <>
                      <button
                        type="button"
                        onClick={() => {
                          onRefreshDirectory(fileMenu.entry!.path)
                          setFileMenu(null)
                        }}
                      >
                        刷新此目录
                      </button>
                      <button
                        type="button"
                        onClick={() => {
                          onCreateDirectory(fileMenu.entry!.path)
                          setFileMenu(null)
                        }}
                      >
                        在此新建目录
                      </button>
                      <button
                        type="button"
                        onClick={() => {
                          onTriggerUpload(fileMenu.entry!.path)
                          setFileMenu(null)
                        }}
                      >
                        上传到此处
                      </button>
                    </>
                  )}
                  <button
                    type="button"
                    onClick={() => {
                      onCopyPath(fileMenu.entry!.path)
                      setFileMenu(null)
                    }}
                  >
                    复制路径
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      onShowInfo(fileMenu.entry)
                      setFileMenu(null)
                    }}
                  >
                    文件信息
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      onRenameEntry(fileMenu.entry!)
                      setFileMenu(null)
                    }}
                  >
                    重命名
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      onDeleteEntry(fileMenu.entry!)
                      setFileMenu(null)
                    }}
                  >
                    删除
                  </button>
                </>
              ) : (
                <>
                  <button
                    type="button"
                    onClick={() => {
                      onCopyPath(session.cwd)
                      setFileMenu(null)
                    }}
                  >
                    复制路径
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      onShowInfo(null)
                      setFileMenu(null)
                    }}
                  >
                    文件信息
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      onSwitchRoot()
                      setFileMenu(null)
                    }}
                  >
                    切换根路径
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      onRefreshDirectory(session.cwd)
                      setFileMenu(null)
                    }}
                  >
                    刷新
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      onCreateDirectory(session.cwd)
                      setFileMenu(null)
                    }}
                  >
                    在此新建目录
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      onTriggerUpload(session.cwd)
                      setFileMenu(null)
                    }}
                  >
                    上传到此处
                  </button>
                </>
              )}
            </div>
          )}
        </section>
      </div>
    </div>
  )
}

function SessionTerminal({
  sessionId,
  active,
  theme,
}: {
  sessionId: string
  active: boolean
  theme: {
    background: string
    foreground: string
    cursor: string
    selectionBackground: string
  }
}) {
  const hostRef = useRef<HTMLDivElement | null>(null)
  const terminalRef = useRef<Terminal | null>(null)
  const fitAddonRef = useRef<FitAddon | null>(null)
  const resizeObserverRef = useRef<ResizeObserver | null>(null)

  useEffect(() => {
    const terminal = new Terminal({
      cursorBlink: true,
      convertEol: true,
      fontFamily: '"Sarasa Mono SC", "Cascadia Mono", "Consolas", "SFMono-Regular", monospace',
      fontSize: 13,
      theme,
    })
    const fitAddon = new FitAddon()
    terminal.loadAddon(fitAddon)
    terminalRef.current = terminal
    fitAddonRef.current = fitAddon

    const host = hostRef.current
    if (host) {
      terminal.open(host)
      fitAddon.fit()
      resizeObserverRef.current = new ResizeObserver(() => {
        void resizeTerminal(sessionId, terminalRef.current, fitAddonRef.current)
      })
      resizeObserverRef.current.observe(host)
    }

    terminal.onData((data) => {
      void invoke('write_input', { sessionId, input: data }).catch((error) => console.error(error))
    })

    return () => {
      resizeObserverRef.current?.disconnect()
      terminal.dispose()
    }
  }, [sessionId])

  useEffect(() => {
    if (!terminalRef.current) return
    terminalRef.current.options.theme = theme
    terminalRef.current.refresh(0, Math.max(0, terminalRef.current.rows - 1))
  }, [theme])

  useEffect(() => {
    if (!active) return
    const timer = window.setTimeout(() => {
      void resizeTerminal(sessionId, terminalRef.current, fitAddonRef.current)
    }, 30)
    return () => window.clearTimeout(timer)
  }, [active, sessionId])

  useEffect(() => {
    let cancelled = false

    async function bindEvents() {
      const unlistenOutput = await listen<SshOutputEvent>('ssh-output', (event) => {
        if (event.payload.sessionId !== sessionId) return
        terminalRef.current?.write(Uint8Array.from(event.payload.bytes))
      })

      const unlistenClosed = await listen<SessionMessageEvent>('session-closed', (event) => {
        if (event.payload.sessionId !== sessionId) return
        terminalRef.current?.writeln('\r\n[session closed]')
      })

      if (cancelled) {
        unlistenOutput()
        unlistenClosed()
        return
      }

      return () => {
        unlistenOutput()
        unlistenClosed()
      }
    }

    const cleanupPromise = bindEvents()
    return () => {
      cancelled = true
      void cleanupPromise.then((cleanup) => cleanup?.())
    }
  }, [sessionId])

  return (
    <div
      ref={hostRef}
      className="terminal-canvas"
      style={{ display: active ? 'block' : 'none' }}
    />
  )
}

function ServerEditorModal({
  draft,
  onChange,
  onClose,
  onSave,
}: {
  draft: ServerDraft
  onChange: (draft: ServerDraft) => void
  onClose: () => void
  onSave: () => void
}) {
  const canSave = draft.host.trim() !== '' && draft.user.trim() !== ''

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(event) => event.stopPropagation()}>
        <div className="modal-header">
          <h2>{draft.id ? '编辑主机' : '新建主机'}</h2>
          <p>保存后会出现在侧栏和首页。</p>
        </div>
        <div className="modal-body">
          <div className="field">
            <label className="label">名称（可选）</label>
            <input
              className="input"
              value={draft.name}
              onChange={(event) => onChange({ ...draft, name: event.target.value })}
              placeholder="生产机 / 开发机"
            />
          </div>
          <div className="field">
            <label className="label">IP 或域名</label>
            <input
              className="input"
              value={draft.host}
              onChange={(event) => onChange({ ...draft, host: event.target.value })}
              placeholder="192.168.1.8"
            />
          </div>
          <div className="field-row">
            <div className="field">
              <label className="label">用户名</label>
              <input
                className="input"
                value={draft.user}
                onChange={(event) => onChange({ ...draft, user: event.target.value })}
                placeholder="root"
              />
            </div>
            <div className="field">
              <label className="label">端口</label>
              <input
                className="input"
                value={draft.port}
                onChange={(event) =>
                  onChange({ ...draft, port: event.target.value.replace(/[^\d]/g, '') || '22' })
                }
              />
            </div>
          </div>
          <div className="field">
            <label className="label">密码</label>
            <input
              className="input"
              type="password"
              value={draft.password}
              onChange={(event) => onChange({ ...draft, password: event.target.value })}
              placeholder="目前仅支持密码认证"
            />
            <div className="hint">当前只支持密码登录。密钥登录会在后续版本补上。</div>
          </div>

          <div className="field">
            <label className="label">初始化脚本</label>
            <div className="script-list">
              {draft.initScripts.map((script, index) => (
                <ScriptEditor
                  key={script.id}
                  script={script}
                  onChange={(nextScript) =>
                    onChange({
                      ...draft,
                      initScripts: draft.initScripts.map((item) =>
                        item.id === script.id ? nextScript : item,
                      ),
                    })
                  }
                  onDelete={() =>
                    onChange({
                      ...draft,
                      initScripts: draft.initScripts.filter((item) => item.id !== script.id),
                    })
                  }
                  index={index}
                />
              ))}
            </div>
            <button
              type="button"
              className="btn"
              onClick={() =>
                onChange({
                  ...draft,
                  initScripts: [
                    ...draft.initScripts,
                    {
                      id: crypto.randomUUID(),
                      name: '新脚本',
                      workingDir: '',
                      content: '',
                    },
                  ],
                })
              }
            >
              <Icon.Plus size={12} /> 添加一条脚本
            </button>
            <div className="hint">连接时可选默认模式或任一脚本。工作目录为空时使用 home。</div>
          </div>
        </div>
        <div className="modal-footer">
          <button type="button" className="btn" onClick={onClose}>
            取消
          </button>
          <button type="button" className="btn primary" onClick={onSave} disabled={!canSave}>
            保存
          </button>
        </div>
      </div>
    </div>
  )
}

function ScriptEditor({
  script,
  onChange,
  onDelete,
  index,
}: {
  script: InitScript
  onChange: (script: InitScript) => void
  onDelete: () => void
  index: number
}) {
  return (
    <div className="script-editor">
      <div className="script-editor-head">
        <strong>脚本 {index + 1}</strong>
        <button type="button" className="icon-btn" title="删除脚本" onClick={onDelete}>
          <Icon.Trash size={12} />
        </button>
      </div>
      <div className="field">
        <label className="label">脚本名称</label>
        <input
          className="input"
          value={script.name}
          onChange={(event) => onChange({ ...script, name: event.target.value })}
          placeholder="部署 / 开发 / 日志"
        />
      </div>
      <div className="field">
        <label className="label">工作目录（可选）</label>
        <input
          className="input"
          value={script.workingDir}
          onChange={(event) => onChange({ ...script, workingDir: event.target.value })}
          placeholder="~/work 或 /opt/app；空 = home"
        />
      </div>
      <div className="field" style={{ marginBottom: 0 }}>
        <label className="label">命令</label>
        <textarea
          className="textarea"
          value={script.content}
          onChange={(event) => onChange({ ...script, content: event.target.value })}
          placeholder={'# 一行一条\nsource venv/bin/activate\nnpm run dev'}
        />
      </div>
    </div>
  )
}

function GuideModal({ onClose }: { onClose: () => void }) {
  const rows: [string, string][] = [
    ['单击主机卡片 / 侧栏主机', '直接连接并新开 SSH 会话标签'],
    ['脚本按钮', '额外点一下再选择默认方式或初始化脚本'],
    ['卡片悬停', '显示编辑 / 删除按钮'],
    ['标签栏', '切换会话；右键可修改会话名或关闭'],
    ['初始化脚本', '可为每台主机保存工作目录和启动命令'],
    ['连接失败', '会在工作区顶部直接显示错误信息'],
  ]

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(event) => event.stopPropagation()}>
        <div className="modal-header">
          <h2>操作指南</h2>
          <p>桌面端保留和 Android 一致的入口和配色。</p>
        </div>
        <div className="modal-body">
          {rows.map(([title, desc]) => (
            <div key={title} className="guide-row">
              <div>
                <strong>{title}</strong>
                <span>{desc}</span>
              </div>
            </div>
          ))}
        </div>
        <div className="modal-footer">
          <button type="button" className="btn primary" onClick={onClose}>
            明白了
          </button>
        </div>
      </div>
    </div>
  )
}

function SettingsModal({
  paletteName,
  customPalettes,
  onPickPalette,
  onAddCustom,
  onEditCustom,
  onDeleteCustom,
  onClose,
}: {
  paletteName: string
  customPalettes: ThemePalette[]
  onPickPalette: (name: string) => void
  onAddCustom: () => void
  onEditCustom: (palette: ThemePalette) => void
  onDeleteCustom: (palette: ThemePalette) => void
  onClose: () => void
}) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal wide" onClick={(event) => event.stopPropagation()}>
        <div className="modal-header">
          <h2>设置</h2>
          <p>配色与 Android 版保持一致。切换后整屏立即生效。</p>
        </div>
        <div className="modal-body">
          <div className="settings-section-title">内置</div>
          <div className="palette-list">
            {PALETTES.map((p) => (
              <button
                type="button"
                key={p.name}
                className={`palette-card${p.name === paletteName ? ' active' : ''}`}
                onClick={() => onPickPalette(p.name)}
              >
                <div className="palette-toprow">
                  <div className="palette-copy">
                    <strong>{p.displayName}</strong>
                  </div>
                  <span className="palette-radio" aria-hidden />
                </div>
                <PalettePreview palette={p} />
              </button>
            ))}
          </div>

          <div className="settings-section-title custom-heading-row">
            <span>自定义</span>
            <button type="button" className="btn sm" onClick={onAddCustom}>
              <Icon.Plus size={12} /> 新建配色
            </button>
          </div>
          <div className="palette-list">
            {customPalettes.length === 0 ? (
              <div className="palette-empty">还没有自定义配色。</div>
            ) : (
              customPalettes.map((p) => (
                <div key={p.name} className={`palette-card${p.name === paletteName ? ' active' : ''}`}>
                  <button
                    type="button"
                    className="palette-select"
                    onClick={() => onPickPalette(p.name)}
                  >
                    <div className="palette-toprow">
                      <div className="palette-copy">
                        <strong>{p.displayName}</strong>
                      </div>
                      <span className="palette-radio" aria-hidden />
                    </div>
                    <PalettePreview palette={p} />
                  </button>
                  <div className="palette-card-actions">
                    <button type="button" className="btn sm" onClick={() => onEditCustom(p)}>
                      <Icon.Edit size={12} /> 编辑
                    </button>
                    <button type="button" className="btn sm danger" onClick={() => onDeleteCustom(p)}>
                      <Icon.Trash size={12} /> 删除
                    </button>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>
        <div className="modal-footer">
          <button type="button" className="btn primary" onClick={onClose}>
            完成
          </button>
        </div>
      </div>
    </div>
  )
}

function CustomPaletteEditorModal({
  draft,
  onChange,
  onClose,
  onSave,
}: {
  draft: CustomPaletteDraft
  onChange: (draft: CustomPaletteDraft) => void
  onClose: () => void
  onSave: () => void
}) {
  const canSave = draft.displayName.trim() !== ''

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(event) => event.stopPropagation()}>
        <div className="modal-header">
          <h2>{draft.id ? '编辑自定义配色' : '新建自定义配色'}</h2>
          <p>与 Android 一样，只需要设置主色、容器色和终端背景。</p>
        </div>
        <div className="modal-body">
          <div className="field">
            <label className="label">配色名称</label>
            <input
              className="input"
              value={draft.displayName}
              onChange={(event) => onChange({ ...draft, displayName: event.target.value })}
              placeholder="夜幕青 / 纸墨灰"
            />
          </div>
          <div className="color-grid">
            <ColorField
              label="主色"
              value={draft.primary}
              onChange={(value) => onChange({ ...draft, primary: value })}
            />
            <ColorField
              label="容器色"
              value={draft.primaryContainer}
              onChange={(value) => onChange({ ...draft, primaryContainer: value })}
            />
            <ColorField
              label="终端背景"
              value={draft.darkBackground}
              onChange={(value) => onChange({ ...draft, darkBackground: value })}
            />
          </div>
          <div className="field">
            <label className="label">预览</label>
            <PalettePreview palette={buildCustomPalette(draft)} />
          </div>
        </div>
        <div className="modal-footer">
          <button type="button" className="btn" onClick={onClose}>
            取消
          </button>
          <button type="button" className="btn primary" onClick={onSave} disabled={!canSave}>
            保存
          </button>
        </div>
      </div>
    </div>
  )
}

function ColorField({
  label,
  value,
  onChange,
}: {
  label: string
  value: string
  onChange: (value: string) => void
}) {
  return (
    <div className="field">
      <label className="label">{label}</label>
      <div className="color-field">
        <input type="color" value={value} onChange={(event) => onChange(event.target.value)} />
        <input
          className="input"
          value={value.toUpperCase()}
          onChange={(event) => onChange(normalizeHex(event.target.value, value))}
        />
      </div>
    </div>
  )
}

function PalettePreview({ palette }: { palette: ThemePalette }) {
  const surface = palette.darkSurface
  const background = palette.darkBackground
  const foreground = isLightColor(background) ? '#243041' : '#F8F8F2'
  const muted = isLightColor(background) ? '#687588' : 'rgba(248, 248, 242, 0.65)'
  const prompt = palette.primary

  return (
    <div className="palette-preview" style={{ background }}>
      <div className="palette-preview-bar" style={{ background: surface }}>
        <span className="mac-dot red" />
        <span className="mac-dot yellow" />
        <span className="mac-dot green" />
      </div>
      <div className="palette-preview-body">
        <div className="preview-line">
          <span style={{ color: prompt }}>$</span>
          <span style={{ color: foreground }}> ls -la</span>
        </div>
        <div className="preview-line">
          <span style={{ color: muted }}>drwxr-xr-x</span>
          <span style={{ color: foreground }}> src/</span>
        </div>
        <div className="preview-line">
          <span style={{ color: muted }}>-rw-r--r--</span>
          <span style={{ color: foreground }}> README.md</span>
        </div>
        <div className="preview-line">
          <span style={{ color: prompt }}>$</span>
          <span className="preview-caret" />
        </div>
      </div>
    </div>
  )
}

function FilePreviewPane({
  preview,
  onClose,
}: {
  preview: Exclude<PreviewState, null>
  onClose: () => void
}) {
  return (
    <div className="preview-pane">
      <div className="preview-header">
        <h3>{preview.name}</h3>
        <button type="button" className="btn sm" onClick={onClose}>
          返回列表
        </button>
      </div>
      <div className="preview-body">
        {preview.kind === 'text' ? (
          <pre>{preview.content}</pre>
        ) : preview.kind === 'image' ? (
          <img src={preview.objectUrl} alt={preview.name} />
        ) : (
          <div className="placeholder">{preview.message}</div>
        )}
      </div>
    </div>
  )
}

function FileInfoModal({ info, onClose }: { info: NonNullable<FileInfoState>; onClose: () => void }) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal small" onClick={(event) => event.stopPropagation()}>
        <div className="modal-header">
          <div>
            <h3>{info.title}</h3>
          </div>
        </div>
        <div className="modal-body">
          <div className="file-info-grid">
            {info.rows.map((row) => (
              <div key={row.label} className="file-info-row">
                <span>{row.label}</span>
                <strong title={row.value}>{row.value}</strong>
              </div>
            ))}
          </div>
        </div>
        <div className="modal-footer">
          <button type="button" className="btn" onClick={onClose}>
            完成
          </button>
        </div>
      </div>
    </div>
  )
}

function TextInputModal({
  request,
  onClose,
  onSubmit,
}: {
  request: TextInputRequest
  onClose: () => void
  onSubmit: (value: string | null) => void
}) {
  const [value, setValue] = useState(request.value)

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal small" onClick={(event) => event.stopPropagation()}>
        <div className="modal-header">
          <div>
            <h3>{request.title}</h3>
          </div>
        </div>
        <div className="modal-body">
          <div className="field">
            <label className="label">{request.label}</label>
            <input
              autoFocus
              className="input"
              value={value}
              onChange={(event) => setValue(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  const trimmed = value.trim()
                  onSubmit(trimmed || null)
                }
              }}
            />
          </div>
        </div>
        <div className="modal-footer">
          <button type="button" className="btn" onClick={onClose}>
            取消
          </button>
          <button
            type="button"
            className="btn primary"
            onClick={() => {
              const trimmed = value.trim()
              onSubmit(trimmed || null)
            }}
          >
            {request.confirmText ?? '确定'}
          </button>
        </div>
      </div>
    </div>
  )
}

async function resizeTerminal(
  sessionId: string,
  terminal: Terminal | null,
  fitAddon: FitAddon | null,
) {
  if (!terminal || !fitAddon) return
  fitAddon.fit()
  if (terminal.cols <= 0 || terminal.rows <= 0) return
  await invoke('resize_session', {
    sessionId,
    cols: terminal.cols,
    rows: terminal.rows,
  })
}

function formatBytes(size: number) {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${Math.floor(size / 1024)} KB`
  if (size < 1024 * 1024 * 1024) return `${Math.floor(size / (1024 * 1024))} MB`
  return `${Math.floor(size / (1024 * 1024 * 1024))} GB`
}

function formatTimeAgo(ms: number): string {
  if (!ms) return ''
  const diff = Date.now() - ms
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < 2 * 86_400_000) return '昨天'
  if (diff < 7 * 86_400_000) return `${Math.floor(diff / 86_400_000)} 天前`
  const d = new Date(ms)
  return `${d.getMonth() + 1}/${d.getDate()}`
}

function loadSavedServers(): SavedServer[] {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as Partial<SavedServer>[]
    return Array.isArray(parsed)
      ? parsed
          .filter(
            (item) =>
              typeof item?.id === 'string' &&
              typeof item?.host === 'string' &&
              typeof item?.user === 'string',
          )
          .map((item) => ({
            id: item.id!,
            name: typeof item.name === 'string' ? item.name : '',
            host: item.host!,
            port: typeof item.port === 'number' ? item.port : 22,
            user: item.user!,
            password: typeof item.password === 'string' ? item.password : '',
            initScripts: Array.isArray(item.initScripts)
              ? item.initScripts
                  .filter((script) => typeof script?.id === 'string')
                  .map((script) => ({
                    id: script.id,
                    name: typeof script.name === 'string' ? script.name : '',
                    workingDir:
                      typeof script.workingDir === 'string' ? script.workingDir : '',
                    content: typeof script.content === 'string' ? script.content : '',
                  }))
              : [],
          }))
      : []
  } catch {
    return []
  }
}

function loadThemeName() {
  return window.localStorage.getItem(THEME_KEY) ?? 'default'
}

function loadCustomPalettes(): ThemePalette[] {
  try {
    const raw = window.localStorage.getItem(CUSTOM_PALETTES_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as ThemePalette[]
    return Array.isArray(parsed)
      ? parsed.filter(
          (item) =>
            typeof item?.name === 'string' &&
            typeof item?.displayName === 'string' &&
            typeof item?.primary === 'string' &&
            typeof item?.primaryContainer === 'string' &&
            typeof item?.darkBackground === 'string',
        )
      : []
  } catch {
    return []
  }
}

function loadLastConnMap(): Record<string, number> {
  try {
    const raw = window.localStorage.getItem(LAST_CONN_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as Record<string, number>
    return typeof parsed === 'object' && parsed !== null ? parsed : {}
  } catch {
    return {}
  }
}

function parentOf(path: string) {
  if (!path || path === '/') return '/'
  const trimmed = path.endsWith('/') ? path.slice(0, -1) : path
  const idx = trimmed.lastIndexOf('/')
  if (idx <= 0) return '/'
  return trimmed.slice(0, idx)
}

function joinPath(parent: string, name: string) {
  const base = parent.endsWith('/') ? parent : `${parent}/`
  return `${base}${name}`
}

function formatError(error: unknown) {
  if (error instanceof Error) return error.message
  if (typeof error === 'string') return error
  return JSON.stringify(error)
}

function flattenFileTree(session: SessionTab) {
  const rows: Array<{ entry: DirEntry; depth: number; expanded: boolean; loading: boolean }> = []
  const walk = (parentPath: string, depth: number) => {
    const entries =
      parentPath === session.cwd ? session.fileChildren[parentPath] ?? session.entries : session.fileChildren[parentPath] ?? []
    for (const entry of entries) {
      const expanded = session.expandedDirs.includes(entry.path)
      const loading = session.loadingDirs.includes(entry.path)
      rows.push({ entry, depth, expanded, loading })
      if (entry.isDir && expanded) {
        walk(entry.path, depth + 1)
      }
    }
  }

  walk(session.cwd, 0)
  return rows
}

function isLightColor(hex: string) {
  const rgb = hexToRgb(hex)
  const l = (rgb.r * 0.299 + rgb.g * 0.587 + rgb.b * 0.114) / 255
  return l > 0.55
}

function lightenHex(hex: string, amount: number) {
  const { r, g, b } = hexToRgb(hex)
  return rgbToHex(
    Math.round(r + (255 - r) * amount),
    Math.round(g + (255 - g) * amount),
    Math.round(b + (255 - b) * amount),
  )
}

function darkenHex(hex: string, amount: number) {
  const { r, g, b } = hexToRgb(hex)
  return rgbToHex(
    Math.round(r * (1 - amount)),
    Math.round(g * (1 - amount)),
    Math.round(b * (1 - amount)),
  )
}

function hexToRgb(hex: string) {
  const sanitized = hex.replace('#', '')
  const full =
    sanitized.length === 3
      ? sanitized
          .split('')
          .map((c) => c + c)
          .join('')
      : sanitized
  return {
    r: parseInt(full.slice(0, 2), 16),
    g: parseInt(full.slice(2, 4), 16),
    b: parseInt(full.slice(4, 6), 16),
  }
}

function rgbToHex(r: number, g: number, b: number) {
  const toHex = (value: number) => value.toString(16).padStart(2, '0')
  return `#${toHex(r)}${toHex(g)}${toHex(b)}`
}

function rgbaFromHex(hex: string, alpha: number) {
  const { r, g, b } = hexToRgb(hex)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

function normalizeHex(value: string, fallback: string) {
  const normalized = value.trim().replace(/[^0-9a-fA-F#]/g, '')
  const withHash = normalized.startsWith('#') ? normalized : `#${normalized}`
  return /^#[0-9a-fA-F]{6}$/.test(withHash) ? withHash : fallback
}

function bestForeground(hex: string) {
  return isLightColor(hex) ? '#0A0A0A' : '#FFFFFF'
}

function buildCustomPalette(draft: CustomPaletteDraft): ThemePalette {
  const id = draft.id ?? `custom-${crypto.randomUUID().slice(0, 8)}`
  const background = normalizeHex(draft.darkBackground, '#0B1A33')
  const primary = normalizeHex(draft.primary, '#4A8FD9')
  const primaryContainer = normalizeHex(draft.primaryContainer, '#1A3A66')
  return {
    name: id,
    displayName: draft.displayName.trim() || '自定义配色',
    primary,
    onPrimary: bestForeground(primary),
    primaryContainer,
    onPrimaryContainer: bestForeground(primaryContainer),
    darkBackground: background,
    darkSurface: lightenHex(background, 0.05),
    darkSurfaceVariant: lightenHex(background, 0.1),
    custom: true,
  }
}

export default App
