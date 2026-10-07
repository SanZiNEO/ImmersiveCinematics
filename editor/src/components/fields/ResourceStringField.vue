<script setup lang="ts">
// 资源字段（0.3.6 文本资源）：字幕 text / 脚本 description = @lang:<key> 引用选择；OVERLAY 图片 path = resource/ 文件选择。
// 与 RegistryStringField 同款交互：纯文本输入 + datalist 候选 + 加载中提示；
// 额外给出「当前值」的校验提示（缺 key / 缺文件只警告、不阻塞——资源目录本来就可以后补）。
import { ref, computed, watch, onMounted } from 'vue'
import type { ResourceLangEntry, SchemaField } from '../../types'
import { state, resourceList } from '../../store'
import { t, reactiveLang } from '../../i18n'
import { LANG_PREFIX, langHint, fileHint, translationOf } from './resourceHint'

const props = defineProps<{
  field: SchemaField
  modelValue: unknown
  fieldKey?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

/** 字段 key → 资源种类（后端 resource.list 的 kind）：text / description 走 lang key，path 走图片文件 */
const KIND_BY_KEY: Record<string, string> = {
  text: 'lang',
  description: 'lang',
  path: 'image',
}

const kind = computed(() => (props.fieldKey ? KIND_BY_KEY[props.fieldKey] ?? '' : ''))

const entries = ref<ResourceLangEntry[]>([])
const files = ref<string[]>([])
const loaded = ref(false)
const loading = ref(false)
const focused = ref(false)
const draft = ref(format(props.modelValue))
const domId = 'ic-res-' + Math.random().toString(36).slice(2)

const uiLang = computed(() => {
  void reactiveLang.value
  return reactiveLang.value
})

function format(v: unknown): string {
  return v === null || v === undefined ? '' : String(v)
}

/** datalist 候选：lang = @lang:<key>（label 显示译文），image = resource/ 下的相对路径 */
const options = computed(() => {
  if (kind.value === 'lang') {
    return entries.value.map(e => ({
      value: LANG_PREFIX + e.key,
      label: (translationOf(e, uiLang.value) ?? '').replace(/\n/g, '⏎'),
    }))
  }
  return files.value.map(f => ({ value: f, label: '' }))
})

const placeholder = computed(() => {
  if (kind.value === 'lang') return t('resource.lang_placeholder')
  if (kind.value === 'image') return t('resource.path_placeholder')
  return props.field.required ? t('resource.required') : ''
})

/** 当前值的提示：命中的译文（普通）或缺失警告；未加载成功时不提示，避免误报 */
const hint = computed(() => {
  if (!loaded.value || !kind.value) return null
  const v = draft.value.trim()
  if (!v) return null
  if (kind.value === 'lang') return langHint(v, entries.value, uiLang.value)
  return fileHint(v, files.value)
})

watch(() => props.modelValue, (v) => {
  if (!focused.value) draft.value = format(v)
})

async function load() {
  if (!kind.value || !state.connected) return
  loading.value = true
  try {
    const r = await resourceList(kind.value)
    entries.value = r.entries || []
    files.value = r.files || []
    loaded.value = true
  } catch {
    entries.value = []
    files.value = []
    loaded.value = false
  } finally {
    loading.value = false
  }
}

function commit(keepFocus = false) {
  const v = draft.value
  if (v !== format(props.modelValue)) {
    emit('update:modelValue', v)
  }
  if (!keepFocus) focused.value = false
}

function onInput(e: Event) {
  draft.value = (e.target as HTMLInputElement).value
}

function onFocus() {
  focused.value = true
  draft.value = format(props.modelValue)
  if (!loaded.value) load()
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
  if (state.connected) load()
})

watch(() => state.connected, (connected) => {
  if (connected) load()
})
</script>

<template>
  <div class="resource-field">
    <div class="row">
      <input
        type="text"
        class="field-input"
        :value="draft"
        :placeholder="placeholder"
        :list="domId"
        @input="onInput"
        @focus="onFocus"
        @blur="onBlur"
        @keydown.enter.prevent="onEnter"
        @keydown.esc.prevent="onEscape"
      />
      <span v-if="loading" class="loading">{{ t('resource.loading') }}</span>
    </div>
    <datalist :id="domId">
      <option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option>
    </datalist>
    <span v-if="hint" class="hint" :class="{ warn: hint.warn }" :title="hint.text">{{ hint.text }}</span>
  </div>
</template>

<style scoped>
.resource-field {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.resource-field .row {
  position: relative;
  display: flex;
  align-items: center;
  min-width: 0;
}
.resource-field .field-input {
  width: 100%;
  background: #111;
  color: #ddd;
  border: 1px solid #333;
  padding: 3px 6px;
  border-radius: 3px;
  font-size: 12px;
  box-sizing: border-box;
}
.resource-field .field-input:focus {
  outline: none;
  border-color: #4e7bd3;
}
.resource-field .loading {
  position: absolute;
  right: 6px;
  font-size: 10px;
  color: #666;
  pointer-events: none;
}
.resource-field .hint {
  font-size: 10px;
  color: #6a8abf;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.resource-field .hint.warn {
  color: #d9a13b;
}
</style>
