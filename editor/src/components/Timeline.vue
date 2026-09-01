<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import {
  state, commit, seek, selectClip, selectKeyframe, clearSelection,
  zoomIn, zoomOut, setTool, toggleSnap, getTrackView, toggleTrackVisible,
  toggleTrackLocked, toggleTrackMuted,
} from '../store'
import * as ops from '../operations'
import { fillClipDefaults, fillKeyframeDefaults } from '../schema'
import type { Clip, Keyframe, Track, TrackType } from '../types'

// ── refs ──────────────────────────────────────────────────────

const scrollerRef = ref<HTMLDivElement | null>(null)
const contentRef = ref<HTMLDivElement | null>(null)

// ── 计算属性 ──────────────────────────────────────────────────

const tracks = computed<Track[]>(() => state.doc?.timeline?.tracks ?? [])
const totalDuration = computed(() => state.doc?.timeline?.total_duration ?? 10)
const contentWidth = computed(() => Math.max(totalDuration.value * state.pxPerSecond + 200, 800))
const pxPerSecond = computed(() => state.pxPerSecond)

// 时间标尺刻度
const rulerMarks = computed(() => {
  const pps = pxPerSecond.value
  // 根据缩放选择刻度间隔
  let interval = 1
  if (pps < 15) interval = 10
  else if (pps < 30) interval = 5
  else if (pps < 80) interval = 1
  else if (pps < 200) interval = 0.5
  else interval = 0.1
  const marks: { time: number; label: string; major: boolean }[] = []
  const count = Math.ceil(totalDuration.value / interval) + 2
  for (let i = 0; i <= count; i++) {
    const t = i * interval
    if (t > totalDuration.value + interval) break
    marks.push({
      time: t,
      label: formatTime(t),
      major: i % (interval >= 1 ? (interval >= 5 ? 1 : 5) : 5) === 0,
    })
  }
  return marks
})

// ── 轨道颜色 ──────────────────────────────────────────────────

function trackColor(type: string): string {
  switch (type) {
    case 'CAMERA': return '#3b6fc4'
    case 'AUDIO': return '#2f9e6e'
    case 'EVENT': return '#c78f3a'
    case 'MOD_EVENT': return '#9a5fc4'
    case 'OVERLAY': return '#c45f8a'
    case 'LETTERBOX': return '#4a7d7d'
    default: return '#555'
  }
}

function trackLabel(type: string): string {
  switch (type) {
    case 'CAMERA': return '相机'
    case 'AUDIO': return '音频'
    case 'EVENT': return '事件'
    case 'MOD_EVENT': return '模组事件'
    case 'OVERLAY': return '覆盖层'
    case 'LETTERBOX': return '遮幅'
    default: return type
  }
}

// ── Clip label ────────────────────────────────────────────────

function clipLabel(clip: Clip, type: string): string {
  switch (type) {
    case 'CAMERA': return `${clip.keyframes?.length ?? 0} 帧`
    case 'AUDIO': return clip.sound ? String(clip.sound).split('/').pop() : '音频'
    case 'EVENT': return clip.keyframes?.find(k => k.command)?.command?.slice(0, 20) ?? '命令'
    case 'OVERLAY': return clip.layer_type ?? '覆盖'
    case 'LETTERBOX': return '遮幅'
    case 'MOD_EVENT': return clip.event_type ?? '事件'
    default: return '片段'
  }
}

// ── 位置计算 ──────────────────────────────────────────────────

function timeToPx(time: number): number {
  return time * pxPerSecond.value
}

function pxToTime(px: number): number {
  return px / pxPerSecond.value
}

function getMouseTime(e: MouseEvent): number {
  const content = contentRef.value
  if (!content) return 0
  const rect = content.getBoundingClientRect()
  return pxToTime(e.clientX - rect.left)
}

// ── 拖拽状态 ──────────────────────────────────────────────────

type DragMode = 'none' | 'playhead' | 'clip-move' | 'clip-resize-l' | 'clip-resize-r' | 'keyframe'
const dragMode = ref<DragMode>('none')
const dragData = ref<{
  trackIndex?: number
  clipIndex?: number
  kfIndex?: number
  startX?: number
  startTime?: number
  startClipStart?: number
  startClipDur?: number
  startKfTime?: number
}>({})

// ── 播放头拖拽 ────────────────────────────────────────────────

function onPlayheadMouseDown(e: MouseEvent) {
  e.stopPropagation()
  dragMode.value = 'playhead'
  window.addEventListener('mousemove', onPlayheadDrag)
  window.addEventListener('mouseup', onDragEnd)
}

function onPlayheadDrag(e: MouseEvent) {
  if (dragMode.value !== 'playhead') return
  const t = Math.max(0, Math.min(totalDuration.value, getMouseTime(e)))
  seek(t)
}

// ── Clip 交互 ─────────────────────────────────────────────────

function onClipMouseDown(e: MouseEvent, trackIndex: number, clipIndex: number) {
  e.stopPropagation()
  const clip = tracks.value[trackIndex]?.clips[clipIndex]
  if (!clip) return

  // 刀片工具：split
  if (state.timelineTool === 'razor') {
    const t = getMouseTime(e)
    commit(() => {
      ops.splitClip(tracks.value, clip, t)
    })
    return
  }

  selectClip(trackIndex, clipIndex)

  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  const relX = e.clientX - rect.left
  const edgeThreshold = 8

  if (relX < edgeThreshold) {
    // resize 左边缘
    dragMode.value = 'clip-resize-l'
    dragData.value = { trackIndex, clipIndex, startX: e.clientX, startClipStart: clip.start_time, startClipDur: clip.duration }
  } else if (relX > rect.width - edgeThreshold) {
    // resize 右边缘
    dragMode.value = 'clip-resize-r'
    dragData.value = { trackIndex, clipIndex, startX: e.clientX, startClipStart: clip.start_time, startClipDur: clip.duration }
  } else {
    // 移动
    dragMode.value = 'clip-move'
    dragData.value = { trackIndex, clipIndex, startX: e.clientX, startTime: clip.start_time }
  }

  window.addEventListener('mousemove', onClipDrag)
  window.addEventListener('mouseup', onDragEnd)
}

function onClipDrag(e: MouseEvent) {
  const dd = dragData.value
  const clip = tracks.value[dd.trackIndex!]?.clips[dd.clipIndex!]
  if (!clip) return

  const dx = e.clientX - (dd.startX ?? 0)
  const dt = pxToTime(dx)

  if (dragMode.value === 'clip-move') {
    let newStart = (dd.startTime ?? 0) + dt
    if (state.snapEnabled) newStart = snapTime(newStart, clip)
    commit(() => {
      ops.moveClip(clip, Math.max(0, newStart), 0)
    })
  } else if (dragMode.value === 'clip-resize-l') {
    const newStart = (dd.startClipStart ?? 0) + dt
    commit(() => {
      ops.resizeClipLeft(clip, Math.max(0, newStart), 0)
    })
  } else if (dragMode.value === 'clip-resize-r') {
    const newEnd = (dd.startClipStart ?? 0) + (dd.startClipDur ?? 0) + dt
    commit(() => {
      ops.resizeClipRight(clip, newEnd, 0)
    })
  }
}

// ── 关键帧交互 ────────────────────────────────────────────────

function onKeyframeMouseDown(e: MouseEvent, trackIndex: number, clipIndex: number, kfIndex: number) {
  e.stopPropagation()
  selectKeyframe(trackIndex, clipIndex, kfIndex)
  const clip = tracks.value[trackIndex]?.clips[clipIndex]
  const kf = clip?.keyframes[kfIndex]
  if (!clip || !kf) return

  dragMode.value = 'keyframe'
  dragData.value = { trackIndex, clipIndex, kfIndex, startX: e.clientX, startKfTime: kf.time }
  window.addEventListener('mousemove', onKeyframeDrag)
  window.addEventListener('mouseup', onDragEnd)
}

function onKeyframeDrag(e: MouseEvent) {
  const dd = dragData.value
  const clip = tracks.value[dd.trackIndex!]?.clips[dd.clipIndex!]
  const kf = clip?.keyframes[dd.kfIndex!]
  if (!clip || !kf) return

  const dx = e.clientX - (dd.startX ?? 0)
  const dt = pxToTime(dx)
  const newTime = Math.max(0, Math.min(clip.duration, (dd.startKfTime ?? 0) + dt))

  commit(() => {
    ops.moveKeyframe(clip, kf, newTime, 0)
    const idx = clip.keyframes.indexOf(kf)
    if (idx >= 0) state.selection.keyframe = idx
  })
}

// ── 双击添加关键帧 ────────────────────────────────────────────

function onClipDblClick(e: MouseEvent, trackIndex: number, clipIndex: number) {
  e.stopPropagation()
  const clip = tracks.value[trackIndex]?.clips[clipIndex]
  const track = tracks.value[trackIndex]
  if (!clip || !track) return

  const t = getMouseTime(e)
  if (!ops.canAddKeyframeAt(clip, t)) return

  commit(() => {
    const kf = ops.addKeyframeAt(clip, t)
    if (kf && state.schema) {
      fillKeyframeDefaults(kf as Record<string, unknown>, state.schema, track.type)
      ops.interpolateNewKeyframe(clip, kf)
      const idx = clip.keyframes.indexOf(kf)
      if (idx >= 0) selectKeyframe(trackIndex, clipIndex, idx)
    }
  })
}

// ── 空白区域点击 ──────────────────────────────────────────────

function onContentMouseDown(e: MouseEvent) {
  if (e.button !== 0) return
  const t = getMouseTime(e)
  seek(Math.max(0, Math.min(totalDuration.value, t)))
  clearSelection()
}

// ── 拖拽结束 ──────────────────────────────────────────────────

function onDragEnd() {
  dragMode.value = 'none'
  dragData.value = {}
  window.removeEventListener('mousemove', onPlayheadDrag)
  window.removeEventListener('mousemove', onClipDrag)
  window.removeEventListener('mousemove', onKeyframeDrag)
  window.removeEventListener('mouseup', onDragEnd)
}

// ── 吸附 ──────────────────────────────────────────────────────

function snapTime(t: number, clip: Clip): number {
  const snapPoints: number[] = [0, state.time]
  // 其他 clip 的起止点
  for (const track of tracks.value) {
    for (const c of track.clips) {
      if (c === clip) continue
      snapPoints.push(c.start_time)
      snapPoints.push(c.start_time + c.duration)
    }
  }
  const threshold = pxToTime(8) // 8px 吸附阈值
  for (const sp of snapPoints) {
    if (Math.abs(t - sp) < threshold) return sp
  }
  return t
}

// ── 轨道操作 ──────────────────────────────────────────────────

function addClipToTrack(trackIndex: number) {
  const track = tracks.value[trackIndex]
  if (!track) return
  commit(() => {
    // 找到该轨道最后一个片段的末尾
    const clips = track.clips
    const lastEnd = clips.length > 0
      ? Math.max(...clips.map(c => c.start_time + c.duration))
      : 0
    const clip = ops.addClip(tracks.value, trackIndex, lastEnd, 5)
    if (clip && state.schema) {
      fillClipDefaults(clip as Record<string, unknown>, state.schema, track.type)
      // 填充关键帧默认值
      for (const kf of clip.keyframes) {
        fillKeyframeDefaults(kf as Record<string, unknown>, state.schema, track.type)
      }
    }
  })
}

function addTrack(type: TrackType) {
  commit(() => {
    ops.addTrack(tracks.value, type)
  })
}

function removeTrack(index: number) {
  commit(() => {
    ops.removeTrack(tracks.value, index)
  })
}

// ── 滚轮缩放 ──────────────────────────────────────────────────

function onWheel(e: WheelEvent) {
  if (e.ctrlKey || e.metaKey) {
    e.preventDefault()
    const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15
    setTool // noop
    state.pxPerSecond = Math.max(5, Math.min(500, state.pxPerSecond * factor))
  }
}

// ── 工具函数 ──────────────────────────────────────────────────

function formatTime(t: number): string {
  if (t >= 60) {
    const m = Math.floor(t / 60)
    const s = (t % 60).toFixed(1)
    return `${m}:${s.padStart(4, '0')}`
  }
  return t.toFixed(1) + 's'
}

function isClipSelected(ti: number, ci: number): boolean {
  return state.selection.track === ti && state.selection.clip === ci
}

function isKfSelected(ti: number, ci: number, ki: number): boolean {
  return isClipSelected(ti, ci) && state.selection.keyframe === ki
}

function cursorForMode(): string {
  switch (dragMode.value) {
    case 'clip-resize-l':
    case 'clip-resize-r': return 'col-resize'
    case 'clip-move': return 'grabbing'
    case 'keyframe': return 'grabbing'
    default: return 'default'
  }
}

// ── 生命周期 ──────────────────────────────────────────────────

onMounted(() => {
  const scroller = scrollerRef.value
  if (scroller) {
    scroller.addEventListener('wheel', onWheel, { passive: false })
  }
})

onUnmounted(() => {
  const scroller = scrollerRef.value
  if (scroller) {
    scroller.removeEventListener('wheel', onWheel)
  }
  onDragEnd()
})
</script>

<template>
  <div class="timeline" :style="{ cursor: cursorForMode() }">
    <!-- 工具栏 -->
    <div class="timeline-toolbar">
      <div class="toolbar-left">
        <span class="timeline-label">时间轴</span>
        <span class="time-display">{{ formatTime(state.time) }} / {{ formatTime(totalDuration) }}</span>
      </div>
      <div class="toolbar-right">
        <button
          class="tool-btn"
          :class="{ active: state.timelineTool === 'select' }"
          title="选择工具"
          @click="setTool('select')"
        >
          <img src="../assets/icons/treeview.svg" alt="" />
        </button>
        <button
          class="tool-btn"
          :class="{ active: state.timelineTool === 'razor' }"
          title="刀片工具（拆分片段）"
          @click="setTool('razor')"
        >
          <img src="../assets/icons/razor.svg" alt="" />
        </button>
        <div class="divider"></div>
        <button class="tool-btn" title="放大" @click="zoomIn">
          <img src="../assets/icons/zoomin.svg" alt="" />
        </button>
        <button class="tool-btn" title="缩小" @click="zoomOut">
          <img src="../assets/icons/zoomout.svg" alt="" />
        </button>
        <div class="divider"></div>
        <button
          class="tool-btn"
          :class="{ active: state.snapEnabled }"
          title="吸附"
          @click="toggleSnap"
        >
          <img src="../assets/icons/magnet.svg" alt="" />
        </button>
        <span class="zoom-label">{{ Math.round(pxPerSecond) }}px/s</span>
      </div>
    </div>

    <!-- 时间轴主体 -->
    <div class="timeline-body">
      <!-- 左侧轨道头列 -->
      <div class="track-header-col">
        <div class="ruler-spacer"></div>
        <div
          v-for="(track, ti) in tracks"
          :key="ti"
          class="track-header"
          :style="{ borderLeftColor: trackColor(track.type) }"
        >
          <div class="track-info">
            <span class="track-type">{{ trackLabel(track.type) }}</span>
            <span class="track-id">{{ track.id }}</span>
          </div>
          <div class="track-controls">
            <button
              class="track-ctrl"
              :class="{ off: !getTrackView(track.id).visible }"
              title="显隐"
              @click="toggleTrackVisible(track.id)"
            >
              <img src="../assets/icons/eye-opened.svg" alt="" />
            </button>
            <button
              class="track-ctrl"
              :class="{ off: getTrackView(track.id).locked }"
              title="锁定"
              @click="toggleTrackLocked(track.id)"
            >
              <img src="../assets/icons/lock-opened.svg" alt="" />
            </button>
            <button
              class="track-ctrl"
              :class="{ off: getTrackView(track.id).muted }"
              title="静音"
              @click="toggleTrackMuted(track.id)"
            >
              <img src="../assets/icons/listview.svg" alt="" />
            </button>
          </div>
          <button class="add-clip-btn" title="添加片段" @click="addClipToTrack(ti)">+</button>
        </div>
        <div class="add-track-row">
          <select class="add-track-select" @change="(e) => { addTrack((e.target as HTMLSelectElement).value as TrackType); (e.target as HTMLSelectElement).value = '' }">
            <option value="">+ 添加轨道</option>
            <option value="CAMERA">相机</option>
            <option value="LETTERBOX">遮幅</option>
            <option value="AUDIO">音频</option>
            <option value="EVENT">事件</option>
            <option value="MOD_EVENT">模组事件</option>
            <option value="OVERLAY">覆盖层</option>
          </select>
        </div>
      </div>

      <!-- 右侧可滚动内容 -->
      <div class="timeline-scroller" ref="scrollerRef">
        <div class="timeline-content" ref="contentRef" :style="{ width: contentWidth + 'px' }" @mousedown="onContentMouseDown">
          <!-- 时间标尺 -->
          <div class="ruler">
            <div
              v-for="mark in rulerMarks"
              :key="mark.time"
              class="ruler-mark"
              :class="{ major: mark.major }"
              :style="{ left: timeToPx(mark.time) + 'px' }"
            >
              <div class="tick"></div>
              <span v-if="mark.major" class="ruler-label">{{ mark.label }}</span>
            </div>
            <!-- 播放头 -->
            <div
              class="playhead"
              :style="{ left: timeToPx(state.time) + 'px' }"
              @mousedown="onPlayheadMouseDown"
            >
              <div class="playhead-handle"></div>
              <div class="playhead-line"></div>
            </div>
          </div>

          <!-- 轨道行 -->
          <div
            v-for="(track, ti) in tracks"
            :key="ti"
            class="track-row"
            :class="{ hidden: !getTrackView(track.id).visible }"
          >
            <div class="track-lines">
              <!-- Clip -->
              <div
                v-for="(clip, ci) in track.clips"
                :key="ci"
                class="clip"
                :class="{
                  active: isClipSelected(ti, ci),
                  infinite: clip.duration < 0,
                }"
                :style="{
                  left: timeToPx(clip.start_time) + 'px',
                  width: Math.max(8, (clip.duration < 0 ? totalDuration : clip.duration) * pxPerSecond) + 'px',
                  background: trackColor(track.type),
                }"
                @mousedown="onClipMouseDown($event, ti, ci)"
                @dblclick="onClipDblClick($event, ti, ci)"
              >
                <span class="clip-label">{{ clipLabel(clip, track.type) }}</span>
                <span class="clip-time">{{ formatTime(clip.start_time) }}</span>

                <!-- 关键帧 -->
                <div
                  v-for="(kf, ki) in clip.keyframes"
                  :key="ki"
                  class="keyframe"
                  :class="{ active: isKfSelected(ti, ci, ki) }"
                  :style="{ left: (kf.time / (clip.duration || 1)) * 100 + '%' }"
                  @mousedown="onKeyframeMouseDown($event, ti, ci, ki)"
                >
                  <div class="kf-diamond"></div>
                </div>

                <!-- resize 手柄 -->
                <div class="resize-handle left"></div>
                <div class="resize-handle right"></div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.timeline {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #1e1e24;
  color: #d8d8e0;
  user-select: none;
}

/* 工具栏 */
.timeline-toolbar {
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 8px;
  border-bottom: 1px solid #33333a;
  background: #202026;
  flex-shrink: 0;
}
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.timeline-label {
  font-size: 12px;
  color: #8a8a96;
}
.time-display {
  font-size: 12px;
  color: #4e7bd3;
  font-family: monospace;
}
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 2px;
}
.tool-btn {
  width: 26px;
  height: 26px;
  background: transparent;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.tool-btn:hover { background: #33333a; }
.tool-btn.active { background: #2f3b55; }
.tool-btn img { width: 15px; height: 15px; }
.divider {
  width: 1px;
  height: 18px;
  background: #33333a;
  margin: 0 4px;
}
.zoom-label {
  font-size: 11px;
  color: #666;
  margin-left: 6px;
  min-width: 50px;
}

/* 主体 */
.timeline-body {
  flex: 1;
  display: flex;
  min-height: 0;
  overflow: hidden;
}

/* 轨道头列 */
.track-header-col {
  width: 150px;
  flex-shrink: 0;
  border-right: 1px solid #33333a;
  background: #202026;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}
.ruler-spacer {
  height: 24px;
  border-bottom: 1px solid #33333a;
  flex-shrink: 0;
}
.track-header {
  height: 44px;
  display: flex;
  align-items: center;
  padding: 4px 6px;
  gap: 4px;
  border-bottom: 1px solid #2a2a30;
  border-left: 3px solid transparent;
  flex-shrink: 0;
}
.track-info {
  flex: 1;
  min-width: 0;
}
.track-type {
  display: block;
  font-size: 11px;
  color: #c9c9d2;
  font-weight: 600;
}
.track-id {
  display: block;
  font-size: 9px;
  color: #666;
}
.track-controls {
  display: flex;
  gap: 1px;
}
.track-ctrl {
  width: 20px;
  height: 20px;
  background: transparent;
  border: none;
  border-radius: 3px;
  cursor: pointer;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0.7;
}
.track-ctrl:hover { background: #33333a; opacity: 1; }
.track-ctrl.off { opacity: 0.25; }
.track-ctrl img { width: 12px; height: 12px; }
.add-clip-btn {
  width: 18px;
  height: 18px;
  background: #28282e;
  border: 1px solid #3a3a44;
  border-radius: 3px;
  color: #888;
  font-size: 14px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
  flex-shrink: 0;
}
.add-clip-btn:hover { background: #34343c; color: #ddd; }
.add-track-row {
  padding: 6px;
  flex-shrink: 0;
}
.add-track-select {
  width: 100%;
  background: #1a1a1e;
  color: #aaa;
  border: 1px solid #333;
  border-radius: 3px;
  padding: 3px;
  font-size: 11px;
}

/* 滚动区 */
.timeline-scroller {
  flex: 1;
  overflow-x: auto;
  overflow-y: hidden;
  position: relative;
}
.timeline-content {
  position: relative;
  min-height: 100%;
}

/* 标尺 */
.ruler {
  height: 24px;
  background: #18181c;
  border-bottom: 1px solid #33333a;
  position: relative;
  flex-shrink: 0;
  overflow: hidden;
}
.ruler-mark {
  position: absolute;
  top: 0;
  height: 100%;
}
.ruler-mark .tick {
  position: absolute;
  bottom: 0;
  left: 0;
  width: 1px;
  height: 6px;
  background: #444;
}
.ruler-mark.major .tick {
  height: 10px;
  background: #666;
}
.ruler-label {
  position: absolute;
  top: 2px;
  left: 3px;
  font-size: 10px;
  color: #777;
  white-space: nowrap;
}

/* 播放头 */
.playhead {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 0;
  z-index: 10;
  cursor: ew-resize;
}
.playhead-handle {
  position: absolute;
  top: 0;
  left: -5px;
  width: 10px;
  height: 12px;
  background: #ef4444;
  clip-path: polygon(0 0, 100% 0, 100% 60%, 50% 100%, 0 60%);
}
.playhead-line {
  position: absolute;
  top: 0;
  left: 0;
  width: 1px;
  height: 100vh;
  background: #ef4444;
  opacity: 0.8;
}

/* 轨道行 */
.track-row {
  height: 44px;
  border-bottom: 1px solid #2a2a30;
  position: relative;
}
.track-row.hidden {
  height: 20px;
  opacity: 0.4;
}
.track-lines {
  position: relative;
  height: 100%;
}

/* Clip */
.clip {
  position: absolute;
  top: 4px;
  height: 36px;
  border-radius: 4px;
  cursor: grab;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0,0,0,0.3);
  transition: box-shadow 0.1s;
}
.clip:hover {
  box-shadow: 0 2px 6px rgba(0,0,0,0.5);
}
.clip.active {
  outline: 2px solid #fff;
  outline-offset: -1px;
}
.clip.infinite {
  background: repeating-linear-gradient(
    45deg,
    transparent,
    transparent 6px,
    rgba(255,255,255,0.1) 6px,
    rgba(255,255,255,0.1) 12px
  ) !important;
}
.clip-label {
  position: absolute;
  top: 3px;
  left: 6px;
  font-size: 10px;
  color: rgba(255,255,255,0.9);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: calc(100% - 12px);
  pointer-events: none;
}
.clip-time {
  position: absolute;
  bottom: 2px;
  left: 6px;
  font-size: 9px;
  color: rgba(255,255,255,0.5);
  font-family: monospace;
  pointer-events: none;
}

/* 关键帧 */
.keyframe {
  position: absolute;
  top: 50%;
  transform: translate(-50%, -50%);
  z-index: 5;
  cursor: pointer;
  padding: 4px;
}
.kf-diamond {
  width: 8px;
  height: 8px;
  background: #fff;
  transform: rotate(45deg);
  box-shadow: 0 0 2px rgba(0,0,0,0.5);
}
.keyframe.active .kf-diamond {
  background: #ffd700;
  width: 10px;
  height: 10px;
}
.keyframe:hover .kf-diamond {
  background: #ffd700;
}

/* resize 手柄 */
.resize-handle {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 6px;
  cursor: col-resize;
}
.resize-handle.left {
  left: 0;
  border-right: 2px solid rgba(255,255,255,0.3);
}
.resize-handle.right {
  right: 0;
  border-left: 2px solid rgba(255,255,255,0.3);
}
.resize-handle:hover {
  background: rgba(255,255,255,0.15);
}
</style>
