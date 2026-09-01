<script setup lang="ts">
import { ref, watch } from 'vue'
import { state } from '../store'

const selectedIndex = ref(-1)

function getTriggers(): any[] {
  if (!state.doc?.meta) return []
  if (!state.doc.meta.triggers) state.doc.meta.triggers = []
  return state.doc.meta.triggers
}

function addTrigger() {
  const triggers = getTriggers()
  const trigger = {
    id: 'trigger_' + (triggers.length + 1),
    type: 'entity_kill',
    repeatable: true,
    delay: 0,
    conditions: {},
  }
  triggers.push(trigger)
  selectedIndex.value = triggers.length - 1
}

function removeTrigger(index: number) {
  getTriggers().splice(index, 1)
  selectedIndex.value = -1
}

function select(index: number) {
  selectedIndex.value = index
}

function updateField(key: string, value: any) {
  const triggers = getTriggers()
  if (selectedIndex.value >= 0 && triggers[selectedIndex.value]) {
    triggers[selectedIndex.value][key] = value
  }
}

function inputValue(e: Event) {
  return (e.target as HTMLInputElement).value
}

function checkedValue(e: Event) {
  return (e.target as HTMLInputElement).checked
}

function numberValue(e: Event) {
  return parseFloat((e.target as HTMLInputElement).value)
}

function textValue(e: Event) {
  return (e.target as HTMLTextAreaElement).value
}

watch(() => state.doc?.meta?.triggers, () => {
  if (selectedIndex.value >= getTriggers().length) selectedIndex.value = -1
})
</script>

<template>
  <div class="trigger-panel">
    <h3>触发器 <button @click="addTrigger">+</button></h3>
    <div v-for="(tr, i) in getTriggers()" :key="i"
         :class="{ active: i === selectedIndex }"
         class="trigger-item"
         @click="select(i)">
      <span>{{ tr.type || '?' }}</span>
      <button class="del" @click.stop="removeTrigger(i)">×</button>
    </div>
    <template v-if="selectedIndex >= 0 && getTriggers()[selectedIndex]">
      <div class="trigger-form">
        <label>id</label>
        <input :value="getTriggers()[selectedIndex].id" @input="updateField('id', inputValue($event))" />
        <label>type</label>
        <input :value="getTriggers()[selectedIndex].type" @input="updateField('type', inputValue($event))" />
        <label>repeatable</label>
        <input type="checkbox" :checked="!!getTriggers()[selectedIndex].repeatable" @change="updateField('repeatable', checkedValue($event))" />
        <label>delay</label>
        <input type="number" :value="getTriggers()[selectedIndex].delay ?? 0" @input="updateField('delay', numberValue($event))" />
        <label>exit_buffer</label>
        <input type="number" :value="getTriggers()[selectedIndex].exit_buffer ?? 0" @input="updateField('exit_buffer', numberValue($event))" />
        <label>conditions (JSON)</label>
        <textarea :value="JSON.stringify(getTriggers()[selectedIndex].conditions || {}, null, 2)"
                  @change="updateField('conditions', JSON.parse(textValue($event) || '{}'))"></textarea>
      </div>
    </template>
    <p v-if="!getTriggers().length" class="empty">暂无触发器</p>
  </div>
</template>

<style scoped>
.trigger-panel { padding: 10px; }
.trigger-panel h3 { margin: 0 0 8px; }
.trigger-panel button { margin-left: 6px; }
.trigger-item {
  display: flex;
  justify-content: space-between;
  padding: 4px 6px;
  cursor: pointer;
  border-radius: 4px;
}
.trigger-item:hover { background: #252a35; }
.trigger-item.active { background: #2f3b55; }
.del { border: none; background: transparent; color: #f66; cursor: pointer; }
.trigger-form label { display: block; margin-top: 6px; color: #888; font-size: 12px; }
.trigger-form input, .trigger-form textarea {
  width: 100%;
  box-sizing: border-box;
  background: #111;
  color: #ddd;
  border: 1px solid #333;
  padding: 3px;
}
.trigger-form textarea { height: 100px; resize: vertical; }
.empty { color: #888; }
</style>
