<script setup lang="ts">
import { computed } from 'vue'
import type { SchemaField } from '../../types'
import CommitNumberField from './CommitNumberField.vue'

const props = defineProps<{
  field: SchemaField
  modelValue: unknown
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: number): void
}>()

const step = computed(() => props.field.type === 'int' ? 1 : 0.1)
</script>

<template>
  <CommitNumberField
    :model-value="modelValue"
    :step="step"
    @update:model-value="emit('update:modelValue', $event)"
  />
</template>
