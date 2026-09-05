<script setup lang="ts">
import { computed } from 'vue'
import type { SchemaField } from '../../types'
import RegistryStringField from '../fields/RegistryStringField.vue'
import CommitNumberField from '../fields/CommitNumberField.vue'

const props = defineProps<{
  data: Record<string, unknown>
}>()

const emit = defineEmits<{
  (e: 'update', key: string, value: unknown): void
}>()

const fieldDef = computed<SchemaField>(() => ({
  type: 'string', default: null, required: true, enumValues: [], section: 'conditions',
}))
</script>

<template>
  <div class="structure-editor">
    <div class="field-row">
      <label>结构</label>
      <RegistryStringField :field="fieldDef" field-key="structure"
        :model-value="(data.structure as string) ?? ''"
        @update:model-value="(v: any) => emit('update', 'structure', v)" />
    </div>
    <div class="field-row">
      <label>半径</label>
      <CommitNumberField
        :model-value="Number(data.radius ?? 0) || 0"
        :step="1"
        @update:model-value="(v: number) => emit('update', 'radius', v)"
      />
    </div>
  </div>
</template>

<style scoped>
.structure-editor { padding: 2px 0; }
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
.field-row :deep(.commit-number) {
  width: 100%;
}
</style>
