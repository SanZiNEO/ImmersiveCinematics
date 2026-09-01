<script setup lang="ts">
import { computed } from 'vue'
import { state } from '../store'

const content = computed(() => {
  if (!state.doc) return ''
  return JSON.stringify(state.doc, null, 2)
})
</script>

<template>
  <pre class="json-content">{{ content }}</pre>
</template>

<style scoped>
.json-content {
  margin: 0;
  padding: 10px;
  font-family: 'Consolas', 'Menlo', monospace;
  font-size: 12px;
  line-height: 1.5;
  color: #d8d8e0;
  white-space: pre-wrap;
  word-break: break-all;
  user-select: text;
  background: #1e1e24;
}
</style>
