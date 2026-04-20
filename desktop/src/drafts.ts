import type { CustomPaletteDraft, ServerDraft } from './types'

export const EMPTY_DRAFT: ServerDraft = {
  name: '',
  host: '',
  port: '22',
  user: '',
  password: '',
  initScripts: [],
}

export const EMPTY_CUSTOM_PALETTE_DRAFT: CustomPaletteDraft = {
  displayName: '',
  primary: '#4A8FD9',
  primaryContainer: '#1A3A66',
  darkBackground: '#0B1A33',
}
