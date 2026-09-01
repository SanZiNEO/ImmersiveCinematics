import { reactive } from 'vue'
import type { ScriptDoc, Schema } from './types'

const SERVER_URL = 'ws://127.0.0.1:8765/ws'

export const state = reactive({
  connected: false,
  status: 'disconnected',
  scripts: [] as string[],
  currentPath: '',
  doc: null as ScriptDoc | null,
  schema: null as Schema | null,
  time: 0,
  playing: false,
  error: '',
  selection: {
    track: -1,
    clip: -1,
    keyframe: -1,
  },
})

const undoStack: string[] = []
const redoStack: string[] = []

let ws: WebSocket | null = null
let nextId = 1
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
const pending = new Map<string, { resolve: (v: any) => void; reject: (e: any) => void }>()
let listener: ((type: string, data: any) => void) | null = null
let frameListener: ((buf: ArrayBuffer) => void) | null = null

export function connect() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  ws = new WebSocket(SERVER_URL)
  ws.binaryType = 'arraybuffer'

  ws.onopen = () => {
    state.connected = true
    state.status = 'connected'
    send('hello', {})
    refreshScripts()
    getSchema()
  }

  ws.onmessage = (e) => {
    if (typeof e.data === 'string') {
      const msg = JSON.parse(e.data)
      handleMessage(msg)
    } else if (e.data instanceof ArrayBuffer) {
      if (frameListener) frameListener(e.data)
    }
  }

  ws.onclose = () => {
    state.connected = false
    state.status = 'disconnected'
    for (const [, p] of pending) p.reject(new Error('connection closed'))
    pending.clear()
    reconnectTimer = setTimeout(() => connect(), 1500)
  }

  ws.onerror = () => {
    state.status = 'error'
  }
}

export function onMessage(fn: (type: string, data: any) => void) {
  listener = fn
}

export function onFrame(fn: (buf: ArrayBuffer) => void) {
  frameListener = fn
}

export function send(type: string, data: any = {}) {
  if (!ws || ws.readyState !== WebSocket.OPEN) return
  ws.send(JSON.stringify({ type, data }))
}

export function request<T = any>(type: string, data: any = {}): Promise<T> {
  return new Promise((resolve, reject) => {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      reject(new Error('not connected'))
      return
    }
    const id = String(nextId++)
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ type, data, id }))
  })
}

export function refreshScripts() {
  return request<{ files: string[] }>('script.list').then(r => {
    state.scripts = r.files || []
    return state.scripts
  })
}

export function getSchema() {
  return request<{ schema: Schema }>('schema.get').then(r => {
    state.schema = r.schema || null
    return state.schema
  })
}

export function loadScript(path: string) {
  return request<{ path: string; doc: ScriptDoc }>('script.load', { path }).then(r => {
    state.currentPath = r.path
    state.doc = r.doc
    resetHistory()
    return r.doc
  })
}

export function pushUndo() {
  if (!state.doc) return
  undoStack.push(JSON.stringify(state.doc))
  if (undoStack.length > 200) undoStack.shift()
  redoStack.length = 0
}

export function undo() {
  if (!undoStack.length || !state.doc) return
  redoStack.push(JSON.stringify(state.doc))
  state.doc = JSON.parse(undoStack.pop()!)
}

export function redo() {
  if (!redoStack.length || !state.doc) return
  undoStack.push(JSON.stringify(state.doc))
  state.doc = JSON.parse(redoStack.pop()!)
}

export function resetHistory() {
  undoStack.length = 0
  redoStack.length = 0
}

export function saveScript() {
  if (!state.currentPath || !state.doc) return Promise.reject(new Error('no current script'))
  return request<{ path: string }>('script.save', { path: state.currentPath, doc: state.doc })
}

export function deleteScript(path: string) {
  return request('script.delete', { path }).then(() => refreshScripts())
}

export function newScript() {
  return request<{ path: string; doc: ScriptDoc }>('script.new').then(r => {
    state.currentPath = r.path
    state.doc = r.doc
    resetHistory()
    return r.doc
  })
}

export function seek(time: number) {
  state.time = time
  send('editor.seek', { time })
}

export function play() {
  state.playing = true
  send('editor.play')
}

export function pause() {
  state.playing = false
  send('editor.pause')
}

export function stop() {
  state.playing = false
  state.time = 0
  send('editor.stop')
}

function handleMessage(msg: any) {
  if (msg.id && pending.has(msg.id)) {
    const p = pending.get(msg.id)!
    pending.delete(msg.id)
    if (msg.type === 'error') p.reject(new Error(msg.data?.error || 'error'))
    else p.resolve(msg.data)
    return
  }
  if (msg.type === 'playback.state') {
    state.time = msg.data?.time ?? state.time
    state.playing = !!msg.data?.playing
  }
  if (listener) listener(msg.type, msg.data || {})
}
