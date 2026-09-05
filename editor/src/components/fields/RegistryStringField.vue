<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import type { SchemaField } from '../../types'
import { state, registryQuery } from '../../store'

const props = defineProps<{
  field: SchemaField
  modelValue: unknown
  fieldKey?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const suggestions = ref<string[]>([])
const loading = ref(false)
const focused = ref(false)
const draft = ref(format(props.modelValue))
let timer: ReturnType<typeof setTimeout> | null = null

function format(v: unknown): string {
  return v === null || v === undefined ? '' : String(v)
}

// 字段 key → 注册表 kind 映射（与旧 Java 编辑器数据源一致）
const KIND_BY_KEY: Record<string, string> = {
  item: 'item',
  target: 'target',
  entity: 'entity',
  biome: 'biome',
  dimension: 'dimension',
  from_dimension: 'dimension',
  advancement: 'advancement',
  structure: 'structure',
  look_at_target_structure: 'structure',
  stage: 'stage',
  sound: 'sound',
}

const kind = computed(() => props.fieldKey ? KIND_BY_KEY[props.fieldKey] : '')
const domId = 'ic-reg-' + Math.random().toString(36).slice(2)

watch(() => props.modelValue, (v) => {
  if (!focused.value) draft.value = format(v)
})

async function load(query: string) {
  if (!kind.value || !state.connected) return
  loading.value = true
  try {
    suggestions.value = await registryQuery(kind.value, query, 30)
  } catch {
    suggestions.value = []
  } finally {
    loading.value = false
  }
}

function onInput(e: Event) {
  const v = (e.target as HTMLInputElement).value
  draft.value = v
  if (!kind.value) return
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => load(v), 120)
}

function commit(keepFocus = false) {
  const v = draft.value
  if (v !== format(props.modelValue)) {
    emit('update:modelValue', v)
  }
  if (!keepFocus) focused.value = false
}

function onFocus() {
  focused.value = true
  draft.value = format(props.modelValue)
  if (!kind.value || suggestions.value.length > 0) return
  load('')
}

function onBlur() {
  commit(false)
}

function onEnter() {
  commit(true)
}

function onEscape() {
  draft.value = format(props.modelValue)
}

onMounted(() => {
  // 打开字段时先拉一次候选，避免用户不知道能补全
  if (kind.value && state.connected) load('')
})

watch(() => state.connected, (connected) => {
  if (connected && kind.value) load(props.modelValue as string || '')
})
</script>

<template>
  <div class="registry-string-field">
    <input
      type="text"
      class="field-input"
      :value="draft"
      :placeholder="field.required ? '必填，支持自动补全' : '支持自动补全'"
      :list="domId"
      @input="onInput"
      @focus="onFocus"
      @blur="onBlur"
      @keydown.enter.prevent="onEnter"
      @keydown.esc.prevent="onEscape"
    />
    <datalist :id="domId">
      <option v-for="s in suggestions" :key="s" :value="s" />
    </datalist>
    <span v-if="loading" class="loading">查询中…</span>
  </div>
</template>

<style scoped>
.registry-string-field {
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}
.registry-string-field .field-input {
  width: 100%;
  background: #111;
  color: #ddd;
  border: 1px solid #333;
  padding: 3px 6px;
  border-radius: 3px;
  font-size: 12px;
  box-sizing: border-box;
}
.registry-string-field .field-input:focus {
  outline: none;
  border-color: #4e7bd3;
}
.registry-string-field .loading {
  position: absolute;
  right: 6px;
  font-size: 10px;
  color: #666;
  pointer-events: none;
}
</style>
