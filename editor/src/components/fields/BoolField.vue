<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import type { SchemaField } from '../../types'

const props = defineProps<{
  field: SchemaField
  modelValue: unknown
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean | null): void
}>()

const checkboxRef = ref<HTMLInputElement | null>(null)

// 三态循环：null(跟随) → true(开启) → false(关闭) → null
function onClick() {
  if (props.modelValue === null || props.modelValue === undefined) {
    emit('update:modelValue', true)
  } else if (props.modelValue === true) {
    emit('update:modelValue', false)
  } else {
    emit('update:modelValue', null)
  }
}

function label(): string {
  if (props.modelValue === null || props.modelValue === undefined) return '跟随'
  return props.modelValue ? '开启' : '关闭'
}

function labelClass(): string {
  if (props.modelValue === null || props.modelValue === undefined) return 'follow'
  return props.modelValue ? 'on' : 'off'
}

// 同步 indeterminate 状态
watch(() => props.modelValue, () => {
  if (checkboxRef.value) {
    checkboxRef.value.indeterminate = props.modelValue === null || props.modelValue === undefined
  }
}, { immediate: true })

onMounted(() => {
  if (checkboxRef.value) {
    checkboxRef.value.indeterminate = props.modelValue === null || props.modelValue === undefined
  }
})
</script>

<template>
  <label class="bool-field" @click.prevent="onClick">
    <input
      ref="checkboxRef"
      type="checkbox"
      :checked="modelValue === true"
      @click.stop="onClick"
    />
    <span class="bool-label" :class="labelClass()">{{ label() }}</span>
  </label>
</template>

<style scoped>
.bool-field {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
}
.bool-field input {
  cursor: pointer;
  width: 14px;
  height: 14px;
  accent-color: #4e7bd3;
}
.bool-label {
  font-size: 12px;
}
.bool-label.follow {
  color: #666;
  font-style: italic;
}
.bool-label.on {
  color: #34d399;
}
.bool-label.off {
  color: #888;
}
</style>
