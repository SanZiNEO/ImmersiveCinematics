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

function onTargetType(e: Event) {
  const v = (e.target as HTMLSelectElement).value
  if (!v) emit('update', 'target_type', undefined)
  else emit('update', 'target_type', v)
}
</script>

<template>
  <div class="observation-editor">
    <div class="field-row">
      <label>注视目标</label>
      <RegistryStringField :field="fieldDef" field-key="target"
        :model-value="(data.target as string) ?? ''"
        @update:model-value="(v: any) => emit('update', 'target', v)" />
    </div>
    <div class="field-row">
      <label>目标类型</label>
      <select :value="(data.target_type as string) ?? ''" @change="onTargetType">
        <option value="">不限</option>
        <option value="block">方块</option>
        <option value="entity">实体</option>
      </select>
    </div>
    <div class="field-row">
      <label>射程</label>
      <CommitNumberField
        :model-value="Number(data.reach ?? 4.5)"
        :step="0.5"
        @update:model-value="(v: number) => emit('update', 'reach', v)"
      />
    </div>
  </div>
</template>

<style scoped>
.observation-editor { padding: 2px 0; }
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
