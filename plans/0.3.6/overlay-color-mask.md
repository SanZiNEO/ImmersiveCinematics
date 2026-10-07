# 0.3.6 计划：完整颜色遮罩（Color Mask / Full Overlay）

## 1. 背景

0.3.5 已实现基础的全屏遮罩能力：

- `FadeLayer`：渲染全屏 ARGB 矩形
- 脚本写法（`layer_type` / `color` 为 clip 级字段，`opacity` 为关键帧级字段）：
  ```json
  {
    "layer_type": "fade",
    "color": "#000000",
    "keyframes": [ { "time": 0, "opacity": 0.4 } ]
  }
  ```
- 黑色全屏遮罩在测试中可观察到，效果正常（`cinematics/tests/overlay/test_overlay_fade.json` 存在；视觉效果未在本次核查中运行验证）。

但在 `test_overlay_zindex` 中放置的红色半透明遮罩（`#FF0000` + `opacity 0.4`）没有明显看出来。

结论：

- 基础 fade 保留在 0.3.5
- **完整/更高级的颜色遮罩放到 0.3.6 做**

## 2. 0.3.6 目标

构建一套"完整颜色遮罩"能力，至少覆盖：

> **底层工具只有一个（已确认·2026-10-07）：纯色覆盖层**（`FadeLayer`：颜色 + 不透明度，全部可关键帧）——"盖一层任意颜色的像素"。淡入淡出、黑场、白场、颜色遮罩都是对它的参数化应用。**应用形态**：黑场 / 白场 / 颜色遮罩是 **OVERLAY 轨上已调好参数的 clip**（`layer_type=fade` + color / opacity 关键帧），由轨道追踪——作者只需决定"放哪、多长"（转场场景见[画面转场](./scene-transition.md)；这类 clip 也可由[模板](./templates.md)片段级模板一键生成）。本节下面列的都是这个工具的增强方向。

### 2.1 基础增强
- 全屏颜色遮罩（已有，继续保留）
- 透明度关键帧控制（已有）
- z_index 分层（已有，但需要测试更直观）

### 2.2 新增能力（讨论项）
- **渐变色遮罩**
  - 线性渐变 / 径向渐变
  - 多色 stops
- **混合模式**
  - 普通 / 正片叠底 / 屏幕 / 柔光 / 叠加等
- **局部遮罩**
  - 矩形 / 圆形区域
  - 可指定 x/y 与尺寸
- ~~颜色调色/滤镜~~ → **已移交 `screen-color-adjust.md`**：RGBA 通道拆分、完整 HSL、亮度/对比度等“改已有画面颜色”的能力归该文档；本文只管“往上盖一层颜色”（遮罩）。

### 2.3 测试可视化
- 重做 `test_overlay_zindex`
- 要求一眼能看出层级：
  - 高对比色（如纯红 / 纯蓝 / 纯黄）
  - 足够不透明度
  - 不同位置 + 不同 z_index
  - 添加说明文字层

## 3. 涉及文件

- `common/.../overlay/FadeLayer.java`
- `common/.../overlay/OverlayManager.java`
- `common/.../overlay/OverlayLayer.java`
- `common/.../script/OverlayTrackPlayer.java`
- 脚本格式文档：
  - `docs/SCRIPT_FORMAT.md`
  - `docs/AI_SCRIPTING_GUIDE.md`
- 测试脚本：
  - `cinematics/tests/overlay/test_overlay_zindex.json`

## 4. 开放问题

1. 颜色遮罩是否要做渐变？
2. 是否要做混合模式？如果做，0.3.6 优先级如何？
3. 局部遮罩是否必须，还是先做全屏高级遮罩？
4. 是否保留现有脚本字段兼容？
   - 现有 `layer_type: "fade"` 是否扩展字段，还是新增 `layer_type: "color_mask"` / `"gradient"`？
5. 0.3.6 是否同时处理 WebUI 编辑器中的颜色/遮罩配置面板？

## 5. 验收标准

- [ ] 颜色遮罩能明显显示，不再依赖“仔细看”
- [ ] z_index 层级一眼可辨
- [ ] 至少支持纯色、渐变、局部区域三种形态（或按讨论范围）
- [ ] 测试脚本更新后可自动验证
- [ ] 文档同步更新

---

## 事实核查（2026-10-07）

核查范围：`overlay/FadeLayer.java`、`overlay/OverlayManager.java`、`overlay/OverlayLayer.java`、`script/OverlayTrackPlayer.java`、`script/ScriptValidator.java`、`script/schema/TrackSchemas.java`、`docs/SCRIPT_FORMAT.md`、`docs/AI_SCRIPTING_GUIDE.md`、`cinematics/tests/overlay/*.json`。当前 `gradle.properties` `version=0.3.5`。

### ① 核实为真的断言

- **§1「FadeLayer 渲染全屏 ARGB 矩形」**：`overlay/FadeLayer.java` `render(GuiGraphics, int, int)` 计算 `int argb = (alpha << 24) | (color & 0x00FFFFFF)`，调用 `guiGraphics.fill(0, 0, screenWidth, screenHeight, argb)`（全屏填充）。✅
- **§1「脚本字段 layer_type: fade + color」**：`script/OverlayTrackPlayer.java` `createLayer(Clip)` 读取 `clip.getString("layer_type", "fade")`、`clip.getString("color", "#000000")`；`ScriptValidator.checkEnum(... "layer_type", ..., "fade", "image", "subtitle", "pip")`；`schema/TrackSchemas.overlay()` 中 `clips.put("layer_type", enum 默认 "fade")`、`clips.put("color", string 默认 "#000000")`。✅（`opacity` 的层级见 ②）
- **§1「z_index 分层存在」**：`overlay/OverlayManager.java` `addLayer(OverlayLayer)` 执行 `layers.sort(Comparator.comparingInt(OverlayLayer::getZIndex))`；`render(...)` 按列表顺序（升序）绘制，注释「zIndex 越小越先绘制（底层），越大越后绘制（顶层）」。`FadeLayer.getZIndex()` 返回自身 `zIndex`。✅
- **§2.1「透明度关键帧控制（已有）」**：`script/OverlayTrackPlayer.java` `onRenderFrame(float)` 从 `clip.getKeyframes()` 用 `interpolateFloat(kfs, localTime, "opacity", 0f, smooth)` 取值，`updateLayer(...)` 对 `FadeLayer` 调用 `fl.setOpacity(opacity)`；`schema/TrackSchemas.overlay()` 将 `opacity` 定义在关键帧字段表（`kfs.put("opacity", ...)`）。✅
- **§3 涉及文件全部存在、路径正确**：`common/src/main/java/com/immersivecinematics/immersive_cinematics/overlay/FadeLayer.java`、`.../overlay/OverlayManager.java`、`.../overlay/OverlayLayer.java`、`.../script/OverlayTrackPlayer.java`、`docs/SCRIPT_FORMAT.md`、`docs/AI_SCRIPTING_GUIDE.md`、`cinematics/tests/overlay/test_overlay_zindex.json` 均存在。✅
- **§1「红色半透明遮罩结论属 0.3.5 时代记录」**：`cinematics/tests/overlay/test_overlay_zindex.json` 最后修改 `2026-08-30T20:22:20+08:00`（commit "feat: Forge/Fabric HUD 白名单工具…"）；`overlay/FadeLayer.java` 最后修改 `2026-08-18T22:58:38+08:00`；`script/OverlayTrackPlayer.java` 最后修改 `2026-08-20T13:12:47+08:00`。均落在 `version=0.3.5` 开发期，确为 0.3.5 时代结论。✅

### ② 已修正的断言

- **§1 脚本示例把 `opacity` 写成 clip 级字段（旧）→ `opacity` 是关键帧级字段（新）**。
  - 证据：`OverlayTrackPlayer.onRenderFrame` 只从 `clip.getKeyframes()` 插值 `opacity`；`updateLayer` 仅在关键帧路径下 `setOpacity`；clip 级 `opacity` 无任何读取点（`applyInitialClipValues` 不读 opacity）。`docs/AI_SCRIPTING_GUIDE.md` 第 203、214 行明示「没有 clip 级简写」「淡入淡出 = 关键帧里写 opacity 0→1→0」。
  - 修正：§1 示例已改为 `"keyframes": [ { "time": 0, "opacity": 0.4 } ]`，并注明字段层级。

### ③ 补全的信息

- **§1 fade 无 ScriptParser 专用解析分支**：`script/ScriptParser.java` 无 `fade`/`layer_type`/`opacity` 相关匹配；字段读取为通用的 `Clip.getString/getInt`，合法性由 `ScriptValidator.checkEnum` 与 `schema/TrackSchemas.overlay()` 约束。原文「ScriptParser 的 fade 解析」若被理解为独立解析逻辑则不成立。
- **`z_index` 默认值不一致（源码内部）**：`OverlayTrackPlayer.createLayer` 用 `clip.getInt("z_index", 10)`（默认 10）；`schema/TrackSchemas.overlay()` 的 FieldDef 默认值为 20；`docs/SCRIPT_FORMAT.md` 第 450 行文档默认值亦为 20。即无 `z_index` 时运行时实际取 10，与 schema/文档默认 20 不符。
- **FadeLayer 颜色解析**：`setColor(String)` 只解析 `#RRGGBB`（`Integer.parseInt(clean,16) & 0x00FFFFFF`），alpha 完全由 `targetOpacity` 决定；非法颜色 WARN 后回退黑色。故 `#FF0000` + 关键帧 opacity 0.4 → ARGB `0x66FF0000`，颜色通道本身正确。

### ④ 无法核实的断言（未验证）

- **§1「黑色全屏遮罩在测试中可观察到，效果正常」**：测试脚本 `test_overlay_fade.json` 存在，但本次核查未运行游戏客户端，视觉效果**未验证**。
- **§1「`test_overlay_zindex` 中红色半透明遮罩没有明显看出来」**：脚本内容确为 `"layer_type":"fade","color":"#FF0000","z_index":20` + 关键帧 opacity 0→0.4→0.4→0；但「不明显」为主观观察结论，源码无法证实，**未验证**（记录于 0.3.5 时代）。

### 跨文档冲突（只报不改）

- `plans/0.3.6/camera-composition.md` 第 124 行称「pip 的 opacity 不生效：`overlay/PipLayer` 算出的 `fillArgb`/`borderArgb` 从未使用（直接用了常量）」。本文档未对 pip 的 opacity 作任何断言，两者不构成直接冲突；仅记录该断言存在（未在本次核查中验证 PipLayer 源码）。
