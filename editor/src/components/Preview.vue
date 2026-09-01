<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { state, onFrame, play, pause, stop, seek } from '../store'
import playIcon from '../assets/icons/play.svg'
import pauseIcon from '../assets/icons/pause.svg'
import prevIcon from '../assets/icons/prev.svg'
import nextIcon from '../assets/icons/next.svg'
import stopIcon from '../assets/icons/record.svg'

const canvasRef = ref<HTMLCanvasElement | null>(null)
const fpsRef = ref(0)
let frames = 0
let lastTime = performance.now()
let cleanup: (() => void) | null = null

function drawBinary(buf: ArrayBuffer) {
  const view = new DataView(buf)
  const type = view.getUint8(0)
  const w = view.getUint16(3)
  const h = view.getUint16(5)
  const canvas = canvasRef.value
  if (!canvas) return
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w
    canvas.height = h
  }
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  if (type === 1) {
    const raw = new Uint8Array(buf, 7)
    const img = ctx.createImageData(w, h)
    img.data.set(raw)
    ctx.putImageData(img, 0, 0)
  } else if (type === 2) {
    const blob = new Blob([buf.slice(7)], { type: 'image/jpeg' })
    createImageBitmap(blob).then(bmp => {
      ctx.drawImage(bmp, 0, 0)
      bmp.close()
    })
  }
  frames++
  const now = performance.now()
  if (now - lastTime >= 1000) {
    fpsRef.value = Math.round(frames * 1000 / (now - lastTime))
    frames = 0
    lastTime = now
  }
}

function onSeekInput(event: Event) {
  seek(parseFloat((event.target as HTMLInputElement).value))
}

onMounted(() => {
  cleanup = onFrame(drawBinary)
})

onUnmounted(() => {
  if (cleanup) cleanup()
})
</script>

<template>
  <div class="preview">
    <div class="bar">
      <span :class="state.connected ? 'ok' : 'bad'">{{ state.status }}</span>
      <span>{{ state.time.toFixed(3) }}s</span>
      <span>{{ fpsRef }} fps</span>
    </div>
    <div class="controls">
      <button class="icon-btn" @click="seek(Math.max(0, state.time - 1))" title="后退 1s">
        <img :src="prevIcon" alt="后退" />
      </button>
      <button class="icon-btn play-btn" @click="state.playing ? pause() : play()" :title="state.playing ? '暂停' : '播放'">
        <img :src="state.playing ? pauseIcon : playIcon" alt="播放" />
      </button>
      <button class="icon-btn" @click="stop" title="停止">
        <img :src="stopIcon" alt="停止" />
      </button>
      <button class="icon-btn" @click="seek(state.time + 1)" title="前进 1s">
        <img :src="nextIcon" alt="前进" />
      </button>
      <input
        type="range"
        class="seek-slider"
        min="0"
        :max="state.doc?.timeline?.total_duration || 100"
        step="0.05"
        :value="state.time"
        @input="onSeekInput"
      />
    </div>
    <div class="canvas-wrap">
      <canvas ref="canvasRef" />
      <div v-if="!state.connected" class="no-signal">
        <div class="no-signal-text">未连接游戏</div>
        <div class="no-signal-hint">在游戏内输入 /webui 启动服务端</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.preview {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #0a0a0c;
}
.bar {
  padding: 6px 12px;
  background: #101318;
  border-bottom: 1px solid #222;
  display: flex;
  gap: 16px;
  font-size: 12px;
  font-family: monospace;
}
.bar span { color: #8a8a96; }
.ok { color: #34d399 !important; }
.bad { color: #ef4444 !important; }
.controls {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 12px;
  background: #101318;
  border-bottom: 1px solid #222;
}
.icon-btn {
  width: 32px;
  height: 32px;
  background: transparent;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.icon-btn:hover { background: #28282e; }
.icon-btn img {
  width: 18px;
  height: 18px;
  filter: brightness(0) invert(0.85);
}
.play-btn {
  width: 36px;
  height: 36px;
  background: #2f3b55;
}
.play-btn:hover { background: #3a4a6b; }
.play-btn img {
  width: 20px;
  height: 20px;
  filter: brightness(0) invert(1);
}
.seek-slider {
  flex: 1;
  height: 4px;
  -webkit-appearance: none;
  background: #333;
  border-radius: 2px;
  outline: none;
  margin-left: 8px;
}
.seek-slider::-webkit-slider-thumb {
  -webkit-appearance: none;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #4e7bd3;
  cursor: pointer;
}
.canvas-wrap {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}
canvas {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  image-rendering: pixelated;
}
.no-signal {
  position: absolute;
  text-align: center;
  color: #444;
}
.no-signal-text {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 8px;
}
.no-signal-hint {
  font-size: 12px;
  color: #555;
}
</style>
