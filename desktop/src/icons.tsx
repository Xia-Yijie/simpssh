import type { CSSProperties, ReactElement } from 'react'

// 2 MB Nerd Symbols font is bundled via @font-face; keep glyphs in sync with android/NerdIcons.kt.
export const NerdGlyphs = {
  // 文件/文件夹
  FOLDER: '\uF07B',
  FOLDER_OPEN: '\uF07C',
  FILE: '\uF15B',
  FOLDER_PLUS: '\uEEC7',

  // 导航
  ARROW_LEFT: '\uF060',
  ARROW_UP: '\uF062',
  CHEVRON_UP: '\uF077',
  CHEVRON_DOWN: '\uF078',
  CHEVRON_RIGHT: '\uF054',
  HOME: '\uF015',
  TIMES: '\uF00D',

  // 操作
  PLUS: '\uF067',
  TRASH: '\uF1F8',
  EDIT: '\uF044',
  PENCIL: '\uF040',
  PLAY: '\uF04B',
  REFRESH: '\uF021',
  UPLOAD: '\uF093',
  DOWNLOAD: '\uF019',
  ELLIPSIS_V: '\uF142',
  SEARCH: '\uF002',
  EYE: '\uF06E',
  STAR: '\uF005',

  // 概念
  TERMINAL: '\uF120',
  CLOUD: '\uF0C2',
  HELP: '\uF059',
  INFO: '\uF05A',
  CODE: '\uF121',
  COG: '\uF013',
  SERVER: '\uF233',
  CLOCK: '\uF017',
} as const

export function NerdIcon({
  glyph,
  size = 14,
  style,
  title,
  className,
}: {
  glyph: string
  size?: number
  style?: CSSProperties
  title?: string
  className?: string
}) {
  return (
    <span
      className={`nerd${className ? ` ${className}` : ''}`}
      style={{
        fontFamily: '"Nerd Symbols"',
        fontSize: size,
        lineHeight: `${size}px`,
        width: size,
        height: size,
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontStyle: 'normal',
        fontWeight: 'normal',
        flexShrink: 0,
        ...style,
      }}
      aria-hidden={title ? undefined : true}
      title={title}
    >
      {glyph}
    </span>
  )
}

export function glyphForFile(name: string): string {
  const dot = name.lastIndexOf('.')
  const ext = dot > 0 ? name.slice(dot + 1).toLowerCase() : ''
  if (!ext) {
    const lower = name.toLowerCase()
    if (lower === '.gitignore' || lower === '.gitattributes') return '\uE702'
    if (lower === 'dockerfile') return '\uE650'
    if (lower === 'makefile') return '\uE673'
    if (lower === 'license') return '\uF15C' // FILE_TEXT
    if (lower === 'readme') return '\uE609'
    return NerdGlyphs.FILE
  }
  switch (ext) {
    case 'py': case 'pyw': case 'pyi': return '\uE606'
    case 'rs': return '\uE68B'
    case 'js': case 'mjs': case 'cjs': return '\uE60C'
    case 'jsx': return '\uE7BA'
    case 'ts': return '\uE628'
    case 'tsx': return '\uE7BA'
    case 'go': return '\uE627'
    case 'java': case 'class': case 'jar': return '\uE66D'
    case 'kt': case 'kts': return '\uE634'
    case 'c': return '\uE649'
    case 'cpp': case 'cc': case 'cxx': case 'c++': return '\uE646'
    case 'h': case 'hpp': case 'hh': return '\uE645'
    case 'rb': return '\uE605'
    case 'swift': return '\uE699'
    case 'lua': return '\uE620'
    case 'php': return '\uE73D'
    case 'scala': case 'sc': return '\uE737'
    case 'cs': return '\uE648'
    case 'vue': return '\uE6A0'
    case 'dart': return '\uE798'
    case 'r': return '\uE68A'
    case 'jl': return '\uE624'
    case 'ex': case 'exs': return '\uE62D'

    case 'md': case 'markdown': return '\uE609'
    case 'rst': case 'txt': case 'log': return '\uF15C'
    case 'tex': return '\uE69B'

    case 'html': case 'htm': case 'xhtml': return '\uE60E'
    case 'css': return '\uE614'
    case 'scss': case 'sass': return '\uE603'
    case 'less': return '\uE60B'

    case 'json': case 'json5': case 'jsonl': return '\uE60B'
    case 'yaml': case 'yml': return '\uE6A8'
    case 'toml': return '\uE6B2'
    case 'xml': return '\uF72D'
    case 'ini': case 'conf': case 'cfg': case 'properties': return '\uE615'
    case 'csv': case 'tsv': return '\uE64A'
    case 'sql': return '\uE706'
    case 'db': case 'sqlite': case 'sqlite3': return '\uE7C4'

    case 'sh': case 'bash': case 'zsh': case 'fish': case 'ksh': return '\uE691'

    case 'dockerfile': return '\uE650'
    case 'mk': case 'makefile': return '\uE673'

    case 'png': case 'jpg': case 'jpeg': case 'gif': case 'webp': case 'bmp': case 'ico': case 'tiff': return '\uE60D'
    case 'svg': return '\uE698'

    case 'mp3': case 'wav': case 'flac': case 'aac': case 'ogg': case 'm4a': return '\uF1C7'
    case 'mp4': case 'mov': case 'mkv': case 'avi': case 'webm': case 'flv': return '\uF1C8'

    case 'pdf': return '\uF1C1'
    case 'zip': case 'tar': case 'gz': case 'bz2': case 'xz': case '7z': case 'rar': return '\uF1C6'
    case 'doc': case 'docx': return '\uF1C2'
    case 'xls': case 'xlsx': return '\uF1C3'
    case 'ppt': case 'pptx': return '\uF1C4'

    case 'ttf': case 'otf': case 'woff': case 'woff2': return '\uF031'

    default: return NerdGlyphs.FILE
  }
}

type IconFn = (props: { size?: number; style?: CSSProperties; title?: string; className?: string }) => ReactElement

function wrap(glyph: string): IconFn {
  return (props) => <NerdIcon glyph={glyph} {...props} />
}

export const Icon = {
  Server: wrap(NerdGlyphs.SERVER),
  Terminal: wrap(NerdGlyphs.TERMINAL),
  Folder: wrap(NerdGlyphs.FOLDER),
  Plus: wrap(NerdGlyphs.PLUS),
  Search: wrap(NerdGlyphs.SEARCH),
  Settings: wrap(NerdGlyphs.COG),
  Close: wrap(NerdGlyphs.TIMES),
  Chevron: wrap(NerdGlyphs.CHEVRON_DOWN),
  ChevronRight: wrap(NerdGlyphs.CHEVRON_RIGHT),
  Clock: wrap(NerdGlyphs.CLOCK),
  Edit: wrap(NerdGlyphs.EDIT),
  Trash: wrap(NerdGlyphs.TRASH),
  Upload: wrap(NerdGlyphs.UPLOAD),
  Download: wrap(NerdGlyphs.DOWNLOAD),
  Refresh: wrap(NerdGlyphs.REFRESH),
  Eye: wrap(NerdGlyphs.EYE),
  ArrowUp: wrap(NerdGlyphs.ARROW_UP),
  Help: wrap(NerdGlyphs.HELP),
  Star: wrap(NerdGlyphs.STAR),
} as const
