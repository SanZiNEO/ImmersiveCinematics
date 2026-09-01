// ─────────────────────────────────────────────────────────────
// ImmersiveCinematics WebUI Editor — 全局状态管理
// WebSocket 连接 + 请求/响应 + 撤销重做 + 编辑器状态
// ─────────────────────────────────────────────────────────────

import { reactive } from 'vue'
import type {
  ScriptDoc, Schema, Selection, TrackViewState, TimelineTool, Track, Clip, Keyframe,
} from './types'
import * as ops from './operations'
import { stripScriptDefaults } from './schema'

const SERVER_URL = 'ws://127.0.0.1:8765/ws'

// ── 响应式状态 ────────────────────────────────────────────────

export const state = reactive({
  // 连接
  connected: false,
  status: 'disconnected' as 'disconnected' | 'connected' | 'error',
  error: '',

  // 脚本
  scripts: [] as string[],
  currentPath: '',
  doc: null as ScriptDoc | null,
  schema: null as Schema | null,
  dirty: false,

  // 播放
  time: 0,
  playing: false,

  // 选中
  selection: {
    track: -1,
    clip: -1,
    keyframe: -1,
  } as Selection,

  // 右面板激活 tab（智能切换用）
  rightTab: 'properties' as 'properties' | 'clip' | 'keyframe' | 'triggers',

  // 时间轴
  pxPerSecond: 40,
  timelineTool: 'select' as TimelineTool,
  snapEnabled: true,
  snapInterval: 0.5,

  // 轨道视图状态（纯编辑器状态，按 track id 索引，不写入脚本）
  trackView: {} as Record<string, TrackViewState>,
})

// ── 撤销重做 ──────────────────────────────────────────────────

const undoStack: string[] = []
const redoStack: string[] = []
const MAX_UNDO = 200

export function pushUndo(): void {
  if (!state.doc) return
  undoStack.push(JSON.stringify(state.doc))
  if (undoStack.length > MAX_UNDO) undoStack.shift()
  redoStack.length = 0
  state.dirty = true
}

export function undo(): void {
  if (!undoStack.length || !state.doc) return
  redoStack.push(JSON.stringify(state.doc))
  state.doc = JSON.parse(undoStack.pop()!)
  state.dirty = true
  clearSelectionIfInvalid()
}

export function redo(): void {
  if (!redoStack.length || !state.doc) return
  undoStack.push(JSON.stringify(state.doc))
  state.doc = JSON.parse(redoStack.pop()!)
  state.dirty = true
  clearSelectionIfInvalid()
}

export function resetHistory(): void {
  undoStack.length = 0
  redoStack.length = 0
  state.dirty = false
}

// ── commit 辅助：自动 undo + 不变量维护 ───────────────────────

/**
 * 所有修改 doc 的操作都应走 commit。
 * 自动 pushUndo → 执行 fn → recalcDuration → applyTransitionAlignment → 标记 dirty
 */
export function commit(fn: () => void): void {
  if (!state.doc) return
  pushUndo()
  fn()
  maintainInvariants()
}

/** 维护不变量：总时长 + 转场对齐 + 排序 */
export function maintainInvariants(): void {
  if (!state.doc?.timeline?.tracks) return
  const tracks = state.doc.timeline.tracks
  ops.sortTrackClips(tracks)
  ops.applyTransitionAlignment(tracks)
  state.doc.timeline.total_duration = ops.recalcDuration(tracks)
}

// ── WebSocket ─────────────────────────────────────────────────

let ws: WebSocket | null = null
let nextId = 1
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
const pending = new Map<string, { resolve: (v: any) => void; reject: (e: any) => void }>()
let listener: ((type: string, data: any) => void) | null = null
let frameListener: ((buf: ArrayBuffer) => void) | null = null

export function connect(): void {
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
    state.error = ''
    send('hello', {})
    refreshScripts()
    getSchema()
  }

  ws.onmessage = (e) => {
    if (typeof e.data === 'string') {
      handleMessage(JSON.parse(e.data))
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

export function onMessage(fn: (type: string, data: any) => void): void {
  listener = fn
}

export function onFrame(fn: (buf: ArrayBuffer) => void): () => void {
  frameListener = fn
  return () => { frameListener = null }
}

export function send(type: string, data: any = {}): void {
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

function handleMessage(msg: any): void {
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

// ── 脚本 CRUD ─────────────────────────────────────────────────

export function refreshScripts(): Promise<string[]> {
  return request<{ files: string[] }>('script.list').then(r => {
    state.scripts = r.files || []
    return state.scripts
  })
}

export function getSchema(): Promise<Schema> {
  return request<{ schema: Schema }>('schema.get').then(r => {
    state.schema = r.schema || null
    return state.schema!
  })
}

export function loadScript(path: string): Promise<ScriptDoc> {
  return request<{ path: string; doc: ScriptDoc }>('script.load', { path }).then(r => {
    state.currentPath = r.path
    state.doc = r.doc
    resetHistory()
    initTrackView()
    clearSelection()
    return r.doc
  })
}

export function newScript(): Promise<ScriptDoc> {
  return request<{ path: string; doc: ScriptDoc }>('script.new').then(r => {
    state.currentPath = r.path
    state.doc = r.doc
    resetHistory()
    initTrackView()
    clearSelection()
    return r.doc
  })
}

export function saveScript(): Promise<{ path: string }> {
  if (!state.currentPath || !state.doc) return Promise.reject(new Error('no current script'))
  // 保存前精简默认字段（深拷贝，不修改编辑中的 doc）
  const docToSave = JSON.parse(JSON.stringify(state.doc))
  if (state.schema) {
    stripScriptDefaults(docToSave, state.schema)
  }
  return request<{ path: string }>('script.save', {
    path: state.currentPath,
    doc: docToSave,
  }).then(r => {
    state.dirty = false
    return r
  })
}

export function deleteScript(path: string): Promise<void> {
  return request('script.delete', { path }).then(() => refreshScripts())
}

// ── 播放控制 ──────────────────────────────────────────────────

export function seek(time: number): void {
  state.time = time
  send('editor.seek', { time })
}

export function play(): void {
  state.playing = true
  send('editor.play')
}

export function pause(): void {
  state.playing = false
  send('editor.pause')
}

export function stop(): void {
  state.playing = false
  state.time = 0
  send('editor.stop')
}

// ── 选中管理 ──────────────────────────────────────────────────

export function selectTrack(index: number): void {
  state.selection.track = index
  state.selection.clip = -1
  state.selection.keyframe = -1
  state.rightTab = 'properties'
}

export function selectClip(trackIndex: number, clipIndex: number): void {
  state.selection.track = trackIndex
  state.selection.clip = clipIndex
  state.selection.keyframe = -1
  state.rightTab = 'clip'
}

export function selectKeyframe(trackIndex: number, clipIndex: number, kfIndex: number): void {
  state.selection.track = trackIndex
  state.selection.clip = clipIndex
  state.selection.keyframe = kfIndex
  state.rightTab = 'keyframe'
}

export function clearSelection(): void {
  state.selection.track = -1
  state.selection.clip = -1
  state.selection.keyframe = -1
}

function clearSelectionIfInvalid(): void {
  if (!state.doc?.timeline?.tracks) { clearSelection(); return }
  const tracks = state.doc.timeline.tracks
  if (state.selection.track >= tracks.length) { clearSelection(); return }
  const track = tracks[state.selection.track]
  if (!track || state.selection.clip >= track.clips.length) {
    state.selection.clip = -1
    state.selection.keyframe = -1
    return
  }
  const clip = track.clips[state.selection.clip]
  if (clip && state.selection.keyframe >= clip.keyframes.length) {
    state.selection.keyframe = -1
  }
}

// ── 选中对象访问器 ────────────────────────────────────────────

export function getSelectedTrack(): Track | null {
  if (!state.doc?.timeline?.tracks) return null
  return state.doc.timeline.tracks[state.selection.track] ?? null
}

export function getSelectedClip(): Clip | null {
  const track = getSelectedTrack()
  if (!track) return null
  return track.clips[state.selection.clip] ?? null
}

export function getSelectedKeyframe(): Keyframe | null {
  const clip = getSelectedClip()
  if (!clip) return null
  return clip.keyframes[state.selection.keyframe] ?? null
}

// ── 轨道视图状态 ──────────────────────────────────────────────

function initTrackView(): void {
  state.trackView = {}
  if (!state.doc?.timeline?.tracks) return
  for (const track of state.doc.timeline.tracks) {
    state.trackView[track.id] = { visible: true, locked: false, muted: false }
  }
}

export function getTrackView(trackId: string): TrackViewState {
  if (!state.trackView[trackId]) {
    state.trackView[trackId] = { visible: true, locked: false, muted: false }
  }
  return state.trackView[trackId]
}

export function toggleTrackVisible(trackId: string): void {
  const v = getTrackView(trackId)
  v.visible = !v.visible
}

export function toggleTrackLocked(trackId: string): void {
  const v = getTrackView(trackId)
  v.locked = !v.locked
}

export function toggleTrackMuted(trackId: string): void {
  const v = getTrackView(trackId)
  v.muted = !v.muted
}

// ── 时间轴缩放 ────────────────────────────────────────────────

export function zoomIn(): void {
  state.pxPerSecond = Math.min(500, state.pxPerSecond * 1.4)
}

export function zoomOut(): void {
  state.pxPerSecond = Math.max(5, state.pxPerSecond / 1.4)
}

export function setZoom(pxPerSecond: number): void {
  state.pxPerSecond = Math.max(5, Math.min(500, pxPerSecond))
}

// ── 工具切换 ──────────────────────────────────────────────────

export function setTool(tool: TimelineTool): void {
  state.timelineTool = tool
}

export function toggleSnap(): void {
  state.snapEnabled = !state.snapEnabled
}
