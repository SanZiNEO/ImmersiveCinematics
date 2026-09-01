<script setup lang="ts">
import { computed } from 'vue'
import { state, pushUndo } from '../store'

const META_FIELDS = [
  'id', 'name', 'author', 'version', 'description', 'dimension',
  'block_keyboard', 'block_mouse', 'block_mob_ai', 'hide_hud',
  'render_player_model', 'pause_when_game_paused', 'interruptible',
  'skippable', 'hold_at_end', 'priority'
]

const selectedClip = computed(() => {
  const doc = state.doc as any
  const t = doc?.timeline?.tracks?.[state.selection.track]
  return t?.clips?.[state.selection.clip] || null
})

const selectedKeyframe = computed(() => {
  const kfs = selectedClip.value?.keyframes || []
  return kfs[state.selection.keyframe] || null
})

function boolValue(key: string): boolean {
  return !!state.doc?.meta?.[key]
}

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

function setClipField(key: string, value: any) {
  if (!selectedClip.value) return
  pushUndo()
  selectedClip.value[key] = value
}

function setClipInput(key: string, event: Event) {
  setClipField(key, (event.target as HTMLInputElement).value)
}

function setClipNumber(key: string, event: Event) {
  setClipField(key, parseFloat((event.target as HTMLInputElement).value))
}

function setClipBool(key: string, event: Event) {
  setClipField(key, (event.target as HTMLInputElement).checked)
}

function selectKeyframe(index: number) {
  state.selection.keyframe = index
}

function addKeyframe() {
  if (!selectedClip.value) return
  pushUndo()
  const kfs = selectedClip.value.keyframes || []
  const time = selectedClip.value.duration || 5
  selectedClip.value.keyframes = [
    ...kfs,
    { time, position: { dx: 0, dy: 2, dz: 0 }, yaw: 0, pitch: 0, roll: 0, fov: 70, zoom: 1 },
  ]
  state.selection.keyframe = selectedClip.value.keyframes.length - 1
}

function deleteKeyframe() {
  if (!selectedClip.value || state.selection.keyframe < 0) return
  pushUndo()
  selectedClip.value.keyframes.splice(state.selection.keyframe, 1)
  state.selection.keyframe = -1
}

function setKfField(key: string, value: any) {
  if (!selectedKeyframe.value) return
  pushUndo()
  selectedKeyframe.value[key] = value
}

function setKfInput(key: string, event: Event) {
  setKfField(key, (event.target as HTMLInputElement).value)
}

function setKfNumber(key: string, event: Event) {
  setKfField(key, parseFloat((event.target as HTMLInputElement).value))
}

function setPosField(key: string, event: Event) {
  if (!selectedKeyframe.value) return
  pushUndo()
  const pos = selectedKeyframe.value.position || {}
  pos[key] = parseFloat((event.target as HTMLInputElement).value)
  selectedKeyframe.value.position = pos
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
               :checked="boolValue(key)"
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

    <template v-if="selectedClip">
      <h4>片段属性</h4>
      <div class="field">
        <label>start_time</label>
        <input type="number" :value="selectedClip.start_time ?? 0" @input="setClipNumber('start_time', $event)" />
      </div>
      <div class="field">
        <label>duration</label>
        <input type="number" :value="selectedClip.duration ?? 1" @input="setClipNumber('duration', $event)" />
      </div>
      <div class="field">
        <label>transition</label>
        <input :value="selectedClip.transition ?? 'cut'" @input="setClipInput('transition', $event)" />
      </div>
      <div class="field">
        <label>interpolation</label>
        <input :value="selectedClip.interpolation ?? 'linear'" @input="setClipInput('interpolation', $event)" />
      </div>
      <div class="field">
        <label>loop</label>
        <input type="checkbox" :checked="!!selectedClip.loop" @change="setClipBool('loop', $event)" />
      </div>

      <h4>关键帧 <button @click="addKeyframe">+</button></h4>
      <div v-for="(kf, i) in (selectedClip.keyframes || [])" :key="i"
           class="kf-item" :class="{ active: state.selection.keyframe === i }"
           @click="selectKeyframe(i)">
        {{ kf.time }}
      </div>
      <button v-if="state.selection.keyframe >= 0" @click="deleteKeyframe">删除关键帧</button>

      <template v-if="selectedKeyframe">
        <div class="field">
          <label>time</label>
          <input type="number" :value="selectedKeyframe.time ?? 0" @input="setKfNumber('time', $event)" />
        </div>
        <div class="field">
          <label>yaw</label>
          <input type="number" :value="selectedKeyframe.yaw ?? 0" @input="setKfNumber('yaw', $event)" />
        </div>
        <div class="field">
          <label>pitch</label>
          <input type="number" :value="selectedKeyframe.pitch ?? 0" @input="setKfNumber('pitch', $event)" />
        </div>
        <div class="field">
          <label>roll</label>
          <input type="number" :value="selectedKeyframe.roll ?? 0" @input="setKfNumber('roll', $event)" />
        </div>
        <div class="field">
          <label>fov</label>
          <input type="number" :value="selectedKeyframe.fov ?? 70" @input="setKfNumber('fov', $event)" />
        </div>
        <div class="field">
          <label>zoom</label>
          <input type="number" :value="selectedKeyframe.zoom ?? 1" @input="setKfNumber('zoom', $event)" />
        </div>
        <div class="field">
          <label>dx</label>
          <input type="number" :value="selectedKeyframe.position?.dx ?? 0" @input="setPosField('dx', $event)" />
        </div>
        <div class="field">
          <label>dy</label>
          <input type="number" :value="selectedKeyframe.position?.dy ?? 0" @input="setPosField('dy', $event)" />
        </div>
        <div class="field">
          <label>dz</label>
          <input type="number" :value="selectedKeyframe.position?.dz ?? 0" @input="setPosField('dz', $event)" />
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.property-panel { padding: 10px; }
.property-panel h3 { margin: 0 0 8px; }
.property-panel h4 { margin: 14px 0 6px; border-top: 1px solid #333; padding-top: 8px; }
.field { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.field label { color: #888; font-size: 12px; }
.field input { width: 150px; background: #111; color: #ddd; border: 1px solid #333; padding: 2px 4px; }
.kf-item {
  padding: 2px 6px;
  cursor: pointer;
  background: #101318;
  margin-bottom: 2px;
}
.kf-item.active { background: #2f3b55; }
</style>
