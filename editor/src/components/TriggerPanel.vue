<script setup lang="ts">
import { ref, computed } from 'vue'
import { state, commit } from '../store'
import DynamicForm from './DynamicForm.vue'
import type { TriggerDefinition, SchemaField } from '../types'

const selectedIndex = ref(-1)

// ── 触发器列表 ────────────────────────────────────────────────

const triggers = computed<TriggerDefinition[]>(() => {
  if (!state.doc?.meta?.triggers) return []
  return state.doc.meta.triggers as TriggerDefinition[]
})

const triggerTypes = computed(() => state.schema?.triggers?.types ?? [])

const selectedTrigger = computed(() => {
  if (selectedIndex.value < 0) return null
  return triggers.value[selectedIndex.value] ?? null
})

// 当前触发器类型的 conditions schema
const conditionFields = computed<Record<string, SchemaField>>(() => {
  const t = selectedTrigger.value?.type
  if (!t || !state.schema?.triggers?.conditions) return {}
  return state.schema.triggers.conditions[t] ?? {}
})

const conditionsData = computed(() => {
  if (!selectedTrigger.value) return {}
  return selectedTrigger.value.conditions as Record<string, unknown>
})

// ── 通用字段 schema（手动定义，不在 Java schema 里）──────────

const commonFields: Record<string, SchemaField> = {
  id: { type: 'string', default: '', required: true, enumValues: [], section: 'info' },
  type: { type: 'enum', default: 'login', required: true, enumValues: [], section: 'info' },
  repeatable: { type: 'bool', default: false, required: false, enumValues: [], section: 'info' },
  delay: { type: 'float', default: 0, required: false, enumValues: [], section: 'info' },
  on_enter: { type: 'bool', default: false, required: false, enumValues: [], section: 'info' },
  exit_buffer: { type: 'float', default: 0, required: false, enumValues: [], section: 'info' },
}

// ── 操作 ──────────────────────────────────────────────────────

function addTrigger() {
  commit(() => {
    if (!state.doc!.meta.triggers) state.doc!.meta.triggers = []
    const trig: TriggerDefinition = {
      id: 'trigger_' + (state.doc!.meta.triggers.length + 1),
      type: 'login',
      conditions: {},
      repeatable: false,
      delay: 0,
    }
    state.doc!.meta.triggers.push(trig)
    selectedIndex.value = state.doc!.meta.triggers.length - 1
  })
}

function removeTrigger(index: number) {
  commit(() => {
    state.doc!.meta.triggers!.splice(index, 1)
    selectedIndex.value = -1
  })
}

function selectTrigger(index: number) {
  selectedIndex.value = index
}

function updateCommon(key: string, value: unknown) {
  const trig = selectedTrigger.value
  if (!trig) return
  commit(() => {
    ;(trig as Record<string, unknown>)[key] = value
  })
}

function updateCondition(key: string, value: unknown) {
  const trig = selectedTrigger.value
  if (!trig) return
  commit(() => {
    trig.conditions[key] = value
  })
}

// ── requires 编辑 ─────────────────────────────────────────────

const requiresList = computed(() => {
  const r = selectedTrigger.value?.requires
  if (!r) return []
  return r.map((item, i) => {
    if (typeof item === 'string') {
      return { index: i, type: 'script_completed', script: item, raw: item }
    }
    const obj = item as Record<string, unknown>
    return {
      index: i,
      type: (obj.type as string) || 'script_completed',
      script: (obj.script as string) || '',
      raw: item,
    }
  })
})

function addRequirement() {
  const trig = selectedTrigger.value
  if (!trig) return
  commit(() => {
    if (!trig.requires) trig.requires = []
    trig.requires.push({ type: 'script_completed', script: '' })
  })
}

function removeRequirement(index: number) {
  const trig = selectedTrigger.value
  if (!trig || !trig.requires) return
  commit(() => {
    trig.requires!.splice(index, 1)
  })
}

function updateRequirement(index: number, key: 'type' | 'script', value: string) {
  const trig = selectedTrigger.value
  if (!trig || !trig.requires) return
  commit(() => {
    const item = trig.requires![index]
    if (typeof item === 'string') {
      // 转换为对象
      trig.requires![index] = { type: 'script_completed', script: item }
    }
    const obj = trig.requires![index] as Record<string, unknown>
    obj[key] = value
  })
}

// 可用脚本列表（用于 requires 下拉）
const availableScripts = computed(() => state.scripts)

// ── 类型标签 ──────────────────────────────────────────────────

function typeLabel(type: string): string {
  const labels: Record<string, string> = {
    login: '登录',
    location: '位置',
    advancement: '进度',
    biome: '生物群系',
    entity_kill: '击杀实体',
    entity_interact: '交互实体',
    block_interact: '交互方块',
    item_on_interact: '手持物品交互',
    dimension_change: '切换维度',
    item_craft: '合成物品',
    item_use: '使用物品',
    item_consume: '用完物品',
    item_release: '释放物品',
    item_instant_use: '瞬间使用',
    item_use_interrupt: '中断使用',
    item_pickup: '拾取物品',
    item_drop: '丢弃物品',
    xp: '经验',
    dimension: '驻留维度',
    observation: '准星注视',
    inventory: '背包检测',
    structure: '结构',
    gamestage: '游戏阶段',
  }
  return labels[type] ?? type
}
</script>

<template>
  <div class="trigger-panel">
    <div class="panel-header">
      <span>触发器</span>
      <button class="add-btn" @click="addTrigger">+ 添加</button>
    </div>

    <div class="trigger-list">
      <div
        v-for="(trig, i) in triggers"
        :key="i"
        class="trigger-item"
        :class="{ active: i === selectedIndex }"
        @click="selectTrigger(i)"
      >
        <div class="trig-info">
          <span class="trig-id">{{ trig.id }}</span>
          <span class="trig-type">{{ typeLabel(trig.type) }}</span>
        </div>
        <button class="del-btn" @click.stop="removeTrigger(i)">×</button>
      </div>
      <p v-if="!triggers.length" class="empty">暂无触发器，点击上方添加</p>
    </div>

    <div v-if="selectedTrigger" class="trigger-editor">
      <!-- 通用字段 -->
      <div class="editor-section">
        <div class="section-title">基本设置</div>
        <div class="form-field">
          <label>触发器 ID</label>
          <input
            type="text"
            :value="selectedTrigger.id"
            @input="updateCommon('id', ($event.target as HTMLInputElement).value)"
          />
        </div>
        <div class="form-field">
          <label>触发类型</label>
          <select
            :value="selectedTrigger.type"
            @change="updateCommon('type', ($event.target as HTMLSelectElement).value)"
          >
            <option v-for="t in triggerTypes" :key="t" :value="t">{{ typeLabel(t) }}</option>
          </select>
        </div>
        <div class="form-field inline">
          <label>
            <input
              type="checkbox"
              :checked="!!selectedTrigger.repeatable"
              @change="updateCommon('repeatable', ($event.target as HTMLInputElement).checked)"
            />
            可重复触发
          </label>
        </div>
        <div class="form-field">
          <label>延迟 (秒)</label>
          <input
            type="number"
            step="0.1"
            :value="selectedTrigger.delay ?? 0"
            @input="updateCommon('delay', parseFloat(($event.target as HTMLInputElement).value) || 0)"
          />
        </div>
        <div class="form-field inline">
          <label>
            <input
              type="checkbox"
              :checked="!!selectedTrigger.on_enter"
              @change="updateCommon('on_enter', ($event.target as HTMLInputElement).checked)"
            />
            仅首次进入
          </label>
        </div>
        <div class="form-field">
          <label>离开缓冲 (格)</label>
          <input
            type="number"
            step="0.5"
            :value="selectedTrigger.exit_buffer ?? 0"
            @input="updateCommon('exit_buffer', parseFloat(($event.target as HTMLInputElement).value) || 0)"
          />
        </div>
      </div>

      <!-- 条件字段（动态） -->
      <div v-if="Object.keys(conditionFields).length > 0" class="editor-section">
        <div class="section-title">触发条件</div>
        <DynamicForm
          :fields="conditionFields"
          :data="conditionsData"
          @update="updateCondition"
        />
      </div>
      <div v-else class="editor-section">
        <div class="section-title">触发条件</div>
        <p class="no-conditions">该类型无额外条件</p>
      </div>

      <!-- 前置条件 -->
      <div class="editor-section">
        <div class="section-title">
          前置依赖
          <button class="mini-btn" @click="addRequirement">+</button>
        </div>
        <div
          v-for="(req, i) in requiresList"
          :key="i"
          class="require-row"
        >
          <select
            :value="req.type"
            @change="updateRequirement(i, 'type', ($event.target as HTMLSelectElement).value)"
          >
            <option value="script_completed">播放完成</option>
            <option value="script_started">开始播放</option>
            <option value="script_played">播放过</option>
          </select>
          <select
            :value="req.script"
            @change="updateRequirement(i, 'script', ($event.target as HTMLSelectElement).value)"
          >
            <option value="">选择脚本...</option>
            <option v-for="s in availableScripts" :key="s" :value="s.replace('.json', '')">{{ s }}</option>
          </select>
          <button class="del-btn" @click="removeRequirement(i)">×</button>
        </div>
        <p v-if="!requiresList.length" class="no-conditions">无前置依赖（立即可触发）</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.trigger-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 10px;
  border-bottom: 1px solid #33333a;
  background: #202026;
  font-size: 13px;
  font-weight: 600;
}
.add-btn {
  font-size: 11px;
  padding: 3px 8px;
}
.trigger-list {
  max-height: 180px;
  overflow-y: auto;
  border-bottom: 1px solid #2a2a30;
}
.trigger-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 10px;
  cursor: pointer;
}
.trigger-item:hover { background: #252530; }
.trigger-item.active { background: #2f3b55; }
.trig-info {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.trig-id {
  font-size: 12px;
  color: #ddd;
}
.trig-type {
  font-size: 10px;
  color: #6a8abf;
}
.del-btn {
  background: transparent;
  border: none;
  color: #f66;
  cursor: pointer;
  padding: 0 4px;
  font-size: 16px;
  line-height: 1;
}
.empty {
  padding: 12px;
  color: #666;
  font-size: 12px;
  text-align: center;
}
.trigger-editor {
  flex: 1;
  overflow-y: auto;
}
.editor-section {
  padding: 8px 10px;
  border-bottom: 1px solid #2a2a30;
}
.section-title {
  font-size: 11px;
  color: #6a8abf;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 6px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.mini-btn {
  background: #28282e;
  border: 1px solid #3a3a44;
  color: #aaa;
  border-radius: 3px;
  padding: 1px 6px;
  font-size: 11px;
  cursor: pointer;
}
.form-field {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.form-field.inline label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #aaa;
  cursor: pointer;
}
.form-field label {
  width: 80px;
  font-size: 12px;
  color: #a0a0aa;
  flex-shrink: 0;
}
.form-field input,
.form-field select {
  flex: 1;
  background: #111;
  color: #ddd;
  border: 1px solid #333;
  padding: 3px 6px;
  border-radius: 3px;
  font-size: 12px;
  box-sizing: border-box;
}
.no-conditions {
  color: #666;
  font-size: 11px;
  margin: 4px 0;
}
.require-row {
  display: flex;
  gap: 4px;
  align-items: center;
  margin-bottom: 4px;
}
.require-row select {
  flex: 1;
  background: #111;
  color: #ddd;
  border: 1px solid #333;
  padding: 3px 4px;
  border-radius: 3px;
  font-size: 11px;
}
</style>
