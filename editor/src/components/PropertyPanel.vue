<script setup lang="ts">
import { computed } from 'vue'
import { state, commit } from '../store'
import DynamicForm from './DynamicForm.vue'

const metaFields = computed(() => state.schema?.meta ?? {})

const meta = computed(() => {
  if (!state.doc) return {}
  return state.doc.meta as Record<string, unknown>
})

function onUpdate(key: string, value: unknown) {
  commit(() => {
    if (!state.doc) return
    state.doc.meta[key] = value
  })
}
</script>

<template>
  <div class="property-panel">
    <div class="panel-header">
      <span>脚本属性</span>
      <span v-if="state.doc?.meta?.id" class="doc-id">{{ state.doc.meta.id }}</span>
    </div>
    <div v-if="state.doc && state.schema" class="panel-body">
      <DynamicForm :fields="metaFields" :data="meta" @update="onUpdate" />
    </div>
    <p v-else class="empty">未打开脚本或未获取 Schema</p>
  </div>
</template>

<style scoped>
.property-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 10px;
  border-bottom: 1px solid #33333a;
  background: #202026;
  font-size: 13px;
  font-weight: 600;
}
.doc-id {
  font-size: 11px;
  color: #6a8abf;
  font-weight: normal;
}
.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 4px 0;
}
.empty {
  padding: 16px;
  color: #666;
  font-size: 12px;
}
</style>
