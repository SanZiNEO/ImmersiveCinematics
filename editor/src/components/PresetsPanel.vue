<script setup lang="ts">
import { ref, reactive } from 'vue'
import { state, commit } from '../store'
import type { ScriptDoc } from '../types'
import { t } from '../i18n'

const selectedPreset = ref('orbit_circle')

const orbitParams = reactive({
  center_x: 0,
  center_y: 0,
  center_z: 0,
  radius: 8,
  height: 2,
  duration: 8,
})

function generateOrbitCircle(): ScriptDoc {
  const CTRL = 0.7698
  const { center_x: cx, center_y: cy, center_z: cz, radius, height, duration } = orbitParams
  const camY = cy + height
  const segDur = Math.max(0.1, duration / 3)

  const clips = []
  for (let i = 0; i < 3; i++) {
    const a0 = (i * 120) * Math.PI / 180
    const a1 = ((i + 1) * 120) * Math.PI / 180
    const sx = cx + radius * Math.cos(a0)
    const sz = cz + radius * Math.sin(a0)
    const ex = cx + radius * Math.cos(a1)
    const ez = cz + radius * Math.sin(a1)
    const p1x = sx + CTRL * radius * -Math.sin(a0)
    const p1z = sz + CTRL * radius * Math.cos(a0)
    const p2x = ex - CTRL * radius * -Math.sin(a1)
    const p2z = ez - CTRL * radius * Math.cos(a1)

    clips.push({
      start_time: i * segDur,
      duration: segDur,
      transition: 'cut',
      interpolation: 'linear',
      loop: false,
      curve: {
        type: 'bezier',
        control_points: [
          { x: p1x, y: camY, z: p1z },
          { x: p2x, y: camY, z: p2z },
        ],
      },
      keyframes: [
        { time: 0, position: { x: sx, y: camY, z: sz }, yaw: 0, pitch: 0, roll: 0, fov: 70 },
        { time: segDur, position: { x: ex, y: camY, z: ez }, yaw: 0, pitch: 0, roll: 0, fov: 70 },
      ],
    })
  }

  return {
    meta: {
      id: 'orbit_circle',
      name: '环绕轨道',
      version: 3,
      description: '围绕中心点做三段贝塞尔拼圆的环绕镜头',
    },
    timeline: {
      total_duration: duration,
      tracks: [
        {
          id: 'camera_1',
          type: 'CAMERA',
          clips,
        },
      ],
    },
    triggers: [],
  }
}

function applyPreset() {
  if (!state.doc) return
  const newDoc = generateOrbitCircle()
  commit(() => {
    state.doc!.meta = { ...state.doc!.meta, ...newDoc.meta }
    state.doc!.timeline = newDoc.timeline
    state.doc!.triggers = newDoc.triggers || []
  })
}
</script>

<template>
  <div class="presets-panel">
    <div class="panel-header">
      <span class="panel-title">{{ t('tab.presets') }}</span>
    </div>

    <div class="preset-list">
      <div
        class="preset-item"
        :class="{ active: selectedPreset === 'orbit_circle' }"
        @click="selectedPreset = 'orbit_circle'"
      >
        <div class="preset-icon">◎</div>
        <div class="preset-info">
          <div class="preset-name">{{ t('preset.orbit_circle') }}</div>
          <div class="preset-desc">围绕中心点做三段贝塞尔拼圆的环绕镜头</div>
        </div>
      </div>
    </div>

    <div v-if="selectedPreset === 'orbit_circle'" class="preset-params">
      <div class="param-row">
        <label>中心 X</label>
        <input type="number" v-model.number="orbitParams.center_x" step="0.5" />
      </div>
      <div class="param-row">
        <label>中心 Y</label>
        <input type="number" v-model.number="orbitParams.center_y" step="0.5" />
      </div>
      <div class="param-row">
        <label>中心 Z</label>
        <input type="number" v-model.number="orbitParams.center_z" step="0.5" />
      </div>
      <div class="param-row">
        <label>半径</label>
        <input type="number" v-model.number="orbitParams.radius" min="2" max="200" step="0.5" />
      </div>
      <div class="param-row">
        <label>高度</label>
        <input type="number" v-model.number="orbitParams.height" step="0.5" />
      </div>
      <div class="param-row">
        <label>时长 (秒)</label>
        <input type="number" v-model.number="orbitParams.duration" min="1" max="600" step="0.5" />
      </div>
      <button class="generate-btn" @click="applyPreset">{{ t('preset.generate') }}</button>
    </div>
  </div>
</template>

<style scoped>
.presets-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.panel-header {
  padding: 8px 10px;
  border-bottom: 1px solid #33333a;
  flex-shrink: 0;
}
.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #d8d8e0;
}
.preset-list {
  padding: 8px;
  flex-shrink: 0;
}
.preset-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  background: #22262f;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all .1s;
}
.preset-item:hover {
  background: #2a2f3b;
}
.preset-item.active {
  border-color: #4e7bd3;
  background: #2f3b55;
}
.preset-icon {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #1a1a1e;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  color: #4e7bd3;
  flex-shrink: 0;
}
.preset-info {
  flex: 1;
  min-width: 0;
}
.preset-name {
  font-size: 13px;
  color: #d8d8e0;
  margin-bottom: 2px;
}
.preset-desc {
  font-size: 11px;
  color: #8a8a96;
  line-height: 1.3;
}
.preset-params {
  flex: 1;
  overflow-y: auto;
  padding: 10px;
  min-height: 0;
}
.param-row {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
  gap: 8px;
}
.param-row label {
  width: 70px;
  font-size: 12px;
  color: #8a8a96;
  flex-shrink: 0;
}
.param-row input {
  flex: 1;
  background: #1a1a1e;
  border: 1px solid #33333a;
  border-radius: 4px;
  padding: 4px 8px;
  color: #d8d8e0;
  font-size: 12px;
  min-width: 0;
}
.param-row input:focus {
  outline: none;
  border-color: #4e7bd3;
}
.generate-btn {
  width: 100%;
  margin-top: 8px;
  padding: 8px;
  background: #4e7bd3;
  border: none;
  border-radius: 6px;
  color: white;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background .1s;
}
.generate-btn:hover {
  background: #5a8ae0;
}
</style>
