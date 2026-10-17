# overlay（覆盖层系统）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/overlay/`

功能树：

- **覆盖层管理**
  - ✅ `OverlayManager` 单例管理所有覆盖层生命周期与渲染顺序，类似剪辑软件的轨道系统（`OverlayManager`）
  - ✅ zIndex 调度：添加层后自动按 zIndex 升序排序，数值越小越底层（先绘制）、越大越顶层（默认 10；只在本模组覆盖层之间排序，不改原版 HUD）（`OverlayManager`、`OverlayLayer`）
  - ✅ 同时显示必须分轨：`OverlayTrackPlayer.findActiveClip` 每条 OVERLAY 轨同一时刻只返回首个活跃 clip，同轨后面的重叠 clip 不会同时渲染；`z_index` 只决定层间顺序，不改变该消费规则（`OverlayTrackPlayer`）
  - ✅ 提供按类型查找多实例层 `getLayers(Class)`（`OverlayManager`）
  - ✅ **层归属（owner token）**：每层持一个不透明 token（`OverlayLayer.owner()`，值 = 建层方的播放实例引用，`null` = 无主层）；`startFadeOut(owner)` / `isAnimating(owner)` / `removeLayersOf(owner)` 按归属过滤——一个实例退场不碰其他实例的层（无参全停版已删除，全停走逐实例 `deactivateNow`）（`OverlayManager`）
  - ✅ 生命周期：每渲染帧 `update(deltaTime)` 驱动层动画（tick），`startFadeOut(owner)` 触发该实例层的退出动画，`reset()` 恢复所有层默认状态（`OverlayManager`）
  - ✅ `isAnimating(owner)` 判定指定实例是否有层正在过渡动画（帧驱动退场判定）；`isAnyAnimating()` 供渲染入口门控（`OverlayManager`）
- **渲染入口**
  - ✅ `CinematicOverlay` 为 HUD 渲染入口：相机激活或有层正在动画时才渲染覆盖层，否则跳过（`CinematicOverlay`）
  - ✅ 提供 overlay 白名单标识 `OVERLAY_ID = "cinematic_overlay"` 供 HUD 拦截放行（`CinematicOverlay`）
- **统一参数与几何解算（0.3.6 步骤 4；口径见 `plans/0.3.6/variable-frame.md` §4.2）**
  - ✅ `CanvasTransform`：统一参数默认值（位置 0.5 / 锚点 0.5 / 缩放 1 / opacity 1 / z_index 10）、编辑基准分辨率缺省常量（1920×1080）、分辨率转义纯函数 `resolutionEscape(wBase, hBase, wPlay, hPlay) = min(W播/W基, H播/H基)`（任一 ≤ 0 → 0，调用方按退化不绘制）、`element(...)` 元素几何解算（窗口百分比位置 + 元素自身锚点 + 素材像素基准 + 缩放 → 元素框，全程浮点比例域）；**不引用任何 MC 类、不做渲染**（`CanvasTransform`）
  - ✅ 覆盖层口径：**位置 = 播放窗口百分比**（元素中心，不钳制）；**尺寸 = 裁切后素材像素 × scale × k**（`k` = 分辨率转义，`W基/H基` 来自脚本 `meta.base_resolution`，缺省 1920×1080）。画布 = 窗口，无参考画布 / Fit 映射；无 `fit` 适配参数（元素框由素材派生）
- **内置层：画幅比黑边**
  - ✅ `LetterboxLayer`（zIndex=0，**per-instance**：每个 LETTERBOX 轨各持一层，构造时加入、`onStop` 移除）：按目标画幅比与窗口尺寸画黑边，宽于窗口画上下黑边，窄于窗口画左右黑边；不乘 `k`。LETTERBOX 关键帧的 `aspect_ratio` 与 `opacity`（默认 1，范围 0~1）均由播放器线性求值，首尾保持；无限片段时间照常推进（`LetterboxLayer`、`LetterboxTrackPlayer`）
  - ✅ 画幅比 ≤0 时不可见/不渲染，重置时归零（`LetterboxLayer`）
  - ✅ 渐出：`startFadeOut()` 起 0.5s 常量速率（内部常量，不做脚本字段）把黑边 alpha 线性降到 0，渐出中 `isAnimating()=true`（渐完 false → 帧驱动硬停本实例）；画幅比不受渐出影响（`LetterboxLayer`）
- **内置层：颜色遮罩（fade）**
  - ✅ `FadeLayer`（默认 zIndex=10）：**纯色 + `full` + `normal`** 保留原有 `GuiGraphics.fill` 全窗口矩形快路径（不建 FBO / shader），颜色由 `#RRGGBB` 解析、透明度由关键帧 opacity 驱动，旧 fade 与规则 `overlay_set` 的临时 fade 行为不变（`FadeLayer`）
  - ✅ 字段归属：在 OVERLAY 内，`gradient` / `mask_shape` / `blend_mode` 与关键帧 `mask_*` / `gradient_*` 只属于 `layer_type=fade`（`blend_mode` 另也用于 ADJUST 轨）；`image` / `subtitle` 写入会被解析 / 校验拒绝，字幕不做裁切。CAMERA 的同名 `mask_shape` / `mask_*` 是 lane 合成遮罩（见 [camera 模块](camera.md)），两者不互通；CAMERA 不接受 `gradient` / `gradient_*` / `blend_mode`（`ScriptParser`、`ScriptValidator`、`FadeLayer`）
  - ✅ 高级颜色遮罩（0.3.6 D4–D7）：clip 的 `gradient` / `mask_shape` / `blend_mode` 与关键帧 `color` 由 `ColorMaskSpec` 在解析期归一化并缓存（无 MC / GL 依赖，`Clip#getColorMaskSpec()` 懒缓存）；关键帧 `mask_*` / `gradient_*` 几何由 `OverlayTrackPlayer` 逐帧采样后送入层。声明渐变、`rect` / `circle` 或非 `normal` 混合时，`FadeLayer` 持有一个 `ColorMaskRenderer` 走专用 `ic_color_mask` core shader。覆盖形状 `full` / `rect`（窗口比例中心与宽高）/ `circle`（中心 + 短边比例半径，非方形窗口仍为真圆）；`linear` 方向角 / `radial` 中心与短边比例半径均按关键帧逐帧送入（`ColorMaskSpec`、`ColorMaskRenderer`、`FadeLayer`）
  - ✅ 混合：`normal` / `multiply` / `screen` / `soft_light` / `overlay`，公式与 ADJUST 轨共用 W3C 口径（`soft_light` 分支看 src、D 曲线作用于 base）；入口先 `guiGraphics.flush()`，保存 / 恢复 shader、纹理单元、blend、depth、colorMask、scissor、framebuffer / viewport；非 `normal` 先把主 framebuffer 复制到可复用 scratch 再采样，避免读写同一纹理；输出只改 RGB，区域外像素、后续 GUI 与 framebuffer alpha 不受影响（`ColorMaskRenderer`、`ic_color_mask.fsh`）
  - ✅ 渐变实现：stops 在 CPU 侧烘成 1024×1 RGB LUT（首尾对齐 `t=0/1`、段内线性、LINEAR 过滤），每个高级层只建一次；stops 定义在 clip 级且整段固定，关键帧只驱动方向 / 中心 / 半径。1024 是**实现采样精度**：任意密集 / 极窄区间的 stops 不保证逐像素与解析式完全一致，不声明误差上限（`ColorMaskRenderer`）
  - ✅ 渐出：`startFadeOut()` 起 0.5s 把渲染不透明度按系数 1→0 乘到关键帧 opacity 上（渐出期间关键帧继续驱动，不互相覆盖）；渐出中 `isAnimating()=true`（`FadeLayer`）
  - ✅ 资源生命周期：`OverlayLayer.close()` 默认空操作；`OverlayManager.removeLayer` / `removeLayersOf` 在层真正移出列表后调用 `close()`，`FadeLayer.close()` 释放自身 `ColorMaskRenderer` 的 LUT；最后一个 renderer 关闭时释放共享 shader / dummy / scratch。`reset()` 不关闭资源（层仍在列表）。F3+T / 资源包重载由 Fabric / Forge 的 reload listener 调 `ColorMaskRenderer.invalidate()`，跨线程时排队到渲染线程，存活层按新代次重建（`OverlayLayer`、`OverlayManager`、`FadeLayer`、`ColorMaskRenderer`、`FabricEvents`、`ForgeClientEvents`）
- **内置层：图片（image）**
  - ✅ `ImageLayer`（默认 zIndex=10）：**位置 = 播放窗口归一化（0~1，未缩放基准矩形中心，0.5 = 窗口正中）**，缩放绕 `anchor_x/anchor_y`（默认 0.5 = 绕中心），上屏尺寸 = 裁切后素材像素 × `scale_x/scale_y` × `k`（素材像素由 `TextureLoader` 记录）；`source` 取材（素材归一化子矩形，原点 = 素材左上角）；支持 PNG/GIF（GIF 由 `GifAnimation` 按全局时间轮播）；透明度由关键帧 opacity 驱动，**渲染用 pose 浮点平移实现亚像素平滑**（`ImageLayer`、`TextureLoader`、`GifAnimation`）；无渐出动画（退场 = 下一帧硬停）
- **内置层：字幕（subtitle）**
  - ✅ `SubtitleLayer`（默认 zIndex=10）：渲染文字，支持多行（`\n` 分隔），**位置 = 播放窗口归一化（0~1，基准矩形中心）**，缩放绕 `anchor_x/anchor_y`，字号两级缩放（`font_scale` 矩阵缩放决定文字块基准尺寸 + `scale_x/y` 素材像素倍数，两者与 `k` 同乘），透明度由 opacity 控制，pose 浮点平移亚像素平滑（`SubtitleLayer`）；无渐出动画（退场 = 下一帧硬停）
  - 字幕保持完整文本，不消费 `source`；取材只适用于图片与相机画面 lane。共有几何参数统一，不要求全部类别支持全部能力。
  - ✅ 文本资源（0.3.6）：字幕文本在**创建层时**由 `OverlayTrackPlayer` 经 `LangResources.resolve` 解析——`text` 写 `@lang:<key>` 时按客户端当前语言查 `resource/lang/<语言>.json`（回退 `en_us` → 原样显示引用串），普通文案原样透传；解析发生在分行之前，见 [SCRIPT_FORMAT §12](../../SCRIPT_FORMAT.md)
  - ⚠️ **MC 透明度补全坑**：`Font.adjustColor()` 会把 alpha 高 6 位为 0 的颜色（alpha 0~3，透明度 <1.6%）补成完全不透明——低透明度文字反而满透明度渲染。渲染层已用 `alpha < 4` 跳过规避；**未来任何走 Font.drawString 的 fade/文字动画都必须避开该区间**（ImageLayer 走 shader 颜色不受影响）
- **画中画：由画面 lane 实现（0.3.6 起）**
  - ✅ 覆盖层不再提供 `pip` 层：`PipLayer` 已删除（原为仅画白框 + 半透明黑底的占位实现，从未接入真实相机画面）。画中画 = 一条 CAMERA lane 用合成参数 `dest`（目标小矩形）+ `source`（取材）+ `opacity`（淡入淡出）实现，见 [画面合成](../../plans/0.3.6/camera-composition.md) 与 [SCRIPT_FORMAT §4](../../SCRIPT_FORMAT.md)；WebUI 里对脚本片段的便捷操作留编辑器阶段
- **扩展接口**
  - ✅ `OverlayLayer` 接口定义统一契约：render/isVisible/getZIndex/**owner**/reset/**close**，动画相关提供默认实现（tick/startFadeOut/isAnimating —— 默认无动画 = 退场下一帧硬停），`close()` 默认空操作（无 GPU 资源的层无需实现；实现方仍需幂等），新层类型只需实现接口并注册（`OverlayLayer`）
