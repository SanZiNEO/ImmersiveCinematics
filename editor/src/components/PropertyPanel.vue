<script setup lang="ts">
import { state } from '../store'

const META_FIELDS = [
  'id', 'name', 'author', 'version', 'description', 'dimension',
  'block_keyboard', 'block_mouse', 'block_mob_ai', 'hide_hud',
  'render_player_model', 'pause_when_game_paused', 'interruptible',
  'skippable', 'hold_at_end', 'priority'
]

function setMeta(key: string, value: any) {
  if (!state.doc) return
  if (!state.doc.meta) state.doc.meta = {}
  state.doc.meta[key] = value
}

function setBool(key: string, event: Event) {
  setMeta(key, (event.target as HTMLInputElement).checked)
}

function setNumber(key: string, event: Event) {
  setMeta(key, parseFloat((event.target as HTMLInputElement).value))
}

function setString(key: string, event: Event) {
  setMeta(key, (event.target as HTMLInputElement).value)
}
</script>

<template>
  <div class="property-panel">
    <h3>属性</h3>
    <template v-if="state.doc?.meta">
      <div v-for="key in META_FIELDS" :key="key" class="field">
        <label>{{ key }}</label>
        <input v-if="typeof state.doc.meta[key] === 'boolean'"
               type="checkbox"
               :checked="!!state.doc.meta[key]"
               @change="setBool(key, $event)" />
        <input v-else-if="typeof state.doc.meta[key] === 'number'"
               type="number"
               :value="state.doc.meta[key]"
               @input="setNumber(key, $event)" />
        <input v-else
               :value="state.doc.meta[key] ?? ''"
               @input="setString(key, $event)" />
      </div>
    </template>
    <p v-else>未打开脚本</p>
  </div>
</template>

<style scoped>
.property-panel { padding: 10px; }
.property-panel h3 { margin: 0 0 8px; }
.field { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.field label { color: #8a8a96; font-size: 12px; }
.field input { width: 150px; background: #111; color: #ddd; border: 1px solid #333; padding: 2px 4px; }
</style>
