<script setup lang="ts">
import { computed } from 'vue'
import CommitTextField from '../fields/CommitTextField.vue'

const props = defineProps<{
  data: Record<string, unknown>
}>()

const emit = defineEmits<{
  (e: 'update', key: string, value: unknown): void
}>()

const itemsText = computed(() => {
  const v = props.data.items
  return Array.isArray(v) ? v.join(', ') : (v as string) ?? ''
})

function onItemsCommit(value: string) {
  emit('update', 'items', value.split(',').map(s => s.trim()).filter(Boolean))
}

function onMode(e: Event) { emit('update', 'mode', (e.target as HTMLSelectElement).value) }
function onChange(e: Event) {
  const v = (e.target as HTMLSelectElement).value
  if (!v) emit('update', 'change', undefined)
  else emit('update', 'change', v)
}
</script>

<template>
  <div class="inventory-editor">
    <div class="field-row">
      <label>物品（逗号分隔）</label>
      <CommitTextField
        :model-value="itemsText"
        @update:model-value="onItemsCommit"
      />
    </div>
    <div class="field-row">
      <label>匹配模式</label>
      <select :value="(data.mode as string) || 'and'" @change="onMode">
        <option value="and">全部包含 (AND)</option>
        <option value="or">任一包含 (OR)</option>
      </select>
    </div>
    <div class="field-row">
      <label>变化</label>
      <select :value="(data.change as string) ?? ''" @change="onChange">
        <option value="">不限</option>
        <option value="increase">增加</option>
        <option value="decrease">减少</option>
      </select>
    </div>
  </div>
</template>

<style scoped>
.inventory-editor { padding: 2px 0; }
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
.field-row :deep(.commit-text) {
  width: 100%;
}
</style>
