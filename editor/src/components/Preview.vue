<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { state, onFrame, play, pause, stop, seek } from '../store'

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
      <button class="icon-btn" @click="state.playing ? pause() : play()" :title="state.playing ? '暂停' : '播放'">
        <img :src="state.playing ? '../assets/icons/pause.svg' : '../assets/icons/play.svg'" alt="" />
      </button>
      <button class="icon-btn" @click="stop" title="停止">
        <img src="../assets/icons/record.svg" alt="" />
      </button>
      <button class="icon-btn" @click="seek(Math.max(0, state.time - 1))" title="后退 1s">
        <img src="../assets/icons/prev.svg" alt="" />
      </button>
      <button class="icon-btn" @click="seek(state.time + 1)" title="前进 1s">
        <img src="../assets/icons/next.svg" alt="" />
      </button>
      <input type="range" min="0" :max="state.doc?.timeline?.total_duration || 0" step="0.1"
             :value="state.time" @input="onSeekInput" />
    </div>
    <canvas ref="canvasRef" />
  </div>
</template>

<style scoped>
.preview {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.bar {
  padding: 6px 10px;
  background: #101318;
}
.bar span { margin-right: 12px; }
.ok { color: #4f4; }
.bad { color: #f66; }
.controls {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  background: #101318;
}
.icon-btn {
  width: 30px;
  height: 30px;
  background: transparent;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.icon-btn:hover { background: #28282e; }
.icon-btn img { width: 18px; height: 18px; }
canvas {
  flex: 1;
  width: 100%;
  height: calc(100% - 28px);
  object-fit: contain;
  background: #000;
  image-rendering: pixelated;
}
</style>
