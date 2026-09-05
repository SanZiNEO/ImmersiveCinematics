<script setup lang="ts">
import { computed, ref } from 'vue'
import { state, registryQuery } from '../../store'
import CommitTextField from '../fields/CommitTextField.vue'

const props = defineProps<{
  data: Record<string, unknown>
}>()

const emit = defineEmits<{
  (e: 'update', key: string, value: unknown): void
}>()

const suggestions = ref<string[]>([])
const entityText = computed(() => {
  const v = props.data.entity
  return Array.isArray(v) ? v.join(', ') : (v as string) ?? ''
})

async function loadSuggestions(query: string) {
  if (!state.connected) return
  suggestions.value = await registryQuery('entity', query || '', 30)
}

function onEntityInput(text: string) {
  // 只查询候选，不提交到 doc；提交由 CommitTextField 失焦/回车触发
  setTimeout(() => loadSuggestions(text), 120)
}

function onEntityCommit(value: string) {
  if (value.includes(',')) {
    emit('update', 'entity', value.split(',').map(s => s.trim()).filter(Boolean))
  } else {
    emit('update', 'entity', value)
  }
}

function onMode(e: Event) {
  emit('update', 'mode', (e.target as HTMLSelectElement).value)
}
</script>

<template>
  <div class="entity-kill-editor">
    <div class="field-row">
      <label>实体（多个用逗号分隔）</label>
      <CommitTextField
        :model-value="entityText"
        list="ic-entity-kill"
        @draft="onEntityInput"
        @update:model-value="onEntityCommit"
      />
      <datalist id="ic-entity-kill">
        <option v-for="s in suggestions" :key="s" :value="s" />
      </datalist>
    </div>
    <div class="field-row">
      <label>匹配模式</label>
      <select :value="(data.mode as string) || 'or'" @change="onMode">
        <option value="or">任一 (OR)</option>
        <option value="and">全部 (AND)</option>
      </select>
    </div>
  </div>
</template>

<style scoped>
.entity-kill-editor { padding: 2px 0; }
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
