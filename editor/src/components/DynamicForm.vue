<script setup lang="ts">
import { computed } from 'vue'
import type { SchemaField } from '../types'
import { groupBySection, getSectionLabel } from '../schema'
import FieldRenderer from './fields/FieldRenderer.vue'
import { t, reactiveLang } from '../i18n'

const props = defineProps<{
  fields: Record<string, SchemaField>
  data: Record<string, unknown>
  /** 只显示指定 section，不填则显示全部 */
  sectionFilter?: string
}>()

const emit = defineEmits<{
  (e: 'update', key: string, value: unknown): void
}>()

// 字段级条件显隐规则（key → 依赖字段和值）
const VISIBILITY_RULES: Record<string, { field: string; equals: unknown }> = {
  transition_duration: { field: 'transition', equals: 'morph' },
  loop_count: { field: 'loop', equals: true },
  loop_mode: { field: 'loop', equals: true },
  cam_breath_intensity: { field: 'cam_breath_enabled', equals: true },
  cam_breath_seed: { field: 'cam_breath_enabled', equals: true },
  cam_breath_type: { field: 'cam_breath_enabled', equals: true },
  cam_breath_speed: { field: 'cam_breath_enabled', equals: true },
  cam_breath_trauma: { field: 'cam_breath_type', equals: 'trauma' },
  cam_breath_decay: { field: 'cam_breath_type', equals: 'trauma' },
  yaw_offset: { field: 'orient', equals: 'tangent' },
  pitch_offset: { field: 'orient', equals: 'tangent' },
  follow_selector: { field: 'follow', equals: 'entity' },
  look_at_selector: { field: 'look_at', equals: 'entity' },
  look_at_target_x: { field: 'look_at', equals: 'coordinate' },
  look_at_target_y: { field: 'look_at', equals: 'coordinate' },
  look_at_target_z: { field: 'look_at', equals: 'coordinate' },
  look_at_target_structure: { field: 'look_at', equals: 'coordinate' },
  look_at_target: { field: 'look_at', equals: 'coordinate' },
  yaw_base_selector: { field: 'yaw_base', equals: 'entity' },
  yaw_base_from: { field: 'yaw_base', equals: 'line' },
  yaw_base_to: { field: 'yaw_base', equals: 'line' },
  fade_in: { field: 'source', equals: undefined }, // 始终显示，占位
}

function isVisible(key: string): boolean {
  const rule = VISIBILITY_RULES[key]
  if (!rule) return true
  if (rule.equals === undefined) return true
  return props.data[rule.field] === rule.equals
}

const sections = computed(() => {
  const grouped = groupBySection(props.fields)
  if (props.sectionFilter) {
    return { [props.sectionFilter]: grouped[props.sectionFilter] || [] }
  }
  return grouped
})

function onUpdate(key: string, value: unknown) {
  emit('update', key, value)
}

function fieldLabel(key: string): string {
  // 响应式依赖语言切换
  void reactiveLang.value
  return t('field.' + key)
}
</script>

<template>
  <div class="dynamic-form">
    <div v-for="(fields, section) in sections" :key="section" class="form-section">
      <div v-if="section !== 'info' || fields.length" class="section-title">{{ getSectionLabel(section) }}</div>
      <div
        v-for="[key, field] in fields"
        :key="key"
        v-show="isVisible(key)"
        class="form-field"
      >
        <label class="field-label" :title="key">
          {{ fieldLabel(key) }}
          <span v-if="field.required" class="required">*</span>
        </label>
        <div class="field-control">
          <FieldRenderer
            :field="field"
            :model-value="data[key]"
            :parent="data"
            :field-key="key"
            @update:model-value="onUpdate(key, $event)"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dynamic-form {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.form-section {
  margin-bottom: 8px;
}
.section-title {
  font-size: 11px;
  color: #6a8abf;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 6px 8px 4px;
  border-bottom: 1px solid #2a2a30;
  margin-bottom: 4px;
}
.form-field {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 3px 8px;
}
.form-field:hover {
  background: rgba(78, 123, 211, 0.06);
}
.field-label {
  width: 90px;
  flex-shrink: 0;
  font-size: 12px;
  color: #a0a0aa;
  padding-top: 3px;
  line-height: 1.3;
}
.required {
  color: #f66;
  margin-left: 2px;
}
.field-control {
  flex: 1;
  min-width: 0;
}
</style>
