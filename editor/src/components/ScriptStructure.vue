<script setup lang="ts">
import { computed } from 'vue'
import { state } from '../store'

const tracks = computed(() => {
  return state.doc?.timeline?.tracks || []
})

function selectClip(ti: number, ci: number) {
  state.selection.track = ti
  state.selection.clip = ci
  state.selection.keyframe = -1
}

function selectKeyframe(ti: number, ci: number, ki: number) {
  state.selection.track = ti
  state.selection.clip = ci
  state.selection.keyframe = ki
}
</script>

<template>
  <div class="structure">
    <h3>脚本结构</h3>
    <p v-if="!state.currentPath" class="empty">未打开脚本</p>
    <div v-else class="path">{{ state.currentPath }}</div>
    <div v-for="(track, ti) in tracks" :key="ti" class="node track-node">
      <div class="track-title">{{ track.type }}</div>
      <div v-for="(clip, ci) in (track.clips || [])" :key="ci"
           class="node clip-node"
           :class="{ active: state.selection.track === ti && state.selection.clip === ci }"
           @click="selectClip(ti, ci)">
        {{ clip.start_time || 0 }}s ~ {{ (clip.start_time || 0) + (clip.duration || 0) }}s
        <span class="kfs" v-for="(kf, ki) in (clip.keyframes || [])" :key="ki"
              :class="{ active: state.selection.keyframe === ki }"
              @click.stop="selectKeyframe(ti, ci, ki)">
          K{{ kf.time }}
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.structure { padding: 10px; }
.structure h3 { margin: 0 0 6px; }
.path { color: #aaa; font-size: 11px; margin-bottom: 8px; word-break: break-all; }
.empty { color: #888; }
.node { font-size: 12px; }
.track-title { font-weight: bold; padding: 4px 0; }
.clip-node {
  margin-left: 10px;
  padding: 3px 6px;
  border-radius: 3px;
  cursor: pointer;
  background: #20242d;
}
.clip-node.active { background: #2f3b55; }
.kfs {
  display: inline-block;
  margin-left: 4px;
  padding: 0 4px;
  background: #101318;
  border-radius: 3px;
  cursor: pointer;
}
.kfs.active { background: #4e7bd3; }
</style>
