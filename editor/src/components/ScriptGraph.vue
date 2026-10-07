<script setup lang="ts">
// ─────────────────────────────────────────────────────────────
// 脚本架构图视图（无限画布）
//
// 数据：Java 侧 script.graph（ScriptGraphService）→ { nodes, edges, folders, warnings }
// 布局：graphLayout.computeLayout（文件夹泳道 + 拓扑分层 + 孤立脚本网格收拢，自研，不引图库）
// 交互（plans/0.3.6/editor-script-graph.md §4）：平移/缩放、节点渲染、悬停摘要、
// 点击高亮上下游、双击在编辑器里打开脚本、按名称/触发器/文件夹过滤。
// ─────────────────────────────────────────────────────────────
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { state, request, loadScript, viewMode } from '../store'
import { computeLayout, folderLabel, type GraphData, type LaidOutEdge, type LaidOutNode } from '../graphLayout'

const graph = ref<GraphData | null>(null)
const loading = ref(false)
const error = ref('')

const layout = computed(() => (graph.value ? computeLayout(graph.value) : null))

const viewport = ref<HTMLElement | null>(null)
const scale = ref(1)
const tx = ref(0)
const ty = ref(0)
const panning = ref(false)

const selectedId = ref<string | null>(null)
const hoverId = ref<string | null>(null)
const hoverX = ref(0)
const hoverY = ref(0)

const query = ref('')
const triggerFilter = ref('')
const folderFilter = ref('')
const onlyMatches = ref(false)

const nodeById = computed(() => {
  const map = new Map<string, LaidOutNode>()
  for (const n of layout.value?.nodes ?? []) map.set(n.id, n)
  return map
})

const hoverNode = computed(() => (hoverId.value ? nodeById.value.get(hoverId.value) ?? null : null))

const triggerTypes = computed(() => {
  const set = new Set<string>()
  for (const n of graph.value?.nodes ?? []) {
    for (const t of n.triggers) if (t.type) set.add(t.type)
  }
  return [...set].sort()
})

const folders = computed(() => (graph.value?.folders ?? []).map(f => f.path))

const hasFilter = computed(() => !!query.value.trim() || !!triggerFilter.value || !!folderFilter.value)

/** 当前过滤条件下某节点是否命中（名称 / 脚本 id / 路径 / 触发器类型 / 文件夹）。 */
function matches(n: LaidOutNode): boolean {
  if (folderFilter.value && n.folder !== folderFilter.value) return false
  if (triggerFilter.value && !n.triggers.some(t => t.type === triggerFilter.value)) return false
  const q = query.value.trim().toLowerCase()
  if (!q) return true
  return n.name.toLowerCase().includes(q)
    || n.path.toLowerCase().includes(q)
    || (n.scriptId ?? '').toLowerCase().includes(q)
    || n.triggers.some(t => t.type.toLowerCase().includes(q))
}

/** 选中节点的上下游链（沿边双向 BFS；不含自身）。 */
const related = computed(() => {
  const l = layout.value
  const sel = selectedId.value
  if (!l || !sel) return null
  const outMap = new Map<string, LaidOutEdge[]>()
  const inMap = new Map<string, LaidOutEdge[]>()
  for (const e of l.edges) {
    if (!outMap.has(e.from.id)) outMap.set(e.from.id, [])
    outMap.get(e.from.id)!.push(e)
    if (!inMap.has(e.to.id)) inMap.set(e.to.id, [])
    inMap.get(e.to.id)!.push(e)
  }
  const walk = (step: (id: string) => string[]) => {
    const seen = new Set<string>()
    const queue = [sel]
    while (queue.length) {
      const cur = queue.shift()!
      for (const next of step(cur)) {
        if (next === sel || seen.has(next)) continue
        seen.add(next)
        queue.push(next)
      }
    }
    return seen
  }
  return {
    up: walk(id => (inMap.get(id) ?? []).map(e => e.from.id)),
    down: walk(id => (outMap.get(id) ?? []).map(e => e.to.id)),
  }
})

const nodeStates = computed(() => {
  const map = new Map<string, { matched: boolean; hidden: boolean; dim: boolean; up: boolean; down: boolean }>()
  for (const n of layout.value?.nodes ?? []) {
    const matched = matches(n)
    const rel = related.value
    const up = rel?.up.has(n.id) ?? false
    const down = rel?.down.has(n.id) ?? false
    // 过滤与选中高亮是两套叠加的视觉：过滤把不匹配的压暗，选中再把无关的压暗
    const dim = (rel ? !(up || down || n.id === selectedId.value) : false) || (hasFilter.value && !matched)
    map.set(n.id, { matched, hidden: onlyMatches.value && !matched, dim, up, down })
  }
  return map
})

const edgeStates = computed(() => {
  const map = new Map<string, { hidden: boolean; dim: boolean; up: boolean; down: boolean }>()
  const states = nodeStates.value
  for (const e of layout.value?.edges ?? []) {
    const from = states.get(e.from.id)
    const to = states.get(e.to.id)
    const rel = related.value
    let up = false
    let down = false
    if (rel) {
      down = (rel.down.has(e.from.id) || e.from.id === selectedId.value) && (rel.down.has(e.to.id) || e.to.id === selectedId.value)
      up = (rel.up.has(e.to.id) || e.to.id === selectedId.value) && (rel.up.has(e.from.id) || e.from.id === selectedId.value)
    }
    map.set(e.key, {
      hidden: !!(from?.hidden || to?.hidden),
      dim: (rel ? !(up || down) : false) || (hasFilter.value && !(from?.matched && to?.matched)),
      up,
      down,
    })
  }
  return map
})

const worldStyle = computed(() => ({
  transform: `translate(${tx.value}px, ${ty.value}px) scale(${scale.value})`,
}))

const tooltipStyle = computed(() => {
  const el = viewport.value
  const width = 320
  const maxX = el ? el.clientWidth - width - 12 : hoverX.value
  const maxY = el ? el.clientHeight - 40 : hoverY.value
  const rect = el?.getBoundingClientRect()
  const x = hoverX.value - (rect?.left ?? 0)
  const y = hoverY.value - (rect?.top ?? 0)
  return {
    left: `${Math.max(8, Math.min(x + 16, maxX))}px`,
    top: `${Math.max(8, Math.min(y + 16, maxY))}px`,
    width: `${width}px`,
  }
})

function fit() {
  const l = layout.value
  const el = viewport.value
  if (!l || !el) return
  const w = el.clientWidth
  const h = el.clientHeight
  if (!w || !h) return
  scale.value = Math.max(0.15, Math.min(1, Math.min((w - 56) / l.width, (h - 56) / l.height)))
  tx.value = (w - l.width * scale.value) / 2
  ty.value = (h - l.height * scale.value) / 2
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    graph.value = await request<GraphData>('script.graph')
    selectedId.value = null
    await nextTick()
    fit()
  } catch (e: any) {
    error.value = state.connected
      ? '读取脚本架构数据失败: ' + (e?.message || e)
      : '未连接模组：启动游戏内 WebUI 服务后再打开架构图'
  } finally {
    loading.value = false
  }
}

function zoomBy(factor: number, cx?: number, cy?: number) {
  const el = viewport.value
  if (!el) return
  const px = cx ?? el.clientWidth / 2
  const py = cy ?? el.clientHeight / 2
  const next = Math.max(0.15, Math.min(2.5, scale.value * factor))
  const k = next / scale.value
  tx.value = px - (px - tx.value) * k
  ty.value = py - (py - ty.value) * k
  scale.value = next
}

function onWheel(e: WheelEvent) {
  const el = viewport.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  zoomBy(Math.exp(-e.deltaY * 0.0015), e.clientX - rect.left, e.clientY - rect.top)
}

let panStart: { x: number; y: number; tx: number; ty: number } | null = null

function onCanvasDown(e: MouseEvent) {
  if (e.button !== 0 && e.button !== 1) return
  if (e.button === 0 && (e.target as HTMLElement).closest('.node')) return
  panStart = { x: e.clientX, y: e.clientY, tx: tx.value, ty: ty.value }
  panning.value = true
  window.addEventListener('mousemove', onPanMove)
  window.addEventListener('mouseup', onPanUp)
  e.preventDefault()
}

function onPanMove(e: MouseEvent) {
  if (!panStart) return
  tx.value = panStart.tx + (e.clientX - panStart.x)
  ty.value = panStart.ty + (e.clientY - panStart.y)
}

function onPanUp() {
  panStart = null
  panning.value = false
  window.removeEventListener('mousemove', onPanMove)
  window.removeEventListener('mouseup', onPanUp)
}

function onNodeClick(n: LaidOutNode) {
  selectedId.value = selectedId.value === n.id ? null : n.id
}

function onNodeEnter(n: LaidOutNode, e: MouseEvent) {
  hoverId.value = n.id
  hoverX.value = e.clientX
  hoverY.value = e.clientY
}

async function onNodeOpen(n: LaidOutNode) {
  if (!state.connected) {
    error.value = '未连接模组：无法打开脚本'
    return
  }
  try {
    await loadScript(n.path)
    viewMode.value = 'editor'
  } catch (e: any) {
    error.value = '打开失败: ' + (e?.message || e)
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') selectedId.value = null
}

function durationText(n: LaidOutNode): string {
  return n.infinite ? '∞' : `${n.duration.toFixed(1)}s`
}

function trackText(n: LaidOutNode): string {
  return n.tracks.length ? n.tracks.map(t => `${t.type}×${t.clips}`).join(' · ') : '无轨道'
}

onMounted(() => {
  load()
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  onPanUp()
})
</script>

<template>
  <div class="graph-view">
    <div class="graph-toolbar">
      <span class="title">脚本架构图</span>
      <button @click="load" :disabled="loading">{{ loading ? '扫描中…' : '刷新' }}</button>
      <span class="divider"></span>
      <input class="search" v-model="query" placeholder="搜索脚本名 / id / 路径 / 触发器" />
      <select v-model="triggerFilter">
        <option value="">全部触发器</option>
        <option v-for="t in triggerTypes" :key="t" :value="t">{{ t }}</option>
      </select>
      <select v-model="folderFilter">
        <option value="">全部分区</option>
        <option v-for="f in folders" :key="f" :value="f">{{ folderLabel(f) }}</option>
      </select>
      <label class="toggle">
        <input type="checkbox" v-model="onlyMatches" :disabled="!hasFilter" />
        只看匹配
      </label>
      <span class="divider"></span>
      <button @click="zoomBy(1 / 1.25)" title="缩小">−</button>
      <span class="zoom">{{ Math.round(scale * 100) }}%</span>
      <button @click="zoomBy(1.25)" title="放大">+</button>
      <button @click="fit" title="适配视图">适配</button>
      <span class="stats" v-if="graph">
        {{ graph.nodes.length }} 脚本 · {{ graph.edges.length }} 依赖 · {{ graph.folders.length }} 分区
      </span>
      <span class="hint">拖拽平移 · 滚轮缩放 · 单击高亮上下游 · 双击打开脚本</span>
    </div>

    <div v-if="error" class="graph-banner error">
      {{ error }}
      <button class="banner-close" @click="error = ''">×</button>
    </div>

    <details v-if="graph && graph.warnings.length" class="graph-banner warn">
      <summary>图数据提示 {{ graph.warnings.length }} 条（悬空 requires / 重复 id / 写坏的脚本）</summary>
      <ul>
        <li v-for="(w, i) in graph.warnings.slice(0, 50)" :key="i">
          <span class="warn-kind">{{ w.kind }}</span>
          <span class="warn-path">{{ w.path }}</span>
          <span>{{ w.message }}</span>
        </li>
      </ul>
    </details>

    <div
      ref="viewport"
      class="graph-canvas"
      :class="{ panning }"
      @wheel.prevent="onWheel"
      @mousedown="onCanvasDown"
    >
      <div v-if="layout" class="world" :style="worldStyle">
        <div
          v-for="p in layout.partitions"
          :key="p.folder"
          class="partition"
          :style="{ left: p.x + 'px', top: p.y + 'px', width: p.w + 'px', height: p.h + 'px' }"
        >
          <div class="partition-title">
            {{ p.label }}<span class="partition-count">{{ p.count }}</span>
          </div>
        </div>

        <svg class="edges" :width="layout.width" :height="layout.height">
          <path
            v-for="e in layout.edges"
            :key="e.key"
            :d="e.d"
            class="edge"
            :class="{
              'edge-up': edgeStates.get(e.key)?.up,
              'edge-down': edgeStates.get(e.key)?.down,
              'edge-dim': edgeStates.get(e.key)?.dim,
              'edge-hidden': edgeStates.get(e.key)?.hidden,
              'edge-back': e.back,
              'edge-cross': e.cross,
            }"
          />
        </svg>

        <div
          v-for="n in layout.nodes"
          :key="n.id"
          class="node"
          :class="{
            'node-selected': n.id === selectedId,
            'node-up': nodeStates.get(n.id)?.up,
            'node-down': nodeStates.get(n.id)?.down,
            'node-dim': nodeStates.get(n.id)?.dim,
            'node-hidden': nodeStates.get(n.id)?.hidden,
            'node-cycle': n.inCycle,
            'node-invalid': !n.valid,
            'node-isolated': n.isolated,
          }"
          :style="{ left: n.x + 'px', top: n.y + 'px', width: n.w + 'px', height: n.h + 'px' }"
          @click.stop="onNodeClick(n)"
          @dblclick.stop="onNodeOpen(n)"
          @mouseenter="onNodeEnter(n, $event)"
          @mousemove="onNodeEnter(n, $event)"
          @mouseleave="hoverId = null"
        >
          <div class="node-head">
            <span class="node-name" :title="n.name">{{ n.name }}</span>
            <span v-if="n.inCycle" class="node-flag" title="处于 requires 环内">环</span>
            <span v-else-if="!n.valid" class="node-flag" title="脚本有问题">!</span>
          </div>
          <div class="node-path" :title="n.path">{{ n.path }}</div>
          <div class="node-meta">
            <span class="node-duration">{{ durationText(n) }}</span>
            <span v-for="t in n.triggers.slice(0, 2)" :key="t.type" class="chip">{{ t.type }}</span>
            <span v-if="n.triggers.length > 2" class="chip">+{{ n.triggers.length - 2 }}</span>
          </div>
        </div>
      </div>

      <div v-if="!loading && (!graph || graph.nodes.length === 0)" class="placeholder">
        <p v-if="error">{{ error }}</p>
        <p v-else-if="!state.connected">未连接模组：连接后可查看脚本架构图</p>
        <p v-else>scripts 目录里还没有脚本</p>
      </div>

      <div v-if="hoverNode" class="tooltip" :style="tooltipStyle">
        <div class="tt-title">{{ hoverNode.name }}</div>
        <div class="tt-sub">{{ hoverNode.path }}<span v-if="hoverNode.scriptId"> · id={{ hoverNode.scriptId }}</span></div>
        <div class="tt-row"><span class="tt-key">时长</span>{{ durationText(hoverNode) }}<span v-if="hoverNode.infinite" class="tt-note">（无限）</span></div>
        <div class="tt-row"><span class="tt-key">轨道</span>{{ trackText(hoverNode) }}</div>
        <div class="tt-row" v-if="hoverNode.description"><span class="tt-key">说明</span>{{ hoverNode.description }}</div>
        <div class="tt-row" v-if="hoverNode.dimension"><span class="tt-key">维度</span>{{ hoverNode.dimension }}</div>
        <div class="tt-row" v-if="hoverNode.author"><span class="tt-key">作者</span>{{ hoverNode.author }}</div>
        <div class="tt-row" v-if="hoverNode.error"><span class="tt-key">问题</span><span class="tt-bad">{{ hoverNode.error }}</span></div>
        <div class="tt-section">触发器（{{ hoverNode.triggers.length }}）</div>
        <div v-for="(t, i) in hoverNode.triggers" :key="i" class="tt-trigger">
          <div class="tt-trigger-head">{{ t.type }}<span v-if="t.repeatable" class="tt-note"> 可重复</span></div>
          <div v-if="!t.requires.length" class="tt-note">无前置依赖</div>
          <div v-for="(r, j) in t.requires" :key="j" class="tt-req">
            <span :class="{ 'tt-bad': r.script && r.resolved === false }">{{ r.type }}</span>
            <span v-if="r.script">→ {{ r.script }}<span v-if="r.resolved === false" class="tt-bad">（缺失）</span></span>
            <span v-else class="tt-note">（自定义前置，无脚本）</span>
          </div>
        </div>
        <div class="tt-foot">
          上游 {{ hoverNode.requires.length }} 个 requires 引用 · 双击打开脚本
        </div>
      </div>
    </div>

    <div class="graph-legend">
      <span class="legend-item"><i class="swatch swatch-up"></i>上游（前置）</span>
      <span class="legend-item"><i class="swatch swatch-down"></i>下游（依赖方）</span>
      <span class="legend-item"><i class="swatch swatch-cycle"></i>requires 环</span>
      <span class="legend-item"><i class="swatch swatch-invalid"></i>脚本有问题</span>
      <span class="legend-item"><i class="swatch swatch-isolated"></i>孤立脚本</span>
    </div>
  </div>
</template>

<style scoped>
.graph-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #16161a;
}
.graph-toolbar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--border);
  background: #1e1e24;
  font-size: 12px;
  flex-wrap: wrap;
}
.graph-toolbar .title { font-weight: 600; margin-right: 4px; }
.graph-toolbar .divider { width: 1px; height: 18px; background: var(--border); margin: 0 4px; }
.graph-toolbar .search {
  background: #26262c;
  border: 1px solid #3a3a44;
  color: var(--text);
  border-radius: 4px;
  padding: 4px 6px;
  width: 220px;
}
.graph-toolbar select {
  background: #26262c;
  border: 1px solid #3a3a44;
  color: var(--text);
  border-radius: 4px;
  padding: 4px 6px;
}
.graph-toolbar .toggle { display: flex; align-items: center; gap: 4px; color: var(--text-dim); }
.graph-toolbar .zoom { min-width: 42px; text-align: center; color: var(--text-dim); }
.graph-toolbar .stats { color: var(--text-dim); margin-left: 6px; }
.graph-toolbar .hint { color: #6a6a76; margin-left: auto; }
.graph-banner {
  padding: 6px 10px;
  font-size: 12px;
  border-bottom: 1px solid var(--border);
  background: #2a2224;
  color: #f0b4b4;
}
.graph-banner.error { background: #3a2226; color: #ffb4b4; display: flex; justify-content: space-between; }
.graph-banner.warn { background: #2a2620; color: #e8c98a; cursor: pointer; }
.graph-banner ul { margin: 6px 0 0; padding-left: 16px; max-height: 160px; overflow: auto; }
.graph-banner li { margin: 2px 0; color: #cbb489; }
.warn-kind { display: inline-block; min-width: 96px; color: #e8a24a; }
.warn-path { margin-right: 8px; color: #d8d8e0; }
.banner-close { background: transparent; border: none; color: inherit; cursor: pointer; }

.graph-canvas {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  cursor: grab;
  background-color: #121216;
  background-image: radial-gradient(#232330 1px, transparent 1px);
  background-size: 24px 24px;
}
.graph-canvas.panning { cursor: grabbing; }
.world {
  position: absolute;
  left: 0;
  top: 0;
  transform-origin: 0 0;
}
.partition {
  position: absolute;
  border: 1px dashed #3a3a48;
  border-radius: 8px;
  background: rgba(38, 38, 48, 0.35);
  box-sizing: border-box;
}
.partition-title {
  position: absolute;
  left: 10px;
  top: 6px;
  font-size: 12px;
  color: #9a9aa8;
  letter-spacing: .5px;
}
.partition-count {
  margin-left: 6px;
  font-size: 10px;
  color: #6a6a78;
  border: 1px solid #3a3a48;
  border-radius: 8px;
  padding: 0 5px;
}
.edges { position: absolute; left: 0; top: 0; pointer-events: none; overflow: visible; }
.edge {
  fill: none;
  stroke: #4a4a5a;
  stroke-width: 1.6;
}
.edge-cross { stroke-dasharray: 6 3; }
.edge-back { stroke: #d9822b; }
.edge-up { stroke: var(--green); stroke-width: 2.4; }
.edge-down { stroke: var(--accent); stroke-width: 2.4; }
.edge-dim { opacity: .12; }
.edge-hidden { display: none; }

.node {
  position: absolute;
  box-sizing: border-box;
  padding: 6px 8px;
  border-radius: 6px;
  border: 1px solid #43434f;
  background: #22222a;
  color: var(--text);
  cursor: pointer;
  overflow: hidden;
  transition: opacity .12s, border-color .12s;
}
.node:hover { border-color: #6a7fd0; }
.node-selected { border-color: #fff; box-shadow: 0 0 0 1px #fff inset; }
.node-up { border-color: var(--green); }
.node-down { border-color: var(--accent); }
.node-cycle { border-color: #d9822b; border-style: dashed; }
.node-invalid { border-color: var(--red); }
.node-isolated { background: #1e1e26; }
.node-dim { opacity: .22; }
.node-hidden { display: none; }
.node-head { display: flex; align-items: center; gap: 4px; }
.node-name {
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
}
.node-flag {
  font-size: 10px;
  color: #ffce8a;
  border: 1px solid #6a5330;
  border-radius: 3px;
  padding: 0 3px;
}
.node-path {
  font-size: 10px;
  color: #7a7a88;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-top: 2px;
}
.node-meta { display: flex; align-items: center; gap: 4px; margin-top: 5px; }
.node-duration { font-size: 10px; color: #9ad0b0; }
.chip {
  font-size: 10px;
  color: #a8b6d8;
  background: #2c3244;
  border-radius: 3px;
  padding: 0 4px;
}
.placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-dim);
  font-size: 13px;
}
.tooltip {
  position: absolute;
  background: #1c1c24;
  border: 1px solid #3f3f4c;
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 11px;
  color: var(--text);
  box-shadow: 0 6px 20px rgba(0, 0, 0, .5);
  pointer-events: none;
  z-index: 5;
  max-height: 70%;
  overflow: hidden;
}
.tt-title { font-size: 12px; font-weight: 600; }
.tt-sub { color: #7a7a88; margin-bottom: 4px; word-break: break-all; }
.tt-row { display: flex; gap: 6px; margin: 2px 0; }
.tt-key { color: #7a7a88; min-width: 30px; }
.tt-note { color: #7a7a88; }
.tt-bad { color: #ff9c9c; }
.tt-section { margin-top: 6px; color: #9a9aa8; border-top: 1px solid #33333c; padding-top: 4px; }
.tt-trigger { margin: 3px 0 3px 4px; }
.tt-trigger-head { color: #a8b6d8; }
.tt-req { margin-left: 10px; color: #c8c8d4; }
.tt-foot { margin-top: 6px; color: #6a6a78; }
.graph-legend {
  display: flex;
  gap: 14px;
  padding: 4px 10px;
  font-size: 11px;
  color: var(--text-dim);
  border-top: 1px solid var(--border);
  background: #1e1e24;
}
.legend-item { display: flex; align-items: center; gap: 4px; }
.swatch { width: 10px; height: 10px; border-radius: 2px; display: inline-block; }
.swatch-up { background: var(--green); }
.swatch-down { background: var(--accent); }
.swatch-cycle { background: #d9822b; }
.swatch-invalid { background: var(--red); }
.swatch-isolated { background: #3a3a48; }
</style>
