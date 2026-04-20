import { create } from 'zustand'

import { STORAGE_KEY } from '../constants'
import type { SavedServer } from '../types'

// localStorage 键名和迁移前一致,避免破坏老用户数据。

type Setter = SavedServer[] | ((prev: SavedServer[]) => SavedServer[])

type State = {
  servers: SavedServer[]
  setServers: (next: Setter) => void
}

function load(): SavedServer[] {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as Partial<SavedServer>[]
    if (!Array.isArray(parsed)) return []
    return parsed
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
                id: script!.id as string,
                name: typeof script!.name === 'string' ? script!.name : '',
                workingDir:
                  typeof script!.workingDir === 'string' ? script!.workingDir : '',
                content: typeof script!.content === 'string' ? script!.content : '',
              }))
          : [],
      }))
  } catch {
    return []
  }
}

export const useServerStore = create<State>((set) => ({
  servers: load(),
  setServers: (next) =>
    set((state) => ({
      servers: typeof next === 'function' ? (next as (p: SavedServer[]) => SavedServer[])(state.servers) : next,
    })),
}))

// 150 ms 尾部 debounce + beforeunload 兜底。连续键入大量服务器时避免反复 JSON.stringify
// 整条列表;关标签页时把最后那次写进去。
let pendingWrite: number | undefined
function flushServersToStorage() {
  window.localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify(useServerStore.getState().servers),
  )
}
useServerStore.subscribe((state, prev) => {
  if (state.servers === prev.servers) return
  if (pendingWrite) window.clearTimeout(pendingWrite)
  pendingWrite = window.setTimeout(() => {
    pendingWrite = undefined
    flushServersToStorage()
  }, 150)
})
window.addEventListener('beforeunload', () => {
  if (pendingWrite) {
    window.clearTimeout(pendingWrite)
    pendingWrite = undefined
    flushServersToStorage()
  }
})
