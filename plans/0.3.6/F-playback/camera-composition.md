# 画面合成：取材区域 / 目标区域 / 不透明度

> 状态：实现中（合成参数、合成层、遮罩与多 lane 上屏已落地；编辑器多 lane 预览与 HUD / 黑边层级基线未定；维度源与合成解耦已实现，跨维度 lane 随 F8 实机验证，见 [跨维度相机](cross-dimension-camera.md)）
> 范围：画面 lane 的纹理怎么铺到屏幕——取材、放置、不透明度、遮罩与叠放顺序
> 关联：[并行播放](parallel-playback.md)、[多相机渲染](multi-camera-rendering.md)、[画面转场](../D-transition/scene-transition.md)、[Overlay 颜色遮罩](../D-transition/overlay-color-mask.md)

## 一、摘要

每条画面 lane 的输出是一张纹理，合成层负责把它们按顺序铺到屏幕：从画面里取哪一块、放到窗口哪一块、以多大不透明度叠上去、被什么形状遮住，以及谁盖在谁上面。几何与透明度参数全部走关键帧，因此整屏画面、画中画、分屏、叠化、局部取景、监视器墙都只是同一套参数的编排结果，没有各自的独立实现。

## 二、为什么做

### 2.1 问题

- 画面只能整幅全屏：缩到角落或同屏双机位没有参数可写。
- 没有叠放概念：多幅画面同时存在时无法表达谁在上、谁透明。
- 裁切只能改相机取景，等于换一个相机。
- 旧的画中画层只是半透明方块占位，不绑画面，不透明度也不生效。

### 2.2 目标

- 单条画面可缩到任意矩形、淡入淡出，不需要第二个相机。
- 两条画面可拼成分屏，也可叠成叠化。
- 同一相机画面可取局部铺满全屏。
- 参数随时间可插值，与相机通道共用同一时间语义。
- 叠放顺序可预期；上屏失败不覆盖已有画面。

### 2.3 非目标

- 不做独立合成轨或合成导演，参数由片段自声明。
- 不做逐 lane 层级字段。
- 不做运行时透明度配对或归一。
- 不裁切字幕与图片。
- 不做编辑器合成面板与多 lane 预览；不定义与 HUD、黑边的层级基线。

## 三、方案

每一段相机片段都可以决定自己那幅画面怎么上屏：从画面里裁出哪一块（取材区域）、放到窗口哪一块（目标区域）、叠上去有多透明。三个参数都是关键帧，能连续变化——把目标区域从全屏缩到四分之一就是推成画中画，把不透明度从零拉到一就是淡入。

画面还可以加几何遮罩：不遮（默认）、矩形或圆形。遮罩决定哪些像素参与合成，中心、尺寸、半径同样走关键帧，所以「从中间向两侧展开」这类划像不需要额外机制；遮罩不会裁切字幕或图片。

层级不需要作者写：先启动的脚本整体在下、后启动的整体在上；同一脚本内先出现的相机轨在下，同一轨道内后出现的片段在上。于是叠化就是「下层保持不透明、上层淡入」，分屏就是「两条片段各占半屏」。没有被画面覆盖的地方露原版玩家视角；想让过场全程不露原版画面，就让片段首尾相接或重叠。合成顺序进行：渲染一幅、贴屏、复用同一块缓冲，显存不随画面数量增长。

## 四、技术细节

### 4.1 核心模型

- **画面 lane 快照**：相机六参数 + 产出它的片段 + 片段内本地时间。合成参数不在快照里求值，渲染侧用同一份片段数据与同一时刻取值。维度是 lane 的源属性（计划），不进入合成参数；CAMERA clip 的 `dimension` 缺省 / 空串 = 玩家当前维度。
- **合成参数槽**：每条 lane 一个可复用槽位，持有不透明度、取材矩形、目标矩形与遮罩采样结果。
- **合成器**：把一条 lane 的纹理按参数铺到主 framebuffer，取材、放置、不透明度与遮罩在同一次 quad 绘制里完成。
- **遮罩采样器**：按片段级形状与关键帧几何逐帧算出覆盖率，只支持不遮、矩形、圆形。
- **守门判定**：上屏前的纯判定（是否写过、有无纹理、是否可见、矩形是否合法）。
- **lane 注册与驱动**：每帧按实例顺序平铺所有活跃实例的 lane，逐条注册、渲染、合成。

### 4.2 契约

- 参数属于片段：写在该相机片段关键帧上，随片段本地时间插值；缺省不透明、全屏、全幅。
- 合成必须当帧完成：所有 lane 共用一块离屏缓冲，贴屏后立即被下一条覆盖，不能攒批排序。
- 叠放顺序 = 注册顺序 = 绘制顺序：实例启动顺序 → 轨道顺序 → 轨道内片段顺序。
- 合成顺序末位（最上层）的 lane 盖在其余画面之上；这只是叠放结果，不是相机身份。链上有修改器或直控类层胜出时，该末位 lane 改取链输出，其余 lane 保持各自片段相机。
- 本帧没有脚本 lane 时，若链输出仍是本帧相机，则由链输出注册一条全屏不透明 lane；否则回落原版视角。
- 失败直通：任一条守门判定不过就跳过这次绘制；非全幅遮罩缺少专用着色器时跳过并报错，不退回无遮罩输出。
- 状态纪律：入口保存、出口还原绑定、视口、投影、模型视图、着色器与纹理、深度与混合；只写颜色，不写 framebuffer alpha。

### 4.3 数据形态

- 片段级：`mask_shape`（`full` / `rect` / `circle`），缺省 `full`。
- 关键帧级：`opacity`（0~1，缺省 1）、`dest` 与 `source`（`{x, y, w, h}`，缺省全屏 / 全幅）、`mask_x` / `mask_y` / `mask_width` / `mask_height` / `mask_radius`（窗口比例，缺省居中、满幅 / 半短边）。
- 矩形语义：`dest` 相对窗口，`source` 相对该 lane 画面；0~1 归一化、原点左上角，互不换算；缩放由两者尺寸之比隐含。
- 遮罩几何独立于取材与放置，始终按窗口物理像素判定。
- 混合 alpha = 不透明度乘覆盖率，不读 lane 纹理 alpha。

### 4.4 边界

- lane 怎么渲染、光影与模组兼容归多相机渲染；lane 从哪来与实例叠放归并行播放。
- 字幕、图片、纯色层与黑边走覆盖层，按自己的层级排序；合成层不参与，遮罩也不裁切它们。
- 合成层不读维度：lane 纹理来自哪个维度由 [多相机渲染](multi-camera-rendering.md) / [跨维度相机](cross-dimension-camera.md) 决定；全屏与分屏只是 `dest` 不同。

### 4.5 维度源（已实现）

合成层只认“一张 lane 纹理 + 合成参数”，不认维度。跨维度时：

- lane 的纹理由该 lane 的 `DimensionView` 渲染；
- `source` / `dest` / `opacity` / 遮罩语义与同维度完全一致；
- 全屏 = `dest` 全屏，分屏 = 两条 lane 的 `dest` 各占一半；
- CAMERA clip 的 `dimension` 缺省 / 空串 = 玩家当前维度，合成层不需要额外分支。

状态：合成侧无需改动；维度源已实现（`c0b1ac1`），并随 F8 实机验证；合成层不负责维度数据流。见 [跨维度相机](cross-dimension-camera.md) §4.2 / §6。

## 五、为什么不选别的

| 备选方案 | 做法 | 代价 | 为什么没选 |
|---|---|---|---|
| 独立合成轨 / 合成导演 | 一条导演轨统一引用并排序所有 lane | 需要跨脚本 lane 寻址 | 先做片段自声明 |
| 每条 lane 自带层级字段 | 作者显式写层级数字 | 直观 | 同一份数据在不同实例数下要重排 |
| 每条 lane 一张离屏缓冲 | 各 lane 独立缓冲，最后排序合成 | 显存随 lane 数增长 | 排序收益不抵成本 |
| 上屏时做透明度配对 / 归一 | 两层 alpha 互补求和为一 | 看似能保证不露底 | 两层同时半透明会漏出第三层 |
| 改相机视锥代替画面裁切 | 用相机参数实现局部画面 | 不需要合成层 | 等价于换相机，做不到同画面取局部 |

**本方案的缺点**：

- 层级由顺序决定：不能把某条 lane 单独提到另一个实例之上。
- 取材只能取矩形；斜线、星形要靠矩形与圆形遮罩拼。
- 遮罩是硬边，没有羽化。
- 目标矩形之外没有兜底，需作者用时间重叠避免。
- 合成与 HUD、黑边的层级基线未定；末位 lane 取链输出与覆盖链耦合。

## 六、实现结果

状态：实现中

合成参数、合成层、遮罩与多 lane 上屏已落地；编辑器多 lane 预览、兜底与层级基线未定。

| 设计目标 | 实现 | 说明 |
|---|---|---|
| 叠放顺序含 z 序 | 实例 → 轨道 → 片段顺序；相机片段没有 z 序字段 | 顺序即层级 |
| 只有不透明度 / 目标 / 取材 | 另加遮罩形状与关键帧几何 | 划像用同一套关键帧 |
| 用原版画面着色器 | 专用合成着色器：只读 RGB，alpha = 不透明度乘覆盖率 | 避免 lane alpha 透底 |
| 单条全屏 lane 走旧路径 | 未做，一律逐 lane 渲染再合成 | 优化收益有限 |
| 目标矩形兜底 / 末位 lane 判定（覆盖链替换位） | 兜底与设计一致；覆盖链替换位 = 合成顺序末位 lane | 该替换位原列为待定，现行按叠放顺序取末位，尚未正式拍板；它不是「主相机」 |
| 编辑器面板与多 lane 预览 | 未实现 | 归 Z 轮 |
| 画中画层（填充 + 白边）升级或保留 | 已删除；画中画 = 一条 lane 的合成参数 | 静态占位绑不了画面 |
| 跨维度 lane 的合成 | 合成层不读维度；纹理由 `DimensionView` 提供 | 合成与维度源解耦，见 [跨维度相机](cross-dimension-camera.md) |

实测：叠化四个稳定像素与端点均值的最大通道误差为 1；划像与着色器探针通过；光影三包 A/B 干净

## 附录：重写前原文

### 0.3.6 画面合成：取材区域 / 目标区域 / 不透明度

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、接口、JSON、迁移步骤等执行时再定
>
> 相关文档：
> - [并行播放](parallel-playback.md)
> - [多相机渲染](multi-camera-rendering.md)
> - [Overlay 颜色遮罩](../D-transition/overlay-color-mask.md)

> **状态（2026-10-18）**：合成参数与上屏已落地（§7 步骤 3–5）；§5 / §6 的 5 项仍开放（汇总见[本目录索引](README.md) §4）。

---

#### 1. 定位（已确认）

每个画面 lane 的输出是**一张纹理**。合成层的工作就是把若干张纹理铺到屏幕上，回答四个问题：

| 参数 | 含义 | 默认 |
|---|---|---|
| 取材区域（source rect） | 从 lane 画面里取哪一块（**素材自身**归一化矩形：lane 画面内、原点左上角） | 全幅 |
| 目标区域（dest rect） | 铺到屏幕哪一块（**窗口**归一化矩形） | 全屏 |
| 不透明度（opacity） | 叠放时的透明度 | 1 |
| 叠放顺序（order） | lane 之间的上下关系 | **先按轨道层级，再按轨道内 clip 顺序（后面的 clip 在上），最后按 z_index**（已确认·2026-10-07；跨实例再叠一层实例启动顺序，见并行播放 §3.4） |

> **口径（2026-10-10）**：`source` / `dest` 与覆盖层统一几何模型（[可变画面](../D-transition/variable-frame.md) §4.2）是**同一套参照**——**取材参照素材自身、放置参照窗口**，两族之间**无换算**。lane 族的「缩放」隐含在 `dest` 尺寸 / `source` 尺寸之比；lane 画面每帧按播放窗口尺寸渲染，故分辨率转义系数 `k = 1`。完整定义见该节，本文不重复。

**四个参数全部走关键帧**（已确认）：随时间变化、可插值——这是 PIP 系统应有的能力。

**合成是顺序进行的**（渲染一张 → 贴到屏幕 → 复用缓冲）→ **显存不随 lane 数增长**；只有叠化预热需要同时保留 2 张（原型实测：1 张共用缓冲跑完 25 个画面，见 `quadrant-perf/summary.md`）。

由此，现有与计划中的所有画面形态都是**同一模型的特例**（已确认）：

| 形态 | 参数描述 |
|---|---|
| 单条全屏 lane（现状） | dest = 全屏，opacity = 1 |
| PIP 画中画 | dest = 小矩形 |
| 分屏 | 两个 lane，dest 各占左 / 右（或上 / 下）半屏 |
| 叠化（dissolve） | 两个全屏 lane，下层 A 保持 opacity=1，上层 B 在重叠窗口 0→1 |
| 数字变焦 / 局部取景 | source rect 取局部，dest 全屏 |
| 监视器墙 | N 个 lane，dest 网格排布 |

> **这些形态没有独立实现（已确认·2026-10-07；叠化合同 2026-10-17 纠正）**：它们都是同一套合成参数关键帧的编排。叠化由下层 clip A 保持 opacity=1、上层 clip B 在重叠窗口 0→1 表达；普通 source-over 下两层同时半透明会漏出第三层基画面，不能以 A+B≈1 证明不露底。**追踪 = 调 clip 的关键帧参数**，没有独立转场轨 / 字段 / 生成代码；多相机渲染负责重叠区双 lane 上屏。详见[画面转场](../D-transition/scene-transition.md) §3.2。

**取材区域语义（已确认）**：对相机**输出画面**的矩形裁剪（屏幕空间 UV），不是改相机视锥的世界空间取景。

**不属于本文**：

- 多张相机画面怎么渲染出来（渲染管线、FBO、模组兼容）→ [多相机渲染](multi-camera-rendering.md)
- lane 从哪来（多脚本实例并行）→ [并行播放](parallel-playback.md)

---

#### 2. 合成参数的归属（已定·2026-10-07）

关键帧数据放在哪：

- **候选 A：自声明**——每个脚本声明自己 lane 的合成参数；跨脚本并行时各管各的，order 冲突按启动顺序。简单，第一版够用。
- **候选 B：合成导演**——独立的导演脚本 / 轨道统一引用并编排各 lane。表达力强，但依赖 lane 寻址（名字 / id）。

**已定：A（自声明）**——参数写在本 clip 自己的关键帧上，不引入独立合成轨 / 导演；候选 B 的结构空间未被堵死（将来可加寻址层，前置 = 跨脚本 lane 寻址，见 §6）。

**关键帧通道的落点（已定）**：合成参数**挂在 CAMERA 轨 clip 的关键帧上**（`Keyframe.data` 通用容器），不新开轨道类型；字段表与校验见 §7「步骤 1 定稿」。

---

#### 3. 与现有 pip 层的关系（已定·2026-10-08）

OVERLAY 现有 `pip` 层是静态占位（半透明黑填充 + 2px 白边，不绑定任何纹理/相机画面）；画面 lane 是动态纹理。方向：`pip` 层升级为可绑定 lane（lane 作为一种图层来源），合成参数即层的几何属性；或 pip 层被合成层取代。**已定（2026-10-08）：pip 层被合成层取代**（见下方裁决）。

> **裁决（2026-10-08，CLEANUP-2）**：走「pip 层被合成层取代」——`pip` 层**已删除**（0.3.6 起，被 lane 取代）。画中画 = 一条 CAMERA lane 的合成参数（`dest` / `source` / `opacity`）。**WebUI 里对脚本片段的便捷操作留 Z 轮。**

---

#### 4. 叠化预热（方向，沿用多相机渲染文档的结论）

叠化开始那一刻，两个 lane 的画面必须同时有效：B lane 不能到叠化开始才第一次渲染，否则第一帧没准备好。

- 按脚本时间线**提前预热 B**：预热开始 = 叠化开始 − 预热时长；
- 不是渲染“未来的世界”（世界是动态的，未来帧不存在），是让 B 的 framebuffer 提前进入有效状态；
- 并行实例模型下，预热 = **提前启动 B 实例**（见[并行播放](parallel-playback.md)）。

**时间轴允许重叠后的结论（已确认·2026-10-07）**：**预热 = 重叠窗口本身**。叠化 = 上一个 clip 的**末尾帧复制延长**（hold）+ 下一个 clip 的**首帧复制延长**（hold），两个冻结区时间交叉（见[脚本模型](../G-script-model/script-model.md) §3 的重叠与 hold 延伸）；B 从自己 clip 的开头（opacity 0 起，hold 区渲染的就是被复制的首帧）就一直在渲染，**不需要独立的预热调度机制**。"预热时长"退化为作者的编排（叠化时长 = 延长区长度），不再是一个框架参数。

---

#### 5. 可能的问题（状态·2026-10-18）

- ✅ **合成参数归属**：已定自声明（§2）；order 冲突规则已定（轨道层级 → 轨道内 clip 顺序 → z_index，见 §1）。
- 🟡 **dest 矩形之外的兜底**：当前实现 = 露**原版玩家视角**（原版玩家相机照常渲染，lane 只覆盖自己的 dest）；是否改为黑场兜底**未定**。时间轴允许重叠后，作者可用重叠保证全屏总有覆盖、无空隙（露原版视角是「有空隙」的结果，靠作者编排避免）。
- 🟡 **编辑器预览的多 lane 合成表现**：步骤 6 未做（WebUI 属性面板 + 多 lane 预览）。
- 🟡 **与 HUD / letterbox 的叠放顺序**：合成层写入主 framebuffer、在原版 GUI 之前；letterbox 属 OVERLAY 层（GUI 内）——两者的层级语义 / z 序基线仍待定（跨文档见 `variable-frame.md` §6）。
- 性能：合成本身是全屏 quad 拷贝，开销小；主要开销在渲染侧（见多相机渲染文档）。原型实测：每 lane ≈3.4–3.6 ms（主画面稳态 2–3 ms）、线性；且 **lane 级后处理（如发光描边）属于该 lane，必须在 lane 的 FBO 内完成**（见 `quadrant-prototype-results.md` §3.2）。**光影每视角历史拷贝成本未测**（F5 回填）。

---

#### 6. 待定

- **听者归属用的 lane 判定规则**（见并行播放 §3.3）。
- **跨脚本 lane 寻址**（候选 B 的前置）。
- **dest 矩形之外无 lane 覆盖时的兜底**（露原版视角 vs 黑场，见 §5）。
- **编辑器预览的多 lane 合成表现**（步骤 6）。
- **与 HUD / letterbox 的叠放顺序 / z 序基线**（跨文档：`variable-frame.md` §6）。

**已定（保留结论）**：

- 合成参数字段形态与归属：`opacity` / `dest` / `source` 挂 CAMERA clip 关键帧，自声明（§2、§7 步骤 1）。
- `pip` 层：已删除，被 lane 取代（2026-10-08，CLEANUP-2）。

---

#### 7. 落地顺序（方向）

| # | 步骤 | 交付物（完成后我们要什么） | 状态 |
|---|---|---|---|
| 1 | 合成参数模型定稿：字段表 + 默认值 + 校验规则 | 文档字段表；手写 JSON 即可表达分屏 / PIP / 叠化意图 | ✅ 2026-10-07（字段表见本节末；`docs/SCRIPT_FORMAT.md` §4 / AI 指南同步） |
| 2 | 单 lane 非全屏：该 lane 的 dest / opacity 可关键帧 | 单相机脚本可把画面缩到屏幕一角、淡入淡出，无需第二相机 | ✅ 2026-10-07（渲染侧消费见「步骤 3–5」） |
| 3 | 双 lane 上屏（依赖多相机渲染原型）：order + dest 铺屏 | 分屏脚本可跑 | ✅ 2026-10-07（合成层 + 脚本侧接线） |
| 4 | opacity 关键帧 → 叠化，含预热 | 两镜头 dissolve 过渡可用 | ✅ 2026-10-17（合同纠正 + 实机像素验证；预热 = 重叠窗口，无独立调度） |
| 5 | source rect 裁剪 | 同一相机画面取局部铺满全屏（数字变焦 / 局部取景） | ✅ 2026-10-07（`LaneCompositor` UV 子区域） |
| 6 | 编辑器：合成参数属性面板 + 多 lane 预览 | WebUI 内可编排并预览分屏 / 叠化 | ☐ 未开工（归 Z 轮） |

> **落地状态（2026-10-18）**：步骤 1–5 已落地——数据层（字段表 + 校验 + 文档）、渲染侧合成层（`LaneCompositor`）与脚本侧接线（关键帧 → lane 注册 → 合成参数 → 上屏）全部完成；
> **旧「单相机替换」链已退役（2026-10-07，见 [并行播放](parallel-playback.md) §3.3 落地记录）**——
> 「合成层输出 = 玩家看到的画面」完全成立；**步骤 6（编辑器预览面板 / 多 lane 预览）未落地**。
> 队列接播（F2）已于 2026-10-17 实现（无头 68/68，实机由 C 轮队列接播覆盖），见并行播放 §7「F2 落地记录」；
> 光影下多 lane 上屏的跨视角污染已于 2026-10-18 隔离（见下方「光影环境下的多 lane 合成」）。
>
> **叠化（步骤 4）合同纠正与实机验证（2026-10-17）**：两条时间重叠的 CAMERA clip，
> 下层 A 全程 opacity=1，上层 B 在重叠窗口淡入；hold = 复制等值关键帧冻结相机姿态。
> `cinematics/tests/transition/test_dissolve.json`（A[0,6] / B[4,10]，重叠窗口 [4,6)）
> 已通过真实解析 / 校验与数据探针；Fabric 10.01s 自然结束，四个稳定像素在叠化中点
> 与 A/B 端点均值的最大通道误差为 1，详见 `scene-transition.md` §9。
> 原 2026-10-07 “A、B opacity 互补即不露底”的离线结论错误，已被本次实机证据取代；
> 不存在运行时 alpha 配对 / 归一机制。此前删除同轨重叠拒绝项的修复仍成立（CAMERA 支持双 lane）。
>
> 步骤 1–2 不依赖多相机渲染，可先行验证关键帧链路；步骤 3 起依赖渲染原型。

##### 步骤 1 定稿：合成参数字段表（已定稿·2026-10-07）

合成参数**全部挂在 CAMERA clip 的关键帧上**（`Keyframe.data` 通用容器，无需改类），随关键帧插值。字段形态与默认值：

| 字段 | 类型 | 默认 | 校验 | 说明 |
|---|---|---|---|---|
| `opacity` | float | `1.0` | `0`~`1` | 叠放不透明度 |
| `dest` | object `{x,y,w,h}` | `{0,0,1,1}`（全屏） | 各分量 `0`~`1` | 目标区域：**窗口**归一化矩形（放置参照窗口） |
| `source` | object `{x,y,w,h}` | `{0,0,1,1}`（全幅） | 各分量 `0`~`1` | 取材区域：**lane 画面（素材）**归一化矩形，原点 = 左上角 |

> **口径更新（2026-10-10）**：字段名 / 类型 / 默认值 / 校验不变；参照系说明对齐统一几何模型（[可变画面](../D-transition/variable-frame.md) §4.2）——`dest` = 窗口归一化矩形（**不再有任何「画布归一化」换算**：参考画布方案已被取代），`source` = 素材（lane 画面）归一化矩形、原点左上角；lane 族的缩放 = `dest` 尺寸 / `source` 尺寸，`k = 1`（lane 画面按播放窗口尺寸实时渲染）。**数据层与渲染层均无需改动**（§11.7：`LaneCompositor` 不动）。

**`order` 不进字段表**：叠放层级由**轨道层级 → 轨道内 clip 顺序**决定（后面的 clip 在上，§1 默认规则），作者不写。

**为什么 `dest` / `source` 用对象而非平铺字段**：矩形是一个整体语义（四个分量同属一个参数），与既有 `position` / `look_at_target` 的复合对象写法一致；关键帧按"复合值整体插值"（`KeyframeInterpolator` 对 `position` 即如此），对象形态让矩形随时间整体插值更自然，字段表也更小。

**落地位置**：字段登记在 `script/schema/TrackSchemas.camera()` 的 `kfFields`；校验在 `ScriptValidator`（`checkUnitFloat` / `checkRect`）；文档见 `docs/SCRIPT_FORMAT.md` §4"合成参数"；测试脚本 `cinematics/tests/camera/test_compositing_params.json`。

**归属裁决**：本定稿取 §2 **候选 A（自声明）**——参数写在本 clip 自己的关键帧上，不引入独立合成轨/导演；候选 B 的结构空间未被堵死（将来可加寻址层）。

**范围**：本步骤只落数据层（能解析、能校验、能存进通用 Keyframe 容器）；渲染侧消费（把参数应用到上屏）属步骤 2 及以后。

##### 步骤 3–5 渲染侧落地：合成层（已落地·2026-10-07）

合成层 = `client/lane/LaneCompositor.java`：`compose(RenderTarget texture, Rect source, Rect dest, float opacity)`
把一条 lane 的画面纹理铺到屏幕（主 framebuffer），**四个参数在同一次 quad 绘制里完成**（不拆两次绘制）：

| 参数 | 落点 |
|---|---|
| `source` | UV 子区域（只采样画面的一块） |
| `dest` | quad 顶点（屏幕归一化矩形 → 像素） |
| `opacity` | 混合 alpha（`ColorModulator.a` + `srcalpha / 1-srcalpha` 混合） |
| `order` | **绘制顺序**：合成即时进行、不缓存纹理（§1「渲染一张 → 贴到屏幕 → 复用缓冲」，lane 共用一张离屏缓冲），所以叠放顺序就是**调用顺序**——调用方按「轨道层级 → 轨道内 clip 顺序 → z_index」升序逐层调用，后调用者盖在先调用者之上 |

- **矩形口径**：`source` / `dest` 都是 0~1 归一化、**原点在左上角**（x 向右、y 向下）；`source` 相对 lane 画面，`dest` 相对屏幕。纹理 v 轴在合成器内部翻转（FBO 纹理 v=0 在画面底部，与原版 `RenderTarget.blitToScreen` 的 UV 口径一致）。
- **绘制路径**：复刻原版 `RenderTarget._blitToScreen` 的屏幕空间画法（屏幕正交投影 + 模型视图 z=−2000 + 4 顶点 quad），shader 用原版 `position_tex`（其自带 `srcalpha / 1-srcalpha` 混合，正是 opacity 需要的），不新建 shader 资产。
- **状态纪律**：一次合成会改动绑定的 framebuffer / 视口、全局投影与 VertexSorting、全局模型视图、shader 颜色与 0 号 shader 纹理、深度测试 / 深度写 / 颜色写 / 混合——合成器入口保存、出口还原（与 lane 渲染同一纪律）；alpha 通道不写（同 `blitToScreen`：主画面 alpha 不归合成层管）。
- **验证入口（已更新·2026-10-07 清理）**：当时用调试驱动（`-Dicinematics.quadrant=4`）改走合成器上屏（dest = 网格格、source = 全幅、opacity = 1）替换临时的 `glBlitFrameBuffer`；该驱动与 `ICINEMATICS_QUADRANT` 开关**已删除**。现在的画面验证入口 = 脚本 lane（四象限 `cinematics/release/quadrant.json`）+ 调试捕获 `ICINEMATICS_CAPTURE`（见[多相机渲染](multi-camera-rendering.md) §12.6）。

##### 步骤 2–5 脚本侧接线：lane 注册与上屏（已落地·2026-10-07）

数据流（全部在渲染线程、每帧一次）：

```text
CameraTrackPlayer.onRenderFrame   本帧活跃 clip 各产一份 lane 快照（CameraLane = 相机六参数 + clip + clip 内本地时间）
  → ScriptPlayer.collectCameraLanes()   按绘制顺序扁平化：轨道层级 → 轨道内 clip 顺序（末位 = 合成顺序最上层）
  → ScriptLaneDriver.tick()             注册 lane：LaneRenderer.setLane(i, 状态, 内容档) + setSink
  → LaneRenderer.render()               逐 lane 整尺寸渲染进共用离屏缓冲
  → LaneCompositor.compose()            该 lane 自己的 opacity / dest / source 贴到屏幕
```

- **合成参数来源**：每条 lane 的参数取自它所属 clip 的关键帧，在**该 clip 的本地时间**处插值——与相机六参数用同一个 `KeyframeInterpolator`，所以 hold（末尾复制延长）、`loop` / `pingpong` 的时间语义与相机完全一致；矩形按分量整体插值（同 `position` 的复合值口径），缺省 = `1` / 全屏 / 全幅。morph（旧转场模型）只有一份 lane，参数取**进入的片段**。
- **叠放顺序**：lane 序号 = 提交顺序 = 绘制顺序，末位（合成顺序最上层 clip）最后画、盖在最上面；叠放位置不等于相机身份。
- **内容档 = `FULL`（与完整画面一致）**：单条全屏 lane（opacity=1、dest 全屏）会盖住主 framebuffer 的既有内容，且叠化要求两条 lane 观感一致 → 实体 / 粒子 / 天空 / 天气必须全开；`WORLD_ONLY` 只画地形，全屏 lane 下会看不到生物 / 掉落物 / 天空 / 雨雪。
- **lane 来源（已更新·2026-10-07 清理）**：脚本 lane 是**唯一来源**——当时的调试驱动共存机制（`-Dicinematics.quadrant` 让位规则）随驱动一起删除；无脚本播放时没有 lane，画面回落原版玩家视角。
- **画面 = 合成层输出（旧「单相机替换」链已退役·2026-10-07）**：原版玩家相机照常走玩家视角，lane 是**唯一画面来源**——单条全屏、opacity=1 的 lane 就是整屏画面特例；零 lane 时回落原版视角。`dest` 非全屏时露出的就是**原版玩家视角**（不再是旧单相机替换链的相机画面）；主 framebuffer 里在 lane 之前画进的内容（`renderLevel` 内的第一人称手臂——仅当脚本显式 `hide_arm: false` 时可见）会被不透明的全屏 lane 盖住（见 [并行播放](parallel-playback.md) §3.3 落地记录）。**已知缺陷（2026-10-18）**：`hide_arm: true` 当前未生效，手臂仍可见（独立问题，见下「光影环境下的多 lane 合成」）。
- **验证**：编译通过；离线冒烟（真实测试脚本 JSON → 关键帧 → 参数取值逐点核对：缺省 / 插值 / 矩形缺分量 / 末尾钳制）；游戏内冒烟 = 播一个带 CAMERA clip 的脚本（如 `cinematics/tests/camera/test_compositing_params.json`）肉眼核对 `dest` 缩角 / `source` 裁剪 / `opacity` 淡入淡出。

##### 光影环境下的多 lane 合成（2026-10-18）

多 lane 上屏在光影环境下的错误（绿色毛边 / 竖缝 / 扇形明暗 / 跨视角颜色污染）已定位为**光影的每视角状态未隔离**——合成层本身无责（`ic_lane_blit` 只读 RGB、alpha 契约不变），污染在 lane 的 RGB 内部。已落地三类新增隔离 + 一项既有修复验证（实现见[多相机渲染](multi-camera-rendering.md) §12.10）：

| 隔离项 | 做法 | 证据 |
|---|---|---|
| 每视角 TAA 颜色历史 | 从当前光影包源码自动探测「上一帧采样」的 colortex（`historyTargets`），按视角保存 / 恢复 main/alt，首帧清空 live | MakeUp `[3]`、ComplementaryUnbound r5.9.3 `[2]`、BSL v8.4 `[2]` |
| 每视角 `shadowcolor` | 包未声明清屏（`buffersToBeCleared=[]`）时，每视角 `beginLevelRendering` 后清 `shadowcolor` main/alt | 清后 B lane 绿墙橙边消失 |
| 每视角 previous / tracker | previous 矩阵与 tracker 按视角恢复；不再回滚全局 COUNTER | main→cam0→cam1 不再串联 |
| 深度附件（既有修复，本轮不改） | lane 首帧已重挂到自己的深度附件（日志 `f1 begin_return irisDepth=78`） | `test_shadow_scene` 首帧日志 |
| 游戏内切换光影包 | pipeline 变化 → 清空每视角历史快照 / `Previous` 实例 / 视角栈，首帧重建 | 多次切换 BSL / Complementary / MakeUp 不再纯白，无需重启 |

**A/B 结果（Fabric / Iris 1.7.6）**：MakeUp / Complementary / BSL 三包 `test_ab_split_scale` r5 的 lane raw 与主视角均干净——无绿边、无竖缝、无扇形。
**F3 验收通过（Forge / Oculus 1.8.0 同 jar 已部署）**；每视角历史拷贝成本未测（F5 回填）；`hide_arm` 未生效是独立问题（不属跨视角污染）。

---

#### 已知缺陷（2026-10-06 代码复查）

> 只读代码审查发现，未在游戏内复现；不影响当前设计，记录备查。

- **pip 的 opacity 不生效**：`overlay/PipLayer` 算出的 `fillArgb` / `borderArgb` 从未使用（直接用了常量）→ `opacity` 对 pip 填充与边框完全不起作用；且 pip 的 `x/y/width/height` 是原始像素而非屏幕百分比。**（存档：pip 层已于 2026-10-08 删除，被 lane 取代。）**

---

#### 事实核查（2026-10-07）

> 依据文件：`common/src/main/java/com/immersivecinematics/immersive_cinematics/overlay/{PipLayer,ImageLayer,FadeLayer,SubtitleLayer,LetterboxLayer,OverlayManager}.java`、`.../proto/QuadrantProto.java`、`.../mixin/QuadrantProtoMixin.java`、`.../script/OverlayTrackPlayer.java`、`plans/0.3.6/quadrant-perf/summary.md`、`plans/0.3.6/quadrant-prototype-results.md`。核查针对本文事实性断言，不改设计方向。
> **本小节是 2026-10-07 快照**：此后 pip 层删除、合成层换 `ic_lane_blit`、多 lane 上屏与光影每视角隔离（2026-10-18）均见 §3 / §7。

##### ① 核实为真

1. **pip 的 opacity 不生效**（已知缺陷条）：`PipLayer.render()` 里算出 `fillArgb`/`borderArgb` 后**均未使用**——填充写死 `FILL_COLOR`（`0x40000000`）、边框写死 `BORDER_COLOR`（`0xFFFFFFFF`）。证据：`overlay/PipLayer.java:14-15,40-45`。
2. **pip 的 x/y/width/height 是原始像素**：`render()` 里 `actualX = x - width*anchorX` 后直接 `(int)` 使用，**无** `* screenWidth/screenHeight`；对比 `ImageLayer.render()` 的 `x*screenWidth`。证据：`overlay/PipLayer.java:31-36`、`overlay/ImageLayer.java:55-56`。
3. **pip 层只画填充 + 边框（无纹理）**：`PipLayer.render()` 只有 5 次 `guiGraphics.fill(...)`（1 填充 + 4 边框），不绑定纹理/相机画面；类注释「Phase 1：仅渲染白色边框和半透明黑色填充，不包含实际摄像头画面」。证据：`overlay/PipLayer.java:5-9,41-53`。
4. **§5 性能数字**：`quadrant-perf/summary.md` 结论表「单画面」4/16/25 画面分别为 3.4 / 3.5 / 3.6 ms、主画面各档 2–3 ms、线性——绝对数值与本文一致；summary 原「≈1.2–1.4 × 主画面」比值经 2026-10-07 跨文档核查与 CSV 明细不符（CSV 推算 ≈1.6–2.0×），已按「晚修改者为准」（multi-camera-rendering.md 同日修正）在各引用文档删除该比值。
5. **§5 「lane 级后处理必须在 lane 的 FBO 内完成」**：`quadrant-prototype-results.md §3.2` 结论原文「lane 的 `renderLevel` 加上所有 lane 级后处理都必须在 lane 的 FBO 内完成，再缩放上屏」，与本文一致。
6. **§1「1 张共用缓冲跑完 25 个画面」**：`proto/QuadrantProto.java` 只持有一个静态 `RenderTarget target`，注释「共用的离屏缓冲（各画面顺序渲染、用完即贴，不必每画面一张）」；`QuadrantProtoMixin` 在每个 view 循环里都调 `QuadrantProto.target(w, h)` 复用同一缓冲。证据：`proto/QuadrantProto.java:70-71,221-227`、`mixin/QuadrantProtoMixin.java:113`。
7. **§1「合成顺序进行、显存不随 lane 数增长、缓冲复用」**：与 `summary.md`「成本线性、显存不随画面数增长（合成顺序进行，缓冲复用）」一致，且有上述单缓冲源码支撑。

##### ② 已修正的断言

1. **§3「OVERLAY 现有 `pip` 层是静态贴图」→「静态占位（半透明黑填充 + 2px 白边，不绑定任何纹理/相机画面）」**：`PipLayer` 不绘制任何纹理（见①.3），「贴图」不准确。已就地改为精确描述，方向句未动。

##### ③ 补全的信息（源码核对，原文未列出/未明确）

1. **overlay 五个内置层类的默认 z 常量**（0/10/20/30/40 的说法与源码一致）：
   - `LetterboxLayer.Z_INDEX = 0`（`LetterboxLayer.java:7`）
   - `FadeLayer.DEFAULT_Z_INDEX = 10`（`FadeLayer.java:13`）
   - `ImageLayer.DEFAULT_Z_INDEX = 20`（`ImageLayer.java:25`）
   - `SubtitleLayer.DEFAULT_Z_INDEX = 30`（`SubtitleLayer.java:18`）
   - `PipLayer.DEFAULT_Z_INDEX = 40`（`PipLayer.java:13`）
   - ⚠ 注意区分：这是**类默认常量**；脚本创建的层 z 值实际来自 clip 的 `z_index` 字段（`OverlayTrackPlayer.createLayer`：`int zIndex = clip.getInt("z_index", 10)`，默认 10），会 `setZIndex(...)` 覆盖类常量。即运行时 z 由脚本指定、缺省 10，而非直接用 0/10/20/30/40。
2. **`OverlayManager` 行为**：单例 `INSTANCE`（`OverlayManager.java:31`）；构造时只注册 `LetterboxLayer`；`addLayer()` 后按 `OverlayLayer::getZIndex` 升序排序（`OverlayManager.java:72-74`）；`render()` 顺序遍历可见层（`isVisible()` 过滤，`OverlayManager.java:55-58`）；`update()/reset()/isAnimating()/startFadeOut()` 均遍历所有注册层（`reset()` 见 `OverlayManager.java:163-165`）。
3. **`ImageLayer` 百分比坐标断言**：`x/y` 为屏幕百分比、元素中心（`actualX = x*screenWidth - dispW/2`），`scaleX/scaleY` 为相对原图尺寸的乘数。与 `variable-frame.md`（「现状对照」行：`ImageLayer` 的 x/y 已是屏幕百分比、元素中心）**一致，两文档无冲突**。证据：`ImageLayer.java:14-24,55-56`。
4. **pip 的合成参数入口**：`PipLayer` 已有 `setPosition/setSize/setAnchor/setOpacity/setZIndex`（`PipLayer.java:73-95`），`OverlayTrackPlayer.applyInitialClipValues` 从关键帧读 `x/y/width/height/anchor_x/anchor_y`（均按原始像素/浮点直传）。证据：`OverlayTrackPlayer.java`（`applyInitialClipValues` 的 `PipLayer` 分支）。

##### ④ 无法核实的断言

- §1「只有叠化预热需要同时保留 2 张」：属设计推论（方向），无现状源码可证，**未验证**。

##### ⑤ 跨文档冲突备注（未改他人文档）

- `quadrant-prototype-results.md §2` 写「每象限整尺寸渲染进**自己的** `RenderTarget`」，而原型源码 `QuadrantProto.java` 实际是**单个共享** `RenderTarget`（各 view 顺序复用）。本文 §1「1 张共用缓冲」与源码一致；差异仅在原型文档 §2 的措辞，未修改该文档，仅在此备注。
