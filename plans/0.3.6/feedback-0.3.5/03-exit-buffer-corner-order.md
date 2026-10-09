# 03 exit_buffer 的方体外扩对角落点顺序敏感（写反会变成"缩小"）

- **来源**：代码排查（写 paoku1/paoku2 区域触发器时发现）
- **状态**：✅ 已实现（2026-10-09 回写：`Evaluators.expandAxis` 已做逐轴 min/max 归一化；脚本侧"最小角在前"写法已非必需）
- **相关**：`Evaluators.expandConditions` / `expandAxis`（退出缓冲外扩）、`Evaluators.inBox`

## 现象（代码层）

- `location` 触发器的 `exit_buffer`（配合 `on_enter`）用"**外扩** buffer 的方体"判断玩家是否真的离开（离开外扩区域才重新武装）。
- 基础判定 `inBox` 对 corner1/corner2 **做 min/max 归一化**（顺序无关）；
- 但 `expandConditions` **当时不做归一化**（2026-10-09 已修复，见文末「修改（已落地）」）：直接对 `corner1` 各轴 −buffer、对 `corner2` 各轴 +buffer——**假设 corner1 是分量最小角、corner2 是最大角**。
- 若角落点写反（例如按"从 A 到 B"的自然顺序写，A 在某轴上比 B 大），该轴的外扩变成**缩小**。

## 后果

- 该轴上的缓冲失效：玩家越过原区域边界就**立刻重新武装**（而不是等离开外扩区域），沿该轴在边界来回走 = 每跨一次边界触发一次（缓冲本要消除的抖动又回来了）。
- 例：`corner1 = (-10,-60,-10)`、`corner2 = (10,-50,-21)`、buffer=2 → z 轴外扩后为 `[-19,-12]`，反而比原来的 `[-21,-10]` 窄 → z 轴缓冲失效。

## 规避（脚本侧，已非必需）

- 写 `corner1` = **三个轴都最小**的角，`corner2` = **三个轴都最大**的角。
  （基础判定不受顺序影响，这样写只为让 `exit_buffer` 正确外扩；外扩归一化后此写法已非必需。）
- paoku1 / paoku2 的触发器已按此写法落盘。

## 修改（已落地 · 2026-10-09 回写）

**代码证据**：`common/src/main/java/com/immersivecinematics/immersive_cinematics/trigger/server/evaluator/Evaluators.java` —— `expandConditions`（:36-58）逐轴调用 `expandAxis`（:54-56）；`expandAxis`（:64-77）先取 `Math.min(a, b) − buffer` / `Math.max(a, b) + buffer`（:70-71）再写回两角，与基础判定 `inBox`（:189-202）的 min/max 顺序无关语义一致。

- ✅ `expandConditions` 已先做 min/max 归一化再外扩（逐轴分别处理 min/max），buffer 与角落点顺序无关；
- 编辑器侧不用改（原样存用户输入），已不需要提示。
