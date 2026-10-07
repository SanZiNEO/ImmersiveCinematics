// ─────────────────────────────────────────────────────────────
// 脚本架构图 — 自研布局（拓扑分层 + 文件夹泳道 + 孤立脚本收拢）
//
// 数据来自 Java 侧 script.graph（见 ScriptGraphService）：
//   节点 = 脚本；边 = 触发器 requires 前置依赖（被依赖 → 依赖方）；分区 = 文件夹。
//
// 布局口径（plans/0.3.6/editor-script-graph.md §3）：
//   - 分区 = 泳道：每个文件夹一条横向泳道（分组外框 + 标题），泳道左边缘对齐，
//     列（层）跨泳道对齐——跨文件夹的依赖边因此仍然是从左到右的流向；
//   - 分区内按依赖拓扑分层：被依赖的靠左，依赖方靠右；
//   - requires 成环：SCC（Tarjan）缩点后再分层，环内节点同层且标记 inCycle（前端标红/虚线）；
//   - 无任何依赖关系的孤立脚本：泳道内按网格收拢在分层区右侧，不散落。
// 不引重型图库：这里只有纯函数，返回绝对坐标，组件只负责画。
// ─────────────────────────────────────────────────────────────

export interface GraphTrack {
  type: string
  clips: number
}

export interface GraphRequirement {
  /** 前置条件类型：内置 script_played / script_started / script_completed，或自定义类型 */
  type: string
  /** 目标脚本 id；自定义类型为空串（不成边） */
  script: string
  /** 目标脚本是否存在（Java 侧解析结果；无脚本 id 的前置条件没有该字段） */
  resolved?: boolean
}

export interface GraphTrigger {
  type: string
  repeatable: boolean
  requires: GraphRequirement[]
}

export interface GraphNode {
  /** 节点 id = scripts 下的相对路径（唯一） */
  id: string
  path: string
  /** 所在文件夹（"" = scripts 根） */
  folder: string
  /** 脚本 id（meta.id）；脚本写坏时 Java 侧不输出该字段 */
  scriptId?: string | null
  name: string
  author: string
  description: string
  dimension: string
  priority: number
  /** 秒；负数 = 无限时长 */
  duration: number
  infinite: boolean
  valid: boolean
  error?: string
  tracks: GraphTrack[]
  triggers: GraphTrigger[]
  /** 本脚本全部 requires 目标脚本 id（去重） */
  requires: string[]
}

export interface GraphEdge {
  /** 被依赖脚本的节点 id（前置） */
  from: string
  /** 依赖方脚本的节点 id */
  to: string
  fromScript: string
  toScript: string
  /** 触发这条依赖的触发器类型 */
  trigger: string
  /** 前置条件类型 */
  requirement: string
}

export interface GraphWarning {
  kind: string
  path: string
  message: string
}

export interface GraphData {
  nodes: GraphNode[]
  edges: GraphEdge[]
  folders: { path: string; count: number }[]
  warnings: GraphWarning[]
}

export interface LaidOutNode extends GraphNode {
  x: number
  y: number
  w: number
  h: number
  /** 拓扑层（列）；孤立脚本为 -1 */
  layer: number
  /** 处于 requires 环内 */
  inCycle: boolean
  /** 无任何依赖关系的孤立脚本 */
  isolated: boolean
}

export interface LaidOutEdge {
  key: string
  from: LaidOutNode
  to: LaidOutNode
  /** SVG path */
  d: string
  /** 跨文件夹的边 */
  cross: boolean
  /** 回流边（目标层 ≤ 源层：环内边或逆流边） */
  back: boolean
}

export interface Partition {
  folder: string
  label: string
  x: number
  y: number
  w: number
  h: number
  count: number
}

export interface GraphLayout {
  nodes: LaidOutNode[]
  edges: LaidOutEdge[]
  partitions: Partition[]
  width: number
  height: number
}

export const NODE_W = 196
export const NODE_H = 64
const GAP_X = 76
const GAP_Y = 18
const LANE_PAD = 18
const LANE_HEADER = 30
const LANE_GAP = 26
/** 孤立脚本网格每列行数 */
const ISOLATED_ROWS = 3

const COL_W = NODE_W + GAP_X
const ROW_H = NODE_H + GAP_Y

export function folderLabel(folder: string): string {
  return folder === '' ? 'scripts' : folder
}

/** 主入口：图数据 → 绝对坐标布局。 */
export function computeLayout(graph: GraphData): GraphLayout {
  const nodes = graph.nodes.slice().sort((a, b) => a.path.localeCompare(b.path))
  const indexOf = new Map<string, number>()
  nodes.forEach((n, i) => indexOf.set(n.id, i))

  // 只保留两端都在图里的边（悬空边在 Java 侧已进 warnings）
  const edges = graph.edges.filter(e => indexOf.has(e.from) && indexOf.has(e.to))

  const outAdj: number[][] = nodes.map(() => [])
  const inAdj: number[][] = nodes.map(() => [])
  const outSeen = new Set<string>()
  const inSeen = new Set<string>()
  for (const e of edges) {
    const u = indexOf.get(e.from)!
    const v = indexOf.get(e.to)!
    if (u === v) continue
    if (outSeen.add(`${u}>${v}`)) outAdj[u].push(v)
    if (inSeen.add(`${v}<${u}`)) inAdj[v].push(u)
  }

  const isolated = nodes.map((_, i) => outAdj[i].length === 0 && inAdj[i].length === 0)

  // ── SCC 缩点（Tarjan，迭代版）→ 环检测 + 分层 ──
  const scc = tarjan(nodes.length, outAdj)
  const inCycle = scc.comp.map(c => scc.cyclic[c])
  const layerOfComp = condenseLayers(scc.count, outAdj, scc.comp)
  const layer = scc.comp.map(c => layerOfComp[c])

  // ── 泳道（文件夹）：根在前，其余按路径排序 ──
  const laneOrder = new Map<string, string[]>()
  nodes.forEach((n, i) => {
    const key = n.folder
    if (!laneOrder.has(key)) laneOrder.set(key, [])
    laneOrder.get(key)!.push(String(i))
  })
  const folders = [...laneOrder.keys()].sort((a, b) =>
    a === '' ? (b === '' ? 0 : -1) : b === '' ? 1 : a.localeCompare(b))

  // 先算每条泳道用到的最大列，保证列跨泳道对齐
  const laneCols = new Map<string, number>()
  let totalCols = 1
  for (const folder of folders) {
    const idxs = laneOrder.get(folder)!.map(Number)
    const layered = idxs.filter(i => !isolated[i])
    const isolatedIdxs = idxs.filter(i => isolated[i])
    const maxLayer = layered.length ? Math.max(...layered.map(i => layer[i])) : -1
    const isolatedCols = isolatedIdxs.length ? Math.ceil(isolatedIdxs.length / ISOLATED_ROWS) : 0
    const cols = Math.max(1, maxLayer + 1 + isolatedCols)
    laneCols.set(folder, cols)
    totalCols = Math.max(totalCols, cols)
  }

  const contentW = totalCols * COL_W - GAP_X
  const laneW = contentW + LANE_PAD * 2

  const laidNodes: LaidOutNode[] = nodes.map((n, i) => ({
    ...n,
    x: 0, y: 0, w: NODE_W, h: NODE_H,
    layer: isolated[i] ? -1 : layer[i],
    inCycle: inCycle[i],
    isolated: isolated[i],
  }))

  const partitions: Partition[] = []
  let laneY = 0
  for (const folder of folders) {
    const idxs = laneOrder.get(folder)!.map(Number)
    const layered = idxs.filter(i => !isolated[i]).sort((a, b) => layer[a] - layer[b])
    const isolatedIdxs = idxs.filter(i => isolated[i])
    const byLayer = new Map<number, number[]>()
    for (const i of layered) {
      if (!byLayer.has(layer[i])) byLayer.set(layer[i], [])
      byLayer.get(layer[i])!.push(i)
    }

    const contentTop = laneY + LANE_HEADER + LANE_PAD
    const rowOf = new Map<number, number>()
    let maxRows = 1
    for (const l of [...byLayer.keys()].sort((a, b) => a - b)) {
      // 同层排序：先按父节点平均行号（减少交叉），再按路径稳定
      const column = byLayer.get(l)!.slice().sort((a, b) => {
        const ba = barycenter(a, inAdj, rowOf)
        const bb = barycenter(b, inAdj, rowOf)
        if (ba !== bb) return ba - bb
        return nodes[a].path.localeCompare(nodes[b].path)
      })
      column.forEach((i, row) => {
        rowOf.set(i, row)
        laidNodes[i].x = LANE_PAD + l * COL_W
        laidNodes[i].y = contentTop + row * ROW_H
      })
      maxRows = Math.max(maxRows, column.length)
    }

    // 孤立脚本：分层区右侧网格收拢
    const baseCol = (layered.length ? Math.max(...layered.map(i => layer[i])) + 1 : 0)
    isolatedIdxs.forEach((i, k) => {
      const col = baseCol + Math.floor(k / ISOLATED_ROWS)
      const row = k % ISOLATED_ROWS
      rowOf.set(i, row)
      laidNodes[i].x = LANE_PAD + col * COL_W
      laidNodes[i].y = contentTop + row * ROW_H
      maxRows = Math.max(maxRows, row + 1)
    })

    const laneH = LANE_HEADER + LANE_PAD * 2 + maxRows * ROW_H - GAP_Y
    partitions.push({
      folder,
      label: folderLabel(folder),
      x: 0,
      y: laneY,
      w: laneW,
      h: laneH,
      count: idxs.length,
    })
    laneY += laneH + LANE_GAP
  }

  const laidEdges: LaidOutEdge[] = []
  for (const e of edges) {
    const from = laidNodes[indexOf.get(e.from)!]
    const to = laidNodes[indexOf.get(e.to)!]
    if (from.id === to.id) continue
    const x1 = from.x + from.w
    const y1 = from.y + from.h / 2
    const x2 = to.x
    const y2 = to.y + to.h / 2
    const dx = Math.max(36, Math.abs(x2 - x1) * 0.5)
    laidEdges.push({
      key: `${e.from}>${e.to}|${e.trigger}|${e.requirement}`,
      from,
      to,
      d: `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`,
      cross: from.folder !== to.folder,
      back: to.layer >= 0 && from.layer >= 0 && to.layer <= from.layer,
    })
  }

  return {
    nodes: laidNodes,
    edges: laidEdges,
    partitions,
    width: Math.max(laneW, 1),
    height: Math.max(laneY - LANE_GAP, 1),
  }
}

/** 同层排序用的父节点平均行号；没有已定位的父节点时排到最后。 */
function barycenter(i: number, inAdj: number[][], rowOf: Map<number, number>): number {
  const parents = inAdj[i].filter(p => rowOf.has(p))
  if (!parents.length) return Number.MAX_SAFE_INTEGER
  let sum = 0
  for (const p of parents) sum += rowOf.get(p)!
  return sum / parents.length
}

/** 迭代版 Tarjan：返回每个节点的 SCC 编号 + 各 SCC 是否成环。 */
function tarjan(n: number, adj: number[][]): { comp: number[]; count: number; cyclic: boolean[] } {
  const index = new Array<number>(n).fill(-1)
  const low = new Array<number>(n).fill(0)
  const onStack = new Array<boolean>(n).fill(false)
  const comp = new Array<number>(n).fill(-1)
  const stack: number[] = []
  const sizes: number[] = []
  let counter = 0
  let compCount = 0

  for (let root = 0; root < n; root++) {
    if (index[root] !== -1) continue
    const frames: { v: number; i: number }[] = []
    index[root] = low[root] = counter++
    stack.push(root)
    onStack[root] = true
    frames.push({ v: root, i: 0 })
    while (frames.length) {
      const frame = frames[frames.length - 1]
      const v = frame.v
      if (frame.i < adj[v].length) {
        const w = adj[v][frame.i++]
        if (index[w] === -1) {
          index[w] = low[w] = counter++
          stack.push(w)
          onStack[w] = true
          frames.push({ v: w, i: 0 })
        } else if (onStack[w]) {
          low[v] = Math.min(low[v], index[w])
        }
      } else {
        frames.pop()
        if (frames.length) {
          const parent = frames[frames.length - 1].v
          low[parent] = Math.min(low[parent], low[v])
        }
        if (low[v] === index[v]) {
          let size = 0
          for (;;) {
            const w = stack.pop()!
            onStack[w] = false
            comp[w] = compCount
            size++
            if (w === v) break
          }
          sizes.push(size)
          compCount++
        }
      }
    }
  }

  // 环 = 分量内节点数 > 1，或存在自环边
  const cyclic = sizes.map(s => s > 1)
  for (let u = 0; u < n; u++) {
    for (const v of adj[u]) {
      if (u === v) cyclic[comp[u]] = true
    }
  }
  return { comp, count: compCount, cyclic }
}

/** 缩点后的 DAG 上做最长路径分层：layer(v) = max(layer(u) + 1)，u → v。 */
function condenseLayers(compCount: number, adj: number[][], comp: number[]): number[] {
  const succ: Set<number>[] = Array.from({ length: compCount }, () => new Set<number>())
  const inDegree = new Array<number>(compCount).fill(0)
  for (let u = 0; u < adj.length; u++) {
    for (const v of adj[u]) {
      const cu = comp[u]
      const cv = comp[v]
      if (cu === cv) continue
      if (!succ[cu].has(cv)) {
        succ[cu].add(cv)
        inDegree[cv]++
      }
    }
  }
  const layer = new Array<number>(compCount).fill(0)
  const queue: number[] = []
  for (let c = 0; c < compCount; c++) if (inDegree[c] === 0) queue.push(c)
  for (let head = 0; head < queue.length; head++) {
    const c = queue[head]
    for (const d of succ[c]) {
      layer[d] = Math.max(layer[d], layer[c] + 1)
      if (--inDegree[d] === 0) queue.push(d)
    }
  }
  return layer
}
