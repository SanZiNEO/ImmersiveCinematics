<script setup lang="ts">
import { computed } from 'vue'
import type { SchemaField } from '../../types'
import StringField from './StringField.vue'
import NumberField from './NumberField.vue'
import BoolField from './BoolField.vue'
import TristateField from './TristateField.vue'
import EnumField from './EnumField.vue'
import PositionField from './PositionField.vue'
import BezierCurveField from './BezierCurveField.vue'
import MapField from './MapField.vue'

const props = defineProps<{
  field: SchemaField
  modelValue: unknown
  /** 所属对象，用于 position 字段读取 position_mode 等联动字段 */
  parent?: Record<string, unknown>
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: unknown): void
}>()

const component = computed(() => {
  switch (props.field.type) {
    case 'string': return StringField
    case 'int':
    case 'float': return NumberField
    case 'bool': return BoolField
    case 'tristate': return TristateField
    case 'enum': return EnumField
    case 'position': return PositionField
    case 'bezier_curve': return BezierCurveField
    case 'map':
    case 'object': return MapField
    default: return StringField
  }
})

function onUpdate(value: unknown) {
  emit('update:modelValue', value)
}
</script>

<template>
  <component
    :is="component"
    :field="field"
    :model-value="modelValue"
    :parent="parent"
    @update:model-value="onUpdate"
  />
</template>
