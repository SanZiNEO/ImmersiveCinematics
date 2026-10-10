# 0.3.6 可变画面：从固定输出到可摆放的画面元素（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、接口、公式、JSON、迁移步骤等执行时再定
>
> **2026-10-10 更新**：几何口径定稿为「**统一几何模型**」——**画布 = 窗口**，取材 / 缩放参照素材自身，位置参照窗口，分辨率转义用单一系数 `k`（**§4.2**）；§4.1 参考画布方案**已被取代**（保留存档）。后续编码改动清单见 **§11**。
>
> 相关文档：
> - [画面合成](./camera-composition.md)
> - [多相机渲染](./multi-camera-rendering.md)
> - [并行播放](./parallel-playback.md)
> - [脚本模型](./script-model.md)

---

## 1. 定位（已确认）

多相机原型验证通过之后，**相机画面不再是“固定的整屏输出”，而是一个可以被裁切、缩放、摆放的画面元素**。

再进一步（已确认）：**我们所有的画面内容都是盖在游戏画面上的覆盖层**，只是分了类。相机画面、图片、字幕、遮罩、黑边是同一类东西的不同类别，用**同一套参数**描述“放在哪、多大、裁哪块、多透明”。

本文只回答**摆放**。不回答：

- 画面怎么渲染出来 → [多相机渲染](./multi-camera-rendering.md)
- 谁在播、同时播几条 → [并行播放](./parallel-playback.md)
- 参数怎么进关键帧 → [脚本模型](./script-model.md)

### 1.1 画面系统：本文是核心（统筹定位·已确认 2026-10-07）

**画面系统** = 以「对象的**位置 / 大小 / 缩放 / 透明度**等参数」为唯一控制面，用**关键帧之间的平滑（插值）**驱动，并在此基础上组合出多种复杂效果的体系：

```text
画面系统
 ├─ 参数模型（本文）：位置 / 锚点 / 缩放 / 取材 / 不透明度 / 顺序（+ meta.base_resolution → 转义系数 k）—— 全部走关键帧
 ├─ 关键帧平滑：
 │    关键帧间插值 → script-model.md（关键帧级插值 / 贝塞尔手柄、参数寻址到分量）
 │    帧间平滑（tick 与渲染帧之间）→ temporal-interpolation.md
 ├─ 画面来源（同一参数模型的不同类别）：
 │    相机 lane（multi-camera-rendering.md 渲染）、图片 / GIF（resource/）、
 │    纯色（overlay-color-mask.md 的纯色覆盖层）、文字（字幕）
 └─ 复杂效果（应用层，全部是参数组合，不新造"效果类型"）：
      PIP / 分屏 / 叠化 / 数字变焦 / 监视器墙 → camera-composition.md 特例表
      转场（叠化 / 黑场 / 白场 / wipe）→ scene-transition.md
      调色（只动 RGB / HSL）→ screen-color-adjust.md
```

| 文档 | 在画面系统里的角色 |
|---|---|
| **本文（variable-frame）** | **核心 / 总纲**：统一参数模型 + 窗口参照 + 分辨率转义（`k`）+ 设备无关 |
| script-model / temporal-interpolation | 关键帧平滑的机制（插值 + 帧间平滑） |
| multi-camera-rendering / camera-composition | 画面来源与上屏（lane 渲染 + 合成参数） |
| overlay-color-mask | 纯色覆盖工具（黑场 / 白场 / 颜色遮罩 clip 的归属） |
| scene-transition | 应用层：转场 = 参数组合调用 |
| screen-color-adjust | 应用层：调色（RGB / HSL，不碰 alpha） |

**原则（已确认）**：复杂效果 = 同一套参数的新取值 / 新组合，**不新造"效果类型"**；发现参数模型表达不了的新效果，先回来给本文加字段，再谈应用。**效果写成系统可追踪的数据（轨道 → clip → 关键帧参数），不写死代码**——机制性能力最小化（实证：ReplayMod 路径系统全数据化、olive 节点参数全关键帧，见 `scene-transition.md` §1）。

---

## 2. 画面 = 覆盖层（已确认）

游戏画面之上的一切内容都是覆盖层，按顺序叠放。分类只体现“内容来源”，摆放参数是同一套：

| 类别 | 内容 | 来源 |
|---|---|---|
| 画面层 | 相机 lane 的输出纹理 | 每条 CAMERA 轨 / 每个相机一条 |
| 图形层 | 图片 / GIF | `resource/` 目录 |
| 文本层 | 字幕 | 脚本 |
| 效果层 | 颜色遮罩 / 淡入淡出 | 脚本 |
| 画幅层 | 黑边（letterbox） | 脚本 |

**主画面也是覆盖层之一**（全屏那一层）——“100% 原画面”就是它的默认形态。

> 现状对照（可验证）：`overlay/` 下现有 Letterbox / Fade / Image / Subtitle 四类实现（均 `implements OverlayLayer`）；画中画由**画面 lane 的合成参数**（`dest` / `source` / `opacity`）实现，原 `pip` 层（占位框，无纹理、无相机画面）已于 0.3.6 删除，见 §6。

---

## 3. 参数（方向）

每个覆盖层用同一组参数描述：

| 参数 | 语义 | 参考系（2026-10-10 口径，定稿见 §4.2） |
|---|---|---|
| 位置 | 元素中心在哪 | **窗口百分比** |
| 锚点 | 缩放（将来含旋转）绕哪个点 | 元素自身百分比（0~1） |
| 缩放 | 相对素材原始像素尺寸的倍数 | **素材自身** |
| 取材 | 裁掉素材的哪几条边 | **素材百分比（0~1，原点 = 素材左上角）** |
| 分辨率转义 | 编辑基准分辨率 → 播放窗口的整体缩放（单一系数 `k`） | 窗口 / `meta.base_resolution` |
| 不透明度 | 叠放时的透明度 | 0~1 |
| 顺序 | 叠放次序 | 整数 |

**全部走关键帧**（见[脚本模型](./script-model.md)）。

**“基准尺寸”必须逐类定义**（缩放 = 1 时上屏多大），否则倍数没有参照物：

| 类别 | 基准尺寸（`scale = 1` 时的上屏像素尺寸） |
|---|---|
| 画面层 | 不适用：放置 = `dest` 矩形（缩放隐含在 dest / source 尺寸比；素材按播放窗口实时渲染，`k = 1`） |
| 图形层 | 裁切后素材像素 × `k`（整幅 = 原图像素 × `k`） |
| 文本层 | 文字块像素（`font_scale` 下）× `k` |
| 效果层 / 画幅层 | 铺满窗口（无缩放概念） |

> 2026-10-10 起**废除“适配（Fit / Fill / Stretch）”参数**：元素框由「裁切后素材 × 缩放」派生，素材恒按元素框铺满（等价旧 `stretch`），没有独立的框可适配 —— 结论与理由见 §4.2。

### 3.1 字段表定稿（2026-10-07 首版；**2026-10-10 按统一几何模型改参照系**）

> **口径更新（2026-10-10）**：本节表格的**参照系**列已按用户裁决「统一几何模型」改写（位置参照 画布 → **窗口**；缩放参照 逐类基准（原图 ÷ 1920）→ **素材原始像素尺寸**；取材参照不变 = 素材归一化，原点明确为素材左上角 (0,0)）；`fit` 字段废除、新增 `meta.base_resolution` 与分辨率转义系数 `k`。完整模型、公式与推导见 **§4.2**；本节的字段名 / 类型 / 默认值 / 插值语义不变。

**参数全部是覆盖层的关键帧字段**（存在通用 `Keyframe.data` 容器里，随关键帧插值；字段名沿用/收敛现有 schema，`source` 与相机侧同名同形）：

| 参数 | 字段 | 类型 | 默认 | 范围 | 插值 | 基准 / 参考系（2026-10-10） |
|---|---|---|---|---|---|---|
| 位置 | `x` / `y` | float | `0.5` / `0.5` | `0`~`1`（**不钳制**，可越界） | 线性 | **窗口**归一化，元素中心 |
| 锚点 | `anchor_x` / `anchor_y` | float | `0.5` / `0.5` | `0`~`1` | 线性 | **元素自身**归一化（`0` = 左/上缘，`1` = 右/下缘），缩放绕点 |
| 缩放 | `scale_x` / `scale_y` | float | `1.0` / `1.0` | `≥ 0` | 线性 | 相对**素材原始像素尺寸**的倍数（`2` = 两倍宽） |
| 取材 | `source` = `{x,y,w,h}` | object | `{0,0,1,1}` | 各分量 `0`~`1`，且 `x+w ≤ 1`、`y+h ≤ 1` | 按分量整体线性（同 `position` 的复合值口径） | **素材**归一化，原点 = 素材**左上角** `(0,0)`（x 向右、y 向下） |
| 不透明度 | `opacity` | float | `1.0` | `0`~`1` | 线性 | — |
| 顺序 | `z_index` | int | `10` | 任意整数（可负） | **步进** | — |

> `z_index` 是离散值：作为关键帧通道成立，但按**步进**语义取值（clip 级常量 = 只有一个值的步进通道，与现状 `z_index` 是 clip 字段等价）。~~`fit`~~ **已废除（2026-10-10）**：元素框由素材派生，映射恒为「铺满元素框」，理由见 §4.2。

**元素几何（定稿口径·2026-10-10）**，基准以**像素**给出（播放窗口像素）：

```text
素材像素  = 图形层：纹理实际像素；文本层：文字块像素（font_scale 下）
裁切后素材像素 Cs = (source.w · 素材宽, source.h · 素材高)
k        = min(W播 / W基, H播 / H基)          分辨率转义（单一系数，§4.2）
B        = (Cs.x · k, Cs.y · k)              未缩放基准尺寸（scale = 1 时的上屏像素）
s        = (scale_x, scale_y)                缩放（相对素材原始像素尺寸的倍数）
中心      = (x · W播, y · H播)                位置 = 未缩放基准矩形的中心
TL0      = 中心 − B/2                         未缩放基准矩形左上角
A        = TL0 + (anchor_x · B.x, anchor_y · B.y)            锚点（缩放不动点）
TL       = A − (anchor_x · B.x · s.x, anchor_y · B.y · s.y)  缩放后左上角
S        = (B.x · s.x, B.y · s.y) = Cs × s × k               上屏尺寸
```

- 位置始终指**未缩放基准矩形**的中心；锚点非中心时，缩放会改变元素的**视觉**中心（同 CSS `transform-origin`）。默认 `anchor = (0.5, 0.5)` 时绕中心缩放，元素中心恒为 `(x·W播, y·H播)`——与现状 `ImageLayer` 一致。
- 取材先于缩放：先按 `source` 从素材裁出子矩形，再按 `scale` 放大到上屏尺寸（`Cs × s × k`）。
- 归一化坐标**不钳制**：越界（`<0` / `>1`）允许表达，元素按公式落到窗口外，由屏幕边界裁剪（§4.2）。
- `k` 只保证「基准构图整体」不溢出播放窗口；**单个元素可以按作者意图超出**（与编辑基准下同样的超出比例）——例：编辑时占基准宽 125% 的元素，播放时仍占窗口宽 125%（§4.2 例证）。

**基准尺寸逐类（定稿·2026-10-10，废除 ÷1920 折算口径）**：

| 类别 | 基准尺寸 B |
|---|---|
| 画面层 | 不适用：放置 = `dest` 矩形（缩放隐含在 dest / source 尺寸比；lane 画面按播放窗口实时渲染，`k = 1`） |
| 图形层 | `(裁切后素材像素) × k` —— 整幅时 = 原图像素 × k |
| 文本层 | `(font_scale 下文字块像素) × k` |
| 效果层 | 铺满窗口（位置 / 锚点 / 缩放不生效） |
| 画幅层 | 铺满窗口（黑边由目标画幅比与窗口决定，不参与该几何链） |

> **迁移等价性**：缺省 `base_resolution = 1920×1080` + 播放窗口 1920×1080（`k = 1`）+ `source` 整幅时，`scale = 1` 恰是素材 1:1 像素、位置 = 窗口百分比 —— 与 0.3.6 现状（旧「参考画布 1920×1080 + Fit」在 1920×1080 屏幕上）**逐像素一致**；差异只在 `fit`（旧默认 `fit` 在 `scale_x ≠ scale_y` 时会等比内缩，新口径直接拉伸，见 §4.2）。

**与相机侧合成参数（已落地）的关系**：同一参数族的两端 —— **两族放置统一为窗口参照，无换算**（2026-10-10 定稿，§4.2）。

- `source` / `opacity`：与相机侧（`camera-composition.md` §7 步骤 1，字段同名同形）语义**完全一致**（取材参照素材自身）。
- `dest`：相机侧的 `dest` 是**窗口（屏幕）归一化**目标矩形 —— 在新模型里就是「放置参照窗口」的直接表达，**不需要与任何画布口径换算**；lane 族的「缩放」隐含在 `dest` 尺寸 / `source` 尺寸之比（`scale_x = dest.w / source.w`），lane 画面每帧按播放窗口尺寸渲染，故 `k = 1`。
- `order`：两边都不是脚本字段（相机侧 = 轨道层级 → 轨道内 clip 顺序；覆盖层 = `z_index` 兜底）。

**与现状的差异（步骤 4 对齐；参照系已按 2026-10-10 口径更新）**：

| 项 | 现状（0.3.6 之前） | 定稿 |
|---|---|---|
| 位置默认 | `x`/`y` 缺省 `0`/`0`（schema + `OverlayTrackPlayer` 回退） | `0.5`/`0.5`（居中，“什么都不写就是合理形态”） |
| `opacity` 默认 | OVERLAY schema 缺省 `0`（不可见） | `1.0`（不透明，与相机侧合成参数一致） |
| `z_index` 默认 | schema `20` / 运行时回退 `10` / 内置类常量 0·10·20·30·40 | `10`（单一取值；letterbox 的 `z = 0` 是内置常量，不在脚本口径内） |
| 锚点 | 仅 `PipLayer` 有 `anchor_x/anchor_y`，且是**定位锚**（`x − width·anchor`）、像素坐标 | 统一为**缩放绕点**（元素自身归一化）；pip 已删除（§6） |
| 取材 | 无字段 | `source`（素材归一化，原点 = 素材左上角） |
| 缩放参照 | `ImageLayer` = 原图尺寸乘数；`SubtitleLayer` = 文字块百分比 | **素材原始像素尺寸的倍数**（文本层 = 文字块像素 × `font_scale` × 倍数） |
| 坐标口径 | `ImageLayer`/`SubtitleLayer` = 屏幕百分比；`PipLayer` = 原始像素 | **窗口百分比（唯一口径）** |
| 分辨率转义 | 无 | `meta.base_resolution` + 单一系数 `k`（§4.2） |
| 适配 | 无字段 | **无该参数**（2026-10-10 废除：元素框由素材派生，映射恒为铺满） |

> **落地状态（0.3.6 步骤 4，已完成 —— 基于 2026-10-07 画布口径）**：上表已落到代码 —— `overlay/CanvasTransform` 增加统一默认值常量与 `element(...)` 几何解算（浮点比例域，只在最终绘制落到像素）；`ImageLayer` / `SubtitleLayer` / `LetterboxLayer` / `FadeLayer` 按字段表读取；`OverlayTrackPlayer` 按字段表取值（含 `source` 分量线性、`fit` 步进）；`TrackSchemas.overlay()` 字段表对齐；`docs/SCRIPT_FORMAT.md` §9 与 `docs/AI_SCRIPTING_GUIDE.md` §3.5b 已同步。
>
> ⚠ **该落地实现的是 §4.1 参考画布口径，已被 2026-10-10 统一几何模型取代**：`CanvasTransform.canvas(...)`（画布 → 屏幕 Fit 映射）与 `fit` 消费在新口径下废除，`ImageLayer` / `SubtitleLayer` / `LetterboxLayer` / `FadeLayer` / `OverlayTrackPlayer` / `TrackSchemas` / `ScriptMeta` 需按 §4.2 重写 —— 逐项清单见 **§11**。在重写落地前，**代码与本文口径不一致**（以本文 §4.2 为准）。
>
> 落地时确认的两点边界（历史记录）：① `fit` 默认 `fit` 意味着 `scale_x ≠ scale_y` 的非等比缩放**不再拉伸素材**（等比放进元素框）——要旧的非等比拉伸需显式写 `"stretch"`；② 非 16:9 屏幕的画布留边（Fit 黑边区）由 `LetterboxLayer` 绘制，且仅在该层可见（LETTERBOX 轨有活跃 clip）时绘制。**① 随 `fit` 废除失效（新口径恒为拉伸，且这正是用户例证的语义：1600×900 ×(2, 0.5) = 3200×450）；② 随「画布 = 窗口」失效（无画布留边；非基准宽高比的留空是否用画幅层遮挡由作者决定）。**

> 现状对照（2026-10-10 复核，可验证）：`ImageLayer.render()` 现走 `CanvasTransform.canvas(screenWidth, screenHeight, FIT)` + `element(x, y, anchor, texW, texH, scale, canvas)` + `map(srcW, srcH, box, fit)`；`SubtitleLayer.render()` 同构（多一级 `fontScale` 矩阵缩放）；`LetterboxLayer.render()` 用 `canvas()` 画「画布留边」+ 目标画幅比黑边；`FadeLayer.render()` 用 `canvas()` 铺满画布；`OverlayTrackPlayer.updateLayer()` 读 `x/y/anchor_*/scale_*/source/font_scale/opacity` 并 `stepFit(...)` 读 `fit`。以上均为待重写项（§11）。

---

## 4. 设备无关性（方向·核心）

> **口径变更（2026-10-10）**：设备无关性的**实现方式**已由「参考画布 + Fit/Fill/Stretch 映射」改为「**窗口百分比 + 单一分辨率转义系数 `k`**」——**画布 = 窗口**（用户裁决，见 §4.2）。§4.1 保留为历史存档（标注已被取代）；本节其余部分（olive 的序列/适配证据）保留为「为什么要做设备无关」的依据。

要求：**不同设备、不同分辨率、不同宽高比下，构图一致**。全部用百分比是必要条件，但不充分——尺寸的像素基准（素材原始像素）会随播放分辨率变化。

剪辑软件的做法（参考 `example/editor/olive` 的 Transform 节点，源码 `app/node/distort/transform/transformdistortnode.{h,cpp}`）：序列有**固定分辨率/宽高比**（`class Sequence : public ViewerOutput`；分辨率来自 `ViewerOutput::GetVideoParams()` 返回的 `VideoParams`，含 `resolution()` / `pixel_aspect_ratio()`），变换先按序列归一化（`AdjustMatrixByResolutions()` 里 `adjusted_matrix.scale(2.0 / sequence_res.x(), 2.0 / sequence_res.y(), 1.0)` 把序列映射成 [-1,1] 方块），再适配到实际输出。

**方向（2026-10-10 定稿，取代原「定义参考画布」）**：**放置用窗口百分比**（与设备无关），**尺寸用素材像素 × 单一转义系数 `k`**（编辑基准分辨率 → 播放窗口），`k` 取 contain 式 `min`（比例保持 + 基准构图不溢出）。这样同一套参数在任何设备上得到同一个构图（同宽高比下逐像素一致；非基准宽高比下位置偏差 ≤ 留边厚度）。完整模型见 **§4.2**。

### 4.1 参考画布定稿（已定稿·2026-10-07）

> ⚠ **本节已被 2026-10-10「统一几何模型」（§4.2）取代，原文保留存档 —— 不要按本节实现。**
> **取代理由（用户裁决）**：① **画布 = 窗口**——参考画布把「非 16:9 / 非 1080p 设备」的构图强行折进一个固定宽高比的虚拟画布，再靠 Fit 映射回真实窗口，多了一层与设备无关性无关的间接层；② 用户的真实需求是「编辑基准分辨率 → 播放窗口的整体缩放」（非 16:9 / 非 1080p 设备上仍要正确），这正是**单一转义系数 `k`** 的直接语义，参考画布 + 三档映射是过度设计；③ 参考画布把「图形层基准尺寸 = 原图像素 ÷ 1920」写进模型（隐含 1080p 假设），与「缩放参照素材自身」冲突。**Fit / Fill / Stretch 的「画布映射」用途随之废除**；「素材 → 元素框」的 `fit` 三档同样废除（结论与理由见 §4.2.5）。

**取值**：宽高比 **16:9**（`CanvasTransform.REFERENCE_ASPECT_RATIO = 16/9`）；像素锚点 **1920×1080**（`REFERENCE_WIDTH` / `REFERENCE_HEIGHT`）——归一化 `(u,v)` 里 1 单位 = 1920×1080 像素。

**声明位置：全局常量**（`overlay/CanvasTransform`），**不进脚本 `meta`**。理由：

1. 参考画布是“同一套参数在任何设备上都是同一个构图”的**唯一基准**——若逐脚本声明，同一组 `(x, y)` 在不同脚本里构图不同，设备无关的保证就没了；
2. **最简**：不加 meta 字段 → 不动 schema / 校验 / 编辑器 / 脚本格式；
3. 它是框架级决定，不是作者级选择；将来若真需要（如 4:3 复古集），再加**脚本级可选覆盖**即可（缺省 = 全局常量），结构上没堵死。

**为什么 16:9 / 1920×1080**：与 `LetterboxLayer` 现有 `targetAspectRatio` 的用法、以及绝大多数实际输出（16:9 窗口）一致；1920×1080 让“图形层基准尺寸 = 原图像素 ÷ 参考分辨率”在参考分辨率下退化为 1:1 像素，**与现状 `ImageLayer` 逐像素等价**（迁移零语义漂移，见 §3.1）。

**映射公式**（素材 `srcW×srcH` → 目标框 `dstW×dstH`；画布 → 屏幕即 `src = 1920×1080`、`dst = 屏幕像素`，两者共用同一纯函数 `CanvasTransform.map`）：

```text
Fit:      s = min(dstW/srcW, dstH/srcH)          scaleX = scaleY = s
Fill:     s = max(dstW/srcW, dstH/srcH)          scaleX = scaleY = s
Stretch:  scaleX = dstW/srcW, scaleY = dstH/srcH

w = srcW·scaleX,  h = srcH·scaleY
offsetX = (dstW − w)/2,  offsetY = (dstH − h)/2      （Fit 偏移 ≥ 0 留边；Fill 偏移 ≤ 0 裁切；Stretch 偏移 = 0）

画布归一化 (u, v) → 屏幕像素：
  sx = offsetX + u·w
  sy = offsetY + v·h
```

**宽高比不一致的边界行为**（`A_s` = 屏幕宽高比）：

| 情形 | Fit | Fill | Stretch |
|---|---|---|---|
| `A_s > 16/9`（屏幕更宽） | **左右黑边**（pillarbox），画布高铺满 | 裁掉画布**上下** | 铺满，横向拉伸 |
| `A_s < 16/9`（屏幕更窄） | **上下黑边**（letterbox），画布宽铺满 | 裁掉画布**左右** | 铺满，纵向拉伸 |
| `A_s = 16/9` | 三者一致（无偏移、等比 `s = dstW/1920`） | 同左 | 同左 |

- Fit 的黑边区**不属于画布**：画布归一化坐标不会落在那里（越界坐标除外）；黑边由 `LetterboxLayer`（或画幅层）绘制，参考画布只提供坐标口径。`LetterboxLayer` 现有算法（`contentHeight = screenWidth / ratio`，`contentHeight < screenHeight` 时画上下黑边）**就是 Fit**——可直接喂 `REFERENCE_ASPECT_RATIO`。
- 归一化坐标**不钳制**：越界（`<0` / `>1`）允许表达，元素按公式落到留边区 / 屏幕外，由屏幕边界裁剪；Fill 下画布超出屏幕的部分同理被裁。
- 参考画布**不是渲染目标**：实际渲染仍在屏幕分辨率进行（不降分辨率，见 `multi-camera-rendering.md`）；画布只是参数口径。
- 需要贴边时按 §3.1 的几何公式（中心 + 尺寸/2）折算，与现状 `ImageLayer` 的做法一致。

**代码承载（历史）**：`common/src/main/java/com/immersivecinematics/immersive_cinematics/overlay/CanvasTransform.java`（常量 + `FitMode` + `map` / `canvas` / `screenX` / `screenY` 纯函数；不引用任何 Minecraft 类、不做渲染）。冒烟验证（各宽高比 × 三档，归一化 → 像素逐点核对，含退化入参与不变量）通过。**该承载的参考画布部分按 §4.2 处置（§11.1）。**

### 4.2 统一几何模型定稿（已定稿·2026-10-10；取代 §4.1）

> **依据（用户裁决）**：`plans/0.3.6/implementation-progress.md`「用户裁决新增（2026-10-08）」表 ——「几何口径」行（2026-10-10）：① 取材 `source` 与缩放 `scale_x/scale_y` 的参照 = **素材自身**；② 放置位置的参照 = **窗口**（百分比）；③ **画布 = 窗口**（废 16:9 参考画布 + Fit 映射）；④ 分辨率转义 = 编辑基准分辨率 → 播放窗口的整体缩放；⑤ 脚本保持标准字段写法（x/y/w/h），起始/结束点等便捷写法归编辑器「一键优化」。
>
> **该行两条「待定」在本节收口**：**编辑基准分辨率落点 = `meta.base_resolution`**（每脚本声明，缺省 1920×1080）；**转义系数溢出口径 = 单一 contain 系数 `k = min(W播/W基, H播/H基)`**（宽高共用，见 §4.2.3）。
>
> **分层归属（2026-10-10 用户重申；通用原则「运行时只读只执行」）**：运行时只做三件事 —— ① **定义**（机制 / 模型 / 字段语义 = 本节）② **按定义解析**（`ScriptParser` / `ScriptValidator` / `schema/`）③ **按规则播放**（几何解算 = 解释器把脚本参数应用到画面；**`k` 系数计算属播放求值，允许在运行时**）。**禁止进运行时**：起始 / 结束点便捷写法解析、像素输入 → `scale` 换算、一键优化、任何生成 / 模板 / 调配运算、**任何改写脚本数据的行为** —— 全部归编辑器；编辑器 = 生成固定格式脚本，产物与手写脚本等价（登记见 §5）。

#### 4.2.1 模型表

| 量 | 参照 | 口径 | 载体 |
|---|---|---|---|
| 取材 `source = {x,y,w,h}` | **素材自身** | 素材归一化 `0~1`，原点 = 素材**左上角** `(0,0)`（x 向右、y 向下）；裁出区域 = `(x·素材宽, y·素材高, w·素材宽, h·素材高)` | 关键帧字段 |
| 缩放 `scale_x` / `scale_y` | **素材自身** | 相对**素材原始像素尺寸**的倍数（`2` = 两倍宽；例：素材 1600×900、`(2, 0.5)` → 处理后 3200×450） | 关键帧字段 |
| 位置 `x` / `y` | **窗口** | 窗口百分比 `0~1`（元素中心 / 锚点规则不变，不钳制） | 关键帧字段 |
| 锚点 `anchor_x` / `anchor_y` | **元素自身** | 归一化 `0~1`，缩放绕点（语义不变） | 关键帧字段 |
| 分辨率转义 `k` | 编辑基准 → 播放窗口 | `k = min(W播/W基, H播/H基)`，**宽高共用单一系数** | **派生量**（不写脚本） |
| 编辑基准分辨率 | `meta` | `meta.base_resolution = {w, h}`（像素，正整数），缺省 `1920×1080` | **meta 字段** |
| 不透明度 / 顺序 | — | 不变（§3.1） | 关键帧 / clip 字段 |

**「素材」逐类**：图形层 = 纹理实际像素（`TextureLoader.getTextureSize`）；文本层 = 文字块像素（`font_scale` 下）；画面层（lane）= 每帧按播放窗口尺寸渲染的 lane 画面（§4.2.4）；效果层 / 画幅层 = 无素材（铺满窗口）。

#### 4.2.2 上屏公式（像素）

```text
W播 × H播 = 播放窗口尺寸（帧缓冲，物理像素）
W基 × H基 = meta.base_resolution（缺省 1920×1080）
k        = min(W播 / W基, H播 / H基)

Cs       = (source.w · 素材宽, source.h · 素材高)          裁切后素材像素
上屏尺寸  = Cs × (scale_x, scale_y) × k                     像素
上屏位置  = (x · W播, y · H播)                              元素中心（锚点 = 元素自身归一化，缩放绕点）
```

完整元素几何 = §3.1 的四式（`B = Cs × k`：`TL0 = 中心 − B/2` → `A = TL0 + anchor·B` → `TL = A − anchor·B·s` → `S = B·s`）。

- **锚点语义不变**：位置 = 未缩放基准矩形（`B`）的中心；`anchor` 是 `B` 上的归一化点，缩放绕它进行（`anchor = 0.5` 时元素中心恒为 `(x·W播, y·H播)`，同 CSS `transform-origin`）。
- **实现注（绘制空间）**：覆盖层在 **GUI 缩放空间**绘制（`ClientEventHandler` 传 `getGuiScaledWidth/Height`），lane 合成在 **帧缓冲空间**（`LaneCompositor` 直接对主 framebuffer 画）。位置是百分比 → 两空间同值；尺寸是像素 → 覆盖层内的有效系数 = `k / guiScale`（等价于在该空间用 `min(W空间/W基, H空间/H基)`，两者至多差 1px 舍入）。**`k` 的定义、推导与验收一律用帧缓冲尺寸**（§4.2.3），换算只发生在绘制那一步。

#### 4.2.3 `k` 的性质推导与边界

1. **比例保持（单系数）**：`k` 是标量，宽高同乘 → 素材宽高比与元素之间的相对尺寸关系不被扭曲（这正是「宽高共用单一系数」的意义）。
2. **不溢出（contain 式）**：`k = min(...)` → `W基·k ≤ W播` 且 `H基·k ≤ H播` —— **基准构图**（编辑基准分辨率下的整幅画面）整体映射为 `W基·k × H基·k`，完整可见、不溢出播放窗口。
3. **非基准宽高比 → 单边留空**：`W播 − W基·k`（左右）或 `H播 − H基·k`（上下）恰有一个 > 0，即基准构图在窗口内居中后一侧留空。**模型本身不画任何东西**：该区域显示下层内容；作者要用黑边遮挡时由画幅层（`aspect_ratio`）表达。
4. **位置 / 空间关系近似保持（位置百分比不受 `k` 影响）**：位置直接用窗口百分比，不乘 `k`。与「基准构图的纯等比映射」相比，偏差 = `留边厚度 × (2·位置百分比 − 1)`——以纵向留边 `bar = (H播 − H基·k)/2` 为例：纯映射下 `y` 的像素 = `bar + y·H基·k`，模型取 `y·H播`，差 = `bar·(2y − 1)`：**中心为 0、边缘最大 = 留边厚度**，即空间关系近似保持。
5. **退化与边界**：
   - `base_resolution` 缺省 → `1920×1080`；`w/h` 非正整数（0 / 负 / 非整数 / 缺分量）→ **`ScriptValidator` 拒绝**（§11.6）；`ScriptParser` 兜底告警 + 回落缺省（防御式，与 `macro_loop_count` 同风格）。
   - 窗口尺寸 ≤ 0（理论不可达）→ 该帧不绘制（沿用现有守门：绘制前判退化）。
   - 素材无内在尺寸（效果层 / 画幅层）→ 不参与该链（铺满窗口）。
   - 播放窗口宽高比 = 基准宽高比 → `k = W播/W基 = H播/H基`，无留空。
   - `k` 的计算尺寸 = **窗口帧缓冲（物理像素）**；覆盖层绘制空间的换算见 §4.2.2 实现注。
6. **与旧口径（§4.1）的关系**：播放窗口宽高比 = 基准宽高比时**逐像素一致**（旧「画布 Fit」≡ 新 `k`，位置口径也相同）；不一致时差异只有「位置参照」——旧 = 画布百分比 + 留边偏移，新 = 窗口百分比，偏差 ≤ 留边厚度（第 4 条）。

#### 4.2.4 两族放置的统一表述

| 族 | 取材（参照素材） | 缩放 | 放置（参照窗口） |
|---|---|---|---|
| **lane 族**（画面层） | `source` = lane 画面内归一化矩形（原点左上角） | **隐含**：`scale_x = dest.w / source.w`、`scale_y = dest.h / source.h`（归一化域同式） | `dest` = 窗口归一化矩形；`k = 1`（lane 画面每帧按播放窗口尺寸渲染，无编辑基准错位） |
| **素材族**（图形 / 文本） | `source` = 素材归一化矩形（原点左上角） | `scale_x` / `scale_y` = 相对素材原始像素尺寸的倍数 | `x` / `y` = 窗口百分比（+ `anchor`）；尺寸另乘 `k` |

两族都满足「**取材 / 缩放参照素材自身，放置参照窗口**」；lane 族不需要换算到任何画布口径（§3.1 的 `dest` 条目即此结论）。

> 备忘：若将来给 lane 引入渲染分辨率降级（< 窗口尺寸），那等价于「素材像素 ≠ 播放像素」，需按同一 `k` 口径补偿 —— 不在本轮。

#### 4.2.5 「素材 → 元素框的适配（`fit`）」为何废除

- **旧口径**：元素框 = 逐类基准 × `scale`，素材子矩形再按 `fit`（fit / fill / stretch）铺进框 —— `fit` 是「框与素材宽高比不一致」时的调节旋钮。
- **新口径**：元素框**由素材派生**（`Cs × scale × k`），素材恒按框铺满 —— 宽高比由 `scale_x : scale_y` 直接表达（用户例证：1600×900 ×(2, 0.5) = 3200×450 就是拉伸），**没有独立的框可适配**。
- **结论：`fit` 字段与 `FitMode` / `map()` 一并废除。** 理由：① 新模型下它无处施加（唯一可能的取值 = 旧 `stretch`）；② 保留 = 第二套几何路径，与「元素框由素材派生」的定义冲突；③ 旧 `fit = fit` 的「等比内缩」能力不丢失 —— 作者写 `scale_x = scale_y` 即等比，位置独立可调。
- **迁移影响**：写死 `fit` 的旧脚本，`fit = "stretch"` 语义不变；`fit = "fit" / "fill"` 的旧脚本需重写（改为等比 `scale` 或调整 `scale_x/scale_y`）。`fit` 是 0.3.6 内新增、尚未发布的字段，**无存量脚本负担**。

#### 4.2.6 例证（推演）

**例 1 —— 基准宽高比（用户例证：k 由窗口与基准共同决定）**

| 项 | 值 |
|---|---|
| 素材 | `element.png` 1600×900 |
| 脚本 | `source = {0,0,1,1}`（整幅）、`scale_x = 2`、`scale_y = 0.5`、`x = 0.5`、`y = 0.5`、`anchor = (0.5, 0.5)` |
| `meta.base_resolution` | 2560×1440 |
| 播放窗口 | 1920×1080 |

```text
① 素材自身链（与播放窗口无关）：1600×900 × (2, 0.5) = 3200 × 450        ← 编辑域像素
② k = min(1920/2560, 1080/1440) = min(0.75, 0.75) = 0.75
③ Cs = 1600 × 900（整幅）→ B = Cs × k = 1200 × 675（scale = 1 时的上屏尺寸）
④ 上屏尺寸 = B × (2, 0.5) = 2400 × 337.5 px
⑤ 上屏位置：中心 = (0.5×1920, 0.5×1080) = (960, 540)
            左上角 = (960 − 2400/2, 540 − 337.5/2) = (−240, 371.25)    ← 左右各溢出 240px
⑥ 占比核对：宽 2400/1920 = 125% ＝ 编辑时 3200/2560 = 125% ✓
            高 337.5/1080 = 31.25% ＝ 编辑时 450/1440 = 31.25% ✓
```

→ 跨分辨率后**构图比例完全一致**（含溢出比例）；`k` 只保证基准构图整体不溢出，单个元素按作者意图超出。

**例 2 —— 非基准宽高比（单边留空 + 位置近似）**

同一脚本，播放窗口改 1920×1200（16:10）：

```text
① k = min(1920/2560, 1200/1440) = min(0.75, 0.8333…) = 0.75     ← 由宽度决定
② 基准构图映射 = 2560×0.75 × 1440×0.75 = 1920 × 1080
   窗口高 1200 → 上下各留空 (1200 − 1080)/2 = 60 px（模型不画；黑边由画幅层决定）
③ 元素上屏尺寸不变：2400 × 337.5（k 未变）；中心 = (960, 600)（窗口正中）
④ 位置偏差（vs 纯等比映射）：y = 0.5  → 60×(2×0.5 − 1) = 0（中心无偏差）
                            y = 0.25 → 模型 300px，纯映射 60 + 0.25×1080 = 330px
                                       偏差 −30px = 60×(2×0.25 − 1) ✓（≤ 留边厚度 60）
```

**例 3 —— k 由宽度决定（4:3 播放窗口）**

`base_resolution = 1920×1080`，播放窗口 1600×1200：

```text
① k = min(1600/1920, 1200/1080) = min(0.8333…, 1.111…) = 0.8333…  ← 由宽度决定
② 基准构图映射 = 1600 × 900 → 窗口高 1200，上下各留空 150 px
③ 素材 1600×900、source 整幅、scale = (1, 1) → 上屏 1333.33 × 750 px
   宽占比 1333.33/1600 = 83.33% ＝ 编辑时 1600/1920 ✓
```

---

## 5. 编辑器（方向）

编辑器的主形态是**参数编辑 + 脚本生成**（见[编辑器 WebUI 迁移](./editor-webui-migration.md)）：本节这些参数在编辑器里首先是**可填写的字段**。**编辑器只生成固定格式脚本，产物与手写脚本等价**（通用原则「运行时只读只执行」，§4.2 分层归属）。

**编辑器阶段登记（几何口径相关，本轮不做）**：

1. **「一键优化」便捷写法 → 标准字段**（用户裁决 ⑤）：起始 / 结束点写法（作者画布上拉一个区域 → 换算成 `source {x,y,w,h}`）、**像素输入 → `scale` 换算**（作者填「我要 3200×450」→ 编辑器按素材原始尺寸反解 `scale_x/scale_y`）、对齐 / 吸附等。**全部是编辑器侧运算，产物写回脚本仍是标准字段**；运行时不做任何便捷写法解析或换算。
2. **`meta.base_resolution` 表单**：脚本属性面板增加编辑基准分辨率字段（缺省 1920×1080），带「当前窗口尺寸」一键填入。
3. **预览画布按 `k` 显示**：编辑器预览需按「预览尺寸 / `base_resolution`」算 `k` 渲染，保证预览与实际播放一致（现有 `WebPreviewScreen` 帧推流为固定 16:9 720p，预览侧换算归编辑器）。
4. **Gizmo**（可选增强）：在预览画布上直接拖拽 / 缩放 / 裁切每个覆盖层，与参数面板双向同步 —— 拖拽结果同样只产出标准字段。

（现有 `WebPreviewScreen` 的帧推流通道可作为预览画布的基础；具体形态执行时定。）

---

## 6. 与现有 pip 层的关系（方向）

现有 `pip` 层是“静态占位框（2px 白框 + 半透明黑填充，无纹理）+ 原始像素坐标”。方向：**并入本文模型**（百分比 + 锚点 + 取材 + ~~适配~~），或者由画面层取代——画面层本身就可以放在任意位置，天然是画中画。（「适配」已于 2026-10-10 废除，见 §4.2.5。）

> **步骤 4 已做（0.3.6）**：pip 已并入统一模型 —— 位置 / 锚点改为画布归一化（`anchor_*` 从「定位锚」改为「缩放绕点」），尺寸改由 `scale_x/scale_y` 表达（画面层基准尺寸 = 铺满画布，`0.5` = 半个画布），旧的像素 `width`/`height` 字段移除；边框粗细也从写死 2px 改为按画布高比例（`2/1080`，参考分辨率下即 2px）。仍无纹理，故不消费 `source` / `fit`。是否由画面层取代（多相机 lane 作为覆盖层）属步骤 3。

> **裁决（2026-10-08，CLEANUP-2）**：走「由画面层取代」——`pip` 层**已删除**（0.3.6 起，被 lane 取代）。画中画 = 一条 CAMERA lane 的合成参数（`dest` / `source` / `opacity`），即 [画面合成](./camera-composition.md) 的特例；`OverlayTrackPlayer` 的 `pip` 分支与 `layer_type` 枚举 / 校验同步移除（现为 `fade` / `image` / `subtitle`）。**WebUI 里对脚本片段的便捷操作留编辑器阶段。**

---

## 7. 可能的问题

> 已解决项不再列出：宽高比不一致的留空策略 → §4.2.3；每类默认参数 → §3.1。

- **画面层在 z 序里的基线**：它应该在 letterbox **之下**（现在 letterbox 的 z=0 已是最小，需要给画面层留更低的基线）。（未决·与几何口径无关）
- ~~素材没有内在尺寸时（纯色遮罩、文本），“取材”的语义。~~ **已定（2026-10-10）**：文本层**有**内在尺寸（文字块像素，`font_scale` 下，§4.2.1「素材逐类」）；效果层 / 画幅层无素材 → **不消费 `source`**（铺满窗口），`source` 对它们无意义。
- ~~取材超出素材边界时的行为（钳制 / 黑 / 循环）。~~ **已定（2026-10-10）**：**钳制到素材内**（现状 `ImageLayer` 的 `clamp01` 口径：`x/y` 钳到 `0~1`，裁出区域按 `x+w ≤ 1`、`y+h ≤ 1` 截断）；裁到 0 宽 / 0 高 → 该层当帧不绘制。无黑边 / 循环语义。
- 旋转是否纳入：不纳入的话锚点的意义减半，但第一版可以只服务缩放。（未决·延后）
- 性能：小尺寸画面是否按显示尺寸渲染（现计划写的是“正式合成不降分辨率”）。（未决·与几何口径无关）

---

## 8. 待定

- ~~取材的边界语义。~~ **已定（2026-10-10）**：钳制到素材内、裁到 0 不绘制（见 §7）。
- 与 HUD / letterbox 的叠放顺序细节。（未决·与几何口径无关）
- ~~pip 层是被取代还是升级。~~ **已定（2026-10-08，CLEANUP-2）**：pip 层已删除，被 lane 取代（画中画 = lane 合成参数 `dest`/`source`/`opacity`）；WebUI 便捷操作留编辑器阶段。
- ~~相机侧 `dest`（屏幕归一化口径，已落地）与画布归一化口径的换算（步骤 3–5）。~~ **已定（2026-10-10）**：**无换算** —— 两族统一为窗口参照（lane 的 `dest` 保持屏幕（窗口）归一化矩形，`source` 参照 lane 画面，缩放隐含在 dest/source 尺寸比，`k = 1`），见 §3.1「与相机侧合成参数的关系」与 §4.2.4。
- **待拍板：画幅层在「目标画幅比窄于窗口」时是否补左右黑边。** 现状 `LetterboxLayer` 只处理「目标画幅比宽于窗口 → 上下黑边」（`contentHeight = W播 / ratio < H播`）；`ratio < 窗口宽高比`（如 16:9 窗口设 `aspect_ratio = 1.0`）时 `contentHeight > H播`，当前**不画任何东西**（露下层画面）。候选：**A 补左右黑边**（遮幅语义对称、作者预期一致）／**B 保持现状**（`aspect_ratio` 只表达「上下电影黑边」，不做 pillarbox）。倾向 **A**（补齐对称语义），但**不属几何口径重写范围**，可独立小任务落地。
- **待拍板（低优先）：`base_resolution` 的校验上限取值。** 倾向 `≤ 16384`（覆盖 8K 宽，超出视为笔误；§11.6 按此实现）。

---

## 9. 落地顺序（方向）

1. ✅ 定义覆盖层统一参数与“基准尺寸”表 —— 定稿见 §3.1（2026-10-07；参照系 2026-10-10 更新）
2. ⛔ **参考画布与设备适配规则** —— 2026-10-07 定稿（§4.1）→ **2026-10-10 被「统一几何模型」取代**（§4.2：画布 = 窗口 + 单一转义系数 `k`）
3. 画面层接入（多相机 lane 作为覆盖层的一类）
4. ✅ 现有 overlay 层按统一参数对齐（含 pip 去像素化）—— 已落地（2026-10-07），但**基于旧画布口径**；**待重写为 §4.2 口径**（逐项清单见 §11）
5. 取材（`source`）补齐到所有类别（图形层已完成；文本层待接入；**适配已废除**）
6. 编辑器：Gizmo + 一键优化（像素输入 → `scale`、起始 / 结束点写法）+ `base_resolution` 表单 + 预览按 `k`（§5 编辑器阶段登记）

---

## 10. 相关文档

- [画面合成](./camera-composition.md)
- [多相机渲染](./multi-camera-rendering.md)
- [并行播放](./parallel-playback.md)
- [脚本模型](./script-model.md)
- [Overlay 颜色遮罩](./overlay-color-mask.md)

---

## 11. 几何口径重写：代码改动清单（2026-10-10；只登记，不改代码）

> **范围**：把 0.3.6 步骤 4 的「参考画布」实现（§4.1）改为 §4.2 的「统一几何模型」。**运行时只做定义 / 解析 / 播放求值**（`k` 计算属播放求值）；**不做任何便捷写法解析、像素换算、脚本改写**。可拆为两个串行编码任务：**任务一 = 11.1–11.5（几何与图层）**，**任务二 = 11.6（meta 字段与校验）+ 11.7–11.8（文档 / 编辑器登记）**。
> **共同验收**：`sh gradlew compileJava` 通过；一次性冒烟（不入库）逐点核对 §4.2.6 三例；**1920×1080 播放窗口 + 缺省 `base_resolution` + `source` 整幅** 下与重写前逐像素一致（回归基线）。

### 11.1 `overlay/CanvasTransform.java`（核心处置）

| 动作 | 内容 |
|---|---|
| **删除** | 参考画布常量 `REFERENCE_ASPECT_RATIO` / `REFERENCE_WIDTH` / `REFERENCE_HEIGHT`；画布映射 `canvas(...)` / `screenX(...)` / `screenY(...)`（画布 = 窗口后为恒等，无消费者）；`FitMode` / `fitMode(...)` / `map(...)` / `Placement`（素材 → 元素框的适配，§4.2.5 已废除） |
| **保留** | `Rect`；默认值常量 `DEFAULT_POSITION` / `DEFAULT_ANCHOR` / `DEFAULT_SCALE` / `DEFAULT_OPACITY` / `DEFAULT_Z_INDEX`（注释改为「窗口百分比 / 素材倍数」口径） |
| **改造** | `element(...)`：基准入参由「参考像素 + 画布 `canvasW/1920` 换算」改为**已裁切素材像素 × k 的直接像素基准**（去掉 `unitX/unitY` 与 `canvas` 参数，位置改 `x·W播` / `y·H播`）；新增纯函数 `resolutionEscape(wBase, hBase, wPlay, hPlay)`（`min`，任一 ≤ 0 → 0，调用方按退化不绘制处理） |

**验收**：`javac -encoding UTF-8` 单文件编译；冒烟断言 —— `k`（等比 / 由宽决定 / 由高决定 / 退化 ≤ 0）；`element` 在 `anchor = 0/0.5/1` 的不动点、`scale = 1` 退化为「中心 − B/2」、`k = 1` 时与旧公式等价；全仓 `grep` 无 `canvas(` / `screenX` / `screenY` / `FitMode` / `map(` 残留调用。

### 11.2 `overlay/ImageLayer.java`

- `render()`：删除 `CanvasTransform.canvas(...)` / `map(srcW, srcH, box, fit)` / scissor 分支（不再有「素材铺进框」的溢出）；改为 `k`（§4.2.2 实现注）→ `B = (srcW·k, srcH·k)` → `element(x, y, anchor, B.x, B.y, scaleX, scaleY)` → 绘制尺寸 = `B × scale`（一次 `round`）；`blit` 目标尺寸即该值，纹理采样仍取 `source` 子矩形。
- 删除 `fit` 字段与 `setFit(...)`。
- **验收**：§4.2.6 例 1（1600×900、`(2, 0.5)`、base 2560×1440、窗口 1920×1080）日志核对 `pos=(−240, 371.25)`、`size=2400×337.5`；`k = 1` + `source` 整幅与重写前逐像素一致。

### 11.3 `overlay/SubtitleLayer.java`

- 文字块基准 = `(maxLineWidth, totalHeight) × font_scale × k`；`element(...)` 用该基准；绘制端 `pose.scale(fontScale · scaleX · k, fontScale · scaleY · k, 0)` —— **几何框与绘制尺寸必须同乘 `k`**（否则锚点 / 位置错位）。
- **验收**：`k = 1` 与重写前逐像素一致；`k = 0.75` 时文字块尺寸 ×0.75、位置仍为窗口百分比；MC 透明度补全坑（`alpha < 4` 跳过）保持不变。
- 备注：文本物理尺寸还受原版 GUI 缩放影响（`getGuiScaledWidth` 口径），`k` 只保证「相对基准分辨率」的比例 —— 属原版机制，不在本模型内。

### 11.4 `overlay/LetterboxLayer.java` / `overlay/FadeLayer.java`

- `LetterboxLayer`：删除 `canvas(...)` 与「画布留边」分支（画布 = 窗口，无留边）；黑边直接按窗口算 `contentHeight = screenWidth / targetAspectRatio`，`contentHeight < screenHeight` 时画上下两条（与旧算法同式、去掉画布层）。**不乘 `k`**（黑边由窗口与目标画幅比决定）。`ratio` 窄于窗口时的左右黑边见 §8 待拍板（本轮保持现状）。
- `FadeLayer`：`canvas(...)` → 直接 `fill(0, 0, screenWidth, screenHeight, argb)`（铺满窗口）；**不乘 `k`**。
- **验收**：16:9 窗口 + `ratio = 2.35` 的黑边高度 = `round((H − W/2.35)/2)`，与重写前逐像素一致；非 16:9 窗口下 fade 铺满整窗（旧实现只铺画布区）。

### 11.5 `script/OverlayTrackPlayer.java`

- 删除 `stepFit(...)` 与 `il.setFit(...)`；`x/y/anchor_*/scale_*/source/opacity/z_index` 取值逻辑不变（参照系改名不影响取值）。
- **把 `base_resolution` 送到层**：层在渲染时需要 `W基/H基`。落点建议 —— `ScriptPlayer.getScript().getMeta()` 取当前脚本 meta（`ScriptPlayer` 已有 `getScript()`），`OverlayTrackPlayer` 创建层时调用 `setBaseResolution(w, h)`；层内保存（缺省 1920×1080）。**不在层里查脚本 / 不改写任何脚本数据**。
- **验收**：脚本不写 `base_resolution` → 层收到 1920×1080；写了 → 收到声明值；`fit` 写进脚本 → 校验器拦下（§11.6）。

### 11.6 字段表与校验：`meta.base_resolution` + 废除 `fit`（`script/`）

| 文件 | 改动 |
|---|---|
| `script/schema/MetaSchemas.java` | 新增 `base_resolution`（`object`，缺省 `null` = 1920×1080），`section = "info"`；形态取复合对象 `{"w": …, "h": …}`（与 `dest` / `source` / `position` 的复合值写法一致），不用数组 / 字符串。**注**：本仓 meta 字段表在 `MetaSchemas`（`TrackSchemas` 只管逐轨字段），任务书按此落点 |
| `script/ScriptMeta.java` | 新增字段 + getter（`int getBaseWidth()` / `getBaseHeight()`，缺省 1920×1080；构造签名同步） |
| `script/ScriptParser.java` | `parseMeta` 读可选 `base_resolution`：缺省 → 1920×1080；非法（非对象 / 缺 `w`/`h` / 非正整数）→ `ErrorLog.log("Parse", …)` 告警 + 回落缺省（防御式，同 `macro_loop_count` 风格） |
| `script/ScriptValidator.java` | ① meta 段新增校验：`base_resolution` 必须是对象且含正整数 `w` / `h`（`> 0`、整数、上限 `16384`），否则 `issues.add(...)` → **脚本被拒**（口径：≤0 / 非法 → validator 拒）。② OVERLAY 分支新增 **`fit` 废除拦截**（关键帧里出现 `fit` → `issues.add("fit 已废除（元素框由素材派生，§4.2.5），请删除或改用 scale_x/scale_y")`，参照 `checkRemovedScopeLane` 的既有先例） |
| `script/schema/TrackSchemas.java` | `overlay()`：删除 `fit` 关键帧字段（`kfs.put("fit", …)`）与其注释；`scale_x/scale_y`、`x/y` 的注释改为「素材原始像素倍数 / 窗口百分比」口径（`source` 注释注明原点 = 素材左上角） |

- **验收**：`/icinematics validate`（与 `ScriptValidator` 同源）逐例核对 —— meta：① 缺省 ② 合法 `{2560,1440}` ③ `w = 0` ④ `w = -1` ⑤ 缺 `h` ⑥ `w = 1.5` / 超上限；OVERLAY：⑦ 关键帧写 `fit` → 报错、⑧ 不写 `fit` → 通过；`ScriptParser` 冒烟确认缺省 1920×1080 与声明值均落到 `ScriptMeta`；编辑器离线 schema（`SchemaExporter` 导出物）自动带出新字段 / 去掉 `fit`。

### 11.7 `client/lane/LaneCompositor.java` / CAMERA 的 `dest`

- **不动**（结论）：`dest` 已是**窗口归一化矩形**（= §4.2 的放置口径）、`source` 已是 **lane 画面归一化矩形**（= 素材口径）；lane 纹理每帧按主画面（播放窗口帧缓冲）尺寸渲染（`LaneRenderer` 用 `mc.getMainRenderTarget().width/height` 建 FBO）→ 无编辑基准错位，**不乘 `k`**；缩放隐含在 dest/source 尺寸比。CAMERA 轨**不新增** `scale` / `base_resolution` 相关字段。
- **验收**：无代码改动；既有合成冒烟（`cinematics/tests/camera/test_compositing_params.json`）结果不变。

### 11.8 文档回写（`docs/`，与代码同批）

| 文件 | 改什么 |
|---|---|
| `docs/SCRIPT_FORMAT.md` §9（Keyframe 字段） | 坐标口径「参考画布百分比」→ **窗口百分比**；`scale` → **相对素材原始像素尺寸的倍数**；删除 `fit` 行；新增 `meta.base_resolution` 说明与 `k` 的播放期行为 |
| `docs/AI_SCRIPTING_GUIDE.md` §3.5b | 同 §9（含「非 16:9 留空由画幅层决定」「不再有画布留边」） |
| `docs/modules/overlay.md` | `CanvasTransform` 描述（删参考画布 / FitMode / map，改为 `element` + `resolutionEscape`）；各层坐标口径；letterbox 删除「画布留边」 |
| `docs/modules/script.md` | `OverlayTrackPlayer` 字段表描述（删 `fit`）、`ScriptMeta` / `meta` 字段（加 `base_resolution`） |
| `docs/modules/camera.md` | 若描述 `dest` / `source` 口径，补「放置参照窗口、无画布换算」（本轮核查未见相关段落，按实际补） |

### 11.9 编辑器阶段（不写运行时实现，仅登记）

见 §5「编辑器阶段登记」：一键优化（像素输入 → `scale`、起始 / 结束点写法）、`base_resolution` 表单、预览按 `k`、Gizmo。**产物 = 标准字段脚本，与手写脚本等价。**

---

## 事实核查（2026-10-07）

> 依据：本仓库 `common/src/main/java/com/immersivecinematics/immersive_cinematics/`（overlay / script / webui）、`example/editor/olive/app/`（Transform / Crop 节点、shader、renderer）、以及 `plans/0.3.6/` 内已存在的旁证文档。每条给到文件与符号。

### ① 核实为真

1. **§2**“`overlay/` 下已有 Letterbox / Fade / Image / Subtitle / Pip 五类实现”——`overlay/` 下 `LetterboxLayer.java`、`FadeLayer.java`、`ImageLayer.java`、`SubtitleLayer.java`、`PipLayer.java` 均存在且 `implements OverlayLayer`；`OverlayLayer.java` 为接口。
2. **§3**“`ImageLayer` 的 `x`/`y` 已是屏幕百分比、元素中心”——`ImageLayer.render()`：`float actualX = x * screenWidth - dispW / 2f; float actualY = y * screenHeight - dispH / 2f;`；字段注释“屏幕百分比位置（0~1，元素中心）”。
3. **§3**“`scaleX`/`scaleY` 是相对原图尺寸的百分比乘数”——`ImageLayer.render()`：`float dispW = texSize[0] * scaleX; float dispH = texSize[1] * scaleY;`，`texSize` 来自 `TextureLoader.getTextureSize(fileName)`；`setScale()` 注释“1 = 原尺寸”。
4. **§3**“`SubtitleLayer` 同构”——`SubtitleLayer.render()`：`blockX = x * screenWidth - (maxLineWidth * fontScale * scaleX) / 2f;`（x/y 屏幕百分比 + 文字块中心、scale 百分比乘数）。
5. **§3**“`PipLayer` 有 `anchorX`/`anchorY`，但坐标用的是原始像素”——`PipLayer.render()`：`float actualX = x - width * anchorX; int ix = (int) actualX; guiGraphics.fill(ix, iy, ix + iw, iy + ih, FILL_COLOR);`，x/y/width/height 直接当像素用、不乘屏幕尺寸；`setAnchor(anchorX, anchorY)`，字段默认 `0.5f`。
6. **§4**“序列有固定分辨率/宽高比”——`example/editor/olive/app/node/project/sequence/sequence.h:32` `class Sequence : public ViewerOutput`；`app/node/output/viewer/viewer.h:64` `VideoParams GetVideoParams(int index = 0) const`、`:99` `void SetVideoParams(...)`、`:197` `static const QString kVideoParamsInput`（`viewer.cpp:29` 值为 `"video_param_in"`）；`app/render/videoparams.h:87` `QVector2D resolution()`、`:92` `square_resolution()`、`:182` `pixel_aspect_ratio()`。
7. **§4**“`scale(2.0 / sequence_res)` 把序列映射成 [-1,1] 方块”——`app/node/distort/transform/transformdistortnode.cpp:296-297`：注释 `// Scale it to a square based on the sequence's resolution` + `adjusted_matrix.scale(2.0 / sequence_res.x(), 2.0 / sequence_res.y(), 1.0);`。佐证 [-1,1]：`app/render/opengl/openglrenderer.cpp:34-37` 的 `blit_vertices` 即 `(-1,-1) (1,-1) (1,1) …` 的 NDC 方块，`app/shaders/default.vert:9` `gl_Position = ove_mvpmat * a_position;`；矩阵链为 `scale(2/seq_res) · translate(offset) · mat · scale(tex_res*0.5)`（Qt 后乘、最外层最后作用于向量）→ 序列像素空间被映到 [-1,1] 方块。
8. **§4**“Fit / Fill / Stretch 三档”——`transformdistortnode.h:62-66` `enum AutoScaleType { kAutoScaleNone, kAutoScaleFit, kAutoScaleFill, kAutoScaleStretch };`；`transformdistortnode.cpp:80` `SetComboBoxStrings(kAutoscaleInput, {tr("None"), tr("Fit"), tr("Fill"), tr("Stretch")});`；分支 `:309-333`（Stretch 用 `scale(sequence_res/texture_res)`，Fit/Fill 用 `scale_by_x`/`scale_by_y` 单一系数）。
9. **§4**“取材四边 0~1 归一化（crop 节点 `CreateCropSideInput`）”——`app/node/distort/crop/cropdistortnode.cpp:152-158` `CreateCropSideInput()`：`AddInput(id, NodeValue::kFloat, 0.0)` + `SetInputProperty(id, "min", 0.0)` + `"max", 1.0` + `"view", FloatSlider::kPercentage`；`UpdateGizmoPositions` `:109-112` `left_pt = resolution.x() * row[kLeftInput].toDouble()`（边值 × 分辨率）；shader `app/shaders/crop.frag` 拿 `ove_texcoord`（0~1）与 `left_in` / `1.0-right_in` / `top_in` / `1.0-bottom_in` 比较。
10. **§5**“现有 `WebPreviewScreen` 的帧推流通道”——`common/.../webui/WebPreviewScreen.java`（`class WebPreviewScreen extends Screen`，类注释“画面传输（把游戏画面编码后通过 WebSocket 发给前端）”，`render` 中 `WebFrameCapture.capture(minecraft); WebFrameStreamer.onFrame();`）；同目录另有 `WebFrameCapture.java` / `WebFrameStreamer.java` / `WebSocketSession.java`。
11. **§7**“现在 letterbox 的 z=0 已是最小”——`LetterboxLayer.java`：`private static final int Z_INDEX = 0;` 且 `getZIndex()` 返回该常量、无 `setZIndex`；`OverlayManager.java:74` `layers.sort(Comparator.comparingInt(OverlayLayer::getZIndex))`、`:14` 注释“zIndex 越小越先绘制（底层）”。
12. **§7**“现计划写的是‘正式合成不降分辨率’”——`plans/0.3.6/multi-camera-rendering.md:98`、`:127` 与 `plans/0.3.6/render-routes.md:21-22`（跨文档引用，未修改他人文档）。
13. **§1**“多相机原型验证通过之后”——`plans/0.3.6/quadrant-prototype-results.md:3` `**状态**: ✅ 验收通过（2026-10-05）`。

### ② 已修正

1. **§2 / §6 把 Pip 说成“静态贴图”**：旧说法“Pip 是‘静态贴图’形态” / “现有 `pip` 层是‘静态贴图 + 原始像素坐标’” → 新事实：`PipLayer` **没有任何纹理**，只画 `FILL_COLOR = 0x40000000` 半透明黑填充 + `BORDER_COLOR = 0xFFFFFFFF` 2px 白框（`BORDER_WIDTH = 2`），类注释“Phase 1：仅渲染白色边框和半透明黑色填充，不包含实际摄像头画面”；字段只有 `x/y/width/height/anchorX/anchorY/opacity/zIndex`。已改为“静态占位框（2px 白框 + 半透明黑填充，无纹理）”，保留原意与 §6 去留方向。
   - **冲突裁决**：`plans/0.3.6/variable-frame.md` 最后修改 `2026-10-06T21:57:01+08:00`；`common/.../overlay/PipLayer.java` 最后修改 `2026-07-27T16:39:22+08:00`（`git log` 仅一条提交，自 0.3.4 起未变）。文档时间戳较晚，但该断言是对 PipLayer **现行实现**的描述、源码未变，故以源码为准修正措辞。

### ③ 补全的信息

1. **§3 文本层基准尺寸**：`SubtitleLayer` 除 `x`/`y`/`scale_x`/`scale_y` 外还有一级 `fontScale`（注释“字号倍数（1.0 = 原版 9px，矩阵缩放实现，同 MC title 机制）”，render 中 `pose.scale(fontScale * scaleX, fontScale * scaleY, 0f)`）——即“基准字号下的文字块”= `fontScale = 1` 时的块尺寸。
2. **§3 现状字段来源与缺口**：`script/OverlayTrackPlayer.java` 的 `applyInitialClipValues()` / `updateLayer()` 从关键帧读 `x`/`y`/`scale_x`/`scale_y`（image/subtitle）与 `x`/`y`/`width`/`height`/`anchor_x`/`anchor_y`（pip），缺省值分别为 `0/1` 与 `0/0/0.5/0.5`；`script/schema/TrackSchemas.java:143-158` 的 `overlay()` schema 字段为 `layer_type` / `color` / `path` / `text` / `z_index` / `interpolation`（clip）与 `opacity` / `x` / `y` / `font_scale` / `scale_x` / `scale_y`（关键帧）——**无取材 / 适配 / 锚点字段**，pip 的 `anchor_x/anchor_y` 也不在 schema 中（播放器按默认 0.5 读）。佐证 §3“要补齐取材 / 适配”。
3. **§7“letterbox 的 z=0 已是最小”的边界**：`z_index` 是脚本可写 `int`（schema 默认 `20`，`OverlayTrackPlayer` 回退默认 `10`），`LetterboxLayer` 的 `0` 是硬编码且不可脚本调整——脚本写负值即可排到 letterbox 之下。故“0 是最小”只在**内置默认值**层面成立，不是全局钳制；给画面层留更低基线时需一并决定 `z_index` 是否允许负值。
4. **§4 olive 侧文件路径补齐**：Transform 节点 `app/node/distort/transform/transformdistortnode.{h,cpp}`；Crop 节点 `app/node/distort/crop/cropdistortnode.{h,cpp}`；crop shader `app/shaders/crop.frag`；默认顶点 quad / 着色器 `app/render/opengl/openglrenderer.cpp`（`blit_vertices`）+ `app/shaders/default.vert`；Gizmo 证据 = Transform 节点的 `point_gizmo_` / `poly_gizmo_` / `rotation_gizmo_`（缩放 / 旋转）与 Crop 节点的 `poly_gizmo_` / `point_gizmo_`（拖边裁切）。
5. **§2 五类之外的实现**：`overlay/` 还有 `CinematicOverlay.java` 与 `OverlayManager.java`（调度器：按 zIndex 升序渲染，`addLayer` 后自动重排）。

### ④ 无法核实（未验证）

1. §2 分类表“画面层 = 相机 lane 的输出纹理 / 每条 CAMERA 轨或每个相机一条”、§5 Gizmo 与参数面板双向同步——均为**方向**，无对应现状实现可核。（§3 字段名 / §9 步骤 1–2 已于 2026-10-07 定稿，见 §3.1；**§4.1 参考画布已于 2026-10-10 被 §4.2 取代**；§8 余下待定项与 §9 步骤 3–6 仍为方向。）
2. §7“小尺寸画面是否按显示尺寸渲染”属取舍问题，非事实断言；其引用的“正式合成不降分辨率”已在 ①.12 核实为真。
3. olive 侧“变换先按序列归一化”的完整语义链（`MatrixGenerator::GenerateMatrix` 生成矩阵的坐标系约定）未逐行追到基类实现，本核查只验证到 `AdjustMatrixByResolutions` 的缩放/适配与 NDC 顶点约定（见 ①.7）。

### ⑤ 定稿落地（2026-10-07）

> ⚠ **本节记录的是「参考画布」口径的落地（§4.1），该口径已于 2026-10-10 被 §4.2 取代** —— 保留为历史记录；重写清单见 §11。

- **交付物**：`common/src/main/java/com/immersivecinematics/immersive_cinematics/overlay/CanvasTransform.java`（`REFERENCE_ASPECT_RATIO = 16/9`、`REFERENCE_WIDTH/HEIGHT = 1920/1080`、`FitMode{FIT,FILL,STRETCH}`、`record Placement`、纯函数 `map` / `canvas` / `screenX` / `screenY`；**不引用任何 MC 类、不做渲染**）。定义写进本文 §3.1（字段表 + 基准尺寸逐类）与 §4.1（参考画布 + 映射公式 + 边界行为）。
- **验证**：`javac -encoding UTF-8` 编译该文件 + 一次性冒烟程序（不入库）→ `SMOKE OK`。覆盖：1920×1080/1280×720/2560×1080/1024×768/3840×2160/800×1280 × Fit/Fill/Stretch 的归一化 → 像素逐点核对（含 `(0,0)`、`(0.5,0.5)`、`(1,1)`）、素材 → 元素框的第二处用法、退化入参（≤0 返回 `EMPTY`）、不变量（Fit 完整可见 / Fill 覆盖屏幕 / 画布中心恒在屏幕中心 / Fit 等比守恒）、以及「1920×1080 + Fit 退化为 `ImageLayer` 语义」的兼容性断言。
- **当时未做**（步骤 3–5 属后续）：渲染 / 图层改动、脚本 schema 与 `docs/SCRIPT_FORMAT.md`。→ 步骤 4 已补齐，见 ⑥。

### ⑥ 步骤 4 落地：现有 overlay 层对齐（2026-10-07）

- **代码**：`CanvasTransform` 增补统一默认值常量（`DEFAULT_POSITION` / `DEFAULT_ANCHOR` / `DEFAULT_SCALE` / `DEFAULT_OPACITY` / `DEFAULT_Z_INDEX`）、`record Rect`、`element(...)`（§3.1 元素几何解算：基准尺寸以参考像素给出，`unit = canvasW / 1920` 换算到当前画布，全程浮点比例域，只在最终绘制落到像素）、`fitMode(String, FitMode)`（`fit` 枚举解析）。
  - `ImageLayer`：位置/锚点/缩放按新口径；新增 `source`（素材归一化取材，越界钳制）与 `fit`（`CanvasTransform.map(素材子矩形, 元素框)`）消费，`fill` 的溢出用 scissor 裁到元素框（`fit`/`stretch` 不溢出，不裁剪）。
  - `SubtitleLayer`：位置/锚点/缩放按新口径；`font_scale` 决定文字块基准尺寸。
  - `PipLayer`：去像素化 —— 位置/锚点画布归一化、尺寸由 `scale_x/scale_y`（基准 = 铺满画布）表达、`width`/`height` 字段移除、边框粗细按画布高比例（`2/1080`）；无纹理故不消费 `source`/`fit`。
  - `LetterboxLayer`：补 `opacity`（alpha）；黑边在画布范围内绘制，并绘制画布 Fit 留边（非 16:9 屏幕）。
  - `FadeLayer`：铺满画布（效果层基准尺寸 = (1,1)），位置/缩放不生效；opacity 为唯一参数。
  - `OverlayTrackPlayer`：按字段表取值（缺省 0.5/0.5、锚点 0.5、缩放 1、opacity 1、`z_index` 10；`source` 按分量线性、`fit` 步进）；删除被 `updateLayer` 同帧覆盖的 `applyInitialClipValues`。
  - `TrackSchemas.overlay()`：字段表对齐（clip `z_index` 默认 10；关键帧补 `anchor_x/anchor_y/source/fit`，`opacity` 默认 1，`x/y` 默认 0.5）。
- **文档**：`docs/SCRIPT_FORMAT.md` §9、`docs/AI_SCRIPTING_GUIDE.md` §3.5b、`docs/modules/overlay.md`、`docs/modules/script.md`。
- **验证**：`sh gradlew compileJava` 通过；一次性冒烟（不入库，`javac -encoding UTF-8` + 真实 `CanvasTransform`）→ 1920×1080 下图片几何 1470/1470 组合与旧口径**逐位一致**（最大位置偏差 0.0 px），字幕块位置/尺寸一致，锚点不动点、非 16:9 Fit 映射、`source`+`fit` 三档、`fit` 枚举解析全通过；schema 冒烟（`ScriptParser` + `ScriptRegistry`）确认新字段解析进关键帧数据、默认值对齐、校验器不报错。
- **遗留**：letterbox 的 `opacity` 已具备渲染能力（`setOpacity` + alpha），但 LETTERBOX 轨的脚本字段（`LetterboxTrackPlayer` / `TrackSchemas.letterbox()`）不在本次改动范围，未接线 —— 目前恒为 1（纯黑边，与旧行为一致）。

### ⑦ 几何口径重写（2026-10-10）

- **依据**：`plans/0.3.6/implementation-progress.md`「用户裁决新增（2026-10-08）」表 ——「几何口径」行与「核心思路（重申）」行（均 2026-10-10，用户口径）；本节把该行的两条「待定」（编辑基准分辨率落点 / 转义系数溢出口径）收口为 `meta.base_resolution` 与 `k = min(W播/W基, H播/H基)`（§4.2 抬头引用）。
- **代码现状核对（2026-10-10，只读）**：
  1. `CanvasTransform` 的全部消费者（`grep`）：`ImageLayer`（`canvas` + `element` + `map`）、`SubtitleLayer`（`canvas` + `element`）、`LetterboxLayer`（`canvas`）、`FadeLayer`（`canvas`）、`OverlayTrackPlayer`（`DEFAULT_*` + `fitMode`）—— 无其它调用者；`client/lane/*` 不引用本类。
  2. **绘制空间不一致（实现关键点）**：`handler/ClientEventHandler` 传 `mc.getWindow().getGuiScaledWidth/Height` → 覆盖层在 **GUI 缩放空间**；`LaneCompositor` 直接对主 framebuffer 画（`dest` 是帧缓冲归一化）→ lane 在 **物理像素空间**。位置是百分比（两空间同值），尺寸是像素 → 覆盖层内 `k` 需按绘制空间换算（§4.2.2 实现注）。**这是本次重写最易错处，已写进清单验收。**
  3. `LaneRenderer` 的 lane FBO 按 `mc.getMainRenderTarget().width/height` 建 / 重建（`offscreenTarget`）→ lane 素材每帧 = 播放窗口尺寸，`k = 1` 成立（§4.2.4）。
  4. `ScriptPlayer` 已暴露 `getScript()`（`ScriptPlayer.java:438`），`CinematicScript.getMeta()` 可用 → `base_resolution` 送层的路径存在，无需新增跨层查询。
  5. `LetterboxLayer` 现只画「目标画幅比宽于窗口」的上下黑边（`contentHeight < canvas.height()` 分支），无 pillarbox 分支 → 列为 §8 待拍板（不属本次重写范围）。
- **本次文档改写范围**：本文 §3 / §3.1（参照系 + 基准尺寸逐类）、§4（方向）、§4.1（标注取代）、§4.2（新增定稿）、§5（编辑器登记）、§6（适配标注）、§7 / §8（悬项收口 + 2 项待拍板）、§9（落地顺序）、§11（新增代码改动清单）。`camera-composition.md` 回写 `dest` / `source` 口径（指向 §4.2，不重复定义）。
- **未验证**：§4.2.6 三例为**手算推演**（尚无代码实现可跑）；`k` 的绘制空间换算只有公式等价性推导，无运行期实测（属重写任务的验收项）。
