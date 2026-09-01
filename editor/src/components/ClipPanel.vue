<script setup lang="ts">
import { computed } from 'vue'
import { state, commit, getSelectedClip, getSelectedTrack } from '../store'
import { fillClipDefaults } from '../schema'
import DynamicForm from './DynamicForm.vue'
import type { Clip, SchemaField } from '../types'

const selectedTrack = computed(() => getSelectedTrack())
const selectedClip = computed(() => getSelectedClip())

const clipFields = computed(() => {
  if (!state.schema || !selectedTrack.value) return {}
  return state.schema.tracks[selectedTrack.value.type]?.clips ?? {}
})

// 合并 start_time / duration（结构字段，不在 schema 里）和 schema 字段
const allFields = computed<Record<string, SchemaField>>(() => {
  const structural: Record<string, SchemaField> = {
    start_time: { type: 'float', default: 0, required: true, enumValues: [], section: 'info' },
    duration: { type: 'float', default: 5, required: true, enumValues: [], section: 'info' },
  }
  return { ...structural, ...clipFields.value }
})

const clipData = computed(() => {
  if (!selectedClip.value) return {}
  return selectedClip.value as Record<string, unknown>
})

function onUpdate(key: string, value: unknown) {
  const clip = selectedClip.value
  if (!clip) return
  commit(() => {
    ;(clip as Record<string, unknown>)[key] = value
  })
}

function ensureDefaults() {
  const clip = selectedClip.value
  const track = selectedTrack.value
  if (!clip || !track || !state.schema) return
  commit(() => {
    fillClipDefaults(clip as Record<string, unknown>, state.schema!, track.type)
  })
}
</script>

<template>
  <div class="clip-panel">
    <div class="panel-header">
      <span>片段属性</span>
      <span v-if="selectedClip" class="clip-info">
        {{ selectedTrack?.type }} #{{ state.selection.clip + 1 }}
      </span>
    </div>
    <div v-if="selectedClip && state.schema" class="panel-body">
      <DynamicForm :fields="allFields" :data="clipData" @update="onUpdate" />
      <div class="panel-actions">
        <button @click="ensureDefaults">补齐默认字段</button>
      </div>
    </div>
    <p v-else class="empty">未选择片段</p>
  </div>
</template>

<style scoped>
.clip-panel {
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
.clip-info {
  font-size: 11px;
  color: #6a8abf;
  font-weight: normal;
}
.panel-body {
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
