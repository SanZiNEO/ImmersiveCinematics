<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { connect, undo, redo, state, loadDemo } from './store'
import TitleBar from './components/TitleBar.vue'
import ScriptDock from './components/ScriptDock.vue'
import ScriptList from './components/ScriptList.vue'
import ScriptStructure from './components/ScriptStructure.vue'
import TrackListPanel from './components/TrackListPanel.vue'
import PresetsPanel from './components/PresetsPanel.vue'
import TabbedPanel from './components/TabbedPanel.vue'
import Preview from './components/Preview.vue'
import PropertyPanel from './components/PropertyPanel.vue'
import ClipPanel from './components/ClipPanel.vue'
import KeyframePanel from './components/KeyframePanel.vue'
import TriggerPanel from './components/TriggerPanel.vue'
import Timeline from './components/Timeline.vue'

// 各区域宽度（可拖拽调整，有最小/最大限制）
const jsonColWidth = ref(280)       // 最左 JSON 实时预览列
const leftPanelWidth = ref(300)     // 编辑器左侧面板（脚本/轨道/预设）
const rightPanelWidth = ref(360)    // 右侧属性面板
const timelineHeight = ref(260)     // 底部时间轴高度
const dragging = ref<'json' | 'left' | 'right' | 'bottom' | null>(null)

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

/**
 * 自适应初始布局：根据窗口大小按比例分配各区域宽度，
 * 保证每个区域有合理的最小宽度，窗口小时自动压缩。
 */
function initSizes() {
  const w = window.innerWidth
  const h = window.innerHeight

  // JSON 列：14%，最小 200，最大 400
  jsonColWidth.value = clamp(Math.floor(w * 0.14), 200, 400)
  // 编辑器左侧面板：16%，最小 220，最大 420
  leftPanelWidth.value = clamp(Math.floor(w * 0.16), 220, 420)
  // 右侧属性面板：20%，最小 280，最大 520
  rightPanelWidth.value = clamp(Math.floor(w * 0.2), 280, 520)
  // 时间轴：28% 高度，最小 180，最大 400
  timelineHeight.value = clamp(Math.floor(h * 0.28), 180, 400)
}

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v))
}

function startDrag(kind: 'json' | 'left' | 'right' | 'bottom', event: MouseEvent) {
  dragging.value = kind
  event.preventDefault()
}

function onMouseMove(event: MouseEvent) {
  if (dragging.value === 'json') {
    jsonColWidth.value = clamp(event.clientX, 180, 450)
  } else if (dragging.value === 'left') {
    // 左侧面板的左边界 = jsonColWidth + 分隔条
    const leftEdge = jsonColWidth.value + 4
    leftPanelWidth.value = clamp(event.clientX - leftEdge, 200, 480)
  } else if (dragging.value === 'right') {
    rightPanelWidth.value = clamp(window.innerWidth - event.clientX, 260, 600)
  } else if (dragging.value === 'bottom') {
    timelineHeight.value = clamp(window.innerHeight - event.clientY, 140, 500)
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

// 窗口大小变化时重新计算（仅在用户从未手动拖拽过时自适应）
let userDragged = false
function onResize() {
  if (userDragged) return
  initSizes()
}

onMounted(() => {
  initSizes()
  loadDemo()
  connect()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseup', onMouseUp)
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
  window.removeEventListener('resize', onResize)
})

// 标记用户已手动拖拽，停止自动自适应
function markUserDragged() {
  userDragged = true
}
</script>

<template>
  <div class="app">
    <TitleBar />

    <div class="body-row">
      <!-- 最左：JSON 实时预览（原始设计，保留） -->
      <aside class="json-column" :style="{ width: jsonColWidth + 'px' }">
        <ScriptDock />
      </aside>
      <div class="resize-h" @mousedown="startDrag('json', $event); markUserDragged()"></div>

      <!-- 编辑器左侧面板（脚本/轨道/预设）—— 参考剪映左侧素材面板模式 -->
      <div class="editor-left-panel" :style="{ width: leftPanelWidth + 'px' }">
        <TabbedPanel
          :tabs="leftTabs"
          :active="leftActiveTab"
          @change="leftActiveTab = $event"
        >
          <template #content>
            <div v-if="leftActiveTab === 'scripts'" class="scripts-tab">
              <div class="scripts-list-section">
                <ScriptList />
              </div>
              <div class="scripts-structure-section">
                <ScriptStructure />
              </div>
            </div>
            <TrackListPanel v-else-if="leftActiveTab === 'tracks'" />
            <PresetsPanel v-else-if="leftActiveTab === 'presets'" />
          </template>
        </TabbedPanel>
      </div>
      <div class="resize-h" @mousedown="startDrag('left', $event); markUserDragged()"></div>

      <!-- 中间编辑区 -->
      <div class="editor-workspace">
        <div class="editor-top">
          <div class="preview-area">
            <Preview />
          </div>
          <div class="resize-h" @mousedown="startDrag('right', $event); markUserDragged()"></div>
          <div class="right-panel" :style="{ width: rightPanelWidth + 'px' }">
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

        <div class="resize-v" @mousedown="startDrag('bottom', $event); markUserDragged()"></div>
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

/* 全局细滚动条 */
::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}
::-webkit-scrollbar-track {
  background: transparent;
}
::-webkit-scrollbar-thumb {
  background: #3a3a44;
  border-radius: 4px;
}
::-webkit-scrollbar-thumb:hover {
  background: #4a4a54;
}
::-webkit-scrollbar-corner {
  background: transparent;
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
  min-width: 0;
}

/* 最左 JSON 列 */
.json-column {
  background: #1e1e24;
  border-right: 1px solid var(--border);
  overflow: hidden;
  flex-shrink: 0;
  min-width: 0;
}

/* 编辑器左侧面板 */
.editor-left-panel {
  background: var(--bg2);
  overflow: hidden;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.scripts-tab {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.scripts-list-section {
  flex-shrink: 0;
  border-bottom: 1px solid var(--border);
}
.scripts-structure-section {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
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
.right-panel {
  border-left: 1px solid var(--border);
  background: var(--bg2);
  overflow: hidden;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.preview-area {
  flex: 1;
  min-width: 200px;
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
  overflow: hidden;
  flex-shrink: 0;
  min-height: 0;
}
</style>
