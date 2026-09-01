<script setup lang="ts">
import { computed } from 'vue'
import { state, pushUndo } from '../store'

const selectedClip = computed(() => {
  const doc = state.doc as any
  const t = doc?.timeline?.tracks?.[state.selection.track]
  return t?.clips?.[state.selection.clip] || null
})

function setClipField(key: string, value: any) {
  if (!selectedClip.value) return
  pushUndo()
  selectedClip.value[key] = value
}

function setInput(key: string, event: Event) {
  setClipField(key, (event.target as HTMLInputElement).value)
}

function setNumber(key: string, event: Event) {
  setClipField(key, parseFloat((event.target as HTMLInputElement).value))
}

function setBool(key: string, event: Event) {
  setClipField(key, (event.target as HTMLInputElement).checked)
}
</script>

<template>
  <div class="clip-panel">
    <h3>片段</h3>
    <template v-if="selectedClip">
      <div class="field">
        <label>start_time</label>
        <input type="number" :value="selectedClip.start_time ?? 0" @input="setNumber('start_time', $event)" />
      </div>
      <div class="field">
        <label>duration</label>
        <input type="number" :value="selectedClip.duration ?? 1" @input="setNumber('duration', $event)" />
      </div>
      <div class="field">
        <label>transition</label>
        <input :value="selectedClip.transition ?? 'cut'" @input="setInput('transition', $event)" />
      </div>
      <div class="field">
        <label>interpolation</label>
        <input :value="selectedClip.interpolation ?? 'linear'" @input="setInput('interpolation', $event)" />
      </div>
      <div class="field">
        <label>loop</label>
        <input type="checkbox" :checked="!!selectedClip.loop" @change="setBool('loop', $event)" />
      </div>
    </template>
    <p v-else class="empty">未选择片段</p>
  </div>
</template>

<style scoped>
.clip-panel { padding: 10px; }
.clip-panel h3 { margin: 0 0 8px; }
.field { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.field label { color: #8a8a96; font-size: 12px; }
.field input { width: 150px; background: #111; color: #ddd; border: 1px solid #333; padding: 2px 4px; }
.empty { color: #666; font-size: 12px; }
</style>
