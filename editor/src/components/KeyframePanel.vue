<script setup lang="ts">
import { computed } from 'vue'
import { state, commit, getSelectedClip, getSelectedTrack, selectKeyframe } from '../store'
import { fillKeyframeDefaults } from '../schema'
import * as ops from '../operations'
import DynamicForm from './DynamicForm.vue'
import type { SchemaField } from '../types'

const selectedTrack = computed(() => getSelectedTrack())
const selectedClip = computed(() => getSelectedClip())

const keyframes = computed(() => selectedClip.value?.keyframes ?? [])

const kfFields = computed(() => {
  if (!state.schema || !selectedTrack.value) return {}
  return state.schema.tracks[selectedTrack.value.type]?.keyframes ?? {}
})

// 合并 time（结构字段）和 schema 字段
const allFields = computed<Record<string, SchemaField>>(() => {
  const structural: Record<string, SchemaField> = {
    time: { type: 'float', default: 0, required: true, enumValues: [], section: 'info' },
  }
  return { ...structural, ...kfFields.value }
})

const selectedKf = computed(() => {
  if (!selectedClip.value) return null
  return selectedClip.value.keyframes[state.selection.keyframe] ?? null
})

const kfData = computed(() => {
  if (!selectedKf.value) return {}
  return selectedKf.value as Record<string, unknown>
})

function onUpdate(key: string, value: unknown) {
  const kf = selectedKf.value
  if (!kf) return
  commit(() => {
    ;(kf as Record<string, unknown>)[key] = value
    if (key === 'time' && selectedClip.value) {
      ops.sortKeyframes(selectedClip.value)
      // 更新选中索引
      const idx = selectedClip.value.keyframes.indexOf(kf)
      if (idx >= 0) state.selection.keyframe = idx
    }
  })
}

function addKeyframe() {
  const clip = selectedClip.value
  const track = selectedTrack.value
  if (!clip || !track) return
  commit(() => {
    const kf = ops.addKeyframeAt(clip, state.time)
    if (kf && state.schema) {
      fillKeyframeDefaults(kf as Record<string, unknown>, state.schema, track.type)
      ops.interpolateNewKeyframe(clip, kf)
      const idx = clip.keyframes.indexOf(kf)
      if (idx >= 0) selectKeyframe(state.selection.track, state.selection.clip, idx)
    }
  })
}

function deleteKeyframe() {
  const clip = selectedClip.value
  const kf = selectedKf.value
  if (!clip || !kf) return
  commit(() => {
    ops.deleteKeyframe(clip, kf)
    state.selection.keyframe = -1
  })
}

function ensureDefaults() {
  const kf = selectedKf.value
  const track = selectedTrack.value
  if (!kf || !track || !state.schema) return
  commit(() => {
    fillKeyframeDefaults(kf as Record<string, unknown>, state.schema, track.type)
  })
}

function formatTime(t: number): string {
  return t.toFixed(2)
}
</script>

<template>
  <div class="kf-panel">
    <div class="panel-header">
      <span>关键帧</span>
      <span v-if="selectedClip" class="kf-count">{{ keyframes.length }} 帧</span>
    </div>

    <div v-if="selectedClip" class="kf-list">
      <div class="kf-list-header">
        <button class="add-btn" @click="addKeyframe" title="在当前播放头位置添加关键帧">+ 添加关键帧</button>
        <button v-if="selectedKf" class="del-btn" @click="deleteKeyframe">删除选中</button>
      </div>
      <div class="kf-items">
        <div
          v-for="(kf, i) in keyframes"
          :key="i"
          class="kf-item"
          :class="{ active: state.selection.keyframe === i }"
          @click="selectKeyframe(state.selection.track, state.selection.clip, i)"
        >
          <span class="kf-diamond"></span>
          <span class="kf-time">{{ formatTime(kf.time) }}s</span>
          <span v-if="kf.yaw !== undefined" class="kf-preview">yaw={{ kf.yaw?.toFixed(0) }}</span>
        </div>
      </div>
    </div>

    <div v-if="selectedKf && state.schema" class="kf-props">
      <DynamicForm :fields="allFields" :data="kfData" @update="onUpdate" />
      <div class="panel-actions">
        <button @click="ensureDefaults">补齐默认字段</button>
      </div>
    </div>

    <p v-if="!selectedClip" class="empty">未选择片段</p>
  </div>
</template>

<style scoped>
.kf-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 10px;
  border-bottom: 1px solid #33333a;
  background: #202026;
  font-size: 13px;
  font-weight: 600;
}
.kf-count {
  font-size: 11px;
  color: #6a8abf;
  font-weight: normal;
}
.kf-list {
  border-bottom: 1px solid #2a2a30;
  max-height: 160px;
  display: flex;
  flex-direction: column;
}
.kf-list-header {
  display: flex;
  gap: 4px;
  padding: 6px 8px;
}
.add-btn, .del-btn {
  flex: 1;
  font-size: 11px;
  padding: 3px 6px;
}
.del-btn {
  color: #f88;
}
.kf-items {
  overflow-y: auto;
  flex: 1;
}
.kf-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  cursor: pointer;
  font-size: 12px;
}
.kf-item:hover {
  background: #252530;
}
.kf-item.active {
  background: #2f3b55;
}
.kf-diamond {
  width: 8px;
  height: 8px;
  background: #4e7bd3;
  transform: rotate(45deg);
  flex-shrink: 0;
}
.kf-time {
  color: #ccc;
  font-family: monospace;
}
.kf-preview {
  color: #666;
  font-size: 11px;
  margin-left: auto;
}
.kf-props {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0;
}
.panel-actions {
  padding: 8px 10px;
  border-top: 1px solid #2a2a30;
}
.panel-actions button {
  width: 100%;
}
.empty {
  padding: 16px;
  color: #666;
  font-size: 12px;
}
</style>
