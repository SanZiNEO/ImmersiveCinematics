<script setup lang="ts">
import { ref } from 'vue'
import { state, newScript, saveScript, undo, redo } from '../store'

const emit = defineEmits<{
  (e: 'toggle-left'): void
  (e: 'toggle-right'): void
}>()

const openMenu = ref<string>('')

function toggle(name: string) {
  openMenu.value = openMenu.value === name ? '' : name
}

function run(action: () => void) {
  action()
  openMenu.value = ''
}

function windowAction(kind: 'minimize' | 'maximize' | 'close') {
  const w = (window as any).electronWindow
  if (w?.[kind]) w[kind]()
}
</script>

<template>
  <div class="chrome">
    <div class="title-bar">
      <div class="brand">
        <span class="logo">IC</span>
        <span class="title">ImmersiveCinematics</span>
      </div>
      <div class="status">
        <span class="dot" :class="state.connected ? 'online' : 'offline'"></span>
        <span>{{ state.connected ? '已连接' : '未连接' }}</span>
      </div>
      <div class="window-controls">
        <button @click="windowAction('minimize')" title="最小化">─</button>
        <button @click="windowAction('maximize')" title="最大化">□</button>
        <button class="close" @click="windowAction('close')" title="关闭">✕</button>
      </div>
    </div>

    <div class="menu-bar">
      <div class="menu-item" @click.stop="toggle('file')">
        <span>文件</span>
        <div v-if="openMenu === 'file'" class="dropdown">
          <button @click="run(() => newScript())">新建</button>
          <button @click="run(() => saveScript())" :disabled="!state.currentPath">保存</button>
          <button @click="openMenu = ''">打开…</button>
        </div>
      </div>
      <div class="menu-item" @click.stop="toggle('edit')">
        <span>编辑</span>
        <div v-if="openMenu === 'edit'" class="dropdown">
          <button @click="run(() => undo())">撤销</button>
          <button @click="run(() => redo())">重做</button>
        </div>
      </div>
      <div class="menu-item" @click.stop="toggle('view')">
        <span>视图</span>
        <div v-if="openMenu === 'view'" class="dropdown">
          <button @click="emit('toggle-left')">左侧面板</button>
          <button @click="emit('toggle-right')">右侧面板</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chrome {
  flex-shrink: 0;
  background: #151519;
  border-bottom: 1px solid #33333a;
  user-select: none;
}
.title-bar {
  height: 32px;
  display: flex;
  align-items: center;
  padding: 0 8px;
  gap: 16px;
  -webkit-app-region: drag;
}
.brand { display: flex; align-items: center; gap: 8px; font-size: 12px; font-weight: 600; }
.logo {
  width: 20px; height: 20px;
  background: #4e7bd3; color: #fff;
  border-radius: 5px;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 10px;
}
.status { display: flex; align-items: center; gap: 5px; font-size: 11px; color: #8a8a96; }
.dot { width: 7px; height: 7px; border-radius: 50%; }
.dot.online { background: #34d399; }
.dot.offline { background: #ef4444; }
.window-controls { margin-left: auto; display: flex; gap: 2px; -webkit-app-region: no-drag; }
.window-controls button {
  width: 36px; height: 26px;
  background: transparent; color: #aaa;
  border: none; border-radius: 4px;
  cursor: pointer; font-size: 12px;
}
.window-controls button:hover { background: #33333a; color: #fff; }
.window-controls .close:hover { background: #e5484d; color: #fff; }
.menu-bar {
  height: 28px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 8px;
  -webkit-app-region: no-drag;
}
.menu-item { position: relative; }
.menu-item > span {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 5px;
  cursor: pointer;
  font-size: 12px;
  color: #d8d8e0;
}
.menu-item > span:hover { background: #28282e; }
.dropdown {
  position: absolute;
  top: 26px;
  left: 0;
  min-width: 160px;
  background: #222226;
  border: 1px solid #3a3a44;
  border-radius: 8px;
  padding: 4px;
  z-index: 100;
  box-shadow: 0 8px 24px rgba(0,0,0,.5);
}
.dropdown button {
  display: block;
  width: 100%;
  text-align: left;
  background: transparent;
  color: #d8d8e0;
  border: none;
  padding: 7px 10px;
  border-radius: 5px;
  cursor: pointer;
  font-size: 12px;
}
.dropdown button:hover { background: #34343c; }
.dropdown button:disabled { opacity: .4; }
</style>
