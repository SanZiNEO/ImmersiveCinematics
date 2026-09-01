// ─────────────────────────────────────────────────────────────
// ImmersiveCinematics WebUI Editor — 统一操作层
// 完整移植 Java 侧 EditorOperations.java + EditorDefaults.java 的数据操作逻辑
// 所有对脚本 doc 的修改必须走这里，保证不变量（排序、边界关键帧、转场对齐）自动维护
// ─────────────────────────────────────────────────────────────

import type { Clip, Keyframe, ScriptDoc, Track, TrackType } from './types'

// ── 常量 ──────────────────────────────────────────────────────

const EPSILON = 0.001
const DEFAULT_SNAP_INTERVAL = 0.5

// ── 通用访问器 ────────────────────────────────────────────────

export function getStart(clip: Clip): number {
  return clip.start_time ?? 0
}

export function getDuration(clip: Clip): number {
  return clip.duration ?? 0
}

export function getEnd(clip: Clip): number {
  return getStart(clip) + getDuration(clip)
}

export function getTransitionDuration(clip: Clip): number {
  if (clip.transition === 'morph' && clip.transition_duration != null) {
    return clip.transition_duration
  }
  return 0
}

/** B 模型：转场区起点 = 片段末尾 − t/2 */
export function getTransitionStart(clip: Clip): number {
  return getEnd(clip) - getTransitionDuration(clip) / 2
}

/** B 模型：转场区终点 = 片段末尾 + t/2 */
export function getTotalEnd(clip: Clip): number {
  return getEnd(clip) + getTransitionDuration(clip) / 2
}

export function keyframes(clip: Clip): Keyframe[] {
  return clip.keyframes ?? []
}

// ── 转场对齐不变量 ────────────────────────────────────────────

/**
 * B 模型：有 transition_duration 的片段，同轨道下一个片段的 start 恒为 end − t/2。
 * 任何编辑路径后调用，保证重叠转场数据恒成立。
 */
export function applyTransitionAlignment(tracks: Track[]): void {
  for (const track of tracks) {
    const clips = track.clips
    if (!clips || clips.length < 2) continue
    const sorted = [...clips].sort((a, b) => getStart(a) - getStart(b))
    for (let i = 0; i < sorted.length - 1; i++) {
      const prev = sorted[i]
      const t = getTransitionDuration(prev)
      if (t <= 0) continue
      const next = sorted[i + 1]
      const expected = Math.max(getStart(prev), getEnd(prev) - t / 2)
      next.start_time = expected
    }
  }
}

// ── Clip 操作 ─────────────────────────────────────────────────

/**
 * 添加片段。默认值由调用方用 schema 填充（见 schema.fillClipDefaults）。
 * 自动创建首尾两个边界关键帧。
 */
export function addClip(
  tracks: Track[],
  trackIndex: number,
  startTime: number,
  duration: number,
): Clip | null {
  if (trackIndex < 0 || trackIndex >= tracks.length) return null
  if (duration <= 0) duration = 0.1

  const clip: Clip = {
    start_time: startTime,
    duration,
    keyframes: [
      { time: 0 },
      { time: duration },
    ],
  }
  tracks[trackIndex].clips.push(clip)
  sortTrackClips(tracks)
  return clip
}

export function deleteClip(tracks: Track[], clip: Clip): void {
  for (const track of tracks) {
    const idx = track.clips.indexOf(clip)
    if (idx >= 0) {
      track.clips.splice(idx, 1)
      return
    }
  }
}

export function moveClip(clip: Clip, newStart: number, snapInterval = 0): void {
  clip.start_time = Math.max(0, snap(newStart, snapInterval))
}

/** 批量移动 clip（保持相对位置；start 不低于 0） */
export function moveClips(tracks: Track[], clips: Clip[], deltaSeconds: number): void {
  for (const clip of clips) {
    const ns = getStart(clip) + deltaSeconds
    moveClip(clip, ns < 0 ? 0 : ns, 0)
  }
  sortTrackClips(tracks)
}

export function resizeClipLeft(clip: Clip, newStart: number, snapInterval = 0): void {
  const ns = Math.max(0, snap(newStart, snapInterval))
  const oldEnd = getEnd(clip)
  if (ns < oldEnd) {
    const oldDur = getDuration(clip)
    const newDur = oldEnd - ns
    clip.start_time = ns
    clip.duration = newDur
    if (keyframes(clip).length > 0) {
      sortKeyframes(clip)
      moveEndBoundaryKeyframe(clip, oldDur, newDur)
      clampKeyframes(clip)
      ensureBoundaryKeyframes(clip)
      sortKeyframes(clip)
    }
  }
}

export function resizeClipRight(clip: Clip, newEnd: number, snapInterval = 0): void {
  const ne = Math.max(getStart(clip) + 0.1, snap(newEnd, snapInterval))
  const oldDur = getDuration(clip)
  const newDur = ne - getStart(clip)
  clip.duration = newDur
  if (keyframes(clip).length > 0) {
    sortKeyframes(clip)
    moveEndBoundaryKeyframe(clip, oldDur, newDur)
    clampKeyframes(clip)
    ensureBoundaryKeyframes(clip)
    sortKeyframes(clip)
  }
}

// ── 关键帧操作 ────────────────────────────────────────────────

/** 在全局时间点添加关键帧，返回 null 表示时间无效或重复 */
export function addKeyframeAt(clip: Clip, globalTime: number): Keyframe | null {
  const localTime = globalTime - getStart(clip)
  if (localTime < 0 || localTime > getDuration(clip)) return null

  const kfs = keyframes(clip)
  for (const kf of kfs) {
    if (Math.abs(kf.time - localTime) < EPSILON) return null
  }

  const kf: Keyframe = { time: localTime }
  kfs.push(kf)
  sortKeyframes(clip)
  return kf
}

export function canAddKeyframeAt(clip: Clip | null, globalTime: number): boolean {
  if (!clip) return false
  const localTime = globalTime - getStart(clip)
  return localTime >= 0 && localTime <= getDuration(clip)
}

export function deleteKeyframe(clip: Clip, kf: Keyframe): void {
  const kfs = keyframes(clip)
  const idx = kfs.indexOf(kf)
  if (idx >= 0) kfs.splice(idx, 1)
}

export function moveKeyframe(clip: Clip, kf: Keyframe, newLocalTime: number, snapInterval = 0): void {
  kf.time = Math.max(0, Math.min(getDuration(clip), snap(newLocalTime, snapInterval)))
  sortKeyframes(clip)
}

/** 按前后关键帧插值填充新关键帧的属性（添加关键帧时调用） */
export function interpolateNewKeyframe(
  clip: Clip,
  newKf: Keyframe,
): void {
  const kfs = keyframes(clip)
  if (kfs.length < 2) return

  const idx = kfs.indexOf(newKf)
  const prev = idx > 0 ? kfs[idx - 1] : null
  const next = idx < kfs.length - 1 ? kfs[idx + 1] : null

  if (prev && next) {
    const t0 = prev.time
    const t1 = next.time
    const ratio = t1 - t0 > EPSILON ? (newKf.time - t0) / (t1 - t0) : 0
    interpolateKeyframe(newKf, prev, next, ratio)
  } else if (prev) {
    copyKeyframeProperties(newKf, prev)
  } else if (next) {
    copyKeyframeProperties(newKf, next)
  }
}

// ── 轨道操作 ──────────────────────────────────────────────────

export function addTrack(tracks: Track[], type: TrackType): Track {
  const track: Track = {
    type,
    id: generateTrackId(tracks, type),
    clips: [],
  }
  tracks.push(track)
  return track
}

export function removeTrack(tracks: Track[], index: number): boolean {
  if (index < 0 || index >= tracks.length) return false
  tracks.splice(index, 1)
  return true
}

export function moveClipToTrack(tracks: Track[], clip: Clip, targetTrackIndex: number): void {
  if (targetTrackIndex < 0 || targetTrackIndex >= tracks.length) return
  deleteClip(tracks, clip)
  tracks[targetTrackIndex].clips.push(clip)
}

export function findTrackIndex(tracks: Track[], clip: Clip): number {
  for (let i = 0; i < tracks.length; i++) {
    if (tracks[i].clips.includes(clip)) return i
  }
  return -1
}

export function getTrackByType(tracks: Track[], type: string): Track | null {
  return tracks.find(t => t.type === type) ?? null
}

/** 生成唯一轨道 id：{type小写}_{n} */
export function generateTrackId(tracks: Track[] | null, type: string): string {
  const base = type.toLowerCase()
  let n = 1
  while (true) {
    const id = `${base}_${n}`
    let taken = false
    if (tracks) {
      for (const t of tracks) {
        if (t.id === id) { taken = true; break }
      }
    }
    if (!taken) return id
    n++
  }
}

// ── Split ─────────────────────────────────────────────────────

/** 在全局时间点拆分片段，返回右半片段，拆分失败返回 null */
export function splitClip(tracks: Track[], clip: Clip, splitGlobalTime: number): Clip | null {
  const cs = getStart(clip)
  const cd = getDuration(clip)
  const ce = getEnd(clip)
  const localSplit = splitGlobalTime - cs
  if (localSplit <= 0.01 || localSplit >= cd - 0.01) return null

  // 左半缩短
  const oldDur = cd
  clip.duration = localSplit

  // 右半新建
  const rightClip: Clip = {
    start_time: cs + localSplit,
    duration: oldDur - localSplit,
    transition: clip.transition ?? 'cut',
    transition_duration: clip.transition_duration ?? 0.5,
    keyframes: [],
  }

  // 分配关键帧
  const leftKfs = keyframes(clip)
  const rightKfs: Keyframe[] = []
  const toRemove: Keyframe[] = []

  for (const kf of leftKfs) {
    const kt = kf.time
    if (kt > localSplit + EPSILON) {
      // 移到右半，调整时间
      const kfCopy: Keyframe = { ...kf, time: kt - localSplit }
      rightKfs.push(kfCopy)
      toRemove.push(kf)
    } else if (Math.abs(kt - localSplit) <= EPSILON) {
      // 精确边界：两边各保留一份
      kf.time = localSplit
      const kfCopy: Keyframe = { ...kf, time: 0 }
      rightKfs.push(kfCopy)
    }
  }

  for (const kf of toRemove) {
    const idx = leftKfs.indexOf(kf)
    if (idx >= 0) leftKfs.splice(idx, 1)
  }

  ensureBoundaryKeyframes(clip)

  if (rightKfs.length === 0) {
    rightKfs.push({ time: 0 })
    rightKfs.push({ time: oldDur - localSplit })
  }
  rightClip.keyframes = rightKfs

  // 插入到同轨道原片段之后
  for (const track of tracks) {
    const idx = track.clips.indexOf(clip)
    if (idx >= 0) {
      track.clips.splice(idx + 1, 0, rightClip)
      break
    }
  }

  sortTrackClips(tracks)
  return rightClip
}

// ── 排序与不变量维护 ──────────────────────────────────────────

export function sortTrackClips(tracks: Track[]): void {
  for (const track of tracks) {
    if (track.clips.length < 2) continue
    track.clips.sort((a, b) => getStart(a) - getStart(b))
  }
}

export function sortKeyframes(clip: Clip): void {
  const kfs = keyframes(clip)
  if (kfs.length < 2) return
  kfs.sort((a, b) => a.time - b.time)
  // 去重：相邻同时间的关键帧只保留一个
  for (let i = kfs.length - 1; i > 0; i--) {
    if (Math.abs(kfs[i].time - kfs[i - 1].time) < EPSILON) {
      kfs.splice(i, 1)
    }
  }
}

/** 确保片段首尾各有一个关键帧（resize/split 后调用） */
export function ensureBoundaryKeyframes(clip: Clip): void {
  const kfs = keyframes(clip)
  sortKeyframes(clip)
  const dur = getDuration(clip)
  let hasStart = false
  let hasEnd = false
  for (const kf of kfs) {
    if (Math.abs(kf.time) < EPSILON) hasStart = true
    if (Math.abs(kf.time - dur) < EPSILON) hasEnd = true
  }
  if (!hasStart) {
    const kf: Keyframe = { time: 0 }
    if (kfs.length > 0) {
      const first = kfs[0]
      if (hasData(first)) copyKeyframeProperties(kf, first)
    }
    kfs.push(kf)
  }
  if (!hasEnd) {
    const kf: Keyframe = { time: dur }
    if (kfs.length > 0) {
      const last = kfs[kfs.length - 1]
      if (hasData(last)) copyKeyframeProperties(kf, last)
    }
    kfs.push(kf)
  }
}

/** 将关键帧时间 clamp 到 [0, duration] */
export function clampKeyframes(clip: Clip): void {
  const dur = getDuration(clip)
  for (const kf of keyframes(clip)) {
    kf.time = Math.max(0, Math.min(dur, kf.time))
  }
}

/** 重新计算总时长 = 所有片段末尾的最大值（至少 1 秒） */
export function recalcDuration(tracks: Track[]): number {
  let maxEnd = 0
  for (const track of tracks) {
    for (const clip of track.clips) {
      // B 模型：转场吃中间不延长总时长
      maxEnd = Math.max(maxEnd, getEnd(clip))
    }
  }
  return Math.max(1, maxEnd)
}

/** 顺排所有片段（首尾相接，转场重叠） */
export function snapAllClips(tracks: Track[]): void {
  for (const track of tracks) {
    let cursor = 0
    for (const clip of track.clips) {
      clip.start_time = cursor
      cursor = getEnd(clip) - getTransitionDuration(clip) / 2
    }
  }
}

export function snap(t: number, interval = DEFAULT_SNAP_INTERVAL): number {
  if (interval <= 0) return t
  return Math.round(t / interval) * interval
}

// ── 验证 ──────────────────────────────────────────────────────

export function validateScript(doc: ScriptDoc): string[] {
  const errors: string[] = []
  if (!doc) { errors.push('脚本为空'); return errors }

  const meta = doc.meta
  if (!meta) { errors.push('缺少 meta'); return errors }
  if (meta.version !== 3) errors.push('meta.version 必须为 3')

  const timeline = doc.timeline
  if (!timeline) { errors.push('缺少 timeline'); return errors }

  const totalDur = timeline.total_duration ?? 0
  if (totalDur === 0) errors.push('total_duration 为 0')

  const tracks = timeline.tracks
  if (!tracks) return errors

  for (let ti = 0; ti < tracks.length; ti++) {
    const track = tracks[ti]
    const type = track.type
    const clips = track.clips
    if (!clips) continue

    for (let ci = 0; ci < clips.length; ci++) {
      const clip = clips[ci]
      const prefix = `tracks[${ti}].clips[${ci}]`
      const dur = clip.duration ?? 0
      if (dur === 0) errors.push(`${prefix}: duration 为 0`)
      if (type === 'CAMERA') {
        if (!clip.keyframes || clip.keyframes.length === 0) {
          errors.push(`${prefix}: CAMERA 片段缺少关键帧`)
        }
      }
      if (clip.keyframes) {
        let prevTime = -Infinity
        for (let ki = 0; ki < clip.keyframes.length; ki++) {
          const t = clip.keyframes[ki].time
          if (t <= prevTime) errors.push(`${prefix}.keyframes[${ki}]: 关键帧时间未递增`)
          prevTime = t
        }
      }
    }

    // 检查重叠
    const sorted = [...clips].sort((a, b) => getStart(a) - getStart(b))
    for (let ci = 1; ci < sorted.length; ci++) {
      const prev = sorted[ci - 1]
      const curr = sorted[ci]
      const prevEnd = getEnd(prev)
      const currStart = getStart(curr)
      if (currStart < prevEnd - EPSILON) {
        errors.push(`tracks[${ti}]: 片段重叠 ${prevEnd.toFixed(2)} > ${currStart.toFixed(2)}`)
      }
    }
  }

  return errors
}

// ── 内部工具 ──────────────────────────────────────────────────

function moveEndBoundaryKeyframe(clip: Clip, oldDur: number, newDur: number): void {
  for (const kf of keyframes(clip)) {
    if (Math.abs(kf.time - oldDur) < EPSILON) {
      kf.time = newDur
      return
    }
  }
}

function hasData(kf: Keyframe): boolean {
  return Object.keys(kf).some(k => k !== 'time')
}

function copyKeyframeProperties(target: Keyframe, source: Keyframe): void {
  if (source.position) target.position = { ...source.position }
  if (source.position_mode != null) target.position_mode = source.position_mode
  if (source.follow != null) target.follow = source.follow
  if (source.follow_selector != null) target.follow_selector = source.follow_selector
  if (source.look_at != null) target.look_at = source.look_at
  if (source.look_at_selector != null) target.look_at_selector = source.look_at_selector
  if (source.look_at_target_x != null) target.look_at_target_x = source.look_at_target_x
  if (source.look_at_target_y != null) target.look_at_target_y = source.look_at_target_y
  if (source.look_at_target_z != null) target.look_at_target_z = source.look_at_target_z
  if (source.look_at_target_structure != null) target.look_at_target_structure = source.look_at_target_structure
  if (source.yaw != null) target.yaw = source.yaw
  if (source.pitch != null) target.pitch = source.pitch
  if (source.roll != null) target.roll = source.roll
  if (source.fov != null) target.fov = source.fov
  if (source.zoom != null) target.zoom = source.zoom
  if (source.aspect_ratio != null) target.aspect_ratio = source.aspect_ratio
  if (source.volume != null) target.volume = source.volume
  if (source.x != null) target.x = source.x
  if (source.opacity != null) target.opacity = source.opacity
  if (source.font_scale != null) target.font_scale = source.font_scale
  if (source.scale_x != null) target.scale_x = source.scale_x
  if (source.scale_y != null) target.scale_y = source.scale_y
  if (source.command != null) target.command = source.command
  if (source.event_type != null) target.event_type = source.event_type
}

function interpolateKeyframe(target: Keyframe, prev: Keyframe, next: Keyframe, ratio: number): void {
  if (prev.position && next.position) {
    const pos: Record<string, number> = {}
    const keys = ['dx', 'dy', 'dz', 'x', 'y', 'z']
    for (const k of keys) {
      const pv = (prev.position as Record<string, number>)[k]
      const nv = (next.position as Record<string, number>)[k]
      if (pv != null && nv != null) pos[k] = lerp(pv, nv, ratio)
    }
    target.position = pos as Keyframe['position']
  }
  if (prev.yaw != null && next.yaw != null) target.yaw = lerp(prev.yaw, next.yaw, ratio)
  if (prev.pitch != null && next.pitch != null) target.pitch = lerp(prev.pitch, next.pitch, ratio)
  if (prev.roll != null && next.roll != null) target.roll = lerp(prev.roll, next.roll, ratio)
  if (prev.fov != null && next.fov != null) target.fov = lerp(prev.fov, next.fov, ratio)
  if (prev.zoom != null && next.zoom != null) {
    target.zoom = lerpZoom(prev.zoom, next.zoom, ratio)
  }
  if (prev.aspect_ratio != null && next.aspect_ratio != null) {
    target.aspect_ratio = lerp(prev.aspect_ratio, next.aspect_ratio, ratio)
  }
  if (prev.volume != null && next.volume != null) {
    target.volume = lerp(prev.volume, next.volume, ratio)
  }
  if (prev.opacity != null && next.opacity != null) {
    target.opacity = lerp(prev.opacity, next.opacity, ratio)
  }
}

function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t
}

function lerpZoom(a: number, b: number, t: number): number {
  if (a <= 0 || b <= 0) return lerp(a, b, t)
  return Math.exp(Math.log(a) + (Math.log(b) - Math.log(a)) * t)
}
