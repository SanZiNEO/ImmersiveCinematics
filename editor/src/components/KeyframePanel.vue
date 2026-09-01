<script setup lang="ts">
import { computed } from 'vue'
import { state, pushUndo } from '../store'

const selectedClip = computed(() => {
  const doc = state.doc as any
  const t = doc?.timeline?.tracks?.[state.selection.track]
  return t?.clips?.[state.selection.clip] || null
})

const selectedKeyframe = computed(() => {
  const kfs = selectedClip.value?.keyframes || []
  return kfs[state.selection.keyframe] || null
})

function selectKeyframe(index: number) {
  state.selection.keyframe = index
}

function addKeyframe() {
  if (!selectedClip.value) return
  pushUndo()
  const kfs = selectedClip.value.keyframes || []
  selectedClip.value.keyframes = [
    ...kfs,
    { time: selectedClip.value.duration || 5, position: { dx: 0, dy: 2, dz: 0 }, yaw: 0, pitch: 0, roll: 0, fov: 70, zoom: 1 },
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
  <div class="keyframe-panel">
    <h3>关键帧</h3>
    <template v-if="selectedClip">
      <button @click="addKeyframe">添加关键帧</button>
      <div v-for="(kf, i) in (selectedClip.keyframes || [])" :key="i"
           class="kf-item"
           :class="{ active: state.selection.keyframe === i }"
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
    <p v-else class="empty">未选择片段</p>
  </div>
</template>

<style scoped>
.keyframe-panel { padding: 10px; }
.keyframe-panel h3 { margin: 0 0 8px; }
.field { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.field label { color: #8a8a96; font-size: 12px; }
.field input { width: 140px; background: #111; color: #ddd; border: 1px solid #333; padding: 2px 4px; }
.kf-item {
  padding: 2px 6px;
  cursor: pointer;
  background: #101318;
  margin-bottom: 2px;
  font-size: 12px;
}
.kf-item.active { background: #2f3b55; }
.empty { color: #666; font-size: 12px; }
</style>
