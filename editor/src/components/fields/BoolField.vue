<script setup lang="ts">
import type { SchemaField } from '../../types'
import { t } from '../../i18n'

const props = defineProps<{
  field: SchemaField
  modelValue: unknown
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

function onToggle(e: Event) {
  emit('update:modelValue', (e.target as HTMLInputElement).checked)
}

function labelText(): string {
  return props.modelValue ? t('bool.on') : t('bool.off')
}
</script>

<template>
  <div class="bool-field">
    <input
      type="checkbox"
      :checked="modelValue === true"
      @change="onToggle"
    />
    <span class="bool-label">{{ labelText() }}</span>
  </div>
</template>

<style scoped>
.bool-field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
  padding: 2px 0;
}
.bool-field input[type="checkbox"] {
  width: 14px;
  height: 14px;
  margin: 0;
  cursor: pointer;
  accent-color: #4e7bd3;
  flex-shrink: 0;
}
.bool-label {
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
  color: #34d399;
}
</style>
