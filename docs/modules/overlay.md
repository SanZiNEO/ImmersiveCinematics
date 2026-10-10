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
- **统一参数与几何解算（0.3.6 步骤 4；口径见 `plans/0.3.6/variable-frame.md` §4.2）**
  - ✅ `CanvasTransform`：统一参数默认值（位置 0.5 / 锚点 0.5 / 缩放 1 / opacity 1 / z_index 10）、编辑基准分辨率缺省常量（1920×1080）、分辨率转义纯函数 `resolutionEscape(wBase, hBase, wPlay, hPlay) = min(W播/W基, H播/H基)`（任一 ≤ 0 → 0，调用方按退化不绘制）、`element(...)` 元素几何解算（窗口百分比位置 + 元素自身锚点 + 素材像素基准 + 缩放 → 元素框，全程浮点比例域）；**不引用任何 MC 类、不做渲染**（`CanvasTransform`）
  - ✅ 覆盖层口径：**位置 = 播放窗口百分比**（元素中心，不钳制）；**尺寸 = 裁切后素材像素 × scale × k**（`k` = 分辨率转义，`W基/H基` 来自脚本 `meta.base_resolution`，缺省 1920×1080）。画布 = 窗口，无参考画布 / Fit 映射；无 `fit` 适配参数（元素框由素材派生）
- **内置层：画幅比黑边**
  - ✅ `LetterboxLayer`（zIndex=0，内置常驻）：按目标画幅比与**窗口**尺寸画黑边 —— 目标画幅比更宽 → 上下黑边；更窄 → 左右黑边（pillarbox）；两者相等不画。黑边由窗口与目标画幅比决定，**不乘 `k`**；`setOpacity` 控制黑边整体 alpha（默认 1）（`LetterboxLayer`）
  - ✅ 画幅比 ≤0 时不可见/不渲染，重置时归零（`LetterboxLayer`）
- **内置层：全屏遮罩（fade）**
  - ✅ `FadeLayer`（默认 zIndex=10）：渲染铺满**窗口**的 ARGB 矩形（效果层基准尺寸 = 铺满窗口，位置/缩放不生效），颜色由 `#RRGGBB` 解析，透明度由关键帧 opacity 驱动，用于淡入淡出与色彩滤镜（`FadeLayer`）
- **内置层：图片（image）**
  - ✅ `ImageLayer`（默认 zIndex=10）：**位置 = 播放窗口归一化（0~1，未缩放基准矩形中心，0.5 = 窗口正中）**，缩放绕 `anchor_x/anchor_y`（默认 0.5 = 绕中心），上屏尺寸 = 裁切后素材像素 × `scale_x/scale_y` × `k`（素材像素由 `TextureLoader` 记录）；`source` 取材（素材归一化子矩形，原点 = 素材左上角）；支持 PNG/GIF（GIF 由 `GifAnimation` 按全局时间轮播）；透明度由关键帧 opacity 驱动，**渲染用 pose 浮点平移实现亚像素平滑**（`ImageLayer`、`TextureLoader`、`GifAnimation`）
- **内置层：字幕（subtitle）**
  - ✅ `SubtitleLayer`（默认 zIndex=10）：渲染文字，支持多行（`\n` 分隔），**位置 = 播放窗口归一化（0~1，基准矩形中心）**，缩放绕 `anchor_x/anchor_y`，字号两级缩放（`font_scale` 矩阵缩放决定文字块基准尺寸 + `scale_x/y` 素材像素倍数，两者与 `k` 同乘），透明度由 opacity 控制，pose 浮点平移亚像素平滑（`SubtitleLayer`）
  - ✅ 文本资源（0.3.6）：字幕文本在**创建层时**由 `OverlayTrackPlayer` 经 `LangResources.resolve` 解析——`text` 写 `@lang:<key>` 时按客户端当前语言查 `resource/lang/<语言>.json`（回退 `en_us` → 原样显示引用串），普通文案原样透传；解析发生在分行之前，见 [SCRIPT_FORMAT §12](../../SCRIPT_FORMAT.md)
  - ⚠️ **MC 透明度补全坑**：`Font.adjustColor()` 会把 alpha 高 6 位为 0 的颜色（alpha 0~3，透明度 <1.6%）补成完全不透明——低透明度文字反而满透明度渲染。渲染层已用 `alpha < 4` 跳过规避；**未来任何走 Font.drawString 的 fade/文字动画都必须避开该区间**（ImageLayer 走 shader 颜色不受影响）
- **画中画：由画面 lane 实现（0.3.6 起）**
  - ✅ 覆盖层不再提供 `pip` 层：`PipLayer` 已删除（原为仅画白框 + 半透明黑底的占位实现，从未接入真实相机画面）。画中画 = 一条 CAMERA lane 用合成参数 `dest`（目标小矩形）+ `source`（取材）+ `opacity`（淡入淡出）实现，见 [画面合成](../../plans/0.3.6/camera-composition.md) 与 [SCRIPT_FORMAT §4](../../SCRIPT_FORMAT.md)；WebUI 里对脚本片段的便捷操作留编辑器阶段
- **扩展接口**
  - ✅ `OverlayLayer` 接口定义统一契约：render/isVisible/getZIndex/reset，动画相关提供默认实现（tick/startFadeOut/isAnimating），新层类型只需实现接口并注册（`OverlayLayer`）
