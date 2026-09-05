<script setup lang="ts">
import { ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  modelValue: unknown
  step?: number
  min?: number
  max?: number
  title?: string
  placeholder?: string
}>(), {
  step: 0.1,
  min: undefined,
  max: undefined,
  title: '',
  placeholder: '',
})

const emit = defineEmits<{
  (e: 'update:modelValue', value: number): void
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
}

function commit(keepFocus = false) {
  const raw = draft.value.trim()
  if (raw === '') {
    draft.value = format(props.modelValue)
    if (!keepFocus) focused.value = false
    return
  }
  const num = Number(raw)
  if (!Number.isFinite(num)) {
    draft.value = format(props.modelValue)
    if (!keepFocus) focused.value = false
    return
  }
  let n = num
  if (props.min != null && n < props.min) n = props.min
  if (props.max != null && n > props.max) n = props.max
  draft.value = format(n)
  const old = Number(props.modelValue)
  if (!Object.is(n, old)) {
    emit('update:modelValue', n)
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
    type="number"
    class="commit-number"
    :value="draft"
    :step="step"
    :min="min"
    :max="max"
    :title="title"
    :placeholder="placeholder"
    @focus="onFocus"
    @input="onInput"
    @blur="onBlur"
    @keydown.enter.prevent="onEnter"
    @keydown.esc.prevent="onEscape"
  />
</template>

<style scoped>
.commit-number {
  width: 100%;
  background: #111;
  color: #ddd;
  border: 1px solid #333;
  padding: 3px 6px;
  border-radius: 3px;
  font-size: 12px;
  box-sizing: border-box;
}
.commit-number:focus {
  outline: none;
  border-color: #4e7bd3;
}
</style>
