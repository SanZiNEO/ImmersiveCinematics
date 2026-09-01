<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import type { SchemaField } from '../../types'
import { t } from '../../i18n'

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

function labelText(): string {
  if (props.modelValue === null || props.modelValue === undefined) return t('bool.follow')
  return props.modelValue ? t('bool.on') : t('bool.off')
}

function labelClass(): string {
  if (props.modelValue === null || props.modelValue === undefined) return 'follow'
  return props.modelValue ? 'on' : 'off'
}

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
  <div class="bool-field" @click="onClick">
    <input
      ref="checkboxRef"
      type="checkbox"
      :checked="modelValue === true"
      @click.stop="onClick"
    />
    <span class="bool-label" :class="labelClass()">{{ labelText() }}</span>
  </div>
</template>

<style scoped>
.bool-field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
  padding: 2px 0;
}
.bool-field input[type="checkbox"] {
  width: 14px;
  height: 14px;
  margin: 0;
  cursor: pointer;
  accent-color: #4e7bd3;
  flex-shrink: 0;
}
.bool-label {
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
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
