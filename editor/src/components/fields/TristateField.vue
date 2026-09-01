<script setup lang="ts">
import type { SchemaField } from '../../types'

defineProps<{
  field: SchemaField
  modelValue: unknown
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean | null): void
}>()

// 三态：null = 跟随 hide_hud，true = 强制开，false = 强制关
function cycle() {
  // null → true → false → null
  if (modelValue === null || modelValue === undefined) {
    emit('update:modelValue', true)
  } else if (modelValue === true) {
    emit('update:modelValue', false)
  } else {
    emit('update:modelValue', null)
  }
}

function label(): string {
  if (modelValue === null || modelValue === undefined) return '跟随'
  return modelValue ? '强制隐藏' : '强制显示'
}

function stateClass(): string {
  if (modelValue === null || modelValue === undefined) return 'follow'
  return modelValue ? 'on' : 'off'
}
</script>

<template>
  <button class="tristate-btn" :class="stateClass()" @click="cycle">
    {{ label() }}
  </button>
</template>

<style scoped>
.tristate-btn {
  width: 100%;
  padding: 3px 8px;
  border-radius: 3px;
  border: 1px solid #333;
  background: #1a1a1e;
  color: #888;
  font-size: 11px;
  cursor: pointer;
  text-align: center;
}
.tristate-btn.follow {
  color: #888;
  border-color: #444;
}
.tristate-btn.on {
  color: #f88;
  border-color: #a44;
  background: #331a1a;
}
.tristate-btn.off {
  color: #8f8;
  border-color: #4a4;
  background: #1a331a;
}
</style>
