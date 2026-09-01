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
import { DEMO_SCHEMA, DEMO_SCRIPT } from './demo'

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
  // 多选 clips（{track, clip} 索引对列表）
  selectedClips: [] as { track: number; clip: number }[],
  // 剪贴板（深拷贝的 clip 数据 + 轨道类型）
  clipboard: [] as { clip: Clip; trackType: string }[],
  // A-B 循环
  loopStart: -1,
  loopEnd: -1,
  // Marker 标记
  markers: [] as number[],

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
  // 编辑即预览：自动推送到游戏端
  if (state.connected) {
    pushScript()
  }
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
  // ── 游戏端 EDITOR_* 绑定键广播事件 ──
  if (msg.type === 'editor.play_pause') {
    if (state.playing) pause()
    else play()
  }
  if (msg.type === 'editor.add_marker') {
    addMarker(state.time)
  }
  if (msg.type === 'editor.set_loop_in') {
    setLoopIn(state.time)
  }
  if (msg.type === 'editor.set_loop_out') {
    setLoopOut(state.time)
  }
  if (msg.type === 'editor.nudge_playhead') {
    const dir = msg.data?.direction ?? 1
    const step = msg.data?.large ? 5 : 0.5
    seek(Math.max(0, state.time + dir * step))
  }
  if (msg.type === 'editor.goto_start') {
    seek(0)
  }
  if (msg.type === 'editor.goto_end') {
    seek(state.doc?.timeline?.total_duration ?? 0)
  }
  if (msg.type === 'editor.delete_selected') {
    deleteSelectedClips()
  }
  if (msg.type === 'editor.frame_all') {
    // 缩放以适应所有内容（目标 800px 宽度）
    const total = state.doc?.timeline?.total_duration ?? 10
    setZoom(800 / Math.max(total, 1))
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

/** 推送当前脚本到游戏端预览（编辑即预览） */
export function pushScript(): void {
  if (!state.doc) return
  const docToPush = JSON.parse(JSON.stringify(state.doc))
  if (state.schema) {
    stripScriptDefaults(docToPush, state.schema)
  }
  send('editor.pushScript', { script: JSON.stringify(docToPush) })
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

// ── 离线演示模式 ──────────────────────────────────────────────

/** 未连接游戏时加载演示脚本和 schema，让编辑器界面完整可见 */
export function loadDemo(): void {
  state.schema = DEMO_SCHEMA
  state.doc = JSON.parse(JSON.stringify(DEMO_SCRIPT))
  state.currentPath = 'demo_cinematic.json (离线演示)'
  state.scripts = ['demo_cinematic.json']
  resetHistory()
  initTrackView()
  clearSelection()
}

// ── 多选 / 剪贴板 ─────────────────────────────────────────────

export function selectClipMulti(trackIndex: number, clipIndex: number, additive: boolean): void {
  const exists = state.selectedClips.findIndex(c => c.track === trackIndex && c.clip === clipIndex)
  if (additive) {
    if (exists >= 0) state.selectedClips.splice(exists, 1)
    else state.selectedClips.push({ track: trackIndex, clip: clipIndex })
  } else {
    state.selectedClips = [{ track: trackIndex, clip: clipIndex }]
  }
  selectClip(trackIndex, clipIndex)
}

export function selectAllClips(): void {
  if (!state.doc?.timeline?.tracks) return
  state.selectedClips = []
  for (let ti = 0; ti < state.doc.timeline.tracks.length; ti++) {
    const track = state.doc.timeline.tracks[ti]
    for (let ci = 0; ci < track.clips.length; ci++) {
      state.selectedClips.push({ track: ti, clip: ci })
    }
  }
}

export function clearSelectedClips(): void {
  state.selectedClips = []
}

export function copySelectedClips(): void {
  if (!state.doc?.timeline?.tracks) return
  state.clipboard = []
  for (const sel of state.selectedClips) {
    const track = state.doc.timeline.tracks[sel.track]
    if (!track) continue
    const clip = track.clips[sel.clip]
    if (!clip) continue
    state.clipboard.push({
      clip: JSON.parse(JSON.stringify(clip)),
      trackType: track.type,
    })
  }
}

export function cutSelectedClips(): void {
  if (state.selectedClips.length === 0) return
  copySelectedClips()
  deleteSelectedClips()
}

export function pasteClips(): void {
  if (state.clipboard.length === 0 || !state.doc?.timeline?.tracks) return
  commit(() => {
    const newSelections: { track: number; clip: number }[] = []
    let offset = 0
    for (const item of state.clipboard) {
      const copy: Clip = JSON.parse(JSON.stringify(item.clip))
      copy.start_time = (copy.start_time ?? 0) + offset
      // 找到对应类型的轨道，没有就新建
      let trackIdx = state.doc!.timeline!.tracks.findIndex(t => t.type === item.trackType)
      if (trackIdx < 0) {
        ops.addTrack(state.doc!.timeline!.tracks, item.trackType as any)
        trackIdx = state.doc!.timeline!.tracks.length - 1
      }
      state.doc!.timeline!.tracks[trackIdx].clips.push(copy)
      newSelections.push({ track: trackIdx, clip: state.doc!.timeline!.tracks[trackIdx].clips.length - 1 })
      offset += 0.5
    }
    state.selectedClips = newSelections
  })
}

export function deleteSelectedClips(): void {
  if (state.selectedClips.length === 0 || !state.doc?.timeline?.tracks) return
  commit(() => {
    // 按轨道和索引降序删除，避免索引偏移
    const sorted = [...state.selectedClips].sort((a, b) => b.clip - a.clip || b.track - a.track)
    for (const sel of sorted) {
      const track = state.doc!.timeline!.tracks[sel.track]
      if (track) track.clips.splice(sel.clip, 1)
    }
    state.selectedClips = []
    clearSelection()
  })
}

export function duplicateSelectedClips(): void {
  copySelectedClips()
  pasteClips()
}

// ── A-B 循环 ──────────────────────────────────────────────────

export function setLoopIn(time: number): void {
  state.loopStart = time
  if (state.loopEnd >= 0 && state.loopEnd <= time) state.loopEnd = time + 1
}

export function setLoopOut(time: number): void {
  state.loopEnd = time
}

export function clearLoop(): void {
  state.loopStart = -1
  state.loopEnd = -1
}

// ── Marker ────────────────────────────────────────────────────

export function addMarker(time: number): void {
  if (state.markers.some(m => Math.abs(m - time) < 0.001)) return
  state.markers.push(time)
  state.markers.sort((a, b) => a - b)
}

export function removeMarker(time: number): void {
  const idx = state.markers.findIndex(m => Math.abs(m - time) < 0.001)
  if (idx >= 0) state.markers.splice(idx, 1)
}
