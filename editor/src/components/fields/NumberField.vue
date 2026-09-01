<script setup lang="ts">
import { computed } from 'vue'
import type { SchemaField } from '../../types'

const props = defineProps<{
  field: SchemaField
  modelValue: unknown
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: number): void
}>()

const step = computed(() => props.field.type === 'int' ? 1 : 0.1)

function onInput(e: Event) {
  const val = parseFloat((e.target as HTMLInputElement).value)
  emit('update:modelValue', isNaN(val) ? 0 : val)
}
</script>

<template>
  <input
    type="number"
    class="field-input"
    :value="modelValue ?? 0"
    :step="step"
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
