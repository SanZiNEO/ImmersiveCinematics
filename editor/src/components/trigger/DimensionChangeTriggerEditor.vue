<script setup lang="ts">
import { computed } from 'vue'
import type { SchemaField } from '../../types'
import RegistryStringField from '../fields/RegistryStringField.vue'

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
  <div class="dimension-change-editor">
    <div class="field-row">
      <label>目标维度</label>
      <RegistryStringField :field="fieldDef" field-key="dimension"
        :model-value="(data.dimension as string) ?? ''"
        @update:model-value="(v: any) => emit('update', 'dimension', v)" />
    </div>
    <div class="field-row">
      <label>来源维度（可选）</label>
      <RegistryStringField :field="fieldDef" field-key="from_dimension"
        :model-value="(data.from_dimension as string) ?? ''"
        @update:model-value="(v: any) => emit('update', 'from_dimension', v)" />
    </div>
  </div>
</template>

<style scoped>
.dimension-change-editor { padding: 2px 0; }
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
