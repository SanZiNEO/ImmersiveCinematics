# 0.3.6 画面颜色调整：现代调色系统（RGBA 通道 / HSL / 曲线 / 色轮）（长期计划·方向稿）

> 本文是 0.3.6 的长期计划方向稿。
> - 已确认的写“已确认”
> - 确定不了的只写方向和可能的问题
> - 字段名、参数清单、JSON、落地位置等执行时再定
>
> 相关文档：
> - [Overlay 颜色遮罩](./overlay-color-mask.md)
> - [画面合成](./camera-composition.md)
> - [并行播放](./parallel-playback.md)
>
> **状态：第一批「标量组」（15 通道 = 12 标量 + R/G/B 每通道系数）已落地（2026-10-07）；lane 级调色（§7 步骤 5）已落地——调色直接写在 CAMERA 片段上（相机片段自带调色，作用于该相机轨产出的 lane；ADJUST 轨只作用于整体画面）；完整 HSL（`hue` / `lightness`）已落地（2026-10-07）→ 共 17 通道；曲线组（形态 b）已落地（2026-10-08）——RGB 复合曲线（clip 级 `rgb_curve` + 关键帧 `curve_strength`）、每通道曲线（clip 级 `r_curve` / `g_curve` / `b_curve` + 三个强度关键帧）与六条 hue 曲线（clip 级 `hv_h_curve` ~ `sv_l_curve` + 六个强度关键帧，DaVinci 曲线页口径）；RGB 通道混合器（`mix_rr` ~ `mix_bb` 九个 3×3 矩阵系数）已落地（2026-10-08）→ 共 26 个标量通道；Lift / Gamma / Gain 色轮（`lift_r` ~ `gain_b` 九个逐通道色轮参数）已落地（2026-10-08）→ 共 35 个标量通道**。**第二批（通道与 HSL 完整）全部落地**；第三批、调整层（步骤 6）、编辑器 UI 未做。执行时定下的取舍见下方「落地标注」；§6 的「数据落点」已定稿。**

---

## 落地标注（2026-10-07 实现：master 第一批标量组）

### 数据落点（§4 / §6-2：定稿）

**独立 ADJUST 轨道**（`TrackType.ADJUST`，JSON `"type": "adjust"`），**不是** OVERLAY 的新层类型。理由：

1. **时机不同**：调色是作用在**合成输出**上的后处理 pass（MCOMP 之后、RPOST/GUI 之前）；OVERLAY 的层是 **GUI 阶段**的视觉元素（`OverlayManager.render` → `FadeLayer.render` 的 `guiGraphics.fill`）。走 OVERLAY 只能得到「在 GUI 阶段画出来的东西」，做不到「改已有画面的颜色」——要么另开旁路（层不画、只当数据容器，那 `z_index` / `opacity` / 位置 / 缩放字段全是噪音），要么时机错位（GUI 之后再调色会连字幕 / 黑边一起调，与 §5-3 的倾向相反）。
2. **字段形状不同**：OVERLAY 层 = 一个元素 + 一套统一参数（x/y/anchor/scale/source/fit/opacity/z_index，四类层共享）；调色 = 一组彼此独立、各带公式的标量通道，两者无一处重合。
3. **成本**：独立轨道 = 枚举 +1、schema +1、校验分支 +1、`TrackPlayer` 工厂 +1、播放器 +1；OVERLAY 路线要在 `OverlayTrackPlayer.createLayer/updateLayer` 里加特例分支 + 一个只存数据的 `OverlayLayer` 子类，渲染侧状态与 pass 一样要新建 —— 代码更多、语义更歪。

**字段表（定稿）：12 个标量通道 + R/G/B 三通道 + 完整 HSL（`hue` / `lightness`）（2026-10-07 增量 → 共 17），全部是关键帧字段，缺省全 0 = 无效果**；**曲线组（形态 b）= clip 级 `rgb_curve` / `r_curve` / `g_curve` / `b_curve` + 关键帧 `curve_strength` / `r_curve_strength` / `g_curve_strength` / `b_curve_strength`（缺省 1）**，见下文「增量：RGB 复合曲线（形态 b）」与「增量：每通道曲线（R / G / B）」；**六条 hue 曲线 = clip 级 `hv_h_curve` / `hv_s_curve` / `hv_l_curve` / `lv_s_curve` / `sv_s_curve` / `sv_l_curve` + 关键帧 `hv_h_strength` ~ `sv_l_strength`（缺省 1）**，见下文「增量：六条 hue 曲线」；**RGB 通道混合器 = 关键帧 `mix_rr` ~ `mix_bb` 九个（3×3 矩阵系数，-1 ~ 1）**，见下文「增量：RGB 通道混合器（Channel Mixer，3×3 矩阵）」；**Lift / Gamma / Gain 色轮 = 关键帧 `lift_r` ~ `gain_b` 九个（三组逐通道色轮，-1 ~ 1）**，见下文「增量：Lift / Gamma / Gain 色轮」——以上合计 **35 个标量通道 + 10 条曲线**。

| 通道 | 默认 | 范围 | 口径 |
|---|---|---|---|
| `exposure` | 0 | -5 ~ 5 | 曝光，EV 档（×2^EV） |
| `contrast` | 0 | -1 ~ 1 | 对比度，以中灰 0.5 为轴（±1 → ×0 / ×2） |
| `highlights` | 0 | -1 ~ 1 | 高光，亮度权重 l²（正 = 向白抬、负 = 向黑压） |
| `shadows` | 0 | -1 ~ 1 | 阴影，亮度权重 (1-l)² |
| `whites` | 0 | -1 ~ 1 | 白场端点（白点 = 1 + 0.5×值） |
| `blacks` | 0 | -1 ~ 1 | 黑场端点（黑点 = 0.5×值） |
| `hue` | 0 | -1 ~ 1 | HSL 的 H 通道：色相旋转（`H' = fract(H + Hue*0.5)`：±1 = ±180°） |
| `saturation` | 0 | -1 ~ 1 | HSL 的 S 通道（-1 = 全灰、1 = 双倍） |
| `vibrance` | 0 | -1 ~ 1 | 自然饱和度（正 = 低饱和优先，负 = 整体降饱和） |
| `lightness` | 0 | -1 ~ 1 | HSL 的 L 通道（正 = 向白推、负 = 向黑压；±1 = 全白 / 全黑） |
| `temperature` | 0 | -1 ~ 1 | 色温（正 = 暖 / 偏红，负 = 冷 / 偏蓝） |
| `tint` | 0 | -1 ~ 1 | 色调（正 = 品红，负 = 绿） |
| `red` | 0 | -1 ~ 1 | R 通道系数（乘性，增益 = 1 + 值：-1 = 归零、-0.5 = 减半、+1 = 双倍） |
| `green` | 0 | -1 ~ 1 | G 通道系数（同上） |
| `blue` | 0 | -1 ~ 1 | B 通道系数（同上） |
| `grayscale` | 0 | 0 ~ 1 | 灰度混合强度（Rec.709 亮度） |
| `invert` | 0 | 0 ~ 1 | 反相混合强度 |

- **没有 clip 级字段**（letterbox 先例）；**不设 `enabled`**：缺省 0 即无效果，关键帧把通道写回 0 就是「该项淡出」——§3.1 的「enabled 可关键帧」由「通道值本身可关键帧」覆盖，少一个字段少一套语义。
- 插值：**匀速线性**（§3.1 的标量形态），走新加的 `KeyframeInterpolator.interpolateChannel`（与 `OverlayTrackPlayer` 的逐通道取值同口径：关键帧缺该字段 → 用缺省值；时间在首 / 末帧之外 → 取边界值，不外推）。
- 曲线（§3.1 形态 a / b）未做，属曲线组任务。

### 渲染挂点（§4 / §5-1 / §5-3 / §6-3：定稿；2026-10-08 用户裁决后移）

- **挂点**：`GameRenderer.render` 内、**原版后处理链（RPOST）之后、GUI 之前** —— `mixin/GameRendererMixin.onWorldPostProcessed`，注入目标 = 对 `RenderTarget.bindWrite(Z)V` 的调用（原版 `postEffect.process(f)` 之后那一句 `getMainRenderTarget().bindWrite(true)`）：即 **MCOMP → RPOST → RADJ → GUI**，与架构图 RADJ 一致。
  - **口径（2026-10-08 用户裁决）**：master 调色（含未来的整体 LUT 烘焙）是世界画面的**最终字**。黑边 / 字幕 / 黑白场转场走 OVERLAY 层、在 GUI 阶段绘制且需要精确色值，不得被调色 / LUT 污染（GUI 不参与调色）；受伤红晕 / 水幕等原版屏幕特效属世界画面，应一并风格化。旧挂点（`renderLevel` 的 RETURN、紧接 lane 合成）已被本挂点取代。
  - **为什么不再与 lane 合成同一注入**：lane 合成（MCOMP）仍留在 `renderLevel` 的 RETURN（`mixin/LaneRendererMixin`）——它必须早于 `doEntityOutline` / RPOST；master 调色读的是 RPOST 的输出，两者天然分处不同阶段，先后由原版语句顺序显式保证，不再需要「同一注入」这一约定。
  - **为什么锚 `bindWrite(true)` 而不是 `Gui.render`**：`gui.render` 被原版 `if (!options.hideGui || screen != null)` 包着——按 F1 隐藏 HUD 时整段 GUI 不渲染，锚在那里会让调色随 HUD 显隐而消失；锚 `bindWrite(true)` 位于同一 `if (renderLevel && level != null)` 分支内、不受 HUD 显隐影响，生效条件与旧挂点逐帧等价。
  - **为什么不用原版 `PostChain`**：`EffectInstance` 的程序 JSON 路径硬编码 `shaders/program/<name>.json`（默认命名空间），且 `PostChain` 需要自己处理尺寸跟随、资源重载重建与用不上的 `Time` 等 uniform。自建 `ShaderInstance` + 自管中转缓冲更短更可控；**代价**：程序 JSON 与 GLSL 仍必须落在 `assets/minecraft/shaders/core/`（`ShaderInstance` 的 String 构造只认默认命名空间）—— 这是沿用原版机制的硬约束，非选择。
  - **两个 pass**：主画面 → 中转缓冲（应用全部调整）→ 主画面（整屏 blit，复用 `LaneCompositor.compose`）。不能同时读写同一张纹理，故与「效果 pass + blit 回 main」的原版链同构（与 §2.1 的措辞修正同源）。
  - **GUI 不受影响**（§5-3）：挂点在 GUI 之前 —— 字幕 / 黑边 / 黑白场 / 跳过提示不被调色（与文档倾向一致）。有意的副作用：F2 截图**不带**调色（`tryTakeScreenshotIfNeeded()` 在原版 `renderLevel` 之后、本挂点之前取图）。
  - **描边**：原版 `doEntityOutline()` 在本挂点**之前**（`renderLevel` 之后、`postEffect` 之前）整屏贴一次（仅在有发光实体时动作）——那一次贴图现在**会**经过调色（属世界画面，符合「最终字」口径；有活跃 lane 时该步本就被 `LevelRendererMixin` 屏蔽）。
- **数据 → 渲染的唯一交接**：`client/post/MasterColorAdjust`（帧内**发布 / 取走**，取走即清空）。播放器每渲染帧发布一次（`AdjustTrackPlayer.onRenderFrame`），pass 每渲染帧取一次 —— 「本帧没人发布」自然等于「本帧不调色」，不依赖结束时的清理时序。同帧多个 ADJUST 轨道 = **后发布者生效**（轨道层级靠后的覆盖靠前的，与 lane 叠放同一口径）。
- **光影（§5-1）**：Iris / Oculus 的 `finalizeGameRendering()` 挂在 `renderLevel` 的 TAIL，Iris 的最终合成在 `LevelRenderer.renderLevel` 尾部 `finalizeLevelRendering()`（更内层）——两者都**早于**本挂点，读到的已是光影处理后的画面；本次后移与 Iris 的色彩空间转换完全解耦、顺序可控。见 [iris-oculus-compat.md](./iris-oculus-compat.md)「主画面挂点兼容性结论（2026-10-08）」。

### shader 数学（§4 方向 → 定稿）

资产（**自建**，非复用原版 shader）：`assets/minecraft/shaders/core/ic_color_adjust.{json,vsh,fsh}`；顶点格式 `POSITION_TEX`（与合成层 / 原版 blit 同一套）。

固定操作栈（与 §3「各批内部顺序固定」一致，不可调）：

| # | 步骤 | 公式（逐通道，`c` = 输入色，`l` = Rec.709 亮度） |
|---|---|---|
| 1 | 曝光 | `c *= exp2(Exposure)` |
| 2 | 对比度 | `c = (c - 0.5) * (1 + Contrast) + 0.5` |
| 3 | 高光 / 阴影 | `m_hi = l²`、`m_lo = (1-l)²`；`c += v * m * (v ≥ 0 ? (1-c) : c)` |
| 4 | 白 / 黑场 | `black = 0.5*Blacks`、`white = 1 + 0.5*Whites`；`c = black + c * (white - black)` |
| 5 | 色温 / 色调 | `gain = vec3(1+0.5*T, 1-0.5*Tint, 1-0.5*T)`，`gain /= dot(gain, LUMA)`，`c *= gain` |
| 6 | RGB 通道系数 | `c *= vec3(1+Red, 1+Green, 1+Blue)`（三值全 0 跳过乘法，保持逐位恒等） |
| 7 | RGB 通道混合器 | 3×3 矩阵（实际矩阵 = 单位阵 + 参数矩阵）：`out.r = (1+MixRR)·r + MixRG·g + MixRB·b`，g / b 行同式；九个全 0 跳过乘法（逐位恒等；2026-10-08 增量） |
| 8 | RGB 复合曲线 | `c = mix(c, vec3(lut(c.r), lut(c.g), lut(c.b)), clamp(CurveStrength, 0, 1))`（`lut` = clip 级 `rgb_curve` 采样的 256×1 LUT；`CurveStrength = 0` 跳过整步；2026-10-08 增量） |
| 9 | 每通道曲线 | `if (RCurveStrength > 0) c.r = mix(c.r, rLut(c.r), clamp(RCurveStrength, 0, 1))`（g / b 同式，各用自己的 LUT 与强度；该通道无曲线 / 强度 0 → 跳过，逐位恒等；2026-10-08 增量） |
| 10 | Lift / Gamma / Gain 色轮 | 逐通道三组：`c = c + lift·(1-c)`、`c = pow(max(c,0), exp2(-gamma))`、`c = c·(1+gain)`（`lift` / `gamma` / `gain` 各 = `vec3(LiftR, LiftG, LiftB)` …）；九个全 0 跳过整步（逐位恒等；2026-10-08 增量） |
| 11 | 色相旋转 | HSL：`H' = fract(H + Hue * 0.5)`（`±1` = ±180°；2026-10-07 增量） |
| 12 | 饱和度 | HSL：`S' = clamp(S * (1 + Saturation), 0, 1)` |
| 13 | 自然饱和度 | HSL：`S' = clamp(Vibrance ≥ 0 ? S + Vibrance*S*(1-S) : S*(1+Vibrance), 0, 1)` |
| 14 | 亮度 | HSL：`L' = clamp(L + Lightness * (Lightness ≥ 0 ? (1-L) : L), 0, 1)`（`±1` = 全白 / 全黑；2026-10-07 增量） |
| 15 | 六条 hue 曲线 | HSL 块内、标量 HSL 之后：`HvH: h = mix(h, HvH(h0), st)`、`HvS: s = mix(s, HvS(h0), st)`、`HvL: l = mix(l, HvL(h0), st)`、`LvS: s = mix(s, LvS(l0), st)`、`SvS: s = mix(s, SvS(s0), st)`、`SvL: l = mix(l, SvL(s0), st)`（键 = 进入块时的 `h0` / `s0` / `l0`；每条各用自己的 LUT 与强度，无曲线 / 强度 0 → 跳过；2026-10-08 增量） |
| 16 | 灰度 | `c = mix(c, vec3(luma(c)), clamp(Grayscale, 0, 1))` |
| 17 | 反相 | `c = mix(c, 1 - c, clamp(Invert, 0, 1))` |

- **RGB↔HSL 标准换算**（§4 的方向）用于完整 HSL 块（步骤 11 ~ 15：色相 / 饱和度 / 自然饱和度 / 亮度 + 六条 hue 曲线，即 H / S / L 三通道）；画面**亮度**一律 **Rec.709**（0.2126 / 0.7152 / 0.0722，比原版 `color_convolve.fsh` 的 0.3/0.59/0.11 更接近现代口径）。
- 第 3 步之后、HSL 块之前**钳制到 [0,1]**（HSL 换算与亮度混合要求有界输入；通道混合器（第 7 步）的矩阵结果、曲线步骤（第 8 / 9 步）与 Lift / Gamma / Gain（第 10 步）的输出都由这一次钳制管辖——曲线输出本身由控制点限定在 [0,1] 内，色轮的 lift / gain 会越界，此处一并兜底）。
- **无 HSL 调整时跳过换算**（四个 HSL 通道全 0 **且六条 hue 曲线都不生效**的 uniform 分支）→ 「只调曝光 / 对比度」这类场景逐位恒等；只有标量 HSL 生效时与不带六条曲线前逐位一致。
- 灰点边界（§5-2）：`max-min ≈ 0` 时 `S = 0`、色相无意义 → `hsl2rgb` 的 `S ≤ 0` 分支直接返回灰度，不会产生 NaN 或跳色。
- 色温 / 色调的增益**按亮度归一化** → 调白平衡不改变整体明暗。

### 增量：R/G/B 通道（2026-10-07）

PS 式「RGBA 通道拆分」的最小形态：**R / G / B 三个每通道系数**（第二批「通道与 HSL 完整」的第一块；通道混合器 / 色相曲线仍未做）。

- **字段名 / 范围 / 缺省**：`red` / `green` / `blue`，全部 `-1 ~ 1`，缺省 `0` = 无效果（`-1` = 该通道归零、`-0.5` = 减半、`+1` = 双倍）。
- **公式**：`c.r *= (1 + Red)`、`c.g *= (1 + Green)`、`c.b *= (1 + Blue)`；三值全 `0` 时**跳过乘法**（保持逐位恒等）。
- **alpha 直通**：本步骤只乘 rgb、不碰 a——输出 alpha 始终 = 输入 alpha（透明度只在合成层调控：`LaneCompositor` 的 opacity / Overlay 层 opacity；依据 README「画面完整性原则」、multi-camera-rendering §12.8-A）。
- **操作栈位置**：第 5 步（色温 / 色调）之后、钳制 `[0,1]` 之前（= 上表第 6 步）——HSL 输入有界的保证不变，色温增益与通道系数按固定顺序复合。
- **五处同步点**（顺序是硬约定，逐项对应）：
  1. `script/schema/TrackSchemas.adjust()`：`tint` 之后、`grayscale` 之前加三个 `FieldDef("float", 0f)`；
  2. `script/ScriptValidator.ADJUST_CHANNELS`：三个 `-1 ~ 1` 区间；
  3. `client/post/ColorAdjustParams`：record 分量（`tint` 与 `grayscale` 之间）+ `IDENTITY` + `isIdentity()`；
  4. `client/post/ColorAdjustPass.upload()`：`Red` / `Green` / `Blue` 三个 uniform；
  5. shader 两文件：`ic_color_adjust.json` uniforms + `ic_color_adjust.fsh`（uniform 声明区 + `main()` 操作栈第 6 步 + 文件头通道表）。
- 另：`script/AdjustTrackPlayer.sample()` 的通道名列表同步加三项（否则新通道不被采样）。
- **测试脚本**：`cinematics/tests/adjust/test_adjust_rgb_channels.json`（red 0→-1、green 恒 -0.5、blue 0→1，5 个关键帧）。
- **验证（2026-10-07）**：
  - `sh gradlew compileJava`（`:common` / `:fabric` / `:forge` 三模块）**通过**；
  - 无头 validator（`E:/tmp/icv` 的 `Validate`，真实 `ScriptValidator`）扫 `cinematics/tests/adjust`：1 脚本 **0 issue**；全量 `cinematics/tests`（108 个）仍只有既有的 3 个已知 FAIL，无新增；
  - **数据层冒烟**（throwaway：`ScriptParser.parse` → 反射调 `AdjustTrackPlayer.sample` → `ColorAdjustParams`；**33 项全过**）：线性插值（t=2.5 → red -0.5 / blue 0.5）、缺字段按缺省 0（`green` 全程未写）、时间越界取边界值（t=-2 / t=20，不外推）、其余 12 通道未串位、`isIdentity()` 全 0 为 true / 单通道（red=-1、green=0.25、blue=-1）为 false、validator 拦下 `red: 2` / `green: -1.5` / `blue: 1.5` 且 ±1 边界合法；
  - **着色器冒烟**（throwaway GL harness，真实 GL 3.2 core / NVIDIA RTX 4060；**45 项全过**）：编译 + 链接通过；JSON ↔ fsh uniform **双向一致**（含新增 `Red` / `Green` / `Blue`）；全 0 对 256 个 byte 值**逐字节恒等**（含 alpha）；`Red=-1` 红通道归零、`Green=-0.5` 减半、`Blue=+1` 双倍封顶、三通道组合正确；全 15 通道生效时 **alpha 逐位直通**（256 texel 无一处变化）。
  - 未验证：游戏内实际画面（需启动客户端）；光影下的执行顺序（§5-1，既有开放问题）。

### 增量：完整 HSL（hue / lightness）（2026-10-07）

把现有「饱和度 / 自然饱和度」的 HSL 块升级为**完整 HSL**（第二批「通道与 HSL 完整」的第二块；色相曲线 / 通道混合器仍未做）。

- **字段名 / 范围 / 缺省**：
  - `hue`：色相旋转，`-1 ~ 1`，缺省 `0` = 无效果；
  - `lightness`：HSL 的 L 通道，`-1 ~ 1`，缺省 `0` = 无效果。
- **公式**：
  - `hue`：`H' = fract(H + Hue * 0.5)`（`H` = 归一化色相 `[0,1)`）—— `±1` = 旋转 **±180°**（`±0.5` = ±90°、`±0.25` = ±45°）；`fract` 对负值同样回绕到 `[0,1)`，所以 `+1` 与 `-1` 对纯红都得到青（只是绕行方向不同）。灰点（`S = 0`）不受影响：走 `hsl2rgb` 的 `s <= 0` 分支，不跳色、不 NaN。
  - `lightness`：`L' = clamp(L + Lightness * (Lightness >= 0 ? (1 - L) : L), 0, 1)` —— 正 = 向白推（`+1` = 全白）、负 = 向黑压（`-1` = 全黑），两端都是**满量程**；与高光 / 阴影通道的「双向混合」同一风格。
- **操作栈位置**：**HSL 块内**（§4 操作栈表的第 7 ~ 10 步），块内顺序 = **hue 旋转 → 饱和度 / 自然饱和度（现有）→ lightness**；块条件扩为「`Hue` / `Saturation` / `Vibrance` / `Lightness` 任一非 0 才做 `rgb2hsl` 换算」，四值全 `0` 时**逐位恒等**（保持「无调整零差异」）。三者只动各自的 H / S / L 分量，互相不干扰，所以块内先后只影响语义表述、不影响数值。
- **alpha 直通契约不变**：本块只动 rgb，`fragColor.a = src.a`（透明度只在合成层由 `opacity` 调控）。
- **七处同步点**（顺序是硬约定，逐项对应）：
  1. `script/schema/TrackSchemas.adjust()`：`hue` 插在 `saturation` **之前**、`lightness` 插在 `vibrance` **之后**（HSL 组 = `hue, saturation, vibrance, lightness` 连续）；javadoc 15 → 17；
  2. `script/ScriptValidator.ADJUST_CHANNELS`：两个 `-1 ~ 1` 区间（同位置）；
  3. `client/post/ColorAdjustParams`：record 分量（同位置）+ `IDENTITY` + `isIdentity()` + javadoc 15 → 17；
  4. `client/post/ColorAdjustPass.upload()`：`Hue` / `Lightness` 两个 uniform（顺序一致）；
  5. shader `ic_color_adjust.json`：uniforms +2（`float`）；
  6. shader `ic_color_adjust.fsh`：uniform 声明 +2、HSL 块改造（块条件 + `fract` 旋转 + 亮度混合）、文件头操作栈表（12 步）与通道清单表（17 通道）；
  7. `script/AdjustTrackPlayer.sample()`：通道名列表 +2（否则新通道不被采样）；javadoc 15 → 17。
- **测试脚本**：`cinematics/tests/adjust/test_adjust_hsl.json`（1 条 ADJUST 轨、5 个关键帧：hue `0 → 0.5 → -0.5 → 0.25 → 0`，lightness `0 → -1 → 1 → -0.5 → 0`）。
- **验证**：
  - `sh gradlew compileJava`（`:common` / `:fabric` / `:forge` 三模块）**通过**；
  - 无头 validator（`E:/tmp/icv` 的 `Validate`，真实 `ScriptValidator`）扫 `cinematics/tests/adjust`：3 脚本 **0 issue**（含既有 2 个无回归）；全量 `cinematics/tests`（110 个）仍只有既有的 3 个已知 FAIL，无新增；
  - **数据层冒烟**（throwaway：`ScriptParser.parse` → 反射调 `AdjustTrackPlayer.sample` → `ColorAdjustParams`；**34 项全过**）：线性插值（t=2.5 → hue 0.25；t=7.5 → hue 0 / lightness 0.5）、缺字段按缺省 0（kf[1] 未写 `lightness`）、时间越界取边界值（t=-2 / t=20，不外推）、其余 15 通道未串位、`isIdentity()` 全 0 为 true / 单通道（hue=0.5、hue=-1、lightness=±1）为 false、validator 拦下 `hue: 2` / `hue: -1.5` / `lightness: 1.5` 且 ±1 边界合法；
  - **着色器冒烟**（throwaway GL harness `E:/tmp/icgl`，真实 GL 3.2 core；新增 `GlHslSmoke` **79 项全过**）：编译 + 链接通过；JSON ↔ fsh uniform **双向一致**（含新增 `Hue` / `Lightness`）；全 0 对灰度梯度与 16 个测试色**逐字节恒等**（含 alpha）；**`hue = ±1` 对纯红 → 青（180°）**、绿 → 品红、蓝 → 黄、黄 → 蓝、青 → 红、品红 → 绿（精确到 byte）；`hue = ±0.5` → ±90°（红 → 青绿 `(128,255,0)` / 红 → 紫 `(128,0,255)`）；`hue = ±0.25` → ±45°（红 → 橙 / 红 → 品红方向）；`hue = 1` 对灰度输入**逐字节恒等**（`s = 0` 分支）；`lightness = +1` → 全白、`-1` → 全黑（alpha 均直通）；`lightness = ±0.5` 对灰度梯度**逐级核对公式**（`L' = (1+L)/2` / `L' = L/2`）；组合用例（`hue + lightness` 对纯红 → 暗青 `(0,128,128)`；`hue + saturation=-1` → 中灰）；全 17 通道生效时 **alpha 逐位直通**（256 texel 无一处变化）。既有 master harness（`GlShaderSmoke`，**49 项**）与 lane harness（`GlLaneAdjustSmoke`，**61 项**）复跑仍全过。
  - 未验证：游戏内实际画面（需启动客户端）；光影下的执行顺序（§5-1，既有开放问题）。

### 增量：RGB 复合曲线（形态 b）（2026-10-08）

曲线组的第一块：**RGB 复合曲线**（§3 第一批的另一半，形态 b —— 曲线定义一次 + 强度关键帧）。每通道曲线（R / G / B 各一条）是它的 ×3 扩展，见下一节「增量：每通道曲线（R / G / B）」。

- **形态选择（§3.1）**：取**形态 b**（曲线静态 + `curve_strength` 关键帧控混合量）——零成本起步、语义清晰；形态 a（曲线点集本身打关键帧）留作曲线编辑器落地后的增强。
- **字段**：
  - **clip 级** `rgb_curve` = 控制点数组 `[[x, y], ...]`（结构字段，非标量）：`x` **严格递增**、`x` / `y` 各 `0~1`、**≥2 点**；缺省 = 无曲线。0.3.6 起改名为 `rgb_curve`（不再与 CAMERA 轨的路径贝塞尔 `curve` 同名，无需按轨道类型分派）。
  - **关键帧** `curve_strength`：`0 ~ 1`，**缺省 1**——曲线字段存在即默认全量生效；关键帧把它写回 `0` = 曲线淡出；**无曲线时忽略**。
- **采样（CPU → LUT）**：控制点采样成 **256 点 LUT**（`lut[i]` = 曲线在 `x = i/255` 处），用 **Fritsch–Carlson 单调三次插值**（PCHIP）：单调控制点 ⇒ 单调 LUT、段内**无过冲**（提亮黑场不会把暗部压暗）；端点外**钳制**到端点值。LUT 由解析出的曲线对象（`script/ColorCurve`）在构造时算好并持有 —— **按 clip 缓存**：一个 clip 一个对象，播放器逐帧拿到同一个数组，渲染侧按引用比较、只在换曲线时重传纹理（与 `BezierPathStrategy` 的 `lutCache` 同思路，键 = 曲线对象自身，不必再建 Map）。
- **渲染**：`ic_color_adjust.fsh` 第 8 步（**RGB 通道系数 / 通道混合器之后、HSL 之前**，后续步骤重编号为 9 ~ 14）：`curved = vec3(lut(c.r), lut(c.g), lut(c.b))`、`c = mix(c, curved, clamp(CurveStrength, 0, 1))`；**仅 `CurveStrength > 0` 时走曲线分支**（无曲线 / 淡出 = 逐位恒等）。查表走**第二纹理单元**的新采样器 `CurveLut`（256×1、LINEAR：半 texel 偏移取到 texel 中心，相邻 LUT 项之间线性插值），新 uniform `CurveStrength`（float）。**恒等 LUT 常绑**（无曲线 / 强度 0 时绑 `y = x` 的恒等 LUT，避免未绑定采样器；恒等 LUT + 强度 1 也逐字节恒等）。alpha 直通契约不变。
- **数据贯通**：`ColorAdjustParams` 增加 `curveLut`（nullable `float[256]`）+ `curveStrength` 两个成员，位置在 `blue` 与 `grayscale` 之间（= 操作栈顺序）；`isIdentity()` = 17 标量全 0 **且**（`curveLut == null || curveStrength == 0`）。`AdjustTrackPlayer.sample()` 读 clip 的 `rgb_curve`（LUT 随 clip 缓存）+ 关键帧 `curve_strength`（缺省 1）；**lane 级与 master 两条路径自动生效**（参数随 `ColorAdjustParams` 走，两条路径共用同一个 `ColorAdjustPass`）。
- **同步点**（顺序是硬约定，逐项对应）：
  1. `script/schema/TrackSchemas.adjust()`：clip 级 `rgb_curve`（新 `FieldDef` 类型 `color_curve`，结构字段、缺省 null）+ 关键帧 `curve_strength`（`FieldDef("float", 1f)`，插在 `blue` 与 `grayscale` 之间）；
  2. `script/ScriptParser`：`parseFieldBySchema` 新增 `case "color_curve"` → `parseColorCurve`（数组 / 每点 `[x,y]` 两个数字 / `x` 严格递增 / 各 0~1 / ≥2 点，违规**直接抛 `ScriptParseException`**——不静默忽略）；`parseClip` 里原有的路径贝塞尔 `curve` 校验加 `instanceof BezierCurve` 守卫（与调色字段名不再相同）；
  3. `script/ScriptValidator`：`rgb_curve` 结构校验（`checkColorCurve`）+ `curve_strength` 进 `ADJUST_CHANNELS`（`0 ~ 1`）；
  4. `script/ColorCurve`（新类）：控制点 + Fritsch–Carlson 采样 + 恒等 LUT 常量 + `Clip.getColorCurve()` 访问器；
  5. `client/post/ColorAdjustParams`：record 分量 + `IDENTITY` + `isIdentity()` + javadoc；
  6. `client/post/ColorAdjustPass`：`CurveStrength` uniform + LUT 纹理（`DynamicTexture` 256×1、LINEAR、按引用比较只在换曲线时重传）+ `setSampler("CurveLut", …)`（恒等 LUT 常绑）；
  7. shader 两文件：`ic_color_adjust.json`（`samplers` + `CurveLut`、`uniforms` + `CurveStrength`）+ `ic_color_adjust.fsh`（uniform / 采样器声明 + `curveLut()` 查表函数 + `main()` 第 8 步 + 文件头操作栈表与通道表）；
  8. `script/AdjustTrackPlayer.sample()`：读 clip 曲线（缓存 LUT）+ `curve_strength`（缺省 1）；javadoc 同步。
- **测试脚本**：`cinematics/tests/adjust/test_adjust_curve.json`（1 条 ADJUST 轨：`rgb_curve = [[0,0],[0.5,0.8],[1,1]]` + `curve_strength` 关键帧 `0 → 1 → 0.5 → 1`）。
- **验证（2026-10-08）**：
  - `sh gradlew compileJava`（`:common` / `:fabric` / `:forge` 三模块）**通过**；
  - 无头 validator（`E:/tmp/icv` 的 `Validate`，真实 `ScriptValidator`）扫 `cinematics/tests/adjust`：4 脚本 **0 issue**；全量 `cinematics/tests`（111 个）仍只有既有的 3 个已知 FAIL，无新增；
  - **数据层冒烟**（throwaway `E:/tmp/icv2` 的 `AdjustCurveSmoke`，真实 `ScriptParser` / `ColorCurve` / `AdjustTrackPlayer` + 桩 `ScriptPlayer`；**67 项全过**）：曲线解析（控制点、结构对象）；LUT[0]/[255] 端点、LUT[128] ≈ 0.8（实测 0.80195）、**单调不减 + 全在 [0,1]**（S 曲线 / 平台曲线 / 先平后升曲线三种都无过冲）、端点外钳制（`[[0.25,0.2],[0.75,0.9]]` → `lut[0] = 0.2` / `lut[255] = 0.9`）、两点曲线退化为线性；`curve_strength` 线性插值（0 / 1 / 0.5 / 1 各关键帧 + 中点）、时间越界取边界值、**缺字段的关键帧 → 缺省 1**、无曲线 → 强度 0；其余 17 通道未串位；`isIdentity()`（无曲线 true / 有曲线 + 强度 > 0 false / 有曲线 + 强度 0 true / 曲线为 null 但强度非 0 仍 true）；**master 与 lane 两条发布路径**（恒等归一化为不发布、非恒等携带 LUT、lane 只有曲线生效也参与归集）；validator 拦下 6 种结构反例 + `curve_strength` 越界，parser 直接拒 3 种结构错误；仓库测试脚本 0 issue + 各时刻强度正确；既有 `AdjustRgbSmoke`（33 项）/ `AdjustHslSmoke`（34 项）复跑全过（`sample` 签名改为按 `Clip` 采样后同步更新）；
  - **着色器冒烟**（throwaway GL harness `E:/tmp/icgl`，真实 GL 3.2 core；新增 `GlCurveSmoke` **71 项全过**）：编译 + 链接通过；JSON ↔ fsh uniform / sampler **双向一致**（含 `CurveStrength` / `CurveLut`，并断言 `samplers` 顺序 = `[Sampler0, CurveLut]`）；恒等 LUT + 强度 0 对灰度梯度与 16 测试色**逐字节恒等**；**恒等 LUT + 强度 1 也逐字节恒等**（恒等 LUT 常绑路径不改变输出）；**曲线生效**：强度 1 时输入 0.5 → 输出 204/255 ≈ 0.8（逐字节 = CPU LUT，全部 256 texel 逐通道核对，含 16 测试色）；反向曲线（中点 0.2）同样逐字节核对；**强度 0.5** → `mix(输入, 曲线, 0.5)`（±1）；**强度 0 → 逐字节恒等**；**栈位置**：`Red = -0.5` 时输出 = 「系数 → 曲线」顺序（与「曲线 → 系数」的 160 个 texel 期望值明显不同）、`Exposure = 1` 时 = 「×2 → 曲线」；**全 17 通道 + 曲线生效时 alpha 逐位直通**（256 texel 无一处变化）。既有 `GlShaderSmoke`（53 项）/ `GlHslSmoke`（83 项）/ `GlLaneAdjustSmoke`（65 项）复跑全过。
  - 未验证：游戏内实际画面（需启动客户端；曲线 LUT 纹理的 `DynamicTexture` 上传路径与 `setSampler` 绑定序列同样只在 harness 层验证，Java 侧 GL 调用序列无头跑不了）；光影下的执行顺序（§5-1，既有开放问题）。

### 增量：每通道曲线（R / G / B）（2026-10-08）

曲线组的第二块（§3 第一批的另一半）：**每通道曲线** —— R / G / B 各一条，PS 式「每通道曲线」。是上一节「RGB 复合曲线（形态 b）」的 **×3 扩展**：结构、采样、缓存、混合口径完全同款，只是每条曲线只作用于自己的通道。

- **字段**：
  - **clip 级** `r_curve` / `g_curve` / `b_curve` = 控制点数组 `[[x, y], ...]`（结构字段，非标量）：`x` **严格递增**、`x` / `y` 各 `0~1`、**≥2 点**；缺省 = 该通道无曲线。与复合曲线 `rgb_curve`、以及 CAMERA 轨的路径贝塞尔 `curve` 都互不影响（字段名不同）。
  - **关键帧** `r_curve_strength` / `g_curve_strength` / `b_curve_strength`：各 `0 ~ 1`、**缺省 1** —— 写了对应曲线就是全量生效，关键帧把它写回 `0` = 该曲线淡出；**无对应曲线时忽略**。
- **采样（CPU → LUT）**：复用 `script/ColorCurve`（Fritsch–Carlson 单调三次插值 + 端点外钳制 + 按 clip 缓存）——四条曲线各有自己的对象与 256 点 LUT，`lut()` 逐帧返回同一个数组，渲染侧按引用比较、只在换曲线时重传纹理。
- **渲染**：`ic_color_adjust.fsh` 在**复合曲线（第 8 步）之后**插入第 9 步「每通道曲线」（后续步骤重编号为 10 ~ 15）：
  `if (RCurveStrength > 0) c.r = mix(c.r, rLut(c.r), clamp(RCurveStrength, 0, 1));`（g / b 同式，各用自己的 LUT 与强度）。
  三条各自只写自己的分量 → **逐通道独立**（`r_curve` 不改 g / b）；该通道无曲线 / 强度 0 → 跳过（逐位恒等）。LUT 查表函数泛化为 `lutLookup(sampler2D lut, float x)`（复合曲线与每通道曲线共用一份实现），新增三个采样器 `RCurveLut` / `GCurveLut` / `BCurveLut`（纹理单元 2 ~ 4，排在 `CurveLut` 之后）与三个 uniform `RCurveStrength` / `GCurveStrength` / `BCurveStrength`；**四个 LUT 都常绑**（无曲线 / 强度 0 时绑恒等 LUT，不会出现未绑定采样器）。alpha 直通契约不变（仍只动 RGB）。
- **数据贯通**：`ColorAdjustParams` 增加 `rCurveLut` / `gCurveLut` / `bCurveLut`（nullable `float[256]`，紧邻 `curveLut`）与 `rCurveStrength` / `gCurveStrength` / `bCurveStrength`（紧邻 `curveStrength`，都在 `grayscale` 之前）；`isIdentity()` = 17 标量全 0 **且**四条曲线都「不存在或强度为 0」。`AdjustTrackPlayer.sample()` 读 clip 的四条曲线（LUT 随 clip 缓存）+ 四个强度（缺省 1）；**lane 级与 master 两条路径自动生效**（参数随 `ColorAdjustParams` 走，两条路径共用同一个 `ColorAdjustPass`）。
- **同步点**（顺序是硬约定，逐项对应）：
  1. `script/schema/TrackSchemas.adjust()`：clip 级 `r_curve` / `g_curve` / `b_curve`（`color_curve`）+ 关键帧三个强度（`FieldDef("float", 1f)`，插在 `curve_strength` 与 `grayscale` 之间）；
  2. `script/ScriptParser`：`color_curve` 分派自动覆盖新字段（`parseColorCurve` 的消息泛化为不带字段名前缀 —— 字段名由路径 `p` 带出）；
  3. `script/ScriptValidator`：三个曲线结构校验（复用 `checkColorCurve`）+ 三个强度进 `ADJUST_CHANNELS`（`0 ~ 1`）；
  4. `script/Clip`：`getRCurve()` / `getGCurve()` / `getBCurve()` 访问器（与 `getColorCurve()` 同构）；
  5. `client/post/ColorAdjustParams`：三个 LUT + 三个强度 + `IDENTITY` + `isIdentity()` + javadoc；
  6. `client/post/ColorAdjustPass`：LUT 纹理机制泛化为 `LutTexture` 槽（4 个实例，各自「引用比较 + 只在换曲线时重传」）+ 三个 `setSampler` + 三个 uniform；
  7. shader 两文件：`ic_color_adjust.json`（`samplers` + 3、`uniforms` + 3）+ `ic_color_adjust.fsh`（uniform / 采样器声明 + `lutLookup` 泛化 + `main()` 第 9 步 + 文件头操作栈表与通道清单）；
  8. `script/AdjustTrackPlayer.sample()`：四条曲线（缓存 LUT）+ 四个强度（缺省 1）；javadoc 同步。
- **测试脚本**：`cinematics/tests/adjust/test_adjust_channel_curves.json`（1 条 ADJUST 轨：`r_curve` 提亮中间调 / `g_curve` 压暗中间调 / `b_curve` S 形提亮 + 三个强度关键帧各 `0 → 1 → 0.5 → 1`）。
- **验证（2026-10-08）**：
  - `sh gradlew compileJava`（`:common` / `:fabric` / `:forge` 三模块）**通过**；
  - 无头 validator（`E:/tmp/icv` 的 `Validate`，真实 `ScriptValidator`）扫 `cinematics/tests/adjust`：5 脚本 **0 issue**；全量 `cinematics/tests`（112 个）仍只有既有的 3 个已知 FAIL，无新增；
  - **数据层冒烟**（throwaway `E:/tmp/icv2` 的 `AdjustChannelCurveSmoke`，真实 `ScriptParser` / `ColorCurve` / `AdjustTrackPlayer` + 桩 `ScriptPlayer`；**92 项全过**）：三条曲线解析（控制点、结构对象、LUT 长度）；三条 LUT 各自正确（中点 0.65 / 0.35 / 0.8，实测 0.65196 / 0.35197 / 0.80195）、单调不减 + 全在 [0,1]、三条是三个独立数组；端点外钳制；**与复合曲线共存**（四条 LUT 都是各自那条、四个强度各自独立、17 标量通道未串位）；三个强度线性插值（各关键帧 + 中点）、时间越界取边界值（不外推）、缺字段的关键帧 → 缺省 1、无曲线 → 强度 0；`isIdentity()`（无曲线 true / 仅 r 曲线 + 强度 > 0 false / 曲线 + 强度 0 true / LUT null 但强度非 0 仍 true / 单通道 g / b 也算）；**master 与 lane 两条发布路径**（只有 r 曲线生效也参与归集、携带同一个 LUT 数组）；validator 拦下 5 种结构反例 + 三个强度越界，parser 直接拒 3 种结构错误；仓库测试脚本 0 issue + 各时刻强度正确；既有 `AdjustRgbSmoke`（33 项）/ `AdjustHslSmoke`（34 项）/ `AdjustCurveSmoke`（67 项）复跑全过（`ColorAdjustParams` 构造器加分量后同步更新）；
  - **着色器冒烟**（throwaway GL harness `E:/tmp/icgl`，真实 GL 3.2 core / NVIDIA RTX 4060；新增 `GlChannelCurveSmoke` **93 项全过**）：编译 + 链接通过；JSON ↔ fsh uniform / sampler **双向一致**（含三个新 sampler / 三个新 uniform，并断言 `samplers` 顺序 = `[Sampler0, CurveLut, RCurveLut, GCurveLut, BCurveLut]`）；全 0 + 恒等 LUT 对灰度梯度与 16 测试色**逐字节恒等**（含 alpha）、**四个恒等 LUT + 强度全 1 也逐字节恒等**（常绑路径）；**逐通道独立**：只开 r 曲线时 g / b 逐字节不变（bad=0）且 r 逐 texel = CPU LUT，只开 g / b 同理；**三条同时生效**逐通道 = 各自曲线 CPU LUT（256 texel 逐字节，含「r/g/b 不串位」）；**强度混合**（r 0.5 / g 0.25 / b 0.75）逐 texel = `mix(输入, 曲线, 强度)`；强度 0 → 逐字节恒等；**栈位置**：复合曲线 + r 曲线时红通道 = 「复合 → 每通道」（bad=0），与「每通道 → 复合」不符（64 texel 期望值不同、bad=64），g / b 只走复合曲线；**全 17 通道 + 四条曲线生效时 alpha 逐位直通**（256 texel 无一处变化）。既有 `GlShaderSmoke`（65 项）/ `GlHslSmoke`（95 项）/ `GlCurveSmoke`（83 项，含同步更新后的 sampler 顺序断言）/ `GlLaneAdjustSmoke`（77 项）复跑全过。
  - 未验证：游戏内实际画面（需启动客户端；四个 LUT 纹理的 `DynamicTexture` 上传路径与 `setSampler` 绑定序列同样只在 harness 层验证，Java 侧 GL 调用序列无头跑不了）；光影下的执行顺序（§5-1，既有开放问题）。

### 增量：六条 hue 曲线（HvH / HvS / HvL、LvS / SvS / SvL）（2026-10-08）

第二批的第三块（§3 第二批「六条 hue 曲线」，对标 DaVinci 曲线页）：**六条曲线各 = 一条曲线（clip 级，结构同 `rgb_curve`）+ 一个强度（关键帧，0~1 缺省 1）**，全部在 **HSL 空间**、**HSL 块内**生效。

- **语义表（键 = 进入该块时的 `h0` / `s0` / `l0`；每条各自按强度 mix 混合；顺序即下表顺序，同一目标分量的多条按表序依次叠加）**：

  | 曲线 | 键（输入） | 目标（输出） | 公式 |
  |---|---|---|---|
  | HvH（`hv_h_curve` / `hv_h_strength`） | hue | hue | `h = mix(h, HvH(h0), st)` |
  | HvS（`hv_s_curve` / `hv_s_strength`） | hue | 饱和度 | `s = mix(s, HvS(h0), st)` |
  | HvL（`hv_l_curve` / `hv_l_strength`） | hue | 亮度 | `l = mix(l, HvL(h0), st)` |
  | LvS（`lv_s_curve` / `lv_s_strength`） | 亮度 | 饱和度 | `s = mix(s, LvS(l0), st)` |
  | SvS（`sv_s_curve` / `sv_s_strength`） | 饱和度 | 饱和度 | `s = mix(s, SvS(s0), st)` |
  | SvL（`sv_l_curve` / `sv_l_strength`） | 饱和度 | 亮度 | `l = mix(l, SvL(s0), st)` |

- **键是「进入 HSL 块时」的值**（`rgb2hsl` 的原始输出）：同帧的 `hue` / `saturation` / `vibrance` / `lightness` 标量块改动**不影响**曲线的键（「只对原本的红色系提饱和」不会因为同帧开了色相旋转而跟着转）。曲线输出是**值**而不是增量（`y = 1` = 把饱和度拉到 1）。
- **操作栈位置**：现有标量 HSL 块（hue 旋转 / 饱和度 / 自然饱和度 / 亮度）**之后**、灰度之前；**共享一次 `rgb2hsl` 换算**——块条件扩为「四个标量任一非 0 **或**六条曲线任一有效」，块内顺序 = 标量 HSL → 六条曲线 → `hsl2rgb`。**只有标量生效时行为与之前逐位一致**（无曲线时渲染侧把强度置 0，六条分支全跳过）。
- **采样 / 缓存**：完全复用 `script/ColorCurve`（Fritsch–Carlson 256 点 LUT + 端点外钳制 + 按 clip 缓存，逐帧拿到同一个数组 → 渲染侧按引用比较、只在换曲线时重传纹理）。
- **alpha 直通契约不变**：只动 rgb（H / S / L 三分量），`fragColor.a = src.a`。
- **同步点**（顺序是硬约定，逐项对应）：
  1. `script/schema/TrackSchemas.adjust()`：clip 级六个 `color_curve` 字段（`hv_h_curve` / `hv_s_curve` / `hv_l_curve` / `lv_s_curve` / `sv_s_curve` / `sv_l_curve`）+ 关键帧六个强度（`FieldDef("float", 1f)`，插在 `b_curve_strength` 之后、`grayscale` 之前）；javadoc 同步；
  2. `script/ScriptParser`：六个新曲线字段经 `color_curve` 分派自动覆盖（无需新 case；`parseColorCurve` 的路径 `p` 带出字段名）；
  3. `script/ScriptValidator`：六个曲线结构校验（复用 `checkColorCurve`）+ 六个强度进 `ADJUST_CHANNELS`（`0 ~ 1`）；
  4. `script/Clip`：六个 getter（`getHvHCurve()` ~ `getSvLCurve()`）；
  5. `client/post/ColorAdjustParams`：六个 LUT（nullable `float[256]`）+ 六个强度（都在 `grayscale` 之前）+ `IDENTITY` + `isIdentity()`（= 17 标量全 0 **且**十条曲线都「不存在或强度 0」）+ javadoc（语义表）；
  6. `client/post/ColorAdjustPass`：六个新 `LutTexture` 槽（共 10 个）+ 六个 `setSampler` + 六个 uniform（`upload()`）；
  7. shader 两文件：`ic_color_adjust.json`（`samplers` + 6、`uniforms` + 6）+ `ic_color_adjust.fsh`（uniform / 采样器声明 + HSL 块改造（共享换算 + 六条曲线步骤）+ 文件头操作栈表重编号 15 步与参数表）；
  8. `script/AdjustTrackPlayer.sample()`：六条曲线（缓存 LUT）+ 六个强度（缺省 1）；javadoc 同步。
- **测试脚本**：`cinematics/tests/adjust/test_adjust_hue_curves.json`（1 条 ADJUST 轨：六条曲线形状各不相同——HvH 红色带 +0.1 旋转、HvS 红色带提饱和 / 蓝带平台、HvL 红色带提亮、LvS 暗部降饱和、SvS 中饱和加强、SvL 高饱和压暗 + 六个强度关键帧各 `0 → 1 → 0.5 → 1`）。
- **验证（2026-10-08）**：
  - `sh gradlew compileJava`（`:common` / `:fabric` / `:forge` 三模块）**通过**；
  - 无头 validator（`E:/tmp/icv` 的 `Validate`，真实 `ScriptValidator`）扫 `cinematics/tests/adjust`：6 脚本 **0 issue**；全量 `cinematics/tests`（113 个）仍只有既有的 3 个已知 FAIL，无新增；
  - **数据层冒烟**（throwaway `E:/tmp/icv2` 的 `AdjustHueCurveSmoke`，真实 `ScriptParser` / `ColorCurve` / `AdjustTrackPlayer` + 桩 `ScriptPlayer`；**99 项全过**）：六条曲线解析（控制点、结构对象、LUT 长度、六个独立数组）；六条 LUT 各自正确（HvH[0] = 0.1、HvS[0] = 1 / HvS[170] = 1（蓝带平台）/ HvS[128] ≈ 0.5、HvL[0] = 0.6 / HvL[255] = 0.5、LvS[0] = 0.25 / LvS[255] = 1、SvS[128] ≈ 0.7、SvL[255] = 0.35）、全在 [0,1]、单调控制点保持单调、非单调 y 也不过冲、端点外钳制；**与 17 标量通道 + 四条已有曲线共存不串位**（十条 LUT 是十个不同数组、十个强度各自独立、标量通道未串位）；六个强度线性插值（各关键帧 + 中点）、时间越界取边界值（不外推）、缺字段的关键帧 → 缺省 1、无曲线 → 强度 0；`isIdentity()`（全缺省 true / 仅 hv_s 曲线 + 强度 1 false / 曲线 + 强度 0 true / LUT null 但强度非 0 仍 true / 首条与末条都算）；**master 与 lane 两条发布路径**（只有 sv_l 曲线生效也参与归集、携带同一个 LUT 数组）；validator 拦下 6 种结构反例 + 六个强度越界，parser 直接拒 3 种结构错误；仓库测试脚本 0 issue + 各时刻强度正确；既有 `AdjustRgbSmoke`（33 项）/ `AdjustHslSmoke`（34 项）/ `AdjustCurveSmoke`（67 项）/ `AdjustChannelCurveSmoke`（92 项）复跑全过（`ColorAdjustParams` 构造器加分量后同步更新）；
  - **着色器冒烟**（throwaway GL harness `E:/tmp/icgl`，真实 GL 3.2 core / NVIDIA RTX 4060；新增 `GlHueCurveSmoke` **137 项全过**）：编译 + 链接通过；JSON ↔ fsh uniform / sampler **双向一致**（含六个新 sampler / 六个新 uniform，断言 `samplers` 顺序 = `[Sampler0, CurveLut, RCurveLut, GCurveLut, BCurveLut, HvHLut, HvSLut, HvLLut, LvSLut, SvSLut, SvLLut]`（11 个 = 纹理单元 0~10））；全 0 + 恒等 LUT 对灰度梯度与 16 测试色**逐字节恒等**（含 alpha）；十个恒等 LUT 常绑 + 十条强度全 0 **逐字节恒等**；**逐条可判别**（每条都用 CPU 侧整套 HSL 块复刻模型逐 texel 比对，并给出「忽略该曲线」的反例期望值证明可判别）：HvH 把纯红旋转到橙（g 156 / b 0）、HvS **红色带提饱和（128/128 低饱和红像素变化）且纯蓝像素逐字节不变（含 alpha）**、HvL 红色带提亮（191/64 → 204/102）且蓝带保持、LvS 暗部降饱和（色差 24 → 12）、SvS 中饱和加强（色差 25 → 34）且全灰 texel 逐字节不变（`s = 0` 分支）、SvL 高饱和压暗（51 → 36）；**键为进入值**（Hue=0.5 + HvS / Lightness=0.5 + LvS / Saturation=-0.5 + SvL / Hue=0.5 + HvH 四个用例：与 CPU 模型一致、与「键 = 块内改动后的值」的错误口径不符，bad = 192 / 224 / 144 / 256）；**强度混合**（0.5 / 0.25 / 0.75 / 0.4 / 0.6 / 0.3）逐 texel = `mix(原值, 曲线值, 强度)`、强度全 0（LUT 已绑）逐字节恒等；**栈位置回归**：既有 RGB 复合曲线单独 / 与 HvS 共存都 = CPU 模型，且与「HSL 先、曲线后」的错误顺序不符（bad = 112）；**全 17 通道 + 十条曲线生效时 alpha 逐位直通**（测试色与灰度梯度各 256 texel 无一处变化）；既有 `GlShaderSmoke`（89 项）/ `GlHslSmoke`（119 项）/ `GlCurveSmoke`（107 项）/ `GlChannelCurveSmoke`（117 项）/ `GlLaneAdjustSmoke`（101 项）复跑全过（旧 harness 的 sampler 顺序断言随 11 个采样器同步更新）。
  - 未验证：游戏内实际画面（需启动客户端；十个 LUT 纹理的 `DynamicTexture` 上传路径与 `setSampler` 绑定序列同样只在 harness 层验证，Java 侧 GL 调用序列无头跑不了）；光影下的执行顺序（§5-1，既有开放问题）。

### 增量：RGB 通道混合器（Channel Mixer，3×3 矩阵）（2026-10-08）

第二批的第四块（§3 第二批「RGB 通道混合器（Channel Mixer）」）：PS 式**通道混合器** —— R / G / B 三个输入通道按 **3×3 矩阵**重新组合。

- **语义**：**矩阵 = 单位阵 + 参数矩阵**（缺省全 0 = 单位阵 = 无效果，与「0 = 无效果」的总约定一致）：
  `out.r = (1+mix_rr)·r + mix_rg·g + mix_rb·b`、`out.g = mix_gr·r + (1+mix_gg)·g + mix_gb·b`、
  `out.b = mix_br·r + mix_bg·g + (1+mix_bb)·b`（行 = 输出通道、列 = 输入通道；
  对角线 = 该通道自己再加 / 再减多少份，`-1` = 去掉自身贡献，如 `mix_bb = -1` = 蓝归零）。
- **字段**：`mix_rr` / `mix_rg` / `mix_rb` / `mix_gr` / `mix_gg` / `mix_gb` / `mix_br` / `mix_bg` / `mix_bb`，
  各 **-1 ~ 1**、缺省 **0**，全部是**关键帧字段**（可各自随时间淡入淡出）。
- **操作栈位置**：**RGB 通道系数（第 6 步）之后、RGB 复合曲线（新第 8 步）之前** → **新第 7 步**
  （通道系数是逐通道乘性、混合器是通道间重组；矩阵在曲线之前，曲线拉回的黑场 / 白场不会再被矩阵放大）。
  九个参数全 0 时**跳过整个乘法**（逐位恒等）。
- **钳制语义**：矩阵结果与通道系数 / 曲线同段，由原有的 `clamp(c, 0.0, 1.0)` 管辖（该钳制仍在每通道曲线之后、
  HSL 块之前，注释已更新为「覆盖步骤 1 ~ 9」）——HSL 输入有界的保证不变。
- **alpha 直通契约不变**：只动 rgb，`fragColor.a = src.a`。
- **同步点**（顺序是硬约定，逐项对应）：
  1. `script/schema/TrackSchemas.adjust()`：九个 `FieldDef("float", 0f)`（插在 `blue` 与 `curve_strength` 之间 = fsh 栈顺序）+ javadoc（17 → 26 通道）；
  2. `script/ScriptValidator.ADJUST_CHANNELS`：九个 `-1 ~ 1` 区间（同位置）；
  3. `client/post/ColorAdjustParams`：九个分量（`blue` 与 `curveLut` 之间）+ `IDENTITY` + `isIdentity()` + javadoc（语义）；
  4. `client/post/ColorAdjustPass.upload()`：九个 uniform（`MixRR` … `MixBB`，顺序一致）；
  5. shader `ic_color_adjust.json`：uniforms +9（`float`）；
  6. shader `ic_color_adjust.fsh`：uniform 声明 +9、`main()` 新第 7 步矩阵（后续步骤重编号为 8 ~ 16）、文件头操作栈表与参数表；
  7. `script/AdjustTrackPlayer.sample()`：九个通道（`channel(...)`，缺省 0）；javadoc 同步。
- **测试脚本**：`cinematics/tests/adjust/test_adjust_channel_mixer.json`（1 条 ADJUST 轨：九个系数各不同——`mix_rg` 恒 0.3、`mix_gr` 恒 -0.2、`mix_bb` 恒 -1（蓝归零），`mix_rr` 0 → 0.5、`mix_rb` / `mix_gg` / `mix_gb` / `mix_br` / `mix_bg` 各自随时间起伏）。
- **验证（2026-10-08）**：
  - `sh gradlew compileJava`（`:common` / `:fabric` / `:forge` 三模块）**通过**；
  - 无头 validator（`E:/tmp/icv` 的 `Validate`，真实 `ScriptValidator`）扫 `cinematics/tests/adjust`：7 脚本 **0 issue**；全量 `cinematics/tests`（114 个）仍只有既有的 3 个已知 FAIL（111 OK），无新增；
  - **数据层冒烟**（throwaway `E:/tmp/icv2` 的 `AdjustChannelMixerSmoke`，真实 `ScriptParser` / `AdjustTrackPlayer` + 桩 `ScriptPlayer`；**66 项全过**）：解析（1 条 ADJUST 轨 / 5 关键帧）；九个通道线性插值（各关键帧 + 各区间中点 + 平台段）、时间越界取边界值（t=-2 / t=20，不外推）、缺字段的关键帧 → 缺省 0、无矩阵 / 全缺省 → 九个全 0；**与既有 17 标量 + 十条曲线共存不串位**（15 个标量 + grayscale / invert + 十个 LUT 与十个强度各就各位）；`isIdentity()`（全缺省 true、写了字段但值全 0 true、九个通道各自单独非 0 都 false、共存用例 false）；**master 与 lane 两条发布路径**（lane 只有 mix_bg 生效也参与、回 0 时归一化为不参与）；validator 拦下九个通道各自越界 + ±1 边界合法；仓库测试脚本 0 issue + t=0/1/2/4/6/8/9 九个系数正确；既有 `AdjustRgbSmoke`（33 项）/ `AdjustHslSmoke`（34 项）/ `AdjustCurveSmoke`（67 项）/ `AdjustChannelCurveSmoke`（92 项）/ `AdjustHueCurveSmoke`（99 项）复跑全过（构造器加 9 个分量后同步更新）；
  - **着色器冒烟**（throwaway GL harness `E:/tmp/icgl`，真实 GL 3.2 core / NVIDIA RTX 4060；新增 `GlChannelMixerSmoke` **131 项全过**）：编译 + 链接通过；JSON ↔ fsh uniform **双向一致**（含九个新 uniform；`samplers` 顺序仍 11 个 = `[Sampler0, CurveLut, RCurveLut, GCurveLut, BCurveLut, HvHLut, HvSLut, HvLLut, LvSLut, SvSLut, SvLLut]`）；全 0（单位阵）对灰度梯度与 16 测试色**逐字节恒等**（含 alpha）；**逐通道可判别**：纯红 + `MixRR=-1` → (0,0,0)、纯红 + `MixGR=1` → (255,255,0)、纯绿 + `MixRG=1` → (255,255,0)（列映射）、纯红 + `MixBR=1` → (255,0,255)、纯红 + `MixGR=0.4` → (255,102,0)、纯红 + `MixRG=1` / `MixBB=-1` → 红不变；纯灰 128 + `MixRR=-1, MixRG=1, MixRB=1` → (255,128,128)（矩阵结果被钳到 255）、三行同时「去掉自己 + 并入另两个」→ 灰变白 (255,255,255)、暗灰 64 + `MixGG=1` → (64,128,64)；**九个系数全非 0 与「矩阵 + 曝光 / 对比度」逐 texel = CPU 侧整套操作栈复刻**（±1）；**栈位置**：`Red=-0.5` + `MixRG=1` 与「矩阵 → 系数」的错误顺序不符（bad=128）、矩阵 + 平直曲线 0.5（全画面 0.5 灰）与「曲线 → 矩阵」不符（bad=256）、矩阵 + S 曲线与错误顺序不符（bad=16）、矩阵输出越界 + `Saturation=1` 与「矩阵结果不进 `[0,1]` 钳制」不符（bad=124）；**全 26 通道 + 矩阵生效时 alpha 逐位直通**（测试色与 alpha 梯度各 256 texel 无一处变化）；既有 `GlShaderSmoke`（107 项）/ `GlHslSmoke`（137 项）/ `GlCurveSmoke`（125 项）/ `GlChannelCurveSmoke`（135 项）/ `GlHueCurveSmoke`（155 项）/ `GlLaneAdjustSmoke`（119 项）复跑全过；
  - 未验证：游戏内实际画面（需启动客户端）；光影下的执行顺序（§5-1，既有开放问题）。

### 增量：Lift / Gamma / Gain 色轮（2026-10-08）

第二批的最后一块（§3 第二批「Lift / Gamma / Gain 色轮（或 LOG 色轮）」、§4「按色调分段的多项式 / 幂次映射」）：**三组逐通道色轮**（Lift = 阴影、Gamma = 中间调、Gain = 高光），每组三个通道参数，共九个关键帧通道。取**逐通道标量**形态（不是「一个色轮 = 一个二维偏移」的 UI 形态）：与既有 26 个通道同一套「关键帧字段 + 匀速线性插值 + 缺省 0 = 无效果」口径，零新数据类型、零新采样机制；色轮的**二维 UI**（色相环 + 亮度滑杆）留给编辑器（§7 步骤 3），届时映射到这三个逐通道参数即可。

- **字段**：`lift_r` / `lift_g` / `lift_b`（Lift，阴影）、`gamma_r` / `gamma_g` / `gamma_b`（Gamma，中间调）、`gain_r` / `gain_g` / `gain_b`（Gain，高光），各 **-1 ~ 1**、缺省 **0** = 无效果，全部是**关键帧字段**（可各自随时间淡入淡出）。
- **语义（逐通道，`c` = 该通道当前值）**：
  - **Lift（阴影）** `c' = c + lift·(1 − c)` —— 正 = 抬阴影（`c = 0` → `lift`）、负 = 压黑（`c = 0` 时压到负值，由钳制兜底到 0）；`c = 1` 的通道**不动**（`1 + lift·0 = 1`）。
  - **Gamma（中间调）** `c' = pow(max(c, 0), exp2(−gamma))` —— `gamma = 0` → 指数 `1` = 恒等；正 = 中间调提亮（`+1` = 指数 0.5 = 平方根：中灰 128 → 181）、负 = 压暗（`-1` = 指数 2 = 平方：128 → 64）；`c = 0` / `c = 1` 两端恒为 0 / 1（黑白不动）。`max(c, 0)` 防负输入进 `pow` 产生 NaN。
  - **Gain（高光）** `c' = c·(1 + gain)` —— 正 = 乘性提亮（`+1` = ×2：中灰 128 → 256，由钳制收到 255）、负 = 压暗（`-1` = ×0 = 该通道归零）。
- **操作栈位置**：**每通道曲线（第 9 步）之后、钳制 `[0,1]` 之前** → **新第 10 步**（后续步骤重编号为 11 ~ 17；原「覆盖步骤 1 ~ 9」的钳制注释改为「覆盖步骤 1 ~ 10」）。理由：色轮是「按亮度区间重映射」——曲线先把整体风格定下来，色轮再在**曲线的结果**上分区微调（阴影 / 中间调 / 高光各管一段，符合 DaVinci 的 tool order：曲线在前、色轮在后）；色轮的 lift / gain 输出会越界（`c + 0.5·(1-c) > c`、`c·2 > 1`），由紧随其后的那一次钳制统一收口，HSL 输入仍有界。
- **实现细节（逐位恒等）**：整步由「九个通道任一非 0」守卫（九个全 0 = 跳过，逐位恒等）；Lift / Gain 对 `参数 = 0` 的通道是精确恒等（`c + 0·(1-c)` / `c·(1+0)`）；Gamma 用 `mix(pow(...), c, equal(gammaExp, vec3(1.0)))` —— **指数恰为 1（该通道 `gamma = 0`）的通道直接用原值**，不依赖 `pow(c, 1.0)` 的数值精确性（否则「只开 lift_r」时 g / b 可能差 1 ulp）。
- **alpha 直通契约不变**：三组都只动 rgb，`fragColor.a = src.a`（逐位直通）。
- **同步点**（顺序是硬约定，逐项对应）：
  1. `script/schema/TrackSchemas.adjust()`：九个 `FieldDef("float", 0f)`（插在 `b_curve_strength` 与 `hv_h_strength` 之间 = fsh 栈顺序）+ javadoc（26 → 35 通道）；
  2. `script/ScriptValidator.ADJUST_CHANNELS`：九个 `-1 ~ 1` 区间（同位置）；
  3. `client/post/ColorAdjustParams`：九个分量（`bCurveStrength` 与 `hvHLut` 之间 = 栈顺序）+ `IDENTITY` + `isIdentity()` + javadoc（三组公式）；
  4. `client/post/ColorAdjustPass.upload()`：九个 uniform（`LiftR` … `GainB`，顺序一致）；
  5. shader `ic_color_adjust.json`：uniforms +9（`float`）；
  6. shader `ic_color_adjust.fsh`：uniform 声明 +9、`main()` 新第 10 步（三组公式，逐通道 vec3）、文件头操作栈表（17 步）与参数表；
  7. `script/AdjustTrackPlayer.sample()`：九个通道（`channel(...)`，缺省 0）；javadoc 同步。
- **测试脚本**：`cinematics/tests/adjust/test_adjust_lgg.json`（1 条 ADJUST 轨、5 关键帧：`lift_r` 0 → 0.5 → 0、`lift_g` 恒 0.25、`lift_b` 0 → 0.2、`gamma_r` 0 → 1、`gamma_g` 0 → 0.5、`gamma_b` 恒 -1（压暗中间调）、`gain_r` 0 → -1、`gain_g` 恒 0.5（绿乘性提亮）、`gain_b` 0 → 0.4）。
- **验证（2026-10-08）**：
  - `sh gradlew compileJava`（`:common` / `:fabric` / `:forge` 三模块）**通过**；
  - 无头 validator（`E:/tmp/icv` 的 `Validate`，真实 `ScriptValidator`）扫 `cinematics/tests/adjust`：8 脚本 **0 issue**；
  - **数据层冒烟**（throwaway `E:/tmp/icv2` 的 `AdjustLggSmoke`，真实 `ScriptParser` / `AdjustTrackPlayer` + 桩 `ScriptPlayer`；**67 项全过**）：解析（1 条 ADJUST 轨 / 5 关键帧）；九个通道线性插值（各关键帧 + 各区间中点 + 平台段）、时间越界取边界值（t=-2 / t=20，不外推）、缺字段的关键帧 → 缺省 0、无 LGG / 全缺省 → 九个全 0；**与既有 26 标量 + 十条曲线共存不串位**（15 个标量 + 九个矩阵系数 + grayscale / invert + 十条 LUT 与十个强度各就各位）；`isIdentity()`（全缺省 true、写了字段但值全 0 true、九个通道各自单独非 0 都 false、共存用例 false）；**master 与 lane 两条发布路径**（lane 只有 lift_b 生效也参与、回 0 时归一化为不参与）；validator 拦下九个通道各自越界 + ±1 边界合法；仓库测试脚本 0 issue + t=0/1/2/4/6/8/9 九个通道正确；既有 `AdjustRgbSmoke`（33 项）/ `AdjustHslSmoke`（34 项）/ `AdjustCurveSmoke`（67 项）/ `AdjustChannelCurveSmoke`（92 项）/ `AdjustHueCurveSmoke`（99 项）/ `AdjustChannelMixerSmoke`（66 项）复跑全过（`ColorAdjustParams` 构造器加 9 个分量后同步更新）；
  - **着色器冒烟**（throwaway GL harness `E:/tmp/icgl`，真实 GL 3.2 core / NVIDIA RTX 4060；新增 `GlLggSmoke` **160 项全过**）：编译 + 链接通过；JSON ↔ fsh uniform **双向一致**（含九个新 uniform；`samplers` 顺序仍 11 个）；全 0 对灰度梯度与 16 测试色**逐字节恒等**（含 alpha）；**三组公式可判别**：纯黑 + `LiftR = 0.4` → 红 = 102 且 g / b 不动、纯白 + `LiftR = 0.4` **逐字节不变**、`LiftR = -0.5` 对纯黑钳到 0；中灰 128 + `Gamma = +1` → **181**（变亮）、`-1` → **64**（变暗），黑白两端 + `Gamma = ±1` 都逐字节不变；中灰 128 + `Gain = +1`（×2 = 256）→ **钳到 255**、`-1` → **0**、`GainR = +0.5` → 红 192 且 g / b 不动；**逐通道独立**：只开 `lift_r` / 只开 `gamma_b` / 只开 `gain_g` 时另两个通道**逐字节不变**（bad = 0）且目标通道逐 texel = CPU 复刻；**与 CPU 侧整套操作栈复刻逐 texel 一致**（九个通道全非 0、LGG + 曝光 / 对比度 / 矩阵 / 复合曲线共存，±1）；**栈位置**：平直曲线 0.5 + `GainR = +1` 时红 = 255（「曲线 → LGG」；「LGG → 曲线」的错误顺序 = 128，bad > 0）、S 曲线 + `LiftR` / `GammaB` 同样只符「曲线 → LGG」；`GainR = +1` + 灰度时 = 54（「LGG → 钳制 → 灰度」；不进钳制的错误口径 = 108，bad > 0）；**全 35 通道 + 曲线 + LGG 生效时 alpha 逐位直通**（测试色与 alpha 梯度两个纹理，256 texel 无一处变化）。既有 `GlShaderSmoke`（125 项）/ `GlHslSmoke`（155 项）/ `GlCurveSmoke`（143 项）/ `GlChannelCurveSmoke`（153 项）/ `GlHueCurveSmoke`（173 项）/ `GlLaneAdjustSmoke`（137 项）/ `GlChannelMixerSmoke`（149 项）复跑全过。
  - 注：本机 NVIDIA 驱动的 float → unorm8 转换**平局向零取整**（实测 `0.5 → 127`、`0.25 → 64`、`0.75 → 191`，独立探针确认），故 harness 里的硬编码期望值都避开 `x.5` 这类平局；harness 与文档里的公式口径不受影响。
  - 未验证：游戏内实际画面（需启动客户端）；光影下的执行顺序（§5-1，既有开放问题）。

### 默认零差异（§2.1 的「零差异」要求）

- 无 ADJUST 轨道 / 无活跃 clip / 26 标量通道全为缺省（且无曲线或曲线强度 0）→ 播放器不发布 → pass **第一行返回**：不取着色器、不建中转缓冲、不切 GL 状态、不画任何东西。
- 着色器**首次真正需要时才编译**（不用不编译）；资源重载后重建（旧实例 `close()` 释放 GL program，避免复用旧编译结果）；加载失败只记一次日志，画面保持未调色。

### 本版本明确不做

- **曲线编辑器、贝塞尔手柄与形态 a**（曲线点集本身打关键帧）：十条曲线（复合 + 每通道 + 六条 hue）已随「增量：RGB 复合曲线（形态 b）」「增量：每通道曲线（R / G / B）」与「增量：六条 hue 曲线」落地（2026-10-08），控制点现为 `[x, y]` 二元组（Fritsch–Carlson 单调三次插值）；**曲线手柄（贝塞尔手柄）留编辑器阶段**（2026-10-08 用户裁决）——手柄属**编辑器侧运算**：手柄的效果计算与曲线编辑在编辑器里完成，产出运行时可直接执行的曲线数据；**运行时不含手柄逻辑**（代码只读只执行，修改 / 生成 / 调配一律在编辑器侧，见 `implementation-progress.md` 通用原则），产物数据形态随编辑器阶段定稿。形态 a（曲线点集打关键帧）同随编辑器落地。
- **第二批（通道与 HSL 完整）全部落地（2026-10-08）**：色相旋转（「增量：完整 HSL」）、六条 hue 曲线、RGB 通道混合器与 Lift / Gamma / Gain 色轮（本轮）都已实现；本版本**只剩第三批（LUT / 六色带 / 混合模式）**。
- lane 级调色（§7 步骤 5）已在本版本落地（相机片段自带调色），见下一节「落地标注（相机片段调色）」；~~调整层（§7 步骤 6，依赖分层模型）~~ 已由 **ADJUST 轨**承担——**调整层 = ADJUST 轨**（顶层、不参与排序、默认比其他层级高一个，管整幅画面），无独立新层、无新机制。
- 编辑器 UI（§7 步骤 3）：`editor/src/types.ts` 的 `TrackType` 联合类型、`TrackListPanel.vue` / `Timeline.vue` 的轨道列表与配色、i18n 键、`demo.ts` 的 schema 快照都需跟着加 `ADJUST`（Java 侧 schema 已随 `SchemaExporter` 导出，前端接上即可）。
- 多实例各写 master 的合并语义（§5-4）：仍开放；本版本至多 1 个活跃实例，行为 = 该实例的最后一个 ADJUST 轨道。

### 验证（2026-10-07）

- `sh gradlew compileJava`（`:common` / `:fabric` / `:forge` 三模块）**通过**。
- **数据层冒烟**（throwaway 脚本，用真实类 + 桩 `ScriptPlayer`；31 项全过）：`ScriptParser` 解析含 `"type": "adjust"` 的两片段脚本 → `AdjustTrackPlayer.onRenderFrame` 逐时刻发布 → `MasterColorAdjust.consume()` 取值：线性插值（中点 0.5）、缺字段按缺省 0（首帧未写 `saturation` → 向末帧 -0.5 取 -0.25）、范围外取边界值、无活跃 clip 不发布、`onStop` 清空、恒等参数归一化为「无调整」、`ScriptValidator` 拦下超范围（`exposure: 99` / `grayscale: -2`）与未知轨道类型。
- **着色器冒烟**（throwaway 脚本，真实 GL 3.2 core / NVIDIA 驱动：GLFW 隐藏窗口 + 直接编译仓库里的 `.vsh` / `.fsh`；36 项全过）：编译 + 链接通过；缺省全 0 对 4 种输入颜色**恒等**；12 通道逐项核对本文档口径（曝光、对比度、高光/阴影权重、白/黑场端点、灰度 Rec.709、反相、饱和度 HSL、自然饱和度、色温/色调与亮度保持、组合顺序、alpha 直通）。
- **未验证**：游戏内实际画面（需启动客户端跑一次）；光影下的执行顺序（§5-1）；性能定量（§5-6，仍是「与内容无关」的定性判断：两个全屏 quad）。

---

## 落地标注（2026-10-08 形态修正：相机片段调色 / §7 步骤 5）

### 数据落点（定稿）

**调色字段挂在 CAMERA clip 上**（与 `dest` / `source` / `opacity` 同层，见 [SCRIPT_FORMAT §4](../docs/SCRIPT_FORMAT.md)）——§2.2「每个 lane / 图层各自持有调整」的落地形态：每个相机片段直接携带自己的调色，作用于**该相机轨产出的每条 lane**（lane 渲染完成、合成之前）。ADJUST 轨只作用于整体画面（master）。

- **clip 级 10 条曲线**（不随时间变，故挂 clip）：`rgb_curve`（RGB 复合曲线）+ `r_curve` / `g_curve` / `b_curve`（每通道曲线）+ 六条 hue 曲线（`hv_h_curve` ~ `sv_l_curve`）。
- **关键帧级 45 个通道**（与 ADJUST 轨**同名同缺省**，两条路径共用同一份通道口径与同一个着色器）：35 个标量通道（缺省 `0`）+ 10 个曲线强度（缺省 `1`）。
- **按片段本地时间采样**：每条 lane 用它自己相机片段的本地时间求值（重叠窗口下同轨的多条 lane 各自独立采样）；参数恒等归一化为「不调色」，走原路径（lane 渲染完直接进合成）。
- **每个关键帧都要写**：关键帧缺字段按缺省解（标量 `0` / 强度 `1`），只写一端会在中间挖出折点。
- 校验（`ScriptValidator`）：ADJUST 与 CAMERA 两种 clip 共用同一套调色字段校验（35 标量区间 + 10 强度 `0~1` + 10 条曲线结构）；`scope` / `lane` 字段已移除——出现在任一 clip 上都会报 issue。

### 数据流

```
ColorAdjustSampler.sample(clip, localTime)（共用采样器：35 通道 + 10 曲线强度插值，曲线 LUT 随 clip 缓存）
  ├─ master：AdjustTrackPlayer.onRenderFrame（ADJUST 轨活跃 clip 的本地时间）→ MasterColorAdjust.publish(params)
  └─ lane 级：ScriptPlayer.collectCameraLanes()（每帧，按绘制顺序）对每条 lane 用
       lane.clip() + lane.clipLocalTime() 采样（恒等归一化为 null）
       → LaneFrame(CameraLane, cameraTrackIndex, laneAdjust)
ScriptLaneDriver.tick → LaneRenderer.setLane(index, state, content, laneAdjust)
LaneRenderer.renderLane（lane 渲染 + 描边之后、合成之前）
  → 参数非空且非恒等 → ColorAdjustPass.applyTo(laneFbo, adjustTarget, params, mc)
  → sink 传 adjustTarget（否则传 laneFbo）→ LaneCompositor.compose
```

- **共用 shader / 上传逻辑**：`ColorAdjustPass.applyTo(src, dst, params, mc)` 是 master 与 lane 级唯一的 pass 实现（着色器获取 + 全部通道 uniform 上传 + 全屏 quad 绘制只有一份）；master 路径 = `applyTo(主画面 → 中转缓冲)` + `LaneCompositor.compose` 回主画面；lane 路径 = `applyTo(lane FBO → adjustTarget)`，之后由 `LaneRenderer` 照常交给合成层。
- **共享缓冲**：lane 级输出缓冲 `LaneRenderer.adjustTarget`（无深度附件、LINEAR 过滤），按主画面尺寸创建 / 跟随窗口 resize，与 lane 的共用离屏缓冲 `offscreenTarget` 同处理方式——显存不随 lane 数增长。

### 顺序与 alpha 契约（定稿）

```
lane 渲染（含 lane 内描边）→ 相机片段调色（只动 RGB）→ 合成（opacity / dest / source）
→ 全部 lane 完成后 → master 调整（ADJUST 轨，合成输出）
```

- 调色只动 RGB：着色器 `fragColor.a = src.a` 逐位直通（lane 纹理里那些 a<255 的像素——星星 127、图集 mipmap 边缘 146~254——在相机片段调色后保持原样）；**透明度（opacity / alpha）一律在画面合成完成之后、由合成层调控**（`opacity` / `ColorModulator.a`），绝不烤进画面、也不在调色里先处理 alpha。该契约写在 `ic_color_adjust.fsh` 文件头与 `ColorAdjustPass` / `LaneRenderer` / `ColorAdjustSampler` 的类注释里。
- **默认零差异**：无相机片段调色 / 参数恒等 / 着色器不可用 → 不建 `adjustTarget`、不跑 pass、sink 收到的仍是 lane FBO——与不带该功能的路径逐位一致。

### 验证

- `sh gradlew compileJava`（`:common` / `:fabric` / `:forge` 三模块）**通过**。
- 无头 validator（`E:/tmp/icv` 的 `Validate`，真实 `ScriptValidator`）扫 `cinematics/tests/adjust`（8 个，含新增 `test_adjust_camera_clip.json`；`test_adjust_lane_scope.json` 已删除）与 `cinematics/release/quadrant.json`：13 脚本 **0 issue**。
- 本轮验收项（数据层冒烟：相机片段采样——每关键帧都写的插值、恒等归一化为 null、重叠窗口多 lane 各自按片段本地时间采样、ADJUST master 路径不变、`scope` 字段被 validator 拒；GL harness 复跑 = shader 未改的回归）：随任务 1 验收执行。
- **未验证**：游戏内实际画面（需启动客户端；相机片段调色的观感 / 性能未实测）；Java 侧 `applyTo` 的 GL 调用序列（需要 Minecraft 实例，无法无头跑——harness 覆盖的是它依赖的 GLSL 与「源 → 目标」方向）。

---

## 1. 定位（已确认）

对画面做**颜色处理**：把画面的 RGBA 原始通道拆出来运算、接入完整 HSL、提供一组调整参数，实现画面整体（及分层）调色。

模型对标 Photoshop（已确认）：

> **各自持有各自的**（每层独立调整）**+ 总体能叠加**（调整层 = ADJUST 轨：顶层、不参与排序、默认比其他层级高一个，管整幅画面）。

- 调整参数**全部可关键帧**（方向，与全模组一致）：调色可以随时间淡入淡出。
- 第一版只做整体（master），结构上给分层留位，不堵死（与并行播放“先立模型、不设限制”的原则一致）。

**与颜色遮罩的边界（已确认）**：

| | 颜色遮罩（overlay-color-mask） | 本文（颜色调整） |
|---|---|---|
| 动作 | 往上**盖**一层颜色（新增像素层） | **改**已有画面的颜色（后处理运算） |
| 例子 | fade 黑场、渐变遮罩、混合模式 | 黑白化、偏色、降饱和、只留红色通道 |

- 两篇互补，不合并；mask 文档 §2.2 的“颜色调色 / 滤镜（LUT、亮度对比度）”归本文。
- **alpha 不归本文**：RGBA 的 A 通道属于覆盖层合成的「不透明度」（`variable-frame.md` 统一参数 + `camera-composition.md` 合成参数），调色只动 RGB / HSL；透明度控制与转场（叠化 / 黑场 / 白场）见[画面转场](./scene-transition.md)。

### 1.1 目标形态：现代调色系统（已确认·2026-10-07）

调研 DaVinci Resolve / Photoshop / After Effects 三套调色体系的组织方式与工具面：

| 系统 | 组织方式 | 核心工具 | 关键帧 |
|---|---|---|---|
| DaVinci Resolve | 节点图（串行 / 并行 / 图层节点），每个 corrector 节点一套完整工具 | Lift/Gamma/Gain 色轮、主色条、LOG 色轮、自定义曲线（RGB + **六条 hue 曲线**：HvH / HvS / HvL、LvS / SvS / SvL）、HSL 限定器、RGB 混合器、通道互换、LUT、窗口 / 跟踪 | 每个参数可打关键帧（动态调色） |
| Photoshop | **调整图层**（非破坏，各持一种调整）+ 图层混合模式 + 蒙版 | 曲线（复合 + 每通道）、色相 / 饱和度（主 + 六色带）、色阶、色彩平衡、通道混合器、可选颜色、LUT、黑白、反相 | 图层不透明度 / 蒙版可动（工具本身静态） |
| After Effects | 效果栈 + **调整层**（影响下方各层） | Lumetri：基本校正 / 创意 / 曲线（RGB + 每通道）/ 色轮（阴影 / 中间调 / 高光）/ 色相饱和度曲线（六条）/ HSL 二级 / 暗角 | **全参数关键帧（含曲线本身）** |

**共性模型（本文采用）**：

1. **非破坏操作栈**：调整按固定顺序串成一条链，每项操作独立可开关——不是"一份参数一次算完"。
2. **三重视角**：复合 RGB（亮度对比度类）+ 每通道 R/G/B + HSL（色相曲线）——同一像素的三种换算，在 shader 内统一表达。
3. **曲线是最高表达力工具**：RGB 曲线 + 六条 hue 曲线覆盖绝大多数风格化调色；且**曲线本身可关键帧**（用户点名需求）。
4. **分层（两级）**：lane 级（对象级，每个 lane 各自持有）→ **调整层 / master**（= ADJUST 轨：顶层、不参与排序、默认比其他层级高一个，管整幅画面）——即 §2 的 PS 式模型。
5. **画面是 RGBA**：所有调整都是逐像素 shader 运算，无素材依赖，实现成本低——"调整不难写"成立。

---

## 2. 调整模型讨论：整体单一 vs PS 式分层（核心）

### 2.1 整体单一调整

**形态**：一份参数作用于合成后的最终画面（只有一个 master）。

- 优：
  - 实现简单——恒定一个全屏 pass（全屏 quad，开销与内容无关；注意源码事实：原版单效果链实际是「效果 pass + blit 回 main」两个 pass，见 §4）；
  - 参数只有一份，作者零学习成本；
  - 与现有 fade 遮罩的“一份参数管全屏”心智一致。
- 劣：
  - PIP / 分屏时主副画面只能同一个色调——做不到“副画面黑白、主画面正常”；
  - 与 lane 模型不对齐：lane 是层，却不能对层调色；
  - 多脚本并行时调整归属不清——谁的全局？两个实例各写一份 master 时听谁的？

### 2.2 PS 式分层调整

**形态**：每个 lane / 图层**各自持有**调整；**调整层 = 总体 master**（ADJUST 轨：顶层、不参与排序、默认比其他层级高一个，管整幅画面）——无独立新层。

- 优：
  - 副画面可独立调色（老电影 PIP、偏绿的监控画面、夜视仪小窗）；
  - 与画面合成的 lane 模型天然对齐——层即 lane，调整即层的属性；
  - 多脚本并行时调整随各自 lane 走，归属清晰（见[并行播放](./parallel-playback.md)）；
  - 表达力上限 = PS。
- 劣：
  - 实现复杂——每层一个 pass，或把层内多个调整合并进一次 shader；
  - 参数组织依赖层序，作者要理解叠放规则；
  - 性能随层数增长（每层一次全屏 / 半屏运算）；
  - 编辑器 UI 复杂度高（层列表 + 每层参数面板）。

### 2.3 结论（已确认）

- **目标模型 = PS 式**（各自持有 + 总体叠加）。
- **落地分期**：第一步只做 master（= ADJUST 轨 = 调整层，整体单一正好是 PS 模型的最小子集）；lane 级调整作为后续批次（§7 步骤 5），数据模型第一步就留好位置。

**补充（2026-10-08 用户口径，与 §1.1 一致）**：

- 分层**两级**定名：**lane 级**（每个画面 lane 的 FBO 内、合成前）→ **调整层 / master**（= ADJUST 轨：顶层、不参与排序、默认比其他层级高一个，管整幅画面——**最终显示画面也要调**）。用户口径（2026-10-08）：「调整层 = ADJUST 轨的现有顶层位置——它不参与排序，默认就比其他层级高一个」，无独立中间层。
- 操作栈顺序固定（非破坏）；同一层内多个调整合并为一次 pass（§4）。

---

## 3. 调整参数（方向，分批清单）

| 批次 | 工具 | 说明 |
|---|---|---|
| **第一批（master 基础）** | 曝光 / 对比度 / 高光 / 阴影 / 白 / 黑、饱和度、自然饱和度（vibrance）、色温 / 色调、灰度（带强度）、反相（带强度）、**RGB 复合曲线（点 + 贝塞尔手柄）**、**每通道曲线（R / G / B）** | 覆盖基础校色 + 最常见风格化；全部可关键帧 |
| **第二批（通道与 HSL 完整）** | RGB 通道混合器（Channel Mixer）、色相旋转、**六条 hue 曲线**（HvH / HvS / HvL、LvS / SvS / SvL）、Lift / Gamma / Gain 色轮（或 LOG 色轮） | 对标 DaVinci 曲线页与色轮；副画面独立调色的主力 |
| **第三批（进阶）** | LUT（Color Lookup）、PS 式六色带微调（Hue / Sat 分色带）、混合模式作用于调整层（= ADJUST 轨） | 预烘焙风格 / 精细分区；排期最晚 |

- 各批内部**顺序固定**（操作栈，非破坏）；加批是"加工具"，不是"改模型"。
- **不做**：限定器（qualifier）/ 窗口 / 跟踪器——属合成与选区问题，超出画面调色范围，远期另议。

### 3.1 关键帧语义：标量与曲线（已确认方向·2026-10-07）

- **标量参数**：普通关键帧通道（沿用现有 `Keyframe { time, data }` 体系），逐参数可打点、可插值。
- **曲线参数（用户点名：关键帧调控 HSL 曲线）**：两种形态，执行时定——
  - 形态 a（曲线点集关键帧）：每个关键帧存整条曲线的控制点 + 手柄，关键帧间逐点插值；表达力最强、数据量大；
  - 形态 b（曲线 + 强度关键帧）：曲线定义一次（静态），关键帧只控 `strength`（0~1 混合量）——AE 常用做法，开销小、语义清晰。
  - 倾向：**先 b 后 a**（b 零成本起步；a 作为曲线编辑器的后续增强）。
- 每个调整项有 `enabled` 可关键帧（整个操作淡入 / 淡出）。

---

## 4. 实现思路（方向）

- **全屏后处理 pass**：master 在合成输出上做；lane 级在 lane 纹理合成前做。
- 数学在 shader 内完成：RGBA 通道运算 + RGB↔HSL 转换。
- **同一层的多个调整参数合并为一次 pass**——不为每个参数单独开 pass。
- 数据落点（**已定稿：独立 ADJUST 轨道**，见文首「落地标注」）：~~OVERLAY 轨新层类型 vs 独立调整轨——与遮罩文档 §4 开放问题 4 一起定。~~（现状事实：OVERLAY 的 `layer_type` 白名单只有 `fade` / `image` / `subtitle`，`TrackType` 枚举里没有调整类轨道，见事实核查小节。）
- **shader 数学（方向）**：RGB↔HSL 标准换算（复用 / 参照原版 `color_convolve.fsh` 的 Luma / Chroma 写法）；Lift / Gamma / Gain = 按色调分段的多项式 / 幂次映射；**曲线 = 预烘焙查找纹理**（如 256×1 LUT，由控制点 + 手柄在 CPU 侧采样生成）或 shader 内贝塞尔求值——执行时定；六条 hue 曲线 = 以 hue 为键的 1D LUT（HvH / HvS / HvL）+ 以 sat / lum 为键的 1D LUT。
- **一次 pass 合并**：同一层所有操作按栈顺序合成为一个 shader（或少量固定 pass），不为每个工具单独开 pass。
- **分层挂点**：lane 级 = lane FBO 内、合成上屏前（`quadrant-prototype-results.md` §3.2「lane 自包含」）；master = 合成输出上、**原版 RPOST 之后、GUI 之前**（2026-10-08 用户裁决后移——GUI 层含黑边 / 字幕 / 黑白场，需精确色值、不参与调色；`mod-architecture-diagram.md` 已补 RADJ 节点）。

**源码事实：原版后处理链（1.20.1，供落地时参照）**

> 依据：`minecraft-merged-…-sources.jar`（`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-d95c7b3016/1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2/`）内同名类；行号为该 jar 内行号。以下只描述原版机制，不代表已定方案。

- **三件套**：`net.minecraft.client.renderer.PostChain`（一条链）→ `PostPass`（链里一个 pass）→ `EffectInstance`（编译后的 program）。`GameRenderer` 持有 `@Nullable PostChain postEffect`（`GameRenderer.java:134`）；`GameRenderer.loadEffect(ResourceLocation)` 以 `new PostChain(textureManager, resourceManager, minecraft.getMainRenderTarget(), loc)` + `postEffect.resize(...)` 建立（`:345-346`），另有 `currentEffect()`（`:674`）、`shutdownEffect()`（`:299`）。
- **确切调用位置（世界之后、GUI 之前）**：`GameRenderer.render(float,long,boolean)`（`:870`）内依次为 `renderLevel(...)`（`:884`）→ `tryTakeScreenshotIfNeeded()`（`:885`）→ `levelRenderer.doEntityOutline()`（`:886`）→ `if (postEffect != null && effectActive) { RenderSystem.disableBlend(); disableDepthTest(); resetTextureMatrix(); postEffect.process(f); }`（`:887-892`）→ `minecraft.getMainRenderTarget().bindWrite(true)`（`:893`）→ GUI 正交投影（`:897-898`）→ `gui.render(...)`（`:917`）→ overlay / screen / toasts。整帧最后一步在 `Minecraft.runTick(boolean)`（`Minecraft.java:980`）：`mainRenderTarget.unbindWrite()`（`:1044`）→ `mainRenderTarget.blitToScreen(window.getWidth(), window.getHeight())`（`:1045`）。
  - 与[多相机渲染](./multi-camera-rendering.md) / `quadrant-prototype-results.md` §3.2、§140 的「`renderLevel` 之后还有哪些整屏步骤」清单一致：`doEntityOutline` / `postEffect` / `tryTakeScreenshotIfNeeded` / `Minecraft` 最后的 `blitToScreen`。
- **一个 pass 画什么**：`PostPass.process(float)`（`PostPass.java:62`）解绑 `inTarget`、把 viewport 设成 out target 尺寸、`setSampler("DiffuseSampler", inTarget::getColorTextureId)`、写 `ProjMat` / `InSize` / `OutSize` / `Time` / `ScreenSize`，然后 `outTarget.clear(...)` + `outTarget.bindWrite(false)`，画 **QUADS / POSITION 的 4 顶点全屏四边形**（覆盖 out target 全幅，z=500）→ 一个 pass 的成本 = 一次全屏 quad 绘制，与场景内容无关。
- **链的驱动与尺寸**：`PostChain.process(float)`（`PostChain.java:288`）推进内部时间（以 `time/20` 传给每个 pass）后顺序调用 `passes` 中每个 `PostPass.process`；`PostChain.resize(int,int)`（`:276`）重算正交矩阵并 resize 全尺寸临时 target。
- **链的 JSON 结构**：`PostChain.load`（`:62-102`）解析 `targets` / `passes`；`parsePassNode`（`:119-198`）要求每个 pass 有 `name` / `intarget` / `outtarget`，可选 `auxtargets` / `uniforms`；`"minecraft:main"` 是 `screenTarget` 的别名（`getRenderTarget`，`:309-316`）；`addTempTarget`（`:246`）用 `TextureTarget` 建临时缓冲。
- **命名空间约束**：`EffectInstance` 的程序 JSON 路径硬编码为 `shaders/program/<name>.json`（`EffectInstance.java:43,65`），而 `new ResourceLocation(String)` 默认命名空间是 `minecraft`（`ResourceLocation.java:31,49,76`）→ 沿用原版 `PostChain` 机制时，post / program JSON 需落在 `assets/minecraft/shaders/{post,program}/` 下。仓库当前**没有任何 shader 资源**（`resources/**/shaders/**` 无匹配）。
- **原版已有先例**：`GameRenderer.EFFECTS` 数组含 `shaders/post/color_convolve.json`、`shaders/post/invert.json`（`GameRenderer.java:135`）。`assets/minecraft/shaders/post/color_convolve.json` 是「main → swap（`color_convolve`）→ main（`blit`）」两个 pass；其 `program/color_convolve.json` 的 uniform 有 `Gray`(0.3, 0.59, 0.11) / `RedMatrix` / `GreenMatrix` / `BlueMatrix` / `Offset` / `ColorScale` / `Saturation`，fsh 里 `OutColor = (Chroma * Saturation) + Luma`（未钳制）→ 「通道矩阵 + 增益/偏移 + 饱和度」在原版就有可参照实现。
- **全局状态语义**：`RenderSystem.setProjectionMatrix(Matrix4f, VertexSorting)` 写的是 `RenderSystem` 的静态字段（`RenderSystem.java:814-825`，配套 `_backupProjectionMatrix` / `_restoreProjectionMatrix`，`:876-892`）；`RenderTarget._blitToScreen` 也会改全局投影（`RenderTarget.java:221`）——与 `quadrant-prototype-results.md` §3.3 结论一致，pass 前后需保存/还原投影。`RenderTarget.bindWrite(true)` 会顺带把 viewport 重置为目标全尺寸，`bindWrite(false)` 只绑 FBO（`RenderTarget.java:177-191`）。
- **lane 级**：lane 的 pass 必须在 lane 自己的 FBO 内完成——与[多相机渲染](./multi-camera-rendering.md)「lane 自包含」、`quadrant-prototype-results.md` §3.2 的结论一致。
- **光影（Iris / Oculus）挂点**：Iris / Oculus 在 `GameRenderer.renderLevel` 的 `TAIL` 调 `finalizeGameRendering()`（`example/Iris-1.20.1/.../mixin/MixinGameRenderer.java:461-464`、`example/Oculus-1.20.1-new/.../mixin/MixinGameRenderer.java:462-464`）；Iris 的最终合成在 `LevelRenderer.renderLevel` 尾部 `finalizeLevelRendering()` → `compositeRenderer.renderAll()` + `finalPassRenderer.renderFinalPass()`（`IrisRenderingPipeline.java:1083-1087`；`finalizeGameRendering()` 做色彩空间转换，`:1090-1092`）→ 都**早于**原版 `postEffect` 那一步（无光影包时 `VanillaRenderingPipeline` 两个方法都是空实现，`:106-114`）。取舍仍待定（见 §5）。

---

## 5. 可能的问题

- 后处理与光影（Iris / Oculus）的顺序：我们的 pass 在谁之后执行。→ **已结清（2026-10-08）**：本 pass 在原版 RPOST **之后**、GUI 之前；Iris / Oculus 的 `finalizeGameRendering()`（`GameRenderer.renderLevel` 的 TAIL）与 Iris 的最终合成（`LevelRenderer.renderLevel` 尾部的 `finalizeLevelRendering()`）都**早于**本 pass——读到的是光影处理后的画面，不被跳过、不双重应用。见 [iris-oculus-compat.md](./iris-oculus-compat.md)「主画面挂点兼容性结论（2026-10-08）」。
- RGB↔HSL 转换的边界：灰点色相未定、饱和度溢出钳制。（可参照原版 `program/color_convolve.fsh`：`Luma = dot(OutColor, Gray)`（Gray = 0.3 / 0.59 / 0.11）、`OutColor = (Chroma * Saturation) + Luma`，**未做钳制**。见 §4。）→ **已结清**：灰点走 `S ≤ 0` 分支直接返回灰度；S 钳制 0~1；亮度用 Rec.709（见文首「落地标注」的 shader 数学）。
- 调整是否影响 GUI 层（字幕 / letterbox / 跳过提示）：倾向不影响，待定。（源码事实：原版 `postEffect` 位于 GUI 之前，而 FadeLayer / letterbox / subtitle / 跳过提示都在 GUI 阶段绘制——Forge `RenderGuiEvent.Post` → `ClientEventHandler.onRenderHud` → `OverlayManager.render` → `FadeLayer.render`（`guiGraphics.fill`）；整帧最后的 `Minecraft.blitToScreen` 在 GUI 之后。挂前者天然不影响 GUI，挂后者会影响。）→ **已结清：不影响**（挂点在 GUI 之前、原版 RPOST 之后，见 §4「渲染挂点」；副作用：F2 截图**不带**调色——截图取在挂点之前）。
- 多实例各写 master 的冲突规则（与并行播放的并集规则对齐：后写覆盖？按实例层级？）。（并行播放 §3.2 的「取并集」只覆盖行为开关（`hide_hud` / 键鼠屏蔽 / `suppress_bob`），不含「单一 master 参数集」的合并语义，所以此处仍是开放问题。）
- 编辑器：滑杆 / 通道 UI 与实时预览。
- 性能：master 一次全屏 pass 可忽略；lane 级随 lane 数增长。（源码事实：「与内容无关」有依据——一个 pass = 一次全屏 quad（`PostPass.process`）；「可忽略」是定量判断，本次**未实测**。）

---

## 6. 待定

- ~~第一版参数清单（§3 表里选哪些）~~ → **已定：第一批标量组 12 通道**（见文首「落地标注」的字段表；曲线组另计）。
- ~~数据落点（层类型 / 轨道）~~ → **已定：独立 ADJUST 轨道**（`TrackType.ADJUST`，JSON `"type": "adjust"`；理由与字段表见文首「落地标注」）。
- ~~调整是否影响 GUI 层~~ → **已定：不影响**（见 §5 与文首「落地标注」）。
- LUT 的时机。
- ~~调整层的排期~~ → **已结清：调整层 = ADJUST 轨**（顶层、不参与排序、默认比其他层级高一个，管整幅画面），无新机制；lane 级（步骤 5）已落地（2026-10-07）。
- 曲线关键帧形态 a / b 的选择（倾向先 b 后 a，§3.1）。
- 编辑器曲线编辑器与色轮的实现形态（自绘 vs 复用；与脚本模型 §6 的"参数寻址到分量"共用曲线编辑能力）。

---

## 7. 落地顺序（方向）

| # | 步骤 | 交付物（完成后我们要什么） |
|---|---|---|
| 1 | master 第一批工具 + RGB 复合曲线（形态 b：曲线 + 强度关键帧）：曝光 / 对比度 / 高光阴影 / 饱和度 / 灰度等，全部可关键帧 | 脚本可让整画面黑白化 / 偏色 / 曲线风格化，并随时间淡入淡出 |
| 2 | RGBA 通道拆分 + 完整 HSL + 每通道曲线（R / G / B） | 通道级调整可用（只留红通道、单通道曲线） |
| 3 | 数据落点定稿 + 编辑器 UI（滑杆 + 曲线编辑器 + 实时预览 + 关键帧打点） | WebUI 内可调参数、打关键帧并实时看到效果 |
| 4 | 第二批：六条 hue 曲线 + RGB 通道混合器 + Lift / Gamma / Gain 色轮 | 达芬奇式曲线 / 色轮调色可用 |
| 5 | 相机片段调色（依赖画面合成） | PIP 副画面单独调色：老电影 PIP、主画面不变（调色写在 CAMERA 片段上，作用于该轨产出的 lane） |
| 6 | 第三批（LUT / 六色带 / 混合模式） | 预烘焙风格 / 精细分区；「调整层」部分已由 ADJUST 轨承担（无新机制），本步骤只剩第三批工具 |

> 步骤 1–4 不依赖画面合成，可先行；步骤 5 起依赖 lane 上屏；步骤 6 只剩第三批工具（调整层已由 ADJUST 轨承担，无新机制，不依赖新分层模型落地）。
>
> **进度（2026-10-08）**：步骤 1 的**标量组**（12 通道 master pass）与**曲线组**（RGB 复合曲线 + 每通道曲线，形态 b）已落地；步骤 2 的**曲线部分（每通道曲线 R / G / B）已落地（2026-10-08）**，同步骤的「RGBA 通道拆分」（`red` / `green` / `blue` 通道系数，2026-10-07）与「完整 HSL」（`hue` / `lightness` + 既有 `saturation` / `vibrance`，2026-10-07）也已落地 → **步骤 2 的三块全部落地**；步骤 3 的**数据落点已定稿**、编辑器 UI 未做；**步骤 4 全部落地（2026-10-08）——六条 hue 曲线（HvH / HvS / HvL、LvS / SvS / SvL）、RGB 通道混合器（`mix_rr` ~ `mix_bb` 3×3 矩阵）与 Lift / Gamma / Gain 色轮（`lift_r` ~ `gain_b` 九个逐通道色轮）都已实现 → 第二批（通道与 HSL 完整）全部落地，标量通道共 35 个 + 10 条曲线**；**步骤 5（lane 级调色）已落地**（调色写在 CAMERA 片段上——相机片段自带调色，作用于该相机轨产出的每条 lane，lane 渲染完 / 合成前过 pass；ADJUST 轨只作用于整体画面）；步骤 6 未做。

---

## 事实核查（2026-10-07）

核查依据：

- 本仓库：`overlay/FadeLayer.java`、`overlay/PipLayer.java`、`overlay/OverlayManager.java`、`script/OverlayTrackPlayer.java`、`script/TrackType.java`、`script/ScriptValidator.java`、`script/schema/TrackSchemas.java`、`handler/ClientEventHandler.java`、`forge/.../ForgeClientEvents.java`、`proto/QuadrantProto.java`、`mixin/MinecraftAccessor.java`、`resources/**`。
- MC 1.20.1 源码：`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-d95c7b3016/1.20.1-loom.mappings.1_20_1.layered+hash.2198-v2/…-sources.jar` 内 `GameRenderer` / `PostChain` / `PostPass` / `EffectInstance` / `RenderTarget` / `RenderSystem` / `Minecraft` / `ResourceLocation`（**行号即该 jar 内行号**）；同目录合并 jar 内 `assets/minecraft/shaders/post|program/*.json|*.fsh|*.vsh`。
- example 参考模组：`example/Iris-1.20.1`、`example/Oculus-1.20.1-new`。
- 当前 `gradle.properties`：`version=0.3.5`。

### ① 核实为真的断言

1. **§1「颜色遮罩 = 往上盖一层颜色（新增像素层）」**：`overlay/FadeLayer.java` `render(GuiGraphics, int, int)` 计算 `int argb = (alpha << 24) | (color & 0x00FFFFFF)` 后 `guiGraphics.fill(0, 0, screenWidth, screenHeight, argb)` —— 全屏纯色填充，**没有任何通道运算**；`script/OverlayTrackPlayer.java` 对 fade 只 `fl.setColor(...)`（`createLayer` 的 fade 分支 `:119-124`）与 `fl.setOpacity(opacity)`（`updateLayer`，`:181-182`）。✅
2. **§1「两篇互补，不合并；mask 文档 §2.2 的调色/滤镜归本文」**：`plans/0.3.6/overlay-color-mask.md` §2.2 已写成「~~颜色调色/滤镜~~ → **已移交 `screen-color-adjust.md`**：RGBA 通道拆分、完整 HSL、亮度/对比度等」。两文档无冲突。✅
3. **§2.1「恒定一个全屏 pass，开销与内容无关」**：`PostPass.process(float)`（`PostPass.java:62`）画 4 顶点 QUADS/POSITION 全屏四边形（覆盖 out target 全幅、z=500），采样 `DiffuseSampler`；`PostChain.process(float)`（`:288`）顺序驱动 passes → 「与内容无关」成立。✅（「恒定一个 pass」的措辞见 ②-1）
4. **§2.1「与现有 fade 遮罩的『一份参数管全屏』心智一致」**：FadeLayer 只有单一 `color` + 单一 `opacity` 通道，作用于全屏。✅
5. **§2.3 / §4「OVERLAY 轨现有层类型只有 fade/image/subtitle/pip」**：`OverlayTrackPlayer.createLayer`（`:113-157`）switch 四类 + `default` 打 warn；`ScriptValidator.java:180` `checkEnum(..., "layer_type", ..., "fade", "image", "subtitle", "pip")`；`schema/TrackSchemas.java:144` enum 四值。三处一致。✅
6. **§4「数据落点（待定）：OVERLAY 轨新层类型 vs 独立调整轨」的现状前提**：`script/TrackType.java` 枚举只有 CAMERA / LETTERBOX / AUDIO / EVENT / MOD_EVENT / OVERLAY，**没有调整类轨道**。✅（"待定"状态保留，未改成已决定）
7. **§2.2「与画面合成的 lane 模型天然对齐」**：`camera-composition.md`「每个画面 lane 的输出是**一张纹理**」；`parallel-playback.md` §2「原则（已确认）：造好用的底层，而不是造限制多的工具」（本文 §1 的「先立模型、不设限制」是该句的转述）。✅
8. **§4「lane 级在 lane 纹理合成前做」**：`quadrant-prototype-results.md` §3.2「lane 的 `renderLevel` **加上所有 lane 级后处理**都必须在 lane 的 FBO 内完成」；`multi-camera-rendering.md` 表「lane 自包含」行同结论。✅
9. **§5「与并行播放的并集规则对齐」**：`parallel-playback.md` §3.2「运行时控制取并集（已确认）」范围是行为开关（`hide_hud` / 键鼠屏蔽 / `suppress_bob`），不涉及「单一 master 参数集」的合并语义 → 本文该开放问题成立。✅
10. **§4「master 在合成输出上做」**：合成输出即 `Minecraft.mainRenderTarget`；原版 `postEffect` 链的 screenTarget 就是 `minecraft.getMainRenderTarget()`（`GameRenderer.loadEffect`，`:345`），lane 原型也靠 `MinecraftAccessor.ic$setMainRenderTarget` 把渲染导向 lane FBO。✅

### ② 已修正的断言

1. **§2.1 优「恒定一个全屏 pass」→ 已修正为**「恒定一个全屏 pass（全屏 quad，开销与内容无关；注意源码事实：原版单效果链实际是「效果 pass + blit 回 main」两个 pass）」。依据：`assets/minecraft/shaders/post/color_convolve.json` 是 `color_convolve`（`minecraft:main` → `swap`）+ `blit`（`swap` → `minecraft:main`）两个 pass，`post/invert.json` 同构 → 「与内容无关」成立，但「恒定一个 pass」在原版 swap 模式下不成立（除非自建单 pass 链）。
2. **未发现与源码矛盾的现状断言。** 一处措辞澄清（未改文档结论）：`overlay/FadeLayer.java:3` 的类 javadoc 写「用于淡入淡出和**色彩滤镜**」，而实现只有纯色填充、无任何颜色运算。**冲突裁决**：`git log -1 --format=%cI` → `overlay/FadeLayer.java` = **2026-08-18T22:58:38+08:00**；`plans/0.3.6/screen-color-adjust.md` = **2026-10-05T11:48:34+08:00** → **文档较晚，且文档的「盖一层颜色」与实现一致，以文档为准**；javadoc 的「色彩滤镜」不代表现有能力（本文档不改该 javadoc，仅记录）。
3. **跨文档无冲突**：mask 文档（`2026-10-05T11:48:34+08:00`）已自行把调色/滤镜条目标记为移交本文，与本文 §1 引用一致（未改他人文档）。

### ③ 补全的信息（已写入 §4「源码事实」与 §5）

1. 原版后处理链的类 / 方法 / 字段：`PostChain`、`PostPass`、`EffectInstance`；`GameRenderer.postEffect`（`:134`）、`loadEffect`（`:345-346`）、`currentEffect`（`:674`）、`shutdownEffect`（`:299`）、`EFFECTS`（`:135`）。
2. **调用位置**：`GameRenderer.render(float,long,boolean)`（`:870`）→ `renderLevel`（`:884`）→ `tryTakeScreenshotIfNeeded`（`:885`）→ `levelRenderer.doEntityOutline()`（`:886`）→ `postEffect.process(f)`（`:887-892`）→ `getMainRenderTarget().bindWrite(true)`（`:893`）→ GUI 正交投影（`:897-898`）→ `gui.render`（`:917`）；帧末 `Minecraft.runTick(boolean)`（`Minecraft.java:980`）→ `unbindWrite()`（`:1044`）→ `blitToScreen(...)`（`:1045`）→ **后处理 pass 位于「世界渲染之后、GUI 之前」**，与 multi-camera-rendering / `quadrant-prototype-results.md` §3.2、§140 的整屏步骤清单一致。
3. pass 绘制语义（全屏 quad、`DiffuseSampler`、`ProjMat`/`InSize`/`OutSize`/`Time`/`ScreenSize`、`outTarget.clear` + `bindWrite(false)`）与链驱动（`PostChain.process` `:288`、`PostChain.resize` `:276`）。
4. 链 JSON 结构：`targets` / `passes`；每个 pass 必填 `name` / `intarget` / `outtarget`，可选 `auxtargets` / `uniforms`；`"minecraft:main"` 是 screenTarget 别名（`getRenderTarget` `:309-316`）；`addTempTarget`（`:246`）建 `TextureTarget`。
5. **命名空间约束**：`EffectInstance` 程序路径硬编码 `shaders/program/<name>.json`（`:43,65`），`new ResourceLocation(String)` 默认命名空间 `minecraft`（`ResourceLocation.java:31,49,76`）→ 沿用原版 `PostChain` 时 shader 资产需放 `assets/minecraft/shaders/{post,program}/`。**仓库现状：没有任何 shader 资源**（`resources/**/shaders/**` 无匹配；`common/src/main/resources/assets/immersive_cinematics/` 下只有 `lang` / `textures` / `icon.png`）。
6. 全局状态语义：`RenderSystem.setProjectionMatrix` 写静态字段（`:814-825`，配套 `_backupProjectionMatrix` / `_restoreProjectionMatrix` `:876-892`）；`RenderTarget._blitToScreen` 改全局投影（`:221`）；`bindWrite(true)` 顺带重置 viewport 为目标全尺寸、`bindWrite(false)` 只绑 FBO（`:177-191`）。
7. 原版先例：`assets/minecraft/shaders/post/color_convolve.json`（main→swap→main 两 pass）与 `program/color_convolve.json` + `.fsh`（uniform：`Gray`(0.3, 0.59, 0.11) / `RedMatrix` / `GreenMatrix` / `BlueMatrix` / `Offset` / `ColorScale` / `Saturation`；`OutColor = (Chroma * Saturation) + Luma`，未钳制）；`post/invert.json` + `program/invert.json`（`InverseAmount`）。
8. 光影挂点：Iris / Oculus 在 `GameRenderer.renderLevel` `TAIL` 调 `finalizeGameRendering()`（`example/Iris-1.20.1/.../mixin/MixinGameRenderer.java:461-464`、`example/Oculus-1.20.1-new/.../mixin/MixinGameRenderer.java:462-464`）；Iris 最终合成在 `LevelRenderer.renderLevel` 尾部 `finalizeLevelRendering()` → `compositeRenderer.renderAll()` + `finalPassRenderer.renderFinalPass()`（`IrisRenderingPipeline.java:1083-1087`；`finalizeGameRendering()` 做色彩空间转换 `:1090-1092`）；无光影包时 `VanillaRenderingPipeline` 两个方法为空实现（`:106-114`）。
9. `overlay/PipLayer.java` 现状（与 §7 步骤 4 相关）：仅半透明黑填充 + 2px 白边，不绑定任何纹理（`FILL_COLOR = 0x40000000`、`BORDER_COLOR = 0xFFFFFFFF`、`BORDER_WIDTH = 2`）——与 `camera-composition.md`「OVERLAY 现有 `pip` 层是静态占位」一致。另：`render(...)` 里算出的 `fillArgb` / `borderArgb` **从未被使用**（`guiGraphics.fill` 传的是常量 `FILL_COLOR` / `BORDER_COLOR`），即 `opacity` 对 pip 的填充/边框不生效（与 `camera-composition.md` 第 124 行的同一断言相符）。

### ④ 无法核实的断言（未验证）

1. **§5「性能：master 一次全屏 pass 可忽略」的定量部分**：未实测，仓库与 `quadrant-perf/` 内没有该 pass 的实测数据（"与内容无关"有源码依据，见 ①-3）。
2. **§7 步骤 3「WebUI 内可调参数并实时看到效果」的现状支撑**：本次未核查 `editor/src` 与 `webui/` 的预览链路（现有 `webui/WebFrameCapture` 只做缩略帧回读，能否承载实时调色未核实）。
3. **§3 的 LUT / 曝光 / 伽马等参数方向**：纯方向，无现状可核，保持「执行时定」。
4. ~~**光影下「我们的 pass 具体挂哪一步」的最终取舍**~~ → **已定（2026-10-08）**：挂点后移到原版 RPOST 之后、GUI 之前，与 Iris 的 `finalizeGameRendering()` 完全解耦；见 §4「渲染挂点」与 [iris-oculus-compat.md](./iris-oculus-compat.md)「主画面挂点兼容性结论（2026-10-08）」。
