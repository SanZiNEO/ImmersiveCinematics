# 05 on_enter（进入时触发）触发器每局只能触发一次

- **来源**：0.3.5 使用反馈（2026-09-28，用户实测）+ 代码排查
- **状态**：✅ 已修复（0.3.6，2026-09-29）
- **相关**：`TriggerEngine.onServerTick` / `onGameEvent` / `checkEnterState`、`ScriptManager.reload`、`Evaluators.expandConditions`

## 修复（0.3.6，2026-09-29）

- **做法**（对应下方“修改方向 A”）：状态机不再嵌在“玩家在区域内”的门控里——轮询路径改为**每次轮询都调用 `checkEnterState`**，并把“是否在原始区域内”的判定结果作为参数传入（避免重复求值）；`checkEnterState` 据此更新状态：进入 → 触发，离开（含 `exit_buffer` 外扩区域）→ 复位，再进入 → 再触发。
- **边界行为保持**：`shouldSkip` 仍在状态机之前——脚本播放期间跳过该触发器、状态机不更新，所以“跳过镜头时人还在区域内不会立刻重播”。
- **事件驱动路径**：仍只在事件发生时才走状态机（没有每 tick 的机会看到玩家离开），但 `on_enter` 只对位置类触发器有效、位置类走轮询，实际用法已覆盖。
- **验证**：✅ 用户进游戏实测（2026-09-29）：离开区域后再进入可再次触发。

## 现象（用户视角）

- 带"进入时触发"（`on_enter`）的 location 触发器，**每次进游戏只能触发一次**；
- 之后离开区域、再进入都不触发；**只有 `/icinematics reload` 或重启游戏后才能再触发一次**。
- 影响范围：paoku1、paoku2、loop_stairs_1 三个脚本全部如此（它们都用了 `on_enter: true` + `exit_buffer`）。

## 复现

1. 任意 location 触发器加 `"on_enter": true`（可带 `exit_buffer`），`repeatable` 随意；
2. 进区域 → 触发（第一次正常）；
3. 离开区域 → 再进入 → **不触发**；
4. `/icinematics reload` → 再进入 → 又能触发一次；如此循环。

## 根因（代码）

- `checkEnterState` 是"进入触发 + 离开复位"的状态机，但它**只在"玩家在区域内"时被调用**：
  两个调用点都嵌在 `if (evaluateSafely(reg, player))` 里（轮询路径 `TriggerEngine.onServerTick`；事件路径 `onGameEvent`）。
- 于是 `checkEnterState` 内部 `inOriginal` 恒为真，"离开复位"分支（`if (!inExpanded) playerStates.put(key, false)`）**不可达**；
- 第一次触发后 `enterStates` 永久停在 `true` → 之后每次进入都判定"不是新进入" → 不触发；
- 该状态表唯一复位点 = `TriggerEngine.clear()`（`enterStates.clear()`），只在启动和 `/icinematics reload` 时调用 → 与用户观察完全一致。
- 附带：`exit_buffer`（离开缓冲）属于同一机制，因此同样从未生效。

## 影响

- 文档承诺的"进入触发、离开缓冲后复位、可重复"（`docs/SCRIPT_FORMAT.md` / `TRIGGER_TYPES.md`）**实际不成立**；
- 所有依赖 `on_enter` 的脚本（区域循环触发类）每局只能生效一次。

## 修改方向

### A. 代码修（✅ 已实施，见上方“修复”；改动小）

- 让状态机**每次轮询都执行**（把"是否在区域内"的判定结果传入 `checkEnterState`），使复位分支可达：
  进入 → 触发；离开（含缓冲）→ 复位；再进入 → 再触发；`repeatable`/`delay` 等不受影响。
- 边界行为（保持现状即可）：脚本播放期间 `shouldSkip` 会跳过该触发器、状态机不更新——效果是"跳过镜头时人还在区域内，不会立刻重播"，符合跑酷类需求。

### B. 脚本层绕过（不改代码）

- 去掉 `on_enter` + `exit_buffer`，只留 `repeatable: true` → 语义变为"在区域内（且脚本未播放）就触发"：
  - 传送陷阱类（loop_stairs_1）：完全够用（人被传走即离开区域）；
  - 无限循环镜头类（paoku1/paoku2）：**不适合**——跳过镜头后若人仍在区域内，会在下一个轮询（0.1 秒）立刻重新触发、镜头重播，需走出区域才停。

## 附

- 事件驱动路径（`onGameEvent`）有同样的门控；但 `on_enter` 文档标注为"仅位置类触发器有效"，修轮询路径即可覆盖实际用法。
