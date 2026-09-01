<script setup lang="ts">
import { computed } from 'vue'
import { state, seek, pushUndo } from '../store'

const tracks = computed(() => {
  if (!state.doc?.timeline?.tracks) return [] as any[]
  return state.doc.timeline.tracks as any[]
})

function selectClip(ti: number, ci: number) {
  state.selection.track = ti
  state.selection.clip = ci
  state.selection.keyframe = -1
}

function addTrack() {
  if (!state.doc?.timeline) return
  pushUndo()
  const tracksArr = state.doc.timeline.tracks as any[]
  const id = 'track_' + (tracksArr.length + 1)
  tracksArr.push({ type: 'CAMERA', id, clips: [] })
  state.selection.track = tracksArr.length - 1
  state.selection.clip = -1
}

function addClip() {
  if (!state.doc?.timeline) return
  const tracksArr = state.doc.timeline.tracks as any[]
  if (!tracksArr.length) addTrack()
  pushUndo()
  const ti = Math.max(0, state.selection.track < 0 ? 0 : state.selection.track)
  const track = tracksArr[Math.min(ti, tracksArr.length - 1)]
  const clips = track.clips || []
  const end = clips.reduce((max: number, c: any) => Math.max(max, (c.start_time || 0) + (c.duration || 0)), 0)
  const clip = {
    start_time: end,
    duration: 5,
    transition: 'cut',
    interpolation: 'linear',
    loop: false,
    keyframes: [
      { time: 0, position: { dx: 0, dy: 2, dz: 0 }, yaw: 0, pitch: 0, roll: 0, fov: 70, zoom: 1 },
      { time: 5, position: { dx: 5, dy: 2, dz: 0 }, yaw: 0, pitch: 0, roll: 0, fov: 70, zoom: 1 },
    ],
  }
  clips.push(clip)
  track.clips = clips
  selectClip(tracksArr.indexOf(track), clips.length - 1)
}

function deleteSelected() {
  if (state.selection.track < 0 || state.selection.clip < 0) return
  const track = tracks.value[state.selection.track]
  if (!track) return
  pushUndo()
  track.clips.splice(state.selection.clip, 1)
  state.selection.clip = -1
}

function moveSelected(delta: number) {
  if (state.selection.track < 0 || state.selection.clip < 0) return
  const clip = tracks.value[state.selection.track]?.clips?.[state.selection.clip]
  if (!clip) return
  pushUndo()
  clip.start_time = Math.max(0, (clip.start_time || 0) + delta)
}

function formatTime(t: number) {
  return t.toFixed(1)
}

function trackColor(type: string) {
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
</script>

<template>
  <div class="timeline">
    <div class="timeline-toolbar">
      <div class="toolbar-left">
        <span class="timeline-label">时间轴</span>
      </div>
      <div class="toolbar-right">
        <button class="icon-btn" title="放大">
          <img src="../assets/icons/zoomin.svg" alt="" />
        </button>
        <button class="icon-btn" title="缩小">
          <img src="../assets/icons/zoomout.svg" alt="" />
        </button>
        <button class="icon-btn" title="关键帧">
          <img src="../assets/icons/treeview.svg" alt="" />
        </button>
        <button class="icon-btn" title="吸附">
          <img src="../assets/icons/magnet.svg" alt="" />
        </button>
        <button class="icon-btn" title="循环">
          <img src="../assets/icons/track-tool.svg" alt="" />
        </button>
      </div>
    </div>

    <div class="timeline-body">
      <div class="tool-rail">
        <button class="icon-btn rail" title="选择" @click="state.selection.clip = -1">
          <img src="../assets/icons/treeview.svg" alt="" />
        </button>
        <button class="icon-btn rail" title="添加轨道" @click="addTrack">
          <img src="../assets/icons/plus.svg" alt="" />
        </button>
        <button class="icon-btn rail" title="添加Clip" @click="addClip">
          <img src="../assets/icons/new.svg" alt="" />
        </button>
        <button class="icon-btn rail" title="拆分">
          <img src="../assets/icons/razor.svg" alt="" />
        </button>
        <button class="icon-btn rail" title="转场">
          <img src="../assets/icons/transition-tool.svg" alt="" />
        </button>
        <button class="icon-btn rail" title="删除选中" @click="deleteSelected">
          <img src="../assets/icons/minus.svg" alt="" />
        </button>
      </div>

      <div class="timeline-canvas">
        <div class="ruler">
          <span>0s</span>
          <span>播放头: {{ state.time.toFixed(2) }}</span>
        </div>
        <div v-for="(track, ti) in tracks" :key="ti" class="track">
          <div class="track-header">{{ track.type }} #{{ ti + 1 }}</div>
          <div class="track-clips">
            <div v-for="(clip, ci) in (track.clips || [])" :key="ci"
                 class="clip"
                 :class="{ active: state.selection.track === ti && state.selection.clip === ci }"
                 :style="{ left: (clip.start_time || 0) * 40 + 'px', width: Math.max(8, (clip.duration || 1) * 40) + 'px', background: trackColor(track.type) }"
                 @click="selectClip(ti, ci)">
              {{ formatTime(clip.start_time || 0) }}
            </div>
          </div>
        </div>
        <div v-if="!tracks.length" class="empty">打开脚本后显示轨道</div>
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
}
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
.timeline-label { font-size: 12px; color: #8a8a96; }
.toolbar-right { display: flex; gap: 4px; }
.icon-btn {
  width: 26px;
  height: 26px;
  background: transparent;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.icon-btn:hover { background: #33333a; }
.icon-btn img { width: 16px; height: 16px; }
.timeline-body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.tool-rail {
  width: 40px;
  border-right: 1px solid #33333a;
  background: #202026;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 6px;
  gap: 4px;
  flex-shrink: 0;
}
.rail { width: 30px; height: 30px; }
.timeline-canvas {
  flex: 1;
  min-width: 0;
  overflow: auto;
  padding: 8px 12px;
}
.ruler {
  background: #18181c;
  padding: 4px 6px;
  margin-bottom: 6px;
  border-radius: 4px;
  font-size: 11px;
  color: #8a8a96;
}
.track { display: flex; margin-bottom: 4px; }
.track-header {
  width: 130px;
  background: #28282e;
  padding: 4px 6px;
  font-size: 11px;
  color: #c9c9d2;
  flex-shrink: 0;
}
.track-clips {
  position: relative;
  flex: 1;
  background: #141418;
  height: 24px;
  overflow: hidden;
}
.clip {
  position: absolute;
  top: 2px;
  height: 20px;
  border-radius: 3px;
  font-size: 10px;
  line-height: 20px;
  padding: 0 4px;
  white-space: nowrap;
  cursor: pointer;
  color: #fff;
}
.clip.active { outline: 2px solid #fff; }
.empty { color: #666; font-size: 12px; padding: 10px; }
</style>
