# overlay（覆盖层系统）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/overlay/`

功能树：

- **覆盖层管理**
  - ✅ `OverlayManager` 单例管理所有覆盖层生命周期与渲染顺序，类似剪辑软件的轨道系统（`OverlayManager`）
  - ✅ zIndex 调度：添加层后自动按 zIndex 升序排序，数值越小越底层（先绘制）、越大越顶层（`OverlayManager`、`OverlayLayer`）
  - ✅ 提供按类型查找多实例层 `getLayers(Class)` 与内置黑边层便捷访问 `getLetterboxLayer()`（`OverlayManager`）
  - ✅ 生命周期：每渲染帧 `update(deltaTime)` 驱动层动画（tick），`startFadeOut()` 触发退出动画，`reset()` 恢复所有层默认状态（`OverlayManager`）
  - ✅ `isAnimating()` 判定是否有层正在过渡动画，供 `CameraManager` 退场判定与渲染入口使用（`OverlayManager`）
- **渲染入口**
  - ✅ `CinematicOverlay` 为 HUD 渲染入口：相机激活或有层正在动画时才渲染覆盖层，否则跳过（`CinematicOverlay`）
  - ✅ 提供 overlay 白名单标识 `OVERLAY_ID = "cinematic_overlay"` 供 HUD 拦截放行（`CinematicOverlay`）
- **统一参数与参考画布（0.3.6 步骤 4）**
  - ✅ `CanvasTransform`：参考画布常量（16:9、1920×1080 像素锚点）、统一参数默认值（位置 0.5 / 锚点 0.5 / 缩放 1 / opacity 1 / z_index 10）、`FitMode` + `map`/`canvas` 适配映射、`element(...)` 元素几何解算（位置/锚点/缩放 → 屏幕矩形，全程浮点比例域）、`fitMode(String)` 枚举解析；**不引用任何 MC 类、不做渲染**（`CanvasTransform`）
  - ✅ 所有覆盖层坐标 = **参考画布归一化**（元素中心；1920×1080 窗口下画布 = 屏幕，与旧「屏幕百分比」逐像素一致；非 16:9 窗口按 Fit 等比居中，留边由画幅层画黑边）
- **内置层：画幅比黑边**
  - ✅ `LetterboxLayer`（zIndex=0，内置常驻）：按目标画幅比计算上下黑边高度并绘制遮幅；并绘制画布 Fit 留边（非 16:9 屏幕）；`setOpacity` 控制黑边整体 alpha（0.3.6 起补 alpha，默认 1）（`LetterboxLayer`）
  - ✅ 画幅比 ≤0 时不可见/不渲染，重置时归零（`LetterboxLayer`）
- **内置层：全屏遮罩（fade）**
  - ✅ `FadeLayer`（默认 zIndex=10）：渲染铺满**参考画布**的 ARGB 矩形（效果层基准尺寸 = 铺满画布，位置/缩放不生效），颜色由 `#RRGGBB` 解析，透明度由关键帧 opacity 驱动，用于淡入淡出与色彩滤镜（`FadeLayer`）
- **内置层：图片（image）**
  - ✅ `ImageLayer`（默认 zIndex=10）：**坐标 = 参考画布归一化（0~1，未缩放基准矩形中心，0.5 = 画布正中）**，缩放绕 `anchor_x/anchor_y`（默认 0.5 = 绕中心），显示尺寸 = 原图分辨率 × `scale_x/scale_y`（原图尺寸由 `TextureLoader` 记录）；`source` 取材（素材归一化子矩形）+ `fit` 适配（fit/fill/stretch）0.3.6 起支持；支持 PNG/GIF（GIF 由 `GifAnimation` 按全局时间轮播）；透明度由关键帧 opacity 驱动，**渲染用 pose 浮点平移实现亚像素平滑**（`ImageLayer`、`TextureLoader`、`GifAnimation`）
- **内置层：字幕（subtitle）**
  - ✅ `SubtitleLayer`（默认 zIndex=10）：渲染文字，支持多行（`\n` 分隔），**坐标 = 参考画布归一化（0~1，文字块中心）**，缩放绕 `anchor_x/anchor_y`，字号两级缩放（`font_scale` 矩阵缩放决定文字块基准尺寸 + `scale_x/y` 百分比缩放），透明度由 opacity 控制，pose 浮点平移亚像素平滑（`SubtitleLayer`）
  - ✅ 文本资源（0.3.6）：字幕文本在**创建层时**由 `OverlayTrackPlayer` 经 `LangResources.resolve` 解析——`text` 写 `@lang:<key>` 时按客户端当前语言查 `resource/lang/<语言>.json`（回退 `en_us` → 原样显示引用串），普通文案原样透传；解析发生在分行之前，见 [SCRIPT_FORMAT §12](../../SCRIPT_FORMAT.md)
  - ⚠️ **MC 透明度补全坑**：`Font.adjustColor()` 会把 alpha 高 6 位为 0 的颜色（alpha 0~3，透明度 <1.6%）补成完全不透明——低透明度文字反而满透明度渲染。渲染层已用 `alpha < 4` 跳过规避；**未来任何走 Font.drawString 的 fade/文字动画都必须避开该区间**（ImageLayer 走 shader 颜色不受影响）
- **内置层：画中画（pip）**
  - ⚠️ `PipLayer`（默认 zIndex=10）：仅渲染白色边框 + 半透明黑色填充的占位框，Phase 1 不包含实际摄像头画面，计划 Phase 2（0.3.5+）接入第二相机帧缓冲。**0.3.6 起已去像素化**：位置/锚点 = 画布归一化，尺寸由 `scale_x/scale_y` 表达（基准 = 铺满画布），边框粗细按画布高比例（`2/1080`）；无纹理故不消费 `source`/`fit`（`PipLayer`）
- **扩展接口**
  - ✅ `OverlayLayer` 接口定义统一契约：render/isVisible/getZIndex/reset，动画相关提供默认实现（tick/startFadeOut/isAnimating），新层类型只需实现接口并注册（`OverlayLayer`）
