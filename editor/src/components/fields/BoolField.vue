<script setup lang="ts">
import type { SchemaField } from '../../types'

defineProps<{
  field: SchemaField
  modelValue: unknown
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

function onChange(e: Event) {
  emit('update:modelValue', (e.target as HTMLInputElement).checked)
}
</script>

<template>
  <label class="bool-field">
    <input
      type="checkbox"
      :checked="!!modelValue"
      @change="onChange"
    />
    <span class="bool-label">{{ modelValue ? '开启' : '关闭' }}</span>
  </label>
</template>

<style scoped>
.bool-field {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}
.bool-field input {
  cursor: pointer;
}
.bool-label {
  font-size: 12px;
  color: #aaa;
}
</style>
