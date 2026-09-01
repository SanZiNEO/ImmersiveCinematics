<script setup lang="ts">
import type { SchemaField } from '../../types'

defineProps<{
  field: SchemaField
  modelValue: unknown
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

function onInput(e: Event) {
  emit('update:modelValue', (e.target as HTMLInputElement).value)
}
</script>

<template>
  <input
    type="text"
    class="field-input"
    :value="modelValue ?? ''"
    :placeholder="field.required ? '必填' : ''"
    @input="onInput"
  />
</template>

<style scoped>
.field-input {
  width: 100%;
  background: #111;
  color: #ddd;
  border: 1px solid #333;
  padding: 3px 6px;
  border-radius: 3px;
  font-size: 12px;
  box-sizing: border-box;
}
.field-input:focus {
  outline: none;
  border-color: #4e7bd3;
}
</style>
