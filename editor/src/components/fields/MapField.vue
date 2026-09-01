<script setup lang="ts">
import { ref, watch } from 'vue'
import type { SchemaField } from '../../types'

const props = defineProps<{
  field: SchemaField
  modelValue: unknown
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, unknown>): void
}>()

const text = ref('')
const error = ref('')

watch(() => props.modelValue, (v) => {
  text.value = v ? JSON.stringify(v, null, 2) : '{}'
  error.value = ''
}, { immediate: true })

function onBlur() {
  try {
    const parsed = JSON.parse(text.value || '{}')
    emit('update:modelValue', parsed)
    error.value = ''
  } catch (e: any) {
    error.value = 'JSON 格式错误: ' + e.message
  }
}
</script>

<template>
  <div class="map-field">
    <textarea
      v-model="text"
      class="map-textarea"
      spellcheck="false"
      @blur="onBlur"
    />
    <p v-if="error" class="map-error">{{ error }}</p>
  </div>
</template>

<style scoped>
.map-field {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.map-textarea {
  width: 100%;
  min-height: 80px;
  background: #111;
  color: #ddd;
  border: 1px solid #333;
  padding: 4px 6px;
  border-radius: 3px;
  font-size: 11px;
  font-family: 'Consolas', monospace;
  box-sizing: border-box;
  resize: vertical;
}
.map-textarea:focus {
  outline: none;
  border-color: #4e7bd3;
}
.map-error {
  color: #f66;
  font-size: 11px;
  margin: 0;
}
</style>
