<script setup lang="ts">
import type { SchemaField } from '../../types'
import CommitTextField from './CommitTextField.vue'

const props = defineProps<{
  field: SchemaField
  modelValue: unknown
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()
</script>

<template>
  <CommitTextField
    :model-value="modelValue"
    :placeholder="props.field.required ? '必填' : ''"
    @update:model-value="emit('update:modelValue', $event)"
  />
</template>
