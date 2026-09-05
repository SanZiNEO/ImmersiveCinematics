<script setup lang="ts">
import { computed } from 'vue'
import type { SchemaField } from '../../types'
import RegistryStringField from '../fields/RegistryStringField.vue'

const props = defineProps<{
  data: Record<string, unknown>
  fieldKey: string
  label?: string
}>()

const emit = defineEmits<{
  (e: 'update', key: string, value: unknown): void
}>()

const fieldDef = computed<SchemaField>(() => ({
  type: 'string',
  default: null,
  required: true,
  enumValues: [],
  section: 'conditions',
}))

function onUpdate(value: unknown) {
  emit('update', props.fieldKey, value)
}
</script>

<template>
  <div class="single-field-editor">
    <div class="field-row">
      <label>{{ label || fieldKey }}</label>
      <RegistryStringField
        :field="fieldDef"
        :field-key="fieldKey"
        :model-value="(data[fieldKey] as string) ?? ''"
        @update:model-value="onUpdate"
      />
    </div>
  </div>
</template>

<style scoped>
.single-field-editor { padding: 2px 0; }
.field-row {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-bottom: 6px;
}
.field-row label {
  font-size: 11px;
  color: #8a8a96;
}
</style>
