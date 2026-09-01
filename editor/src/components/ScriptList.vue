<script setup lang="ts">
import { state, loadScript, newScript, saveScript, deleteScript, refreshScripts } from '../store'

async function onLoad(path: string) {
  await loadScript(path)
}

async function onNew() {
  await newScript()
  await refreshScripts()
}

async function onSave() {
  await saveScript()
  await refreshScripts()
}

async function onDelete(path: string) {
  if (!confirm(`删除 ${path}?`)) return
  await deleteScript(path)
}
</script>

<template>
  <div class="script-list">
    <h3>脚本</h3>
    <button @click="onNew">新建</button>
    <button @click="onSave" :disabled="!state.currentPath">保存</button>
    <ul>
      <li v-for="path in state.scripts" :key="path"
          :class="{ active: path === state.currentPath }"
          @click="onLoad(path)">
        <span>{{ path }}</span>
        <button class="del" @click.stop="onDelete(path)">×</button>
      </li>
    </ul>
    <p v-if="!state.connected" class="offline">未连接模组</p>
  </div>
</template>

<style scoped>
.script-list { padding: 10px; }
.script-list h3 { margin: 0 0 8px; }
.script-list button { margin-right: 4px; }
.script-list ul { list-style: none; padding: 0; margin: 8px 0; }
.script-list li {
  display: flex;
  justify-content: space-between;
  padding: 4px 6px;
  cursor: pointer;
  border-radius: 4px;
}
.script-list li:hover { background: #252a35; }
.script-list li.active { background: #2f3b55; }
.del { border: none; background: transparent; color: #f66; cursor: pointer; }
.offline { color: #f66; }
</style>
