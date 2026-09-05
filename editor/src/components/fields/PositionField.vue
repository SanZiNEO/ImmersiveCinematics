<script setup lang="ts">
import { computed, watch } from 'vue'
import type { SchemaField } from '../../types'
import CommitNumberField from './CommitNumberField.vue'

const props = defineProps<{
  field: SchemaField
  modelValue: unknown
  parent?: Record<string, unknown>
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, number>): void
}>()

const pos = computed(() => {
  const v = props.modelValue
  if (v && typeof v === 'object') return v as Record<string, number>
  return {}
})

// 读取 position_mode 决定显示 dx/dy/dz 还是 x/y/z
const isAbsolute = computed(() => props.parent?.position_mode === 'absolute')

const keys = computed(() => isAbsolute.value ? ['x', 'y', 'z'] : ['dx', 'dy', 'dz'])

// 旧 Java 逻辑：切换 relative/absolute 时把坐标字段互转，避免保存出脏数据
watch(isAbsolute, (abs) => {
  const p = pos.value
  if (abs && p.dx !== undefined) {
    const converted = { ...p, x: p.dx, y: p.dy, z: p.dz }
    delete converted.dx; delete converted.dy; delete converted.dz
    emit('update:modelValue', converted)
  } else if (!abs && p.x !== undefined) {
    const converted = { ...p, dx: p.x, dy: p.y, dz: p.z }
    delete converted.x; delete converted.y; delete converted.z
    emit('update:modelValue', converted)
  }
})

function setKey(key: string, value: number) {
  emit('update:modelValue', { ...pos.value, [key]: value })
}
</script>

<template>
  <div class="position-field">
    <div v-for="k in keys" :key="k" class="pos-row">
      <span class="pos-key">{{ k }}</span>
      <CommitNumberField
        :model-value="pos[k] ?? 0"
        :step="0.1"
        @update:model-value="setKey(k, $event)"
      />
    </div>
  </div>
</template>

<style scoped>
.position-field {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.pos-row {
  display: flex;
  align-items: center;
  gap: 4px;
}
.pos-key {
  width: 24px;
  font-size: 11px;
  color: #888;
  text-align: right;
}
.pos-row :deep(.commit-number) {
  flex: 1;
}
</style>
