<script setup lang="ts">
import ScriptContent from './ScriptContent.vue'
</script>

<template>
  <div class="script-dock">
    <ScriptContent />
  </div>
</template>

<style scoped>
.script-dock {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #1e1e24;
  overflow: hidden;
}
</style>
