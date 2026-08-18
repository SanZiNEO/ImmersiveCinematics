# 触发器前置依赖（Trigger Prerequisites）

## 一句话定义

给触发器加"解锁条件"：**某个脚本触发/播放过之后，另一个触发器才允许触发**——脚本按剧情顺序逐级解锁，而不是全部触发器一上来就待命。

## 术语界定（为什么这么叫）

| 候选说法 | 为什么不用 |
|---|---|
| **触发器前置依赖**（本方案） | 准确描述：B 的激活前提是 A 已触发——是"触发器的解锁条件" |
| 执行顺序 | 那是播放队列/优先级的事（已有 ScriptQueue），与"能否触发"无关 |
| 多触发器 | 那是"同一脚本多个触发源"（如 login + 进区域都能触发），不是依赖 |
| 条件树 | 多条依赖链组合起来确实像树，但单条依赖本质是"前置条件"，树只是多条链的叠加 |

## 场景（用户例子）

```
脚本 A：无任何前置，进游戏即触发（login）
脚本 B：A 播放过之后（跳过/打断/播完都算），根据自身触发器触发
脚本 C：B 触发过之后才能触发
```

效果：脚本多了以后不会"一堆触发器同时待命"——按剧情线逐步解锁，降低误触发，也符合叙事推进。

## 现有基础设施（可直接复用）

`TriggerStateStore` 已经在记录并持久化（世界存档 SNBT）：

- `markTriggered(player, scriptId, triggerId)` —— **触发即标记**（fireTrigger 时调用，不管后来是跳过还是打断）
- `markScriptCompleted(player, scriptId)` —— 脚本完整播完后标记
- `isTriggered(player, scriptId, triggerId)` / `isScriptCompleted(player, scriptId)` —— 查询
- `shouldSkip` 已用 isTriggered 做 repeatable 去重

"播放过（跳过/打断也算）" ≈ **isTriggered**（触发即标记，天然满足"不管怎么结束都算"）。

## 设计草案

### 脚本格式（触发器级新字段）

```json
{
  "id": "trigger_b",
  "type": "login",
  "repeatable": false,
  "delay": 0.5,
  "requires": ["script_a"],
  "conditions": {}
}
```

- `requires`: 数组，前置脚本 id 列表（**全部满足 = AND**）
- 缺省 = 无前置（现状，兼容旧脚本）

### 求值流程（TriggerEngine.onGameEvent / 轮询 统一入口）

```
onGameEvent / onServerTick
  → 依赖检查：requires 中任一脚本未触发 → 跳过（不触发）
  → shouldSkip（repeatable 去重）
  → evaluate（触发器自身条件）
  → fireTrigger
```

依赖检查放最前面——依赖未解锁时连 shouldSkip 都不需要碰。

### 支持树状

一条 `requires` 链是线性的；树 = 多条链（A→B、A→C、B→D、C→D）用各脚本的 requires 组合表达，无需专门建树结构。

## 边界与待定

1. **"触发过"还是"播完过"**：建议用 triggered（触发即算，跳过/打断都解锁）；若需要"必须完整播完才解锁"可用 completed——两个字段都在，可按需选（或字段加模式：`"requires": [{"script": "script_a", "mode": "completed"}]`）
2. **AND vs OR**：先只做 AND（requires 全部满足）；OR（任一满足）等玩家反馈再考虑
3. **与 repeatable 的关系**：requires 只做解锁门槛，解锁后 repeatable 语义照旧（repeatable=true 可重复触发，false 触发一次）
4. **被依赖脚本被删/改名**：requires 指向不存在的脚本 → 该触发器永不触发（建议 validator 提示）
5. **持久化**：复用 TriggerStateStore（already done），跨会话解锁状态保留

## 待玩家反馈项

- 解锁粒度：触发过 vs 播完（当前倾向 triggered）
- 是否需要 OR 组合
- 是否需要"解锁但不自动触发"（A 播完 → B 自动接续，而非等 B 自己的触发器）——这是另一种机制（自动接续），可能未来单独讨论

## 执行前再看 / 具体方案

- **项目文件**：
  - `script/TriggerDefinition.java`（目前只有 type/conditions/repeatable/delay/on_enter/exit_buffer，需加 `requires`）
  - `script/ScriptParser.parseTriggerDefinition`（解析 `requires` 数组）
  - `trigger/server/TriggerEngine.java`（`onGameEvent` / `onServerTick` 最前面加依赖检查）
  - `trigger/server/store/TriggerStateStore.java`（复用 `isTriggered` / `isScriptCompleted`）
  - `script/ScriptValidator.java`（加 requires 指向不存在脚本的提示）
- **改法**：`TriggerDefinition`/`TriggerRegistration` 增加 `List<String> requires`；`TriggerEngine` 两个入口在 `shouldSkip` 前遍历 requires，任一 `!TriggerStateStore.isTriggered(...)` 则跳过；默认解锁语义 = triggered（触发即算）。
- **执行时再看**：`TriggerEngine.onGameEvent/onServerTick`、`TriggerStateStore`、`ScriptParser.parseTriggerDefinition`。
