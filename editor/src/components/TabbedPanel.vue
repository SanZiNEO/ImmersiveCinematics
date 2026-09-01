<script setup lang="ts">
import PanelTabs from './PanelTabs.vue'

defineProps<{
  tabs: { id: string; label: string }[]
  active: string
  showList?: boolean
}>()

const emit = defineEmits<{
  (e: 'change', id: string): void
}>()
</script>

<template>
  <div class="tabbed-panel">
    <PanelTabs :tabs="tabs" :active="active" @change="emit('change', $event)" />
    <div v-if="showList" class="tabbed-body split">
      <div class="panel-list">
        <slot name="list" />
      </div>
      <div class="panel-content">
        <slot name="content" />
      </div>
    </div>
    <div v-else class="tabbed-body">
      <slot name="content" />
    </div>
  </div>
</template>

<style scoped>
.tabbed-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.tabbed-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.tabbed-body.split {
  display: flex;
}
.panel-list {
  width: 180px;
  border-right: 1px solid #33333a;
  background: #1c1c22;
  overflow: auto;
  flex-shrink: 0;
}
.panel-content {
  flex: 1;
  min-width: 0;
  overflow: auto;
}
</style>
