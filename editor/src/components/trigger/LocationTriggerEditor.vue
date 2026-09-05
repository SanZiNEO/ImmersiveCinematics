<script setup lang="ts">
import { computed } from 'vue'
import type { SchemaField } from '../../types'
import RegistryStringField from '../fields/RegistryStringField.vue'
import CommitNumberField from '../fields/CommitNumberField.vue'

const props = defineProps<{
  data: Record<string, unknown>
}>()

const emit = defineEmits<{
  (e: 'update', key: string, value: unknown): void
  (e: 'updateMany', patch: Record<string, unknown>): void
}>()

const fieldDef = computed<SchemaField>(() => ({
  type: 'string', default: null, required: true, enumValues: [], section: 'conditions',
}))

const isBox = computed(() => !!props.data.corner1)

function ensurePos(v: unknown): Record<string, number> {
  const o = (v && typeof v === 'object') ? v as Record<string, unknown> : {}
  return { x: Number(o.x ?? 0) || 0, y: Number(o.y ?? 0) || 0, z: Number(o.z ?? 0) || 0 }
}

function setPos(key: string, axis: string, value: number) {
  const pos = ensurePos(props.data[key])
  pos[axis] = value
  emit('update', key, pos)
}

function onSubmode(e: Event) {
  const mode = (e.target as HTMLSelectElement).value
  if (mode === 'box') {
    emit('updateMany', {
      corner1: ensurePos(props.data.corner1),
      corner2: ensurePos(props.data.corner2),
      position: undefined,
      radius: undefined,
    })
  } else {
    emit('updateMany', {
      position: ensurePos(props.data.position),
      radius: Number(props.data.radius ?? 0) || 0,
      corner1: undefined,
      corner2: undefined,
    })
  }
}
</script>

<template>
  <div class="location-editor">
    <div class="field-row">
      <label>维度</label>
      <RegistryStringField
        :field="fieldDef"
        field-key="dimension"
        :model-value="(data.dimension as string) ?? ''"
        @update:model-value="(v: any) => emit('update', 'dimension', v)"
      />
    </div>

    <div class="field-row">
      <label>区域模式</label>
      <select :value="isBox ? 'box' : 'point'" @change="onSubmode">
        <option value="point">点 + 半径</option>
        <option value="box">两点方体区域</option>
      </select>
    </div>

    <template v-if="!isBox">
      <div class="pos-block">
        <div class="pos-title">中心位置</div>
        <div class="pos-row" v-for="axis in ['x','y','z']" :key="axis">
          <span>{{ axis }}</span>
          <CommitNumberField
            :model-value="ensurePos(data.position)[axis]"
            :step="0.5"
            @update:model-value="setPos('position', axis, $event)"
          />
        </div>
      </div>
      <div class="field-row">
        <label>半径</label>
        <CommitNumberField
          :model-value="Number(data.radius ?? 0) || 0"
          :step="0.5"
          @update:model-value="(v: number) => emit('update', 'radius', v)"
        />
      </div>
    </template>

    <template v-else>
      <div class="pos-block">
        <div class="pos-title">角点 1</div>
        <div class="pos-row" v-for="axis in ['x','y','z']" :key="axis">
          <span>{{ axis }}</span>
          <CommitNumberField
            :model-value="ensurePos(data.corner1)[axis]"
            :step="0.5"
            @update:model-value="setPos('corner1', axis, $event)"
          />
        </div>
      </div>
      <div class="pos-block">
        <div class="pos-title">角点 2</div>
        <div class="pos-row" v-for="axis in ['x','y','z']" :key="axis">
          <span>{{ axis }}</span>
          <CommitNumberField
            :model-value="ensurePos(data.corner2)[axis]"
            :step="0.5"
            @update:model-value="setPos('corner2', axis, $event)"
          />
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.location-editor { padding: 2px 0; }
.field-row {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-bottom: 6px;
}
.field-row label {
  font-size: 11px;
  color: #8a8a96;
}
.field-row :deep(.commit-number) {
  width: 100%;
}
.pos-block {
  border: 1px solid #2a2a30;
  border-radius: 6px;
  padding: 6px;
  margin-bottom: 6px;
}
.pos-title {
  font-size: 11px;
  color: #6a8abf;
  margin-bottom: 4px;
}
.pos-row {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 3px;
}
.pos-row span {
  width: 18px;
  font-size: 11px;
  color: #888;
}
.pos-row :deep(.commit-number) {
  flex: 1;
}
</style>
