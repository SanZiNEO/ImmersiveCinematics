<script setup lang="ts">
import { computed } from 'vue'
import type { SchemaField } from '../../types'

const props = defineProps<{
  field: SchemaField
  modelValue: unknown
  parent?: Record<string, unknown>
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, number>): void
}>()

const pos = computed(() => {
  const v = props.modelValue
  if (v && typeof v === 'object') return v as Record<string, number>
  return {}
})

// 读取 position_mode 决定显示 dx/dy/dz 还是 x/y/z
const isAbsolute = computed(() => props.parent?.position_mode === 'absolute')

const keys = computed(() => isAbsolute.value ? ['x', 'y', 'z'] : ['dx', 'dy', 'dz'])

function setKey(key: string, e: Event) {
  const val = parseFloat((e.target as HTMLInputElement).value)
  emit('update:modelValue', { ...pos.value, [key]: isNaN(val) ? 0 : val })
}
</script>

<template>
  <div class="position-field">
    <div v-for="k in keys" :key="k" class="pos-row">
      <span class="pos-key">{{ k }}</span>
      <input
        type="number"
        step="0.1"
        :value="pos[k] ?? 0"
        @input="setKey(k, $event)"
      />
    </div>
  </div>
</template>

<style scoped>
.position-field {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.pos-row {
  display: flex;
  align-items: center;
  gap: 4px;
}
.pos-key {
  width: 24px;
  font-size: 11px;
  color: #888;
  text-align: right;
}
.pos-row input {
  flex: 1;
  background: #111;
  color: #ddd;
  border: 1px solid #333;
  padding: 2px 4px;
  border-radius: 3px;
  font-size: 12px;
}
</style>
