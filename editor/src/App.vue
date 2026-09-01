<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { connect, undo, redo } from './store'
import TitleBar from './components/TitleBar.vue'
import ScriptDock from './components/ScriptDock.vue'
import ScriptList from './components/ScriptList.vue'
import Preview from './components/Preview.vue'
import PropertyPanel from './components/PropertyPanel.vue'
import TriggerPanel from './components/TriggerPanel.vue'
import Timeline from './components/Timeline.vue'

const leftWidth = ref(320)
const panelWidth = ref(340)
const timelineHeight = ref(260)
const dragging = ref<'left' | 'panel' | 'bottom' | null>(null)

function initSizes() {
  leftWidth.value = Math.max(240, Math.min(520, Math.floor(window.innerWidth * 0.2)))
  panelWidth.value = Math.max(260, Math.min(520, Math.floor(window.innerWidth * 0.22)))
  timelineHeight.value = Math.max(180, Math.min(380, Math.floor(window.innerHeight * 0.3)))
}

function startDrag(kind: 'left' | 'panel' | 'bottom', event: MouseEvent) {
  dragging.value = kind
  event.preventDefault()
}

function onMouseMove(event: MouseEvent) {
  if (dragging.value === 'left') {
    leftWidth.value = Math.min(520, Math.max(180, event.clientX))
  } else if (dragging.value === 'panel') {
    panelWidth.value = Math.min(640, Math.max(200, event.clientX - leftWidth.value - 4))
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
  initSizes()
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
    <TitleBar />

    <div class="body-row">
      <aside class="script-column" :style="{ width: leftWidth + 'px' }">
        <ScriptDock />
      </aside>

      <div class="resize-h" @mousedown="startDrag('left', $event)"></div>

      <div class="editor-workspace">
        <div class="editor-top">
          <div class="panel-area" :style="{ width: panelWidth + 'px' }">
            <ScriptList />
            <PropertyPanel />
            <TriggerPanel />
          </div>
          <div class="resize-h panel" @mousedown="startDrag('panel', $event)"></div>
          <div class="preview-area">
            <Preview />
          </div>
        </div>

        <div class="resize-v" @mousedown="startDrag('bottom', $event)"></div>
        <footer class="timeline-area" :style="{ height: timelineHeight + 'px' }">
          <Timeline />
        </footer>
      </div>
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
.body-row {
  flex: 1;
  display: flex;
  min-height: 0;
}
.script-column {
  border-right: 1px solid var(--border);
  background: var(--bg2);
  overflow: hidden;
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
.editor-workspace {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.editor-top {
  flex: 1;
  display: flex;
  min-height: 0;
}
.panel-area {
  border-right: 1px solid var(--border);
  background: var(--bg2);
  overflow: auto;
  flex-shrink: 0;
}
.preview-area {
  flex: 1;
  min-width: 0;
  background: #121216;
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
