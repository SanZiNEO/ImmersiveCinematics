<script setup lang="ts">
import { ref, computed } from 'vue'
import { send } from '../store'

const props = defineProps<{
  yaw: number
  pitch: number
  roll: number
  fov: number
  zoom: number
}>()

const emit = defineEmits<{
  (e: 'update', params: { yaw: number; pitch: number; roll: number; fov: number; zoom: number }): void
}>()

const dragging = ref(false)
const lastX = ref(0)
const lastY = ref(0)
const fineMode = ref(false) // Shift 精细模式

const gizmoSize = 120

function onMouseDown(e: MouseEvent) {
  dragging.value = true
  lastX.value = e.clientX
  lastY.value = e.clientY
  fineMode.value = e.shiftKey
  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseup', onMouseUp)
  e.preventDefault()
}

function onMouseMove(e: MouseEvent) {
  if (!dragging.value) return
  const dx = e.clientX - lastX.value
  const dy = e.clientY - lastY.value
  lastX.value = e.clientX
  lastY.value = e.clientY
  const sensitivity = fineMode.value ? 0.1 : 0.5
  const newYaw = props.yaw + dx * sensitivity
  const newPitch = Math.max(-90, Math.min(90, props.pitch - dy * sensitivity))
  emit('update', { yaw: newYaw, pitch: newPitch, roll: props.roll, fov: props.fov, zoom: props.zoom })
  sendCamera(newYaw, newPitch)
}

function onMouseUp() {
  dragging.value = false
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
}

function onDoubleClick() {
  emit('update', { yaw: 0, pitch: 0, roll: 0, fov: 70, zoom: 1 })
  sendCamera(0, 0)
}

function sendCamera(yaw: number, pitch: number) {
  send('editor.setCamera', { yaw, pitch, roll: props.roll, fov: props.fov, zoom: props.zoom })
}

function onSliderChange(key: 'yaw' | 'pitch' | 'roll' | 'fov' | 'zoom', value: number) {
  const params = { yaw: props.yaw, pitch: props.pitch, roll: props.roll, fov: props.fov, zoom: props.zoom }
  params[key] = value
  emit('update', params)
  send('editor.setCamera', params)
}

// 轨迹球上的指示器位置
const indicatorX = computed(() => {
  const angle = (props.yaw % 360) * Math.PI / 180
  return 50 + 35 * Math.sin(angle)
})
const indicatorY = computed(() => {
  const pitchAngle = props.pitch * Math.PI / 180
  return 50 - 35 * Math.sin(pitchAngle)
})
</script>

<template>
  <div class="orbit-gizmo">
    <div class="gizmo-header">相机控制</div>
    <div class="gizmo-body">
      <!-- 轨迹球 -->
      <div
        class="gizmo-ball"
        :style="{ width: gizmoSize + 'px', height: gizmoSize + 'px' }"
        @mousedown="onMouseDown"
        @dblclick="onDoubleClick"
      >
        <div class="gizmo-ring ring-1"></div>
        <div class="gizmo-ring ring-2"></div>
        <div class="gizmo-ring ring-3"></div>
        <div
          class="gizmo-indicator"
          :style="{ left: indicatorX + '%', top: indicatorY + '%' }"
        ></div>
        <div class="gizmo-center"></div>
      </div>
      <!-- 参数滑块 -->
      <div class="gizmo-sliders">
        <div class="slider-row">
          <label>Yaw</label>
          <input type="range" min="-180" max="180" step="0.5" :value="yaw"
            @input="onSliderChange('yaw', parseFloat(($event.target as HTMLInputElement).value))" />
          <span class="val">{{ yaw.toFixed(1) }}</span>
        </div>
        <div class="slider-row">
          <label>Pitch</label>
          <input type="range" min="-90" max="90" step="0.5" :value="pitch"
            @input="onSliderChange('pitch', parseFloat(($event.target as HTMLInputElement).value))" />
          <span class="val">{{ pitch.toFixed(1) }}</span>
        </div>
        <div class="slider-row">
          <label>Roll</label>
          <input type="range" min="-180" max="180" step="0.5" :value="roll"
            @input="onSliderChange('roll', parseFloat(($event.target as HTMLInputElement).value))" />
          <span class="val">{{ roll.toFixed(1) }}</span>
        </div>
        <div class="slider-row">
          <label>FOV</label>
          <input type="range" min="30" max="110" step="0.5" :value="fov"
            @input="onSliderChange('fov', parseFloat(($event.target as HTMLInputElement).value))" />
          <span class="val">{{ fov.toFixed(1) }}</span>
        </div>
        <div class="slider-row">
          <label>Zoom</label>
          <input type="range" min="0.1" max="5" step="0.05" :value="zoom"
            @input="onSliderChange('zoom', parseFloat(($event.target as HTMLInputElement).value))" />
          <span class="val">{{ zoom.toFixed(2) }}</span>
        </div>
      </div>
    </div>
    <div class="gizmo-hint">拖拽旋转 · Shift 精细 · 双击重置</div>
  </div>
</template>

<style scoped>
.orbit-gizmo {
  background: #18181c;
  border: 1px solid #33333a;
  border-radius: 8px;
  padding: 10px;
  margin: 8px;
}
.gizmo-header {
  font-size: 12px;
  font-weight: 600;
  color: #8a8a96;
  margin-bottom: 8px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.gizmo-body {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.gizmo-ball {
  position: relative;
  border-radius: 50%;
  background: radial-gradient(circle at 30% 30%, #2a2f3b, #1a1a1e);
  border: 1px solid #3a3a44;
  cursor: grab;
  flex-shrink: 0;
  overflow: hidden;
}
.gizmo-ball:active {
  cursor: grabbing;
}
.gizmo-ring {
  position: absolute;
  border: 1px solid rgba(78, 123, 211, 0.3);
  border-radius: 50%;
}
.ring-1 {
  inset: 15%;
}
.ring-2 {
  inset: 30%;
  border-color: rgba(78, 123, 211, 0.2);
}
.ring-3 {
  inset: 0;
  border: none;
  background: linear-gradient(90deg, transparent 49.5%, rgba(78, 123, 211, 0.2) 49.5%, rgba(78, 123, 211, 0.2) 50.5%, transparent 50.5%),
              linear-gradient(0deg, transparent 49.5%, rgba(78, 123, 211, 0.2) 49.5%, rgba(78, 123, 211, 0.2) 50.5%, transparent 50.5%);
}
.gizmo-indicator {
  position: absolute;
  width: 10px;
  height: 10px;
  background: #4e7bd3;
  border-radius: 50%;
  transform: translate(-50%, -50%);
  box-shadow: 0 0 8px rgba(78, 123, 211, 0.6);
  pointer-events: none;
}
.gizmo-center {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 4px;
  height: 4px;
  background: #666;
  border-radius: 50%;
  transform: translate(-50%, -50%);
}
.gizmo-sliders {
  flex: 1;
  min-width: 0;
}
.slider-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}
.slider-row label {
  width: 36px;
  font-size: 11px;
  color: #8a8a96;
  flex-shrink: 0;
}
.slider-row input[type="range"] {
  flex: 1;
  height: 3px;
  -webkit-appearance: none;
  background: #333;
  border-radius: 2px;
  outline: none;
  min-width: 0;
}
.slider-row input[type="range"]::-webkit-slider-thumb {
  -webkit-appearance: none;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #4e7bd3;
  cursor: pointer;
}
.slider-row .val {
  width: 42px;
  font-size: 11px;
  color: #d8d8e0;
  text-align: right;
  font-family: monospace;
  flex-shrink: 0;
}
.gizmo-hint {
  font-size: 10px;
  color: #555;
  margin-top: 6px;
  text-align: center;
}
</style>
