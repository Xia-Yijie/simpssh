// Shared types used across App.tsx and feature modules. Split out of App.tsx
// so future sub-components can import without tugging on the monolith.

export type ConnectReply = {
  sessionId: string
  rootPath: string
}

export type DirEntry = {
  name: string
  path: string
  size: number
  mtime: number
  isDir: boolean
  isLink: boolean
  mode: number
}

export type InitScript = {
  id: string
  name: string
  workingDir: string
  content: string
}

export type SavedServer = {
  id: string
  name: string
  host: string
  port: number
  user: string
  password: string
  initScripts: InitScript[]
}

export type SessionTab = {
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

export type PreviewState =
  | { sessionId: string; path: string; kind: 'text'; name: string; content: string; language: string }
  | { sessionId: string; path: string; kind: 'image'; name: string; objectUrl: string }
  | { sessionId: string; path: string; kind: 'binary'; name: string; message: string }
  | null

export type FileInfoState =
  | {
      title: string
      rows: Array<{ label: string; value: string }>
    }
  | null

export type TextInputRequest = {
  title: string
  label: string
  confirmText?: string
  value: string
}

export type SshOutputEvent = {
  sessionId: string
  bytes: number[]
}

export type SessionMessageEvent = {
  sessionId: string
  message: string
}

export type ServerDraft = {
  id?: string
  name: string
  host: string
  port: string
  user: string
  password: string
  initScripts: InitScript[]
}

export type ThemePalette = {
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

export type CustomPaletteDraft = {
  id?: string
  displayName: string
  primary: string
  primaryContainer: string
  darkBackground: string
}
