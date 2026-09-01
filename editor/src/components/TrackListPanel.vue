<script setup lang="ts">
import { computed, ref } from 'vue'
import { state, commit, selectTrack } from '../store'
import * as ops from '../operations'
import type { TrackType } from '../types'
import { t } from '../i18n'

const tracks = computed(() => state.doc?.timeline?.tracks || [])
const showAddMenu = ref(false)

const trackTypes: { type: TrackType; label: string; color: string }[] = [
  { type: 'CAMERA', label: '相机', color: '#4e7bd3' },
  { type: 'AUDIO', label: '音频', color: '#34d399' },
  { type: 'EVENT', label: '事件', color: '#f0b429' },
  { type: 'MOD_EVENT', label: '模组事件', color: '#a78bfa' },
  { type: 'OVERLAY', label: '覆盖层', color: '#f472b6' },
  { type: 'LETTERBOX', label: '遮幅', color: '#94a3b8' },
]

function trackLabel(type: string): string {
  const found = trackTypes.find(t => t.type === type)
  return found ? found.label : type
}

function trackColor(type: string): string {
  const found = trackTypes.find(t => t.type === type)
  return found ? found.color : '#666'
}

function addTrack(type: TrackType) {
  commit(() => {
    ops.addTrack(state.doc!.timeline!.tracks, type)
  })
  showAddMenu.value = false
}

function removeTrack(index: number) {
  if (tracks.value.length <= 1) return
  commit(() => {
    state.doc!.timeline!.tracks.splice(index, 1)
  })
}

function moveTrack(index: number, dir: -1 | 1) {
  const target = index + dir
  if (target < 0 || target >= tracks.value.length) return
  commit(() => {
    const arr = state.doc!.timeline!.tracks
    const [item] = arr.splice(index, 1)
    arr.splice(target, 0, item)
  })
}

function onTrackClick(index: number) {
  selectTrack(index)
}
</script>

<template>
  <div class="track-list-panel">
    <div class="panel-header">
      <span class="panel-title">{{ t('tab.tracks') }}</span>
      <div class="add-track-wrap">
        <button class="add-btn" @click="showAddMenu = !showAddMenu">+ {{ t('track.add') }}</button>
        <div v-if="showAddMenu" class="add-menu">
          <div
            v-for="tt in trackTypes"
            :key="tt.type"
            class="add-menu-item"
            @click="addTrack(tt.type)"
          >
            <span class="dot" :style="{ background: tt.color }"></span>
            <span>{{ tt.label }}</span>
          </div>
        </div>
      </div>
    </div>
    <div class="track-list">
      <div
        v-for="(track, i) in tracks"
        :key="i"
        class="track-item"
        :class="{ active: state.selection.track === i }"
        @click="onTrackClick(i)"
      >
        <span class="track-dot" :style="{ background: trackColor(track.type) }"></span>
        <span class="track-name">{{ trackLabel(track.type) }}</span>
        <span class="track-count">{{ track.clips?.length || 0 }}</span>
        <div class="track-actions" @click.stop>
          <button class="action-btn" title="上移" :disabled="i === 0" @click="moveTrack(i, -1)">↑</button>
          <button class="action-btn" title="下移" :disabled="i === tracks.length - 1" @click="moveTrack(i, 1)">↓</button>
          <button class="action-btn del" title="删除轨道" :disabled="tracks.length <= 1" @click="removeTrack(i)">×</button>
        </div>
      </div>
      <div v-if="!tracks.length" class="empty">暂无轨道</div>
    </div>
  </div>
</template>

<style scoped>
.track-list-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 10px;
  border-bottom: 1px solid #33333a;
  flex-shrink: 0;
}
.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #d8d8e0;
}
.add-track-wrap {
  position: relative;
}
.add-btn {
  font-size: 11px;
  padding: 3px 8px;
}
.add-menu {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 4px;
  background: #28282e;
  border: 1px solid #3a3a44;
  border-radius: 6px;
  padding: 4px;
  z-index: 100;
  min-width: 120px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.4);
}
.add-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}
.add-menu-item:hover {
  background: #34343c;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.track-list {
  flex: 1;
  overflow-y: auto;
  padding: 6px;
  min-height: 0;
}
.track-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
  background: #22262f;
  transition: background .1s;
}
.track-item:hover {
  background: #2a2f3b;
}
.track-item.active {
  background: #2f3b55;
  outline: 1px solid #4e7bd3;
}
.track-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
.track-name {
  flex: 1;
  font-size: 12px;
  color: #d8d8e0;
}
.track-count {
  font-size: 11px;
  color: #8a8a96;
  background: #1a1a1e;
  padding: 1px 6px;
  border-radius: 8px;
}
.track-actions {
  display: flex;
  gap: 2px;
  opacity: 0;
  transition: opacity .15s;
}
.track-item:hover .track-actions {
  opacity: 1;
}
.action-btn {
  width: 20px;
  height: 20px;
  padding: 0;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  color: #8a8a96;
  cursor: pointer;
  border-radius: 3px;
}
.action-btn:hover:not(:disabled) {
  background: #3a3a44;
  color: #d8d8e0;
}
.action-btn.del:hover:not(:disabled) {
  color: #ef4444;
}
.action-btn:disabled {
  opacity: .2;
  cursor: default;
}
.empty {
  color: #666;
  font-size: 12px;
  padding: 16px;
  text-align: center;
}
</style>
