<script setup lang="ts">
import CommitNumberField from '../fields/CommitNumberField.vue'

const props = defineProps<{
  data: Record<string, unknown>
}>()

const emit = defineEmits<{
  (e: 'update', key: string, value: unknown): void
}>()
</script>

<template>
  <div class="xp-editor">
    <div class="field-row">
      <label>经验等级</label>
      <CommitNumberField
        :model-value="Number(data.level ?? 0) || 0"
        :step="1"
        @update:model-value="(v: number) => emit('update', 'level', v)"
      />
    </div>
    <div class="field-row">
      <label>累计经验</label>
      <CommitNumberField
        :model-value="Number(data.total ?? 0) || 0"
        :step="1"
        @update:model-value="(v: number) => emit('update', 'total', v)"
      />
    </div>
  </div>
</template>

<style scoped>
.xp-editor { padding: 2px 0; }
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
