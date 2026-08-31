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
- **内置层：画幅比黑边**
  - ✅ `LetterboxLayer`（zIndex=0，内置常驻）：按目标画幅比计算上下黑边高度并绘制纯黑遮幅（`LetterboxLayer`）
  - ✅ 画幅比 ≤0 时不可见/不渲染，重置时归零（`LetterboxLayer`）
- **内置层：全屏遮罩（fade）**
  - ✅ `FadeLayer`（默认 zIndex=10）：渲染全屏 ARGB 矩形，颜色由 `#RRGGBB` 解析，透明度由关键帧 opacity 驱动，用于淡入淡出与色彩滤镜（`FadeLayer`）
- **内置层：图片（image）**
  - ✅ `ImageLayer`（默认 zIndex=20）：**坐标 = 屏幕百分比（0~1，元素中心锚点，0.5 = 屏幕正中）**，显示尺寸 = 原图分辨率 × `scale_x/scale_y` 百分比乘数（原图尺寸由 `TextureLoader` 记录）；支持 PNG/GIF（GIF 由 `GifAnimation` 按全局时间轮播）；透明度由关键帧 opacity 驱动，**渲染用 pose 浮点平移实现亚像素平滑**（`ImageLayer`、`TextureLoader`、`GifAnimation`）
- **内置层：字幕（subtitle）**
  - ✅ `SubtitleLayer`（默认 zIndex=30）：渲染文字，支持多行（`\n` 分隔），**坐标 = 屏幕百分比（0~1，文字块中心）**，字号两级缩放（`font_scale` 矩阵缩放 + `scale_x/y` 百分比缩放），透明度由 opacity 控制，pose 浮点平移亚像素平滑（`SubtitleLayer`）
  - ⚠️ **MC 透明度补全坑**：`Font.adjustColor()` 会把 alpha 高 6 位为 0 的颜色（alpha 0~3，透明度 <1.6%）补成完全不透明——低透明度文字反而满透明度渲染。渲染层已用 `alpha < 4` 跳过规避；**未来任何走 Font.drawString 的 fade/文字动画都必须避开该区间**（ImageLayer 走 shader 颜色不受影响）
- **内置层：画中画（pip）**
  - ⚠️ `PipLayer`（默认 zIndex=40）：仅渲染 2px 白色边框 + 半透明黑色填充的占位框，Phase 1 不包含实际摄像头画面，计划 Phase 2（0.3.5+）接入第二相机帧缓冲（`PipLayer`）
- **扩展接口**
  - ✅ `OverlayLayer` 接口定义统一契约：render/isVisible/getZIndex/reset，动画相关提供默认实现（tick/startFadeOut/isAnimating），新层类型只需实现接口并注册（`OverlayLayer`）
