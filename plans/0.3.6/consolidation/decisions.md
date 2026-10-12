# 0.3.6 决策与核实记录（2026-10-12）

> 只记录两类：① 用户拍板的结论（附依据）；② 代码核实的证据。不含推测。

---

## 一、拍板结论

| # | 项 | 结论 | 依据 |
|---|---|---|---|
| 1 | 触发器连续化（P3-impl）默认档 | **默认连续** | 用户：连续化升级模型（点状态 → 连续参照），服务脚本触发，也对后续判定系统有好处 |
| 2 | 画面层 pillarbox | **不存在此问题** | 代码核实（见 §二-1） |
| 3 | `meta.base_resolution` 上限 | **不设上限** | 用户：提供平台和工具，不给作者限制 |
| 4 | `/icinematics template` 命令 | **删除** | 用户：运行时只读只执行，模板展开归编辑器 |
| 5 | 判定规则语义 | **「如果满足 xx 条件，就执行 xx」** | 用户：条件→动作；等待点 = 其中一个条件类型，即「脚本内执行到 xx 时间」 |
| 6 | switch_variant / 分支 | **做，数据化进 JSON** | 用户：塞进 JSON，具体形态待讨论，可放后续版本 |
| 7 | interrupt 打断默认形态 | **硬切** | 用户 |
| 8 | 播放队列满策略 | **不设上限 + 按脚本控制的排队语义** | 用户：按 `playback-relations.md` 的 play_mode 走 |

## 二、代码核实（证据）

1. **pillarbox 不存在**（`client/lane/`）：`LaneCompositor.compose`（`LaneCompositor.java:149-163`）的 `dest` = 屏幕归一化矩形 × `main.width/height`（主 framebuffer = 实际窗口尺寸）、`source` = 画面归一化矩形（相对全幅 lane 纹理）；`ScriptLaneDriver.interpolateRect`（`ScriptLaneDriver.java:272-280`）缺省 `Rect.FULL`（0,0,1,1），全程 0~1 归一化、无固定分辨率基准。
2. **触发器判定全在服务端**：`Evaluators`（`trigger/server/evaluator/`）与 `TriggerEngine`（`trigger/server/`）均 import `ServerLevel`/`ServerPlayer` 求值；`trigger/client/` 下 7 个类均非求值器（缓存/接收/预加载）。
3. **玩家输入**：`control/InputRouter` 接收层（`KeyboardHandlerMixin`/`MouseHandlerMixin` 在 HEAD 捕获、永不 cancel）已在非破坏性地观察每个按键/鼠标事件，可作输入采集点。

## 三、架构口径（用户澄清）

- **底层「判定连续采样系统」**（服务端）：条件求值 + 连续采样/边沿 + 事实；从「点状」（离散采样点求值一次）升级为「连续」（采样点之间补扫掠/边沿）。
- **应用 1 — 判定规则（`clip.rules`）**：挂在 clip 级（与 keyframes 平级），**每个 clip 都吃**（相机 / 图片 / 任意轨道 clip）；语义 `if→do` / `until→do`。
- **应用 2 — 触发器**：脚本启动侧（外部事件 → 起播）。
- 两者是底层的**两个不同应用，不合并**，只是共用同一套条件求值 + 连续采样/边沿 + 事实（服务端）。

## 四、同步动作（本次已执行）

- `plans/0.3.6/re/` 目录（7 篇 2026-10-09 评估报告）已删除。
