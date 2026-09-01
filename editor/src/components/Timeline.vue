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
    <div class="toolbar">
      <button @click="addTrack">添加轨道</button>
      <button @click="addClip">添加片段</button>
      <button @click="deleteSelected">删除选中</button>
      <button @click="moveSelected(-0.5)">←0.5</button>
      <button @click="moveSelected(0.5)">0.5→</button>
      <button @click="seek(Math.max(0, state.time - 5))">⏪5s</button>
      <button @click="seek(state.time + 5)">5s⏩</button>
    </div>
    <div class="ruler">
      <span>0s</span>
      <span>播放头: {{ state.time.toFixed(2) }}</span>
      <span>选中: T{{ state.selection.track }} C{{ state.selection.clip }} K{{ state.selection.keyframe }}</span>
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
</template>

<style scoped>
.timeline { padding: 8px 12px; height: 100%; overflow: auto; }
.toolbar { margin-bottom: 6px; }
.toolbar button { margin-right: 4px; }
.ruler { background: #101318; padding: 4px; margin-bottom: 6px; }
.ruler span { margin-right: 12px; }
.track { display: flex; margin-bottom: 4px; }
.track-header {
  width: 140px;
  background: #252a35;
  padding: 4px 6px;
  font-size: 12px;
  flex-shrink: 0;
}
.track-clips {
  position: relative;
  flex: 1;
  background: #0d0f13;
  height: 24px;
  overflow: hidden;
}
.clip {
  position: absolute;
  top: 2px;
  height: 20px;
  background: #3b4a6b;
  border-radius: 3px;
  font-size: 10px;
  line-height: 20px;
  padding: 0 4px;
  white-space: nowrap;
  cursor: pointer;
}
.clip.active { background: #4e7bd3; }
.empty { color: #888; }
</style>
