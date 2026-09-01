<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { connect, undo, redo, state } from './store'
import TitleBar from './components/TitleBar.vue'
import ScriptDock from './components/ScriptDock.vue'
import ScriptList from './components/ScriptList.vue'
import TrackListPanel from './components/TrackListPanel.vue'
import PresetsPanel from './components/PresetsPanel.vue'
import TabbedPanel from './components/TabbedPanel.vue'
import Preview from './components/Preview.vue'
import PropertyPanel from './components/PropertyPanel.vue'
import ClipPanel from './components/ClipPanel.vue'
import KeyframePanel from './components/KeyframePanel.vue'
import TriggerPanel from './components/TriggerPanel.vue'
import Timeline from './components/Timeline.vue'

const leftWidth = ref(330)
const leftPanelWidth = ref(340)
const rightPanelWidth = ref(380)
const timelineHeight = ref(280)
const dragging = ref<'left' | 'leftPanel' | 'rightPanel' | 'bottom' | null>(null)

const leftTabs = [
  { id: 'scripts', label: '脚本' },
  { id: 'tracks', label: '轨道' },
  { id: 'presets', label: '预设' },
]
const rightTabs = [
  { id: 'properties', label: '属性' },
  { id: 'clip', label: '片段' },
  { id: 'keyframe', label: '关键帧' },
  { id: 'triggers', label: '触发器' },
]
const leftActiveTab = ref('scripts')
const rightActiveTab = computed({
  get: () => state.rightTab,
  set: (v: any) => { state.rightTab = v },
})

function initSizes() {
  const w = window.innerWidth
  const h = window.innerHeight
  leftWidth.value = Math.max(280, Math.min(560, Math.floor(w * 0.18)))
  leftPanelWidth.value = Math.max(280, Math.min(520, Math.floor(w * 0.18)))
  rightPanelWidth.value = Math.max(300, Math.min(600, Math.floor(w * 0.22)))
  timelineHeight.value = Math.max(200, Math.min(420, Math.floor(h * 0.3)))
}

function startDrag(kind: 'left' | 'leftPanel' | 'rightPanel' | 'bottom', event: MouseEvent) {
  dragging.value = kind
  event.preventDefault()
}

function onMouseMove(event: MouseEvent) {
  if (dragging.value === 'left') {
    leftWidth.value = Math.min(560, Math.max(200, event.clientX))
  } else if (dragging.value === 'leftPanel') {
    leftPanelWidth.value = Math.min(560, Math.max(220, event.clientX - leftWidth.value - 8))
  } else if (dragging.value === 'rightPanel') {
    rightPanelWidth.value = Math.min(700, Math.max(240, window.innerWidth - event.clientX))
  } else if (dragging.value === 'bottom') {
    timelineHeight.value = Math.min(640, Math.max(120, window.innerHeight - event.clientY))
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
          <div class="left-panel-area" :style="{ width: leftPanelWidth + 'px' }">
            <TabbedPanel
              :tabs="leftTabs"
              :active="leftActiveTab"
              :show-list="true"
              @change="leftActiveTab = $event"
            >
              <template #list>
                <ScriptList v-if="leftActiveTab === 'scripts'" />
                <TrackListPanel v-else-if="leftActiveTab === 'tracks'" />
                <PresetsPanel v-else />
              </template>
              <template #content>
                <div class="panel-placeholder">
                  {{ leftActiveTab === 'scripts' ? '脚本详情' : leftActiveTab === 'tracks' ? '轨道详情' : '预设详情' }}
                </div>
              </template>
            </TabbedPanel>
          </div>
          <div class="resize-h panel-left" @mousedown="startDrag('leftPanel', $event)"></div>
          <div class="preview-area">
            <Preview />
          </div>
          <div class="resize-h panel-right" @mousedown="startDrag('rightPanel', $event)"></div>
          <div class="right-panel-area" :style="{ width: rightPanelWidth + 'px' }">
            <TabbedPanel
              :tabs="rightTabs"
              :active="rightActiveTab"
              @change="rightActiveTab = $event"
            >
              <template #content>
                <PropertyPanel v-if="rightActiveTab === 'properties'" />
                <ClipPanel v-else-if="rightActiveTab === 'clip'" />
                <KeyframePanel v-else-if="rightActiveTab === 'keyframe'" />
                <TriggerPanel v-else />
              </template>
            </TabbedPanel>
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
button {
  background: #28282e;
  color: #d8d8e0;
  border: 1px solid #3a3a44;
  border-radius: 4px;
  padding: 4px 8px;
  cursor: pointer;
  font-size: 12px;
}
button:hover:not(:disabled) { background: #34343c; }
button:disabled { opacity: .4; cursor: default; }
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
.left-panel-area {
  border-right: 1px solid var(--border);
  background: var(--bg2);
  overflow: auto;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
}
.panel-placeholder {
  padding: 10px;
  font-size: 12px;
  color: #8a8a96;
}
.right-panel-area {
  border-left: 1px solid var(--border);
  background: var(--bg2);
  overflow: auto;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
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
