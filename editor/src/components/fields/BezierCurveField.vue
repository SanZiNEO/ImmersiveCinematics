<script setup lang="ts">
import { computed } from 'vue'
import type { SchemaField } from '../../types'

const props = defineProps<{
  field: SchemaField
  modelValue: unknown
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, unknown>): void
}>()

const curve = computed(() => {
  const v = props.modelValue
  if (v && typeof v === 'object') return v as Record<string, unknown>
  return { type: 'bezier', control_points: [] }
})

const points = computed(() => {
  const cp = curve.value.control_points
  return Array.isArray(cp) ? cp : []
})

function addPoint() {
  const pts = [...points.value, { dx: 0, dy: 2, dz: 0 }]
  emit('update:modelValue', { ...curve.value, control_points: pts })
}

function removePoint(idx: number) {
  const pts = points.value.filter((_, i) => i !== idx)
  emit('update:modelValue', { ...curve.value, control_points: pts })
}

function setPoint(idx: number, key: string, e: Event) {
  const val = parseFloat((e.target as HTMLInputElement).value)
  const pts = points.value.map((p, i) =>
    i === idx ? { ...p, [key]: isNaN(val) ? 0 : val } : p
  )
  emit('update:modelValue', { ...curve.value, control_points: pts })
}
</script>

<template>
  <div class="bezier-field">
    <div v-for="(p, i) in points" :key="i" class="cp-row">
      <span class="cp-label">P{{ i + 1 }}</span>
      <input type="number" step="0.1" :value="p.dx ?? p.x ?? 0" @input="setPoint(i, p.dx !== undefined ? 'dx' : 'x', $event)" placeholder="x" />
      <input type="number" step="0.1" :value="p.dy ?? p.y ?? 0" @input="setPoint(i, p.dy !== undefined ? 'dy' : 'y', $event)" placeholder="y" />
      <input type="number" step="0.1" :value="p.dz ?? p.z ?? 0" @input="setPoint(i, p.dz !== undefined ? 'dz' : 'z', $event)" placeholder="z" />
      <button class="cp-del" @click="removePoint(i)">×</button>
    </div>
    <button class="cp-add" @click="addPoint">+ 控制点</button>
    <p class="hint">dx/dy/dz = 相对段起点；x/y/z = 绝对坐标</p>
  </div>
</template>

<style scoped>
.bezier-field {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.cp-row {
  display: flex;
  gap: 3px;
  align-items: center;
}
.cp-label {
  width: 22px;
  font-size: 11px;
  color: #888;
}
.cp-row input {
  width: 50px;
  background: #111;
  color: #ddd;
  border: 1px solid #333;
  padding: 2px 3px;
  border-radius: 3px;
  font-size: 11px;
}
.cp-del {
  background: transparent;
  border: none;
  color: #f66;
  cursor: pointer;
  padding: 0 4px;
  font-size: 14px;
}
.cp-add {
  background: #28282e;
  color: #aaa;
  border: 1px solid #3a3a44;
  border-radius: 3px;
  padding: 3px 8px;
  font-size: 11px;
  cursor: pointer;
  align-self: flex-start;
}
.hint {
  font-size: 10px;
  color: #666;
  margin: 2px 0 0;
}
</style>
