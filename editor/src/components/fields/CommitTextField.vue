<script setup lang="ts">
import { ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  modelValue: unknown
  placeholder?: string
  title?: string
  list?: string
}>(), {
  placeholder: '',
  title: '',
  list: '',
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'draft', value: string): void
}>()

const focused = ref(false)
const draft = ref(format(props.modelValue))

function format(v: unknown): string {
  if (v === null || v === undefined) return ''
  return String(v)
}

watch(() => props.modelValue, (v) => {
  if (!focused.value) draft.value = format(v)
})

function onFocus() {
  focused.value = true
  draft.value = format(props.modelValue)
}

function onInput(e: Event) {
  draft.value = (e.target as HTMLInputElement).value
  emit('draft', draft.value)
}

function commit(keepFocus = false) {
  const v = draft.value
  if (v !== String(props.modelValue ?? '')) {
    emit('update:modelValue', v)
  }
  if (!keepFocus) focused.value = false
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
</script>

<template>
  <input
    type="text"
    class="commit-text"
    :value="draft"
    :placeholder="placeholder"
    :title="title"
    :list="list"
    @focus="onFocus"
    @input="onInput"
    @blur="onBlur"
    @keydown.enter.prevent="onEnter"
    @keydown.esc.prevent="onEscape"
  />
</template>

<style scoped>
.commit-text {
  width: 100%;
  background: #111;
  color: #ddd;
  border: 1px solid #333;
  padding: 3px 6px;
  border-radius: 3px;
  font-size: 12px;
  box-sizing: border-box;
}
.commit-text:focus {
  outline: none;
  border-color: #4e7bd3;
}
</style>
