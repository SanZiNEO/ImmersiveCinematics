<script setup lang="ts">
const sections = [
  { name: '素材', items: [] as string[] },
  { name: '预设', items: [] as string[] },
  { name: '模板', items: [] as string[] },
]
</script>

<template>
  <div class="library-panel">
    <div v-for="section in sections" :key="section.name" class="library-section">
      <div class="section-title">{{ section.name }}</div>
      <div class="section-items">
        <div v-if="!section.items.length" class="empty">暂无内容</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.library-panel {
  padding: 10px;
  background: #222226;
  color: #d8d8e0;
}
.library-section { margin-bottom: 16px; }
.section-title {
  font-size: 12px;
  font-weight: 600;
  color: #8a8a96;
  margin-bottom: 6px;
}
.section-items {
  min-height: 40px;
}
.empty {
  font-size: 12px;
  color: #666;
  padding: 8px;
  border: 1px dashed #33333a;
  border-radius: 6px;
  text-align: center;
}
</style>
