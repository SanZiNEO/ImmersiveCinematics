<script setup lang="ts">
import { computed } from 'vue'
import { state } from '../store'

// 彩虹括号颜色（按深度循环）
const BRACKET_COLORS = [
  '#ff7b72', // 红
  '#ffa657', // 橙
  '#e3b341', // 黄
  '#7ee787', // 绿
  '#56d4dd', // 青
  '#79c0ff', // 蓝
  '#d2a8ff', // 紫
  '#ff9bce', // 粉
]

const highlighted = computed(() => {
  if (!state.doc) return ''
  const json = JSON.stringify(state.doc, null, 2)
  return highlightJson(json)
})

/**
 * JSON 语法高亮 + 彩虹括号
 * - key 字符串：浅蓝
 * - value 字符串：浅绿
 * - 数字：橙
 * - 布尔/关键字：紫
 * - 括号：按深度彩虹色
 */
function highlightJson(json: string): string {
  let result = ''
  let i = 0
  let depth = 0
  const bracketStack: number[] = [] // 记录每个左括号的深度

  while (i < json.length) {
    const ch = json[i]

    // 字符串
    if (ch === '"') {
      let j = i + 1
      let str = '"'
      while (j < json.length) {
        str += json[j]
        if (json[j] === '\\' && j + 1 < json.length) {
          str += json[j + 1]
          j += 2
          continue
        }
        if (json[j] === '"') { j++; break }
        j++
      }
      // 判断是 key 还是 value：看后面是否跟冒号
      const rest = json.slice(j).trimStart()
      const isKey = rest.startsWith(':')
      const color = isKey ? '#79c0ff' : '#a5d6ff'
      result += `<span style="color:${color}">${escapeHtml(str)}</span>`
      i = j
      continue
    }

    // 数字
    if (/[0-9-]/.test(ch) && (i === 0 || !/[a-zA-Z_]/.test(json[i - 1]))) {
      let j = i
      while (j < json.length && /[0-9.eE+-]/.test(json[j])) j++
      result += `<span style="color:#ffa657">${json.slice(i, j)}</span>`
      i = j
      continue
    }

    // 关键字 true/false/null
    if (/[a-z]/.test(ch)) {
      let j = i
      while (j < json.length && /[a-z]/.test(json[j])) j++
      const word = json.slice(i, j)
      if (word === 'true' || word === 'false' || word === 'null') {
        result += `<span style="color:#ff7b72">${word}</span>`
      } else {
        result += word
      }
      i = j
      continue
    }

    // 括号
    if (ch === '{' || ch === '[') {
      const color = BRACKET_COLORS[depth % BRACKET_COLORS.length]
      bracketStack.push(depth)
      depth++
      result += `<span style="color:${color};font-weight:600">${ch}</span>`
      i++
      continue
    }
    if (ch === '}' || ch === ']') {
      depth = Math.max(0, depth - 1)
      const d = bracketStack.pop() ?? depth
      const color = BRACKET_COLORS[d % BRACKET_COLORS.length]
      result += `<span style="color:${color};font-weight:600">${ch}</span>`
      i++
      continue
    }

    // 冒号
    if (ch === ':') {
      result += `<span style="color:#8a8a96">:</span>`
      i++
      continue
    }

    // 逗号
    if (ch === ',') {
      result += `<span style="color:#8a8a96">,</span>`
      i++
      continue
    }

    result += ch
    i++
  }

  return result
}

function escapeHtml(str: string): string {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
</script>

<template>
  <div class="json-viewer">
    <pre class="json-content" v-html="highlighted"></pre>
  </div>
</template>

<style scoped>
.json-viewer {
  height: 100%;
  overflow: auto;
  background: #1e1e24;
}
.json-content {
  margin: 0;
  padding: 12px;
  font-family: 'Consolas', 'Menlo', 'Cascadia Code', monospace;
  font-size: 12px;
  line-height: 1.6;
  color: #d8d8e0;
  white-space: pre;
  user-select: text;
  min-width: max-content;
}
</style>
