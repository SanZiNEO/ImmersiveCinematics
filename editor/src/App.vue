<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { connect, undo, redo, newScript, saveScript, state } from './store'
import TitleBar from './components/TitleBar.vue'
import ScriptList from './components/ScriptList.vue'
import ScriptStructure from './components/ScriptStructure.vue'
import Preview from './components/Preview.vue'
import PropertyPanel from './components/PropertyPanel.vue'
import TriggerPanel from './components/TriggerPanel.vue'
import Timeline from './components/Timeline.vue'

const leftWidth = ref(250)
const propWidth = ref(340)
const timelineHeight = ref(240)
const dragging = ref<'left' | 'right' | 'bottom' | null>(null)
const leftVisible = ref(true)
const rightVisible = ref(true)

function startDrag(kind: 'left' | 'right' | 'bottom', event: MouseEvent) {
  dragging.value = kind
  event.preventDefault()
}

function onMouseMove(event: MouseEvent) {
  if (dragging.value === 'left') {
    leftWidth.value = Math.min(520, Math.max(140, event.clientX))
  } else if (dragging.value === 'right') {
    propWidth.value = Math.min(640, Math.max(220, window.innerWidth - event.clientX))
  } else if (dragging.value === 'bottom') {
    timelineHeight.value = Math.min(640, Math.max(100, window.innerHeight - event.clientY))
  }
}

function onMouseUp() {
  dragging.value = null
}

function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') {
    e.preventDefault()
    if (e.shiftKey) redo()
    else undo()
  } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y') {
    e.preventDefault()
    redo()
  }
}

onMounted(() => {
  connect()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseup', onMouseUp)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
})
</script>

<template>
  <div class="app">
    <TitleBar
      @toggle-left="leftVisible = !leftVisible"
      @toggle-right="rightVisible = !rightVisible"
    />

    <div class="body">
      <div class="main-row">
        <div class="tool-rail">
          <button title="脚本列表" :class="{ active: leftVisible }" @click="leftVisible = !leftVisible">📁</button>
          <button title="脚本结构" @click="leftVisible = true">🗂</button>
          <button title="新建脚本" @click="newScript()">＋</button>
          <button title="保存" @click="saveScript()" :disabled="!state.currentPath">💾</button>
          <button title="撤销" @click="undo()">↶</button>
          <button title="重做" @click="redo()">↷</button>
        </div>

        <template v-if="leftVisible">
          <aside class="left-panel" :style="{ width: leftWidth + 'px' }">
            <ScriptList />
            <ScriptStructure />
          </aside>
          <div class="resize-h" @mousedown="startDrag('left', $event)"></div>
        </template>

        <main class="center">
          <div class="preview-area">
            <Preview />
          </div>
          <template v-if="rightVisible">
            <div class="resize-h right" @mousedown="startDrag('right', $event)"></div>
            <div class="property-area" :style="{ width: propWidth + 'px' }">
              <PropertyPanel />
              <TriggerPanel />
            </div>
          </template>
        </main>
      </div>

      <div class="resize-v" @mousedown="startDrag('bottom', $event)"></div>
      <footer class="timeline-area" :style="{ height: timelineHeight + 'px' }">
        <Timeline />
      </footer>
    </div>
  </div>
</template>

<style>
:root {
  --bg: #1a1a1e;
  --bg2: #222226;
  --bg3: #28282e;
  --border: #33333a;
  --text: #d8d8e0;
  --text-dim: #8a8a96;
  --accent: #4e7bd3;
  --green: #34d399;
  --red: #ef4444;
}
html, body, #app {
  height: 100%;
  margin: 0;
  font-family: 'Segoe UI', system-ui, sans-serif;
  color: var(--text);
  background: var(--bg);
  overflow: hidden;
}
.app {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.topbar {
  height: 36px;
  background: #151519;
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  padding: 0 12px;
  gap: 20px;
  flex-shrink: 0;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}
.logo {
  width: 22px;
  height: 22px;
  background: var(--accent);
  color: white;
  border-radius: 5px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
}
.title { font-size: 13px; }
.menu { display: flex; gap: 16px; font-size: 12px; color: var(--text-dim); }
.menu span { cursor: default; padding: 2px 4px; border-radius: 4px; }
.menu span:hover { background: var(--bg3); color: var(--text); }
.status {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-dim);
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.dot.online { background: var(--green); box-shadow: 0 0 6px var(--green); }
.dot.offline { background: var(--red); }
.body {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.main-row {
  flex: 1;
  display: flex;
  min-height: 0;
}
.tool-rail {
  width: 44px;
  background: #151519;
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 8px;
  gap: 6px;
  flex-shrink: 0;
}
.tool-rail button {
  width: 32px;
  height: 32px;
  background: transparent;
  color: var(--text-dim);
  border: none;
  border-radius: 6px;
  font-size: 16px;
  cursor: pointer;
}
.tool-rail button:hover { background: var(--bg3); color: var(--text); }
.tool-rail button.active { background: var(--accent); color: white; }
.left-panel {
  border-right: 1px solid var(--border);
  background: var(--bg2);
  overflow: auto;
  flex-shrink: 0;
}
.resize-h {
  width: 4px;
  cursor: col-resize;
  background: var(--border);
  flex-shrink: 0;
  transition: background .15s;
}
.resize-h:hover { background: var(--accent); }
.center {
  flex: 1;
  display: flex;
  min-height: 0;
}
.preview-area {
  flex: 1;
  min-width: 0;
  background: #121216;
}
.property-area {
  border-left: 1px solid var(--border);
  background: var(--bg2);
  overflow: auto;
  flex-shrink: 0;
}
.resize-v {
  height: 4px;
  cursor: row-resize;
  background: var(--border);
  flex-shrink: 0;
  transition: background .15s;
}
.resize-v:hover { background: var(--accent); }
.timeline-area {
  border-top: 1px solid var(--border);
  background: var(--bg2);
  overflow: auto;
  flex-shrink: 0;
}
</style>
