<script setup lang="ts">
import type { SchemaField } from '../../types'

defineProps<{
  field: SchemaField
  modelValue: unknown
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

function onChange(e: Event) {
  emit('update:modelValue', (e.target as HTMLSelectElement).value)
}
</script>

<template>
  <select class="field-select" :value="modelValue ?? ''" @change="onChange">
    <option v-for="opt in field.enumValues" :key="opt" :value="opt">{{ opt }}</option>
  </select>
</template>

<style scoped>
.field-select {
  width: 100%;
  background: #111;
  color: #ddd;
  border: 1px solid #333;
  padding: 3px 4px;
  border-radius: 3px;
  font-size: 12px;
  box-sizing: border-box;
}
.field-select:focus {
  outline: none;
  border-color: #4e7bd3;
}
</style>
