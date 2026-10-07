# 0.3.6 画面转场与透明度控制：底层工具的调用层（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写"已确认"
> - 确定不了的只写方向和可能的问题
> - 字段名、参数清单、JSON、轨道形态等执行时再定
>
> 相关文档：
> - [画面合成](./camera-composition.md)（lane 合成参数：不透明度 / 叠化 / 预热）
> - [可变画面](./variable-frame.md)（覆盖层统一参数：不透明度 0~1）
> - [Overlay 颜色遮罩](./overlay-color-mask.md)（fade 遮罩层）
> - [画面颜色调整](./screen-color-adjust.md)（只动 RGB / HSL，不碰 alpha）
> - [过渡](./transition.md)（相机六参数 A→B 状态过渡）
> - [多相机渲染](./multi-camera-rendering.md)（叠化预热）

---

## 1. 定位（已确认）

**本文是应用层文档**：转场 = 底层工具的组合调用，本文不新造底层。

底层工具已在三篇定义：

| 底层工具 | 定义处 | 本文用它做什么 |
|---|---|---|
| 覆盖层统一参数「不透明度 0~1」（关键帧驱动） | `variable-frame.md` §3 | 任何图层（含画面 lane）的淡入淡出 |
| lane 合成参数「不透明度关键帧」+ 叠化 + 叠化预热 | `camera-composition.md` §1/§4 | 两个画面交叉淡化的底层 |
| **纯色覆盖层**（solid color overlay：颜色 + 不透明度关键帧——一个工具） | `overlay-color-mask.md`（FadeLayer） | 黑场 / 白场 = **OVERLAY 轨上已调好参数的 clip**（`layer_type=fade` + color/opacity 关键帧，归 overlay 系统、由轨道追踪），见 §3.3 |

**用户原则（已确认·2026-10-07）**：先定义工具，再考虑怎么调用、整合工具——先底层后应用。本文就是"调用层"；将来发现转场缺底层能力（如形状 wipe 的遮罩形状），先回 `overlay-color-mask.md` / `variable-frame.md` 定义工具，再回本文加调用方式。

**效果 = 可追踪的数据，不是死代码（已确认·2026-10-07）**：大部分效果不要写成一次性硬编码实现（系统追踪不了、编辑器看不见），要写成**系统能追踪的东西**——轨道 → clip → 关键帧参数。转场尤其如此：叠化 = 两个 clip 的 opacity 关键帧交叉（§3.2），黑场 / 白场 = 预配置 clip（§3.3）——都是关键帧数据。**参考项目实证**（`example/` 下拉的源码）：
- **ReplayMod**（`example/ReplayMod-stable`，`com.replaymod.pathing`）：相机路径 = `Timeline` → `Path`（位置路径 / 时间路径）→ `Keyframe`（时间 + 值 + 属性）→ `PathSegment`（关键帧间，插值器是段属性：LINEAR / CATMULL_ROM / CUBIC，经 `SetInterpolator` 数据变更切换）；编辑 = `AddKeyframe` / `RemoveKeyframe` / `UpdateKeyframeProperties` 数据操作（带撤销），GUI 时间轴直接拖关键帧——相机运动 100% 数据化，无硬编码动作。
- **Olive**（`example/editor/olive`）：效果 = 节点图，每个参数都是可关键帧的 NodeInput（`kInputFlagNotKeyframable` 显式排除例外）——与 script-model.md §3 已核实的模型一致。

---

## 2. 透明度（alpha）的总账

RGBA 的 A 通道**不属于调色**（[画面颜色调整](./screen-color-adjust.md)只动 RGB / HSL），alpha 属于**覆盖层合成**维度，现状分布：

| 对象 | alpha 现状（代码） | 归属文档 |
|---|---|---|
| 画面 lane（相机画面） | 合成参数 `opacity`（0~1，关键帧）——**尚未实现**（合成层未落地） | camera-composition |
| 图片层 / 字幕层 | `setOpacity`（关键帧驱动，已实现） | variable-frame / overlay-color-mask |
| fade 遮罩层（= 纯色覆盖层） | `setOpacity`（关键帧驱动，已实现）；`setColor`（任意色——白场就是 `#FFFFFF`） | overlay-color-mask / scene-transition |
| 黑边层（letterbox） | **无 alpha 字段**（纯黑 0xFF000000 写死）——如需淡入淡出要补 | variable-frame |
| pip 层 | 有 `opacity` 字段但**填充/边框未使用**（已知缺陷，见 camera-composition 核查小节） | camera-composition |

**统一方向（已确认）**：alpha 在覆盖层统一参数里是同一个「不透明度 0~1」关键帧通道（`variable-frame.md` §3 已定义）；本文只负责回答"怎么用 alpha 做转场"，不重复定义 alpha 本身。

---

## 3. 转场清单（应用层：每类 = 一套工具调用）

### 3.1 Cut（硬切）

- **调用**：上/下两个画面无 alpha 过渡直接切换——不透明度不参与；等价于叠化中过渡时长为 0 的特例。
- 相机状态是否同步硬切由[过渡](./transition.md)（cut / blend / 分参数）决定，两者正交：画面 cut + 相机 blend 可以同时发生。

### 3.2 Dissolve（叠化 / 交叉淡化）——两个相机 clip 的 opacity 关键帧（已确认·2026-10-07）

- **本质**：叠化是**对两个相机 clip 的状态（合成参数 opacity）做处理**——clip A 末尾 opacity 关键帧 1→0、clip B 开头 opacity 关键帧 0→1，两者在时间上交叉。
- **重叠区是我们自动补充的（已确认·2026-10-07）**：作者不写延长区内容。叠化 = 把 clip A 的**末尾帧复制延长**（hold：延长区是等值关键帧，画面冻结在最后一帧）+ 把 clip B 的**首帧复制延长**（同样 hold），两个冻结区在时间上交叉；**透明度更改只发生在两个延长区里**。"复制延长"= 插等值关键帧，仍是纯关键帧数据、可追踪，可由模板一键生成。不允许重叠的话，两 clip 之间出现空隙 → 没有 lane 覆盖 → **露出游戏原本画面**。
- **预热自然解决**：重叠窗口内 B 从自己 clip 的开头（opacity 0）就在渲染（hold 区渲染的就是被复制的首帧），无需独立预热调度（`camera-composition.md` §4）。
- **追踪方式只有一种**：调这两个 clip 的关键帧参数（编辑器里能看见、能拖、能打点）。**没有**独立转场轨 / 转场字段 / 转场代码——否则就是系统追踪不了的东西。
- **唯一机制性支持**：多相机渲染本身（重叠窗口内两个 lane 同时渲染，见 `multi-camera-rendering.md`）。这是**渲染层**的支持，不是转场机制。

### 3.3 黑场 / 白场（Fade to black / white）——OVERLAY 轨的预配置 clip（已确认·2026-10-07）

- **归属**：黑场 / 白场**算在 overlay 系统里**——它们是 OVERLAY 轨上的内容，由轨道追踪（时间轴上的 clip），不是转场机制自己实现的东西。
- **应用形态**：在衔接处插入一段**已调好参数的 clip**——`layer_type=fade`，黑场 = `color=#000000`、白场 = `color=#FFFFFF`，opacity 关键帧 0→1（压场）→ 切下一段 → 1→0（亮起）。参数已定，作者只需决定"放哪、多长"。
- **与模板的关系**：这类 clip 是[模板](./templates.md)片段级模板的天然素材（"黑场 1s" = 一个片段模板，一键插入）。
- **底层已具备**：纯色覆盖层（FadeLayer）+ opacity 关键帧（0.3.5）；黑场与白场之间没有任何实现差异（同一个工具换 `color`）。
- 与 `hold_at_end` / 末尾保持的组合（压场停在末尾等触发）属脚本编排，不属本文。

### 3.5 Wipe / 划像（后续候选）

- **调用方向**：用画面 lane 的**取材区域 / 目标区域**（source / dest rect）关键帧滑动实现"新画面推进、旧画面退出"。
- **前置缺口**：形状 wipe（圆 / 斜线 / 星形边缘）需要**遮罩形状**——`overlay-color-mask.md` 的局部遮罩（矩形 / 圆形，🟡 范围未收敛）落地后才能做。先定义遮罩工具，再回本文加调用方式（原则一致）。

---

## 4. 转场的数据落点（待定）

转场是"片段之间"的概念，候选形态（执行时定）：

- **候选 A：衔接字段**——挂在 clip 级（`transition_in` / `transition_out`：类型 + 时长 + 曲线）；
- **候选 B：独立转场轨**——`TRANSITION` 轨道类型，转场本身是一条时间轴内容（表达力强，依赖 TrackType 扩展）；
- **候选 C：手写两段关键帧**——不新增字段，作者直接用现有 fade/lane 关键帧拼（零成本起步，作为 A/B 落地前的兜底）。**黑场 / 白场已经是候选 C 的成立实例**：它们就是 OVERLAY 轨上的预配置 clip（§3.3），无需任何新字段。

倾向：**C 先行（零成本验证语义），A 作为默认形态，B 远期**。落地时定。

---

## 5. 与其他文档的边界（已确认）

| 文档 | 管什么 | 与本文的关系 |
|---|---|---|
| `transition.md` | **相机六参数**（position/yaw/pitch/roll/fov/zoom）的 A→B 状态过渡 | 正交：本文管"画面之间怎么换"，它管"相机状态怎么变"；叠化 + 相机 blend 可组合 |
| `camera-composition.md` | 叠化的**底层**（opacity 交叉、预热） | 本文只引用，不重复定义 |
| `overlay-color-mask.md` | **纯色覆盖层**的底层定义（FadeLayer：颜色 + 不透明度） | 黑场 / 白场 / 淡入淡出 / 颜色遮罩都是它的应用；形状 wipe 的遮罩先回它定义 |
| `script-loop.md` | 循环回卷瞬间的画面衔接 | 回卷 = 时间轴末端折回，衔接处可复用本文转场（repeat 硬切 or 可选叠化——待定，与 script-loop §11 衔接问题挂钩） |
| `wait-point-track.md` | 等待点后的继续 / 接播 | 接播瞬间用哪种转场，属脚本编排，引用本文 |

---

## 6. 可能的问题

- 转场归属形态（§4 A/B/C）与 TrackType 扩展的关系。
- 叠化期间主相机是否可动（沿用 `multi-camera-rendering.md` §9 的开放问题）。
- 转场与跳过 / 打断的交互：跳过途中转场 = 立即硬切还是加速完成。
- alpha 关键帧的插值曲线：现有 OverlayTrackPlayer 的 `interpolation`（linear / smooth）只作用于 overlay 层；lane 的 opacity 插值曲线复用还是另定。
- 黑场 / 白场与字幕 / letterbox 的叠放次序（fade z=10 之下、字幕 z=30 之上——转场遮罩是否要压住字幕？现状 fade 压不住，需要更高 z 或转场专用层——待定）。
- 转场是否影响 GUI 层（与 screen-color-adjust §5 的同一问题共用结论）。

---

## 7. 待定

- 数据落点形态（A / B / C）。
- 转场类型清单第一版范围（cut / dissolve / black / white 之外是否纳入 wipe）。
- 转场时长 / 曲线的默认值。
- 转场期间的时间线语义（转场时长算前一片段还是后一片段，还是独立区间——与 script-model 的"片段窗口"语义联动）。

---

## 8. 落地顺序（方向）

| # | 步骤 | 交付物 |
|---|---|---|
| 1 | 黑场 / 白场作为"转场"文档化 + 测试脚本（底层已有，先确认语义与 z 序） | 黑场/白场转场可用（手写 JSON） |
| 2 | 叠化（依赖 camera-composition 步骤 3 双 lane 上屏 + 步骤 4 opacity 关键帧） | 两镜头 dissolve 可用 |
| 3 | 转场数据落点定稿（§4） | 衔接字段或转场轨的字段表 |
| 4 | wipe（依赖 overlay-color-mask 局部遮罩 + 取材区域关键帧） | 划像转场可用 |
| 5 | 编辑器转场面板 + 转场预览 | WebUI 内拖拽转场并预览 |
