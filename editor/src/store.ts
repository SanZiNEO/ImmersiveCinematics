// ─────────────────────────────────────────────────────────────
// ImmersiveCinematics WebUI Editor — 全局状态管理
// WebSocket 连接 + 请求/响应 + 撤销重做 + 编辑器状态
// ─────────────────────────────────────────────────────────────

import { reactive, ref } from 'vue'
import type {
  ScriptDoc, Schema, Selection, TrackViewState, TimelineTool, Track, Clip, Keyframe,
} from './types'
import * as ops from './operations'
import { fillClipDefaults, fillKeyframeDefaults, stripScriptDefaults } from './schema'
import { DEMO_SCHEMA, DEMO_SCRIPT } from './demo'

/** 外部编辑器前端日志，统一前缀便于排查 */
function log(...args: any[]): void {
  console.log('[IC-WebUI]', ...args)
}

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
  validationIssues: [] as string[],

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

// ── 飞控模式状态 ──────────────────────────────────────────────
export const flightMode = ref(false)
export const flightState = ref<{
  x: number; y: number; z: number; yaw: number; pitch: number; roll: number; fov: number; zoom: number
} | null>(null)

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
  // 编辑即预览：只调度一次防抖推送，不在每次输入事件里同步全量推送
  scheduleScriptPush()
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

/** 脚本推送防抖：对齐旧 Java EditorOutput（200ms 节流），避免每次输入全量推送 */
const SCRIPT_PUSH_DELAY_MS = 160
/** seek 节流：对齐旧 Java EditorOutput（50ms 节流） */
const SEEK_SEND_DELAY_MS = 50
let scriptPushTimer: ReturnType<typeof setTimeout> | null = null
let seekSendTimer: ReturnType<typeof setTimeout> | null = null

function wsOpen(): boolean {
  return !!ws && ws.readyState === WebSocket.OPEN
}

export function connect(): void {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  ws = new WebSocket(SERVER_URL)
  ws.binaryType = 'arraybuffer'

  ws.onopen = () => {
    log('WS connected')
    state.connected = true
    state.status = 'connected'
    state.error = ''
    send('hello', {})
    refreshScripts()
    getSchema()
    // 关键通信：连接后立刻把当前编辑的脚本推给游戏播放器并定位，
    // 否则外部编辑器只会看到原始游戏画面，永远没有脚本预览。
    if (state.doc) {
      log('onopen -> pushScript current doc', state.currentPath)
      pushScript()
      seek(state.time)
    }
  }

  ws.onmessage = (e) => {
    if (typeof e.data === 'string') {
      const msg = JSON.parse(e.data)
      log('<=', msg.type, msg.data ? JSON.stringify(msg.data).slice(0, 200) : '')
      handleMessage(msg)
    } else if (e.data instanceof ArrayBuffer) {
      if (frameListener) frameListener(e.data)
    }
  }

  ws.onclose = () => {
    log('WS closed')
    state.connected = false
    state.status = 'disconnected'
    if (scriptPushTimer) { clearTimeout(scriptPushTimer); scriptPushTimer = null }
    if (seekSendTimer) { clearTimeout(seekSendTimer); seekSendTimer = null }
    for (const [, p] of pending) p.reject(new Error('connection closed'))
    pending.clear()
    reconnectTimer = setTimeout(() => connect(), 1500)
  }

  ws.onerror = () => {
    log('WS error')
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
  // ── 飞控模式实时状态：只更新 HUD/状态，绝不每帧写关键帧 ──
  if (msg.type === 'flight.state') {
    flightState.value = { ...msg.data }
  }
  // 旧 Java 编辑器语义：只有退出飞控时才把最终相机参数写回关键帧
  if (msg.type === 'flight.exit') {
    flightMode.value = false
    if (!msg.data?.cancelled) {
      updateKeyframeFromFlight(msg.data)
      // 写回是一次性操作，推送一次脚本让预览立即使用新关键帧
      if (state.connected) pushScript()
    }
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

/** 注册表自动补全：即时查询，成功后由调用方决定是否缓存 */
export function registryQuery(kind: string, query: string, limit = 50): Promise<string[]> {
  return request<{ kind: string; matches: string[] }>('registry.query', { kind, query, limit }).then(r => r.matches || [])
}

/** 小表全量获取 */
export function registryGet(kind: string): Promise<string[]> {
  return request<{ kind: string; values: string[] }>('registry.get', { kind }).then(r => r.values || [])
}

/** 服务端完整校验；离线时用前端简化校验兜底 */
export async function validateCurrentDoc(): Promise<string[]> {
  if (!state.doc) return ['没有可校验的脚本']
  return validateScriptDocument(JSON.parse(JSON.stringify(state.doc)) as any)
}

async function validateScriptDocument(doc: any): Promise<string[]> {
  let issues = state.schema ? ops.validateScript(doc) : []
  if (state.connected) {
    try {
      const r = await request<{ ok: boolean; issues: string[] }>('script.validate', { doc })
      const server = r.issues || []
      // 合并去重：保留顺序，但避免同一问题出现两次
      for (const s of server) {
        if (!issues.some(i => i === s)) issues.push(s)
      }
    } catch (e: any) {
      issues.push('服务端校验失败: ' + (e?.message || e))
    }
  }
  state.validationIssues = issues
  return issues
}

export function loadScript(path: string): Promise<ScriptDoc> {
  log('loadScript', path)
  return request<{ path: string; doc: ScriptDoc }>('script.load', { path }).then(r => {
    state.currentPath = r.path
    state.doc = r.doc
    state.validationIssues = []
    resetHistory()
    initTrackView()
    loadTimelineExtras()
    // 旧编辑器逻辑：打开后修正旧脚本转场重叠、重新计算总时长
    maintainInvariants()
    // 自动选中第一个 CAMERA clip，并推送到游戏预览
    selectFirstCameraClip()
    pushScript()
    seek(0)
    return r.doc
  })
}

export function newScript(): Promise<ScriptDoc> {
  log('newScript')
  return request<{ path: string; doc: ScriptDoc }>('script.new').then(r => {
    state.currentPath = r.path
    state.doc = r.doc
    state.validationIssues = []
    resetHistory()
    initTrackView()
    loadTimelineExtras()
    // 旧编辑器逻辑：新建时生成默认 CAMERA + LETTERBOX 引导内容
    bootstrapScript(state.doc!)
    maintainInvariants()
    selectFirstCameraClip()
    pushScript()
    seek(0)
    return r.doc
  })
}

export async function saveScript(): Promise<{ path: string }> {
  if (!state.currentPath || !state.doc) return Promise.reject(new Error('no current script'))
  // 保存前精简默认字段（深拷贝，不修改编辑中的 doc）
  const docToSave = JSON.parse(JSON.stringify(state.doc))
  if (state.schema) {
    stripScriptDefaults(docToSave, state.schema)
  }
  // 与旧编辑器一致：保存前先校验，错误不阻断保存，但记录到界面
  await validateScriptDocument(docToSave)
  log('saveScript', state.currentPath, 'issues=' + state.validationIssues.length)
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
  log('seek', time)
  state.time = time
  if (!wsOpen()) return
  if (seekSendTimer) clearTimeout(seekSendTimer)
  seekSendTimer = setTimeout(() => {
    seekSendTimer = null
    if (wsOpen()) {
      send('editor.seek', { time: state.time })
    }
  }, SEEK_SEND_DELAY_MS)
}

/** 调度一次防抖脚本推送：多次 commit 只推最新一份，未连接直接不调度 */
function scheduleScriptPush(): void {
  if (!wsOpen()) return
  if (scriptPushTimer) clearTimeout(scriptPushTimer)
  scriptPushTimer = setTimeout(() => {
    scriptPushTimer = null
    pushScript()
  }, SCRIPT_PUSH_DELAY_MS)
}

/** 推送当前脚本到游戏端预览（编辑即预览；静音 AUDIO 轨道按旧逻辑剔除） */
export function pushScript(): void {
  if (!state.doc || !wsOpen()) return
  const docToPush = JSON.parse(JSON.stringify(state.doc))
  const tracks = (docToPush as any).timeline?.tracks
  if (Array.isArray(tracks)) {
    for (const track of tracks) {
      if (track?.type === 'AUDIO' && getTrackView(track.id).muted) {
        track.clips = []
      }
    }
  }
  if (state.schema) {
    stripScriptDefaults(docToPush, state.schema)
  }
  log('pushScript', state.currentPath, 'len=' + JSON.stringify(docToPush).length)
  send('editor.pushScript', { script: JSON.stringify(docToPush) })
}

// ── 飞控模式 ──────────────────────────────────────────────────

/** 进入飞控模式：用当前选中关键帧初始化；没有选中关键帧时自动在当前时间新建 */
export function enterFlightMode(): void {
  let kf = getSelectedKeyframe()
  if (!kf) {
    const clip = getSelectedClip()
    const track = getSelectedTrack()
    if (!clip || !track || track.type !== 'CAMERA') return
    // 旧逻辑：有 CAMERA clip 且当前时间在片段内，自动添加关键帧
    if (!ops.canAddKeyframeAt(clip, state.time)) return
    const existing = clip as Clip
    commit(() => {
      const k = ops.addKeyframeAt(existing, state.time)
      if (k && state.schema) {
        fillKeyframeDefaults(k as any, state.schema, track.type)
        ops.interpolateNewKeyframe(existing, k)
        const idx = existing.keyframes.indexOf(k)
        if (idx >= 0) selectKeyframe(state.selection.track, state.selection.clip, idx)
      }
    })
    kf = getSelectedKeyframe()
    if (!kf) return
  }

  // 飞行前的 undo 快照：退出后可用 Ctrl+Z 回到进入前状态
  pushUndo()
  log('enterFlightMode', JSON.stringify(kf))

  flightMode.value = true
  const k = kf as any
  const pos = k.position || {}
  const isAbsolute = k.position_mode === 'absolute'
    || (k.position_mode !== 'relative' && pos.x !== undefined && pos.dx === undefined)
  send('editor.enter_flight_mode', {
    x: pos.x ?? pos.dx ?? 0,
    y: pos.y ?? pos.dy ?? 2,
    z: pos.z ?? pos.dz ?? 0,
    yaw: k.yaw ?? 0,
    pitch: k.pitch ?? 0,
    roll: k.roll ?? 0,
    fov: k.fov ?? 70,
    zoom: k.zoom ?? 1,
    absolute: isAbsolute,
  })
}

/** 用飞控模式的实时数据更新当前选中关键帧（不记录 undo，退出时统一记录） */
function updateKeyframeFromFlight(data: any): void {
  if (state.selection.track < 0 || state.selection.clip < 0 || state.selection.keyframe < 0) return
  const track = state.doc?.timeline?.tracks[state.selection.track]
  const clip = track?.clips[state.selection.clip]
  const kf = clip?.keyframes[state.selection.keyframe]
  if (!kf) return
  const k = kf as any
  if (!k.position) k.position = {}
  const pos = k.position as any
  const isAbsolute = k.position_mode === 'absolute'
    || (k.position_mode !== 'relative' && (pos.x !== undefined || pos.dx === undefined))
  if (isAbsolute) {
    pos.x = data.x
    pos.y = data.y
    pos.z = data.z
    delete pos.dx; delete pos.dy; delete pos.dz
  } else {
    pos.dx = data.x
    pos.dy = data.y
    pos.dz = data.z
    delete pos.x; delete pos.y; delete pos.z
  }
  k.yaw = data.yaw
  k.pitch = data.pitch
  k.roll = data.roll
  k.fov = data.fov
  k.zoom = data.zoom
  state.dirty = true
}

export function play(): void {
  log('play')
  state.playing = true
  send('editor.play')
}

export function pause(): void {
  log('pause')
  state.playing = false
  send('editor.pause')
}

export function stop(): void {
  log('stop')
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
  // 旧编辑器逻辑：选中关键帧 = 预览定位到该关键帧（暂停显示这一帧）
  const track = state.doc?.timeline?.tracks[trackIndex]
  const clip = track?.clips[clipIndex]
  const kf = clip?.keyframes[kfIndex]
  if (clip && kf) {
    seek((clip.start_time ?? 0) + (kf.time ?? 0))
  }
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

/** 从 doc 中同步 marker / A-B 循环点到编辑器状态（旧编辑器写入 timeline） */
function loadTimelineExtras(): void {
  const tl = state.doc?.timeline as any
  state.markers = Array.isArray(tl?.markers) ? tl.markers.map((m: any) => Number(m.time ?? m)) : []
  state.loopStart = typeof tl?.loop_start === 'number' ? tl.loop_start : -1
  state.loopEnd = typeof tl?.loop_end === 'number' ? tl.loop_end : -1
}

/** 自动选中第一个 CAMERA clip（旧编辑器打开/新建后的习惯） */
function selectFirstCameraClip(): void {
  if (!state.doc?.timeline?.tracks) return
  for (let ti = 0; ti < state.doc.timeline.tracks.length; ti++) {
    const track = state.doc.timeline.tracks[ti]
    if (track.type === 'CAMERA' && track.clips.length > 0) {
      state.selection.track = ti
      state.selection.clip = 0
      state.selection.keyframe = -1
      state.rightTab = 'clip'
      return
    }
  }
}

/**
 * 新建脚本引导（对应旧 EditorScreen.bootstrapNewScript）：
 * CAMERA 默认 10s 片段 + LETTERBOX 全段 2.35 遮幅。
 */
function bootstrapScript(doc: ScriptDoc): void {
  if (!doc.timeline || !state.schema) return
  const tracks = doc.timeline.tracks

  let camTrack = tracks.find(t => t.type === 'CAMERA')
  if (!camTrack) {
    ops.addTrack(tracks, 'CAMERA')
    camTrack = tracks[tracks.length - 1]
  }
  const clip = ops.addClip(tracks, tracks.indexOf(camTrack), 0, 10)
  if (clip && state.schema) {
    fillClipDefaults(clip as any, state.schema, 'CAMERA')
    for (const kf of clip.keyframes) {
      fillKeyframeDefaults(kf as any, state.schema, 'CAMERA')
    }
    clip.transition = 'cut'
    clip.interpolation = 'linear'
    const first = clip.keyframes[0]
    const last = clip.keyframes[1]
    for (const kf of [first, last]) {
      if (kf) {
        kf.position = { dx: 0, dy: 2, dz: 0 }
        kf.yaw = 0
        kf.pitch = 0
        kf.roll = 0
        kf.fov = 70
        kf.zoom = 1
      }
    }
  }

  let lbTrack = tracks.find(t => t.type === 'LETTERBOX')
  if (!lbTrack) {
    ops.addTrack(tracks, 'LETTERBOX')
    lbTrack = tracks[tracks.length - 1]
  }
  const lbClip = ops.addClip(tracks, tracks.indexOf(lbTrack), 0, 10)
  if (lbClip && state.schema) {
    fillClipDefaults(lbClip as any, state.schema, 'LETTERBOX')
    for (const kf of lbClip.keyframes) {
      fillKeyframeDefaults(kf as any, state.schema, 'LETTERBOX')
      kf.aspect_ratio = 2.35
    }
  }
  doc.timeline.total_duration = 10
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
  loadTimelineExtras()
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
  // 锁定轨道不允许删除
  for (const sel of state.selectedClips) {
    const t = state.doc.timeline.tracks[sel.track]
    if (t && getTrackView(t.id).locked) return
  }
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

function persistTimelineExtras(): void {
  if (!state.doc?.timeline) return
  const tl = state.doc.timeline as any
  tl.markers = state.markers.map(t => ({ time: t }))
  if (state.loopStart >= 0) tl.loop_start = state.loopStart
  else delete tl.loop_start
  if (state.loopEnd >= 0) tl.loop_end = state.loopEnd
  else delete tl.loop_end
}

export function setLoopIn(time: number): void {
  state.loopStart = time
  if (state.loopEnd >= 0 && state.loopEnd <= time) state.loopEnd = -1
  commit(() => persistTimelineExtras())
}

export function setLoopOut(time: number): void {
  if (state.loopStart < 0 || time <= state.loopStart) {
    clearLoop()
    return
  }
  state.loopEnd = time
  commit(() => persistTimelineExtras())
}

export function clearLoop(): void {
  state.loopStart = -1
  state.loopEnd = -1
  commit(() => persistTimelineExtras())
}

// ── Marker ────────────────────────────────────────────────────

export function addMarker(time: number): void {
  if (state.markers.some(m => Math.abs(m - time) < 0.001)) return
  state.markers.push(time)
  state.markers.sort((a, b) => a - b)
  commit(() => persistTimelineExtras())
}

export function removeMarker(time: number): void {
  const idx = state.markers.findIndex(m => Math.abs(m - time) < 0.001)
  if (idx >= 0) {
    state.markers.splice(idx, 1)
    commit(() => persistTimelineExtras())
  }
}
