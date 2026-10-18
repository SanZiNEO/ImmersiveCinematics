# D4–D7：高级颜色覆盖层执行契约（2026-10-17）

> **范围说明（2026-10-17）**：本文只记录 D4–D7。D1–D3 已另行收口（纯显式 clip / 关键帧，C 方案为最终合同），实现与验收见 [scene-transition.md](scene-transition.md) §9；本文当时把 D1–D3 列为未验证边界，该边界已解除。
>
> **当前状态（2026-10-18 回写）**：D4–D7 已随 D 轮关单（2026-10-17），本文是执行契约与验收的历史记录；表中能力全部落地，无遗留待办。仍开放的只有编辑器配置面板（编辑器阶段）与 Forge / Iris / Oculus 未实机（验收边界）。

## 目标与依据

本轮完整实现 D4 多色线性 / 径向渐变、D5 五种混合、D6 局部矩形 / 圆形、D7 高对比层级演示。依据 `overlay-color-mask.md` §2 / §5、README“先底层后应用 / 纯数据执行 / 浮点比例”、`scene-transition.md` §1。扩展既有 `layer_type=fade`，不新增 layer_type / 轨道 / 转场机制；编辑器最后做。字幕完整呈现，不做裁切。

此前 MC / example 调研继续作为前置证据：GuiGraphics 批次必须在改变 GL 状态前 flush；柔光 / 叠加需要读取 backdrop，不能靠固定 blend factor；BBS ColorStop 段内线性插值、Olive mask / shape 参数化可借鉴。本轮不把技术可行性误当额外产品需求。

## 数据形态（执行时定型）

### fade clip 结构字段

- `color`：沿用 `#RRGGBB`，缺省黑色。
- `gradient`：可选对象；缺省为纯色。`{"type":"linear|radial","stops":[{"offset":0,"color":"#000000"},{"offset":1,"color":"#FFFFFF"}]}`。至少两点，offset 有限且 0..1、严格递增，颜色为 RGB；首尾不必是 0 / 1，范围外保持端点。不增加自定义 stops 数量政策上限。
- `mask_shape`：`full`（缺省）/ `rect` / `circle`；这是颜色层覆盖范围，不是字幕或画面纹理裁切。
- `blend_mode`：`normal`（缺省）/ `multiply` / `screen` / `soft_light` / `overlay`。公式取本仓 A 轮 W3C 口径；这里的上层来源是颜色遮罩，不是 ADJUST 的调色结果。

### fade 关键帧通道

- `opacity`：沿用 0..1，缺省 1，最后作用于合成结果。
- `color`：可选 `#RRGGBB`，纯色模式下逐 RGB 通道线性插值；缺省回到 clip.color。解析后缓存颜色，不逐帧解析字符串。渐变模式颜色来自 stops。
- `mask_x` / `mask_y`：覆盖区域中心，窗口比例，缺省 0.5；允许在窗口外。
- `mask_width` / `mask_height`：rect 宽高占窗口比例，缺省 1，非负，不设人为上限；零面积不绘制。
- `mask_radius`：circle 半径 / 窗口较短边，缺省 0.5，非负；因此非方形窗口里仍为真圆，0 不绘制。
- `gradient_angle`：linear 方向角度，缺省 0（向右），90 向下；有限值。
- `gradient_center_x` / `_y`：窗口比例，缺省 0.5；有限值，可越界。
- `gradient_radius`：radial 半径 / 窗口较短边，缺省 0.5，必须 >0。

全为正常线性通道，没有缓动预设或创作运算。渐变 stops 是结构数据，关键帧驱动方向 / 中心 / 半径；不实现拓扑可变 stops 动画。已有 image / subtitle 的 x/y/source 语义不变。规则 overlay_set 的 v1 静态 fade 继续可用，不扩规则动作字段。

## 数学与渲染契约

- linear：GUI 空间点 p，方向 d=(cosθ,sinθ)，L=abs(dx)*W+abs(dy)*H；t=0.5+dot(p-center,d)/L，再按 stops 保持端点 / 段内插值。
- radial：t=distance(p,center)/(gradient_radius*min(W,H))；圆形区域同理使用实际距离，矩形按窗口比例边界。
- 合成：base 是此颜色层之前的实际屏幕内容；normal 的 B(base,src)=src，其余四式沿用已有 blend 数学；RGB=mix(base,B(base,src),opacity*shapeCoverage)。不得污染区域外、后续 GUI 或 framebuffer alpha。
- 纯色 + full + normal 保留现有 GuiGraphics.fill 快路径（不新建 FBO / shader），保持既有默认表现。
- 其余路径使用专用 core shader。normal 不需要 backdrop 副本；非 normal 先复制实际主 framebuffer 到可复用 scratch，再绘制，绝不同时读写同一纹理。scratch 不随层数量增长；不同层按当前 z 序顺次合成。
- 多色渐变允许解析后烘成 1024×1 RGB 采样表（段内线性、端点对齐，最大单通道误差按图像验收记录），不限制输入 stops 数量；每层只创建一次 GPU 纹理，不逐帧建资源 / 数组。没有渐变就不建 LUT。
- 资源生命周期必须闭合：新增默认 `OverlayLayer.close()` 供持有 GPU 资源的层释放；OverlayManager 的所有移除路径调用 close，FadeLayer 关闭自身 renderer。共享 shader / scratch 在最后 renderer 释放及资源重载时释放；F3+T 双端接入 invalidate。失败跳过本颜色层并清楚记录，不输出伪造纯黑或偷偷换回不相符效果。
- 绘制前 flush，保存 / 恢复 shader、纹理单元、GL blend / equation、depth、color mask、scissor、framebuffer/viewport；pose / projection 不留污染。缓存 BufferBuilder / 工作区，不每帧分配。

## 工作包与固定接口

所有编码代理跳过构建 / 测试 / 格式化，不提交；主代理最终统一运行。独立文件可并行，禁止覆盖其他包文件。

1. **数据全链**：新增 `script/ColorMaskSpec.java`（无 MC / GL 依赖）；`Clip.getColorMaskSpec()` 懒缓存；ScriptParser 的 fade clip 解析期调用并将非法结构转成有字段路径的 ScriptParseException；TrackSchemas 与 ScriptValidator 同步结构和通道合法性。只拥有这五类文件，不碰渲染或接线。
2. **渲染与资源生命周期**：ColorMaskRenderer / FadeLayer / OverlayLayer / OverlayManager / 两端资源重载 hook / shader 资源；不碰 schema/parser/OverlayTrackPlayer。
3. **播放接线与演示**：OverlayTrackPlayer 读取定义并逐帧传送参数；D4–D6 分阶段场景与 D7 zindex 演示；不碰数据类和渲染内部。
4. **仓库外验收资产**：准备真实解析 / 校验探针及 shader GL 像素探针，只写 E:/tmp/036-mask；不改项目文件，不自己运行。

接口：

- `ColorMaskSpec.from(Clip)`；`clip.getColorMaskSpec()`；`boolean hasGradient()`；`String gradientType()`；`float[] stopOffsets()`；`int[] stopColors()`；`String maskShape()`；`String blendMode()`；`int sampleColor(float localTime)`（纯色关键帧缓存采样）。结构数组只读，不向脚本 map 写值。
- `FadeLayer.setMaskSpec(ColorMaskSpec)`（创建时一次）；`setMaskGeometry(float x,float y,float width,float height,float radius)`；`setGradientGeometry(float angle,float centerX,float centerY,float radius)`；`setColorRgb(int rgb)`；既有 setColor(String) / setOpacity / setZIndex 保持，规则动作无改动。
- `ColorMaskRenderer` 每个高级 FadeLayer 持一实例；`render(GuiGraphics,int,int,ColorMaskSpec,int color,float opacity,float maskX,float maskY,float maskW,float maskH,float maskRadius,float gradientAngle,float gradientCenterX,float gradientCenterY,float gradientRadius)`；`close()`；静态 `invalidate()` 由两端资源重载调用。

## 验收与交付

- 解析 / validator 正例与字段路径负例，有限数、stops重复 / 倒序 / 非法色、枚举、关键帧类型与范围；旧默认fade及规则临时fade不受影响。
- GL 像素：多色分段、radial中心边缘、非正方形窗口真圆、rect区域外不变、五模式独立CPU期望、半透明、连续层顺序、后续GUI状态恢复。
- Fabric 实际客户端演示，直接截图像素测量；F3+T重载、脚本结束资源清理 / 再播放；Forge编译，未跑实机则明示。
- 同步格式文档 / 指南 / overlay模块 / changelog / 进度真源，实测通过再本地提交，不推送。**历史口径**：当时 D1–D3 未完成，不冒充完成；D1–D3 已于同日另行收口（见 `scene-transition.md` §9）。F/G/H 仍不冒充完成。

## 基准纠偏：柔光操作数（实现前核实）

主代理对照 [W3C §10.1.10](https://www.w3.org/TR/compositing-1/#blendingsoftlight) 原始规范核实：`soft_light(base, src)` 必须按 **src** 是否大于 0.5 分支，并对 **base** 计算 D 曲线。既有 `ic_color_adjust.fsh` 的两者角色写反了，不能作为新 shader 的正确性基准。本轮由渲染工作包最小修正既有函数和新 mask shader，使 A / D 同一模式语义一致，不增加模式或兼容分支。

`D(base) = base <= .25 ? ((16*base-12)*base+4)*base : sqrt(base)`；
`B(base,src) = src <= .5 ? base-(1-2*src)*base*(1-base) : base+(2*src-1)*(D(base)-base)`。

验收不能从被测 shader 抄期望值：50% 灰上层应保持底色，纯黑 / 纯白底色应保持边界；对分支上下及 D 的两个区间独立 CPU 复算。演示背景采用 `#4080C0`，避免中灰底让 normal / overlay 难以区分。


## 验收记录（2026-10-17）

| 验收面 | 实际结果 |
|---|---|
| 构建 | `sh gradlew --offline compileJava`：Common / Fabric / Forge 均 `BUILD SUCCESSFUL`；保留既有 Gradle 过时特性提示 |
| 数据合同 | 真实 `ScriptParser` / `ScriptValidator` / `ColorMaskSpec` 探针 **278 OK / 0 FAIL**；覆盖颜色回落与插值、stops、字段归属、有限值、范围和负例字段路径 |
| 新 shader | 独立 GL 像素探针 **334 OK / 0 FAIL**，覆盖五模式、非等距多色渐变、真圆、局部区域外不变、透明度和连续层顺序 |
| 既有 GL 回归 | 14 套均零失败；修正独立 W3C 柔光基准后的 `GlBlendSmoke` **240 / 0** |
| 生产渲染器 | 在真实 Fabric 客户端渲染线程调用真实 `FadeLayer` / `ColorMaskRenderer`，**129 / 0**：像素、GL 状态恢复、LUT 删除、共享 shader / dummy / scratch 释放、`invalidate()` 后同对象重建；探针最后还原主 FBO 画面与状态 |
| 全量脚本 | `cinematics/tests` + `cinematics/release` 共 **166** 支，仅 2 支既有问题：`test_camera_facing_origin_coordinate` id 过长、`test_camera_relative_axis` 缺 yaw；本轮两支 fixture 通过 |
| 实际资源重载 | 使用 F3+T 同一入口 `Minecraft.reloadResourcePacks()`；future 成功，generation **5 → 6**，同一存活层的 LUT **50 → 49**、lutGeneration **5 → 6**，shader 实例重建 |
| 再次播放与强停 | 再播建立新层与有效 LUT；`/icinematics stop` 后 rendererCount=0、layers=0、shader=null、scratch=0 |
| D7 实际界面 | `2026-10-17_14.00.08.png`：红 / 蓝 / 黄三层同时显示，黄盖蓝红、蓝盖红，说明字幕完整；10.00s `NATURAL_END → FINISHED`，最终 layers=0 / rendererCount=0 |
| D4–D6 实际像素 | `2026-10-17_14.01.42.png`：三色非等距渐变四点与独立公式一致；`2026-10-17_14.02.09.png`：正片叠底三个点均为 **RGB(56,80,144)**，与期望一致 |
| 完整演示自然结束 | 无重载场景在内容时间 **68.00s** 触发 `NATURAL_END → FINISHED`；最终 layers=0、rendererCount=0、shader=null、dummy=0、scratch texture/FBO=0 |

### 验收边界与过程纠偏

- Forge 仅编译，未运行 Forge 客户端；未验证 Iris / Oculus、编辑器。D1–D3 不属本文，已于同日另行收口（见 `scene-transition.md` §9）。
- 独立 shader 探针的状态恢复不代替生产代码证明；生产状态与释放另由真实客户端的 129 项探针覆盖。状态恢复按 `GuiGraphics.flush()` 后的基线比较，不把 flush 自身的状态变化算作 renderer 泄漏。
- 数据探针原始输入漏必填 `layer_type`，另有一例错误地期待末帧后返回 clip.color；均修探针输入，不改生产合同。生产探针修正局部变量重名、默认 fill 路径的 GUI flush / main FBO 上下文；没有压掉失败或修改生产代码迁就探针。
- 资源重载会暂停内容推进；首次以 70 秒墙钟命名的快照仍有末段说明层，之后再播属于打断而非自然退出，不作为自然结束证据。重载完成立即 F2 捕获的是 Mojang 加载遮罩，也不作为渐变视觉证据。
- 渐变使用 1024 采样表；已测场景通过独立像素判据，不推导任意密集 stops 的统一误差上限。
- 验收客户端已正常退出（exit code 0）；仓库外临时探针和 runtime 测试副本在取证后清理，报告与截图仅作本地证据，不纳入提交。
