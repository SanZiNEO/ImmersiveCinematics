<script setup lang="ts">
defineProps<{
  tabs: { id: string; label: string }[]
  active: string
}>()

const emit = defineEmits<{
  (e: 'change', id: string): void
}>()
</script>

<template>
  <div class="panel-tabs">
    <button
      v-for="tab in tabs"
      :key="tab.id"
      class="tab"
      :class="{ active: tab.id === active }"
      @click="emit('change', tab.id)"
    >
      {{ tab.label }}
    </button>
  </div>
</template>

<style scoped>
.panel-tabs {
  display: flex;
  border-bottom: 1px solid #33333a;
  background: #1c1c22;
  flex-shrink: 0;
}
.tab {
  flex: 1;
  background: transparent;
  border: none;
  border-right: 1px solid #2c2c34;
  color: #8a8a96;
  padding: 8px 4px;
  font-size: 12px;
  cursor: pointer;
  border-radius: 0;
}
.tab:hover { background: #2a2a31; color: #d8d8e0; }
.tab.active {
  background: #2a2a31;
  color: #d8d8e0;
  border-bottom: 2px solid #4e7bd3;
}
</style>
