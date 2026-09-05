<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { connect, undo, redo, state, loadDemo, play, pause, seek, stop,
  copySelectedClips, cutSelectedClips, pasteClips, deleteSelectedClips,
  selectAllClips, duplicateSelectedClips, setLoopIn, setLoopOut, clearLoop,
  addMarker, clearSelectedClips, enterFlightMode, flightMode,
  saveScript, getSelectedClip, getSelectedTrack } from './store'
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
  const target = e.target as HTMLElement
  const isInput = target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable

  // Ctrl+Z / Ctrl+Y
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') {
    e.preventDefault()
    if (e.shiftKey) redo()
    else undo()
    return
  }
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y') {
    e.preventDefault()
    redo()
    return
  }

  // 文本输入时不触发编辑器快捷键
  if (isInput) return

  // Space — 播放/暂停
  if (e.code === 'Space') {
    e.preventDefault()
    if (state.playing) pause()
    else play()
    return
  }

  // Delete / Backspace — 删除选中
  if (e.key === 'Delete' || e.key === 'Backspace') {
    e.preventDefault()
    deleteSelectedClips()
    return
  }

  // Ctrl+C — 复制
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'c') {
    e.preventDefault()
    copySelectedClips()
    return
  }

  // Ctrl+X — 剪切
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'x') {
    e.preventDefault()
    cutSelectedClips()
    return
  }

  // Ctrl+V — 粘贴
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'v') {
    e.preventDefault()
    pasteClips()
    return
  }

  // Ctrl+A — 全选
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'a') {
    e.preventDefault()
    selectAllClips()
    return
  }

  // Ctrl+D — 复制片段
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'd') {
    e.preventDefault()
    duplicateSelectedClips()
    return
  }

  // Ctrl+S — 保存
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault()
    saveScript()
    return
  }

  // Ctrl+0 — 重置缩放
  if ((e.ctrlKey || e.metaKey) && e.key === '0') {
    e.preventDefault()
    state.pxPerSecond = 40
    return
  }

  // M — 添加 marker
  if (e.key.toLowerCase() === 'm') {
    e.preventDefault()
    addMarker(state.time)
    return
  }

  // I — 设置循环入点
  if (e.key.toLowerCase() === 'i') {
    e.preventDefault()
    if (e.shiftKey) clearLoop()
    else setLoopIn(state.time)
    return
  }

  // O — 设置循环出点
  if (e.key.toLowerCase() === 'o') {
    e.preventDefault()
    if (e.shiftKey) clearLoop()
    else setLoopOut(state.time)
    return
  }

  // 方向键 — 移动播放头
  const step = e.shiftKey ? 5 : 0.5
  if (e.key === 'ArrowLeft') {
    e.preventDefault()
    seek(Math.max(0, state.time - step))
    return
  }
  if (e.key === 'ArrowRight') {
    e.preventDefault()
    const total = state.doc?.timeline?.total_duration ?? 100
    seek(Math.min(total, state.time + step))
    return
  }

  // Home — 跳到起点
  if (e.key === 'Home') {
    e.preventDefault()
    seek(0)
    return
  }

  // End — 跳到终点
  if (e.key === 'End') {
    e.preventDefault()
    seek(state.doc?.timeline?.total_duration ?? 0)
    return
  }

  // F — Frame All
  if (e.key.toLowerCase() === 'f') {
    e.preventDefault()
    const total = state.doc?.timeline?.total_duration || 10
    state.pxPerSecond = Math.max(5, Math.min(500, (window.innerWidth - 480) / total))
    return
  }

  // Enter — 播放选中 clip
  if (e.key === 'Enter') {
    const clip = getSelectedClip()
    if (clip) {
      e.preventDefault()
      seek(clip.start_time)
      play()
    }
    return
  }

  // PageUp / PageDown — 切换上/下轨道
  if (e.key === 'PageUp' || e.key === 'PageDown') {
    const cur = getSelectedTrack()
    if (cur) {
      const idx = state.selection.track
      const dir = e.key === 'PageUp' ? -1 : 1
      const target = idx + dir
      const tracks = state.doc?.timeline?.tracks || []
      if (target >= 0 && target < tracks.length && tracks[target].clips.length > 0) {
        e.preventDefault()
        state.selection.track = target
        state.selection.clip = 0
        state.selection.keyframe = -1
      }
    }
    return
  }

  // [ / ] — 跳转到选中 clip 起点/终点
  if (e.key === '[' || e.key === ']') {
    const clip = getSelectedClip()
    if (clip) {
      e.preventDefault()
      seek(e.key === '[' ? clip.start_time : clip.start_time + clip.duration)
    }
    return
  }

  // Escape — 取消选择
  if (e.key === 'Escape') {
    clearSelectedClips()
    return
  }

  // F7 — 进入飞控模式（编辑当前选中关键帧；没有关键帧时自动在当前时间新建）
  if (e.key === 'F7') {
    e.preventDefault()
    if (state.connected && (state.selection.keyframe >= 0 || state.selection.clip >= 0)) {
      enterFlightMode()
    }
    return
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

    <div v-if="state.validationIssues.length" class="validation-banner">
      <span class="validation-title">校验问题 ({{ state.validationIssues.length }})</span>
      <span class="validation-item">{{ state.validationIssues[0] }}</span>
      <button class="validation-clear" @click="state.validationIssues = []">×</button>
    </div>

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

.validation-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 10px;
  background: #3a2a2a;
  border-bottom: 1px solid #643;
  color: #fbb;
  font-size: 12px;
  flex-shrink: 0;
}
.validation-title {
  color: #f88;
  font-weight: 600;
  white-space: nowrap;
}
.validation-item {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.validation-clear {
  background: transparent;
  border: none;
  color: #f88;
  cursor: pointer;
  padding: 0 4px;
  font-size: 14px;
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
