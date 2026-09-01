<script setup lang="ts">
import { computed } from 'vue'
import type { SchemaField } from '../types'
import { groupBySection, getSectionLabel } from '../schema'
import FieldRenderer from './fields/FieldRenderer.vue'

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
  // 常用字段的中文标签
  const labels: Record<string, string> = {
    id: '脚本 ID',
    name: '名称',
    author: '作者',
    version: '格式版本',
    description: '描述',
    dimension: '维度限制',
    priority: '播放优先级',
    skip_vote_ratio: '跳过投票比例',
    block_keyboard: '屏蔽键盘',
    block_mouse: '屏蔽鼠标',
    block_mob_ai: '清除怪物 AI',
    hide_hud: '隐藏 HUD',
    hide_arm: '隐藏手臂',
    suppress_bob: '抑制视角晃动',
    suppress_distortion: '抑制画面扭曲',
    hide_chat: '隐藏聊天',
    hide_scoreboard: '隐藏记分板',
    hide_action_bar: '隐藏动作栏',
    hide_title: '隐藏标题',
    hide_subtitles: '隐藏字幕',
    hide_hotbar: '隐藏快捷栏',
    hide_crosshair: '隐藏准星',
    hide_bossbar: '隐藏 Boss 栏',
    hide_skip_hud: '隐藏跳过提示',
    render_player_model: '渲染玩家模型',
    pause_when_game_paused: '游戏暂停时暂停',
    interruptible: '可被打断',
    skippable: '可跳过',
    hold_at_end: '结束后停留',
    camera_mob_spawn: '相机区域刷怪',
    camera_mob_radius: '刷怪半径',
    camera_mob_ai: '相机区实体 AI',
    transition: '转场方式',
    transition_duration: '转场时长',
    interpolation: '插值方式',
    curve: '贝塞尔曲线',
    orient: '朝向模式',
    yaw_offset: '水平偏移',
    pitch_offset: '垂直偏移',
    loop: '循环播放',
    loop_count: '循环次数',
    loop_mode: '循环模式',
    cam_breath_enabled: '呼吸扰动',
    cam_breath_type: '扰动类型',
    cam_breath_intensity: '扰动强度',
    cam_breath_seed: '扰动种子',
    cam_breath_speed: '扰动速度',
    cam_breath_trauma: '冲击强度',
    cam_breath_decay: '衰减速率',
    position: '位置',
    position_mode: '坐标模式',
    follow: '位置跟随',
    follow_selector: '跟随目标',
    look_at: '注视模式',
    look_at_selector: '注视目标',
    look_at_target_x: '注视 X',
    look_at_target_y: '注视 Y',
    look_at_target_z: '注视 Z',
    look_at_target_structure: '注视结构',
    yaw_base: '偏航基准',
    pitch_base: '俯仰基准',
    yaw_base_selector: '基准实体',
    yaw_base_from: '连线起点',
    yaw_base_to: '连线终点',
    yaw: '偏航角',
    pitch: '俯仰角',
    roll: '翻滚角',
    fov: '视场角',
    zoom: '缩放',
    aspect_ratio: '宽高比',
    sound: '声音',
    source: '音频来源',
    category: '音频类别',
    volume: '音量',
    fade_in: '淡入时长',
    fade_out: '淡出时长',
    attenuation: '空间衰减',
    event_type: '事件类型',
    command: '命令',
    layer_type: '覆盖层类型',
    color: '颜色',
    path: '文件路径',
    text: '文本',
    z_index: '层级',
    opacity: '透明度',
    x: 'X 位置',
    y: 'Y 位置',
    z: 'Z 位置',
    font_scale: '字号',
    scale_x: '横向缩放',
    scale_y: '纵向缩放',
    time: '时间',
    start_time: '起始时间',
    duration: '持续时间',
  }
  return labels[key] ?? key
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
