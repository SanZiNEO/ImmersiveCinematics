<script setup lang="ts">
import { computed } from 'vue'
import { state } from '../store'

const tracks = computed(() => state.doc?.timeline?.tracks || [])
</script>

<template>
  <div class="track-list">
    <div v-for="(track, i) in tracks" :key="i"
         class="track-row"
         :class="{ active: state.selection.track === i }"
         @click="state.selection.track = i; state.selection.clip = -1">
      <span class="type">{{ track.type }}</span>
      <span class="count">{{ track.clips?.length || 0 }} clips</span>
    </div>
    <div v-if="!tracks.length" class="empty">暂无轨道</div>
  </div>
</template>

<style scoped>
.track-list { padding: 8px; }
.track-row {
  display: flex;
  justify-content: space-between;
  padding: 6px 8px;
  border-radius: 5px;
  cursor: pointer;
  margin-bottom: 4px;
  background: #22262f;
}
.track-row:hover { background: #2a2f3b; }
.track-row.active { background: #2f3b55; }
.type { font-size: 12px; color: #d8d8e0; }
.count { font-size: 11px; color: #8a8a96; }
.empty { color: #666; font-size: 12px; padding: 8px; }
</style>
