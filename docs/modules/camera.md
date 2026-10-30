> **过期文档（OUTDATED）——不得作为当前设计或实施依据**
>
> 本文未随最新设计更新，仅保留作历史参考。AI 与自动化工具不得据此推断当前格式、功能状态、接口或实施要求，也不得直接照搬其中示例用于新实现。
>
> 当前规划请先查阅 [0.3.6 计划状态索引](../../plans/0.3.6/README.md) 与 [I 轮实施基准](../../plans/0.3.6/I-script-core/README.md)，再按状态查阅对应未完成轮次。
>
> 计划目标不代表已经实现；核实当前代码行为时须结合源码，不得将旧文或未实现计划当作现状。

# camera（相机包）

对应路径：`common/src/main/java/com/immersivecinematics/immersive_cinematics/camera/`

功能树：

- **相机接管**
  - ✅ 提供单例管理器 `CameraManager.INSTANCE`，通过激活/停用状态机接管相机（`CameraManager`）
  - ✅ 激活时以玩家当前位置/朝向为起点接管相机，停用时先触发覆盖层淡出再释放（`CameraManager`）
  - ✅ 支持统一退出入口 `requestExit()`，按退出原因（强退/系统停止/打断/用户跳过/自然结束）区分处理（`CameraManager`）
  - ✅ 播放结束后自动释放相机并恢复原相机状态（`CameraManager`）
- **虚拟时钟**
  - ✅ 维护独立游戏时间 `gameTimeSeconds`，用 `System.nanoTime()` 真实时间累加，不受游戏内时间影响（`CameraManager`）
  - ✅ 游戏暂停（且配置允许）或编辑器预览暂停时冻结时钟，暂停/恢复转换时向服务端发送暂停包并同步暂停音频（`CameraManager`）
- **播放调度**
  - ✅ 支持脚本播放/排队：播放中可打断则打断旧脚本，不可打断则排队等待自然结束后播放（`CameraManager`）
  - ✅ 维护待播放脚本队列 `pendingScript`，当前脚本结束后自动接播（`CameraManager`）
  - ✅ 帧回调驱动：每渲染帧 `onRenderFrame()` 推进时钟、更新覆盖层、驱动 `ScriptPlayer` 并计算活跃相机轨道缓存（`CameraManager`）
  - ✅ 脚本结束（非 holdAtEnd）时触发自然结束退出流程（`CameraManager`）
- **编辑器预览**
  - ✅ 支持 `pushScript()` 接收编辑器传入的 JSON 并解析为预览脚本（`CameraManager`）
  - ✅ 支持 `setTime()`/`resume()`/`pause()`/`stop()` 控制预览播放头、跳转时间并对齐音频位置（`CameraManager`）
  - ✅ 预览模式下允许键盘/鼠标输入，正式播放时屏蔽输入（`CameraManager`）
  - ✅ 提供 staged 预置通道：`stageTargetPosition/Yaw/Pitch/Roll/Fov/Zoom` 写入暂存缓冲，`commitStagedState()` 原子提交到活跃缓冲（`CameraManager`、`CameraPath`、`CameraProperties`）
- **相机属性控制**
  - ✅ 管理五类相机属性：yaw、pitch、roll、fov、zoom，每帧由 `CameraTrackPlayer.onRenderFrame()` 直接写入精确值，无 partialTick 插值层（`CameraProperties`）
  - ✅ 每个属性独立跟踪过渡状态，支持直接设置（瞬移）与目标值+时长过渡两种模式（`CameraProperties`）
  - ✅ 角度属性用角度环绕插值（lerpAngle），标量属性用线性插值（`CameraProperties`）
  - ✅ 支持从另一实例整体覆盖（硬切换）与重置到默认值（yaw/pitch/roll=0、fov=70、zoom=1）（`CameraProperties`）
- **相机路径**
  - ✅ 只管理相机在世界中的位置轨迹（x, y, z），与朝向/光学属性完全解耦（`CameraPath`）
  - ✅ 支持直接设位（帧回调模式）、目标位置+过渡时长、每 tick 线性插值过渡（`CameraPath`）
  - ✅ 支持硬切换覆盖与重置到原点（`CameraPath`）
- **翻滚角（roll）**
  - ✅ roll 已修复为绕相机视线轴旋转：任何朝向下 roll>0 均为屏幕顺时针（画面向右倒）（CHANGELOG 0.3.4）
- **镜头追踪（关键帧级 follow / look_at）**
  - ✅ 支持 `follow=entity` 跟随实体，position 的 dx/dy/dz 为相对实体脚底偏移（`CameraTrackPlayer`）
  - ✅ 支持 `look_at=coordinate` 注视固定坐标/结构中心、`look_at=player` 注视玩家激活位置，注视点偏移 `look_at_offset`（世界轴 / 基准坐标系）（`CameraKeyframeEvaluator`）
  - ✅ 支持 `look_at=entity` 注视实体包围盒内取点（部位 `look_at_part` 每轴百分比，缺省中心），`look_at_selector` 选择器解析目标（`CameraKeyframeEvaluator`）
  - ✅ 支持 `look_at=block` 注视就近方块中心（`look_at_target_block`，点源方块形态 `block:id[:radius]`）（`CameraKeyframeEvaluator`）
  - ✅ 点源 = 来源 + 偏移（`PointSource`：五形态 + 实体百分比取点 + 偏移两表达空间）；位置点源 `facing_origin` 与 `yaw_base_from/to` / `facing_target` / `look_at` 共用同一份解析（`CameraKeyframeEvaluator`、`EntityTargetResolver`）
  - ✅ 方向锁 `yaw_lock` / `pitch_lock`（每轴一把，锁值 = 世界角绝对值；锁替换该轴基准、关键帧 `yaw`/`pitch` 偏移仍叠加；`look_at != none` 时静态侧整体被覆盖）；`position.up_axis` 移除，基准坐标系上轴固定为世界竖直（`CameraKeyframeEvaluator`、`PositionData`）
  - ✅ 目标点/实体/开关之间按两端世界坐标插值平滑过渡（`CameraTrackPlayer`）
- **镜头呼吸扰动（cam_breath_*）**
  - ✅ v2：`cam_breath_type` 多类型（`perlin` 默认 / `perlin_axis` / `sine` / `trauma`）+ `cam_breath_speed`，旧 `enabled/intensity/seed` 兼容（缺省 type 按 perlin 处理）；`trauma` 专属 `cam_breath_trauma/decay`；确定性（同 seed + 同时间 → 同抖动），叠加在最终 yaw/pitch/roll 上（`BreathDisturbance`、`CameraTrackPlayer`）
- **合成参数 `dest` / `source` 的口径（0.3.6）**
  - ✅ CAMERA 关键帧的 `dest` = **窗口（屏幕）归一化**目标矩形（放置参照窗口）、`source` = **lane 画面归一化**取材矩形（取材参照素材自身，原点 = 左上角）；缩放隐含在 `dest` 尺寸 / `source` 尺寸之比，**不乘分辨率转义系数 `k`**（lane 画面每帧按播放窗口尺寸渲染，`k = 1`）。两族（lane / 素材）放置统一为窗口参照、无画布换算 —— 完整模型见 `plans/0.3.6/variable-frame.md` §4.2；字段表见 `docs/SCRIPT_FORMAT.md` §4「合成参数」。消费方在 `client/lane/ScriptLaneDriver` 逐帧取值、`client/lane/LaneCompositor` 上屏
- **画面 lane 合成遮罩（0.3.6 D2）**
  - ✅ CAMERA clip 级 `mask_shape`（`full` 缺省 / `rect` / `circle`）+ 关键帧 `mask_x` / `mask_y` / `mask_width` / `mask_height` / `mask_radius`：`LaneMask` 按该 clip 的本地时间线性采样，每槽复用一个实例；`full` 不读几何并复位缺省，旧 clip 输出不变（`LaneMask`、`ScriptLaneDriver`）
  - ✅ `LaneCompositor` 在合成阶段按**窗口物理像素**判定覆盖率（`rect` 中心 / 宽高为窗口比例；`circle` 半径为窗口短边比例，非方形窗口仍是真圆），覆盖率与 `opacity` 相乘作为混合 alpha；不读 lane 纹理 alpha、不改 `source` / `dest` 的 UV / 顶点映射；区域外保持底图，`rect` 宽/高或 `circle` 半径为 `0` 时该 lane 不绘制；非 `full` 而专用 shader 不可用时跳过该 lane 并报错，不退回无遮罩输出（`LaneCompositor`、`ic_lane_blit`）
  - ✅ 遮罩只作用于相机 lane，**不裁切字幕 / 图片**；CAMERA 不接受 `gradient` / `gradient_*` / `blend_mode`（`gradient` / `gradient_*` 专属 OVERLAY fade；`blend_mode` 属于 ADJUST / fade），`image` / `subtitle` 不接受 `mask_shape` / `mask_*`（`ScriptParser`、`ScriptValidator`）
- **相机过渡与画面转场的区别（0.3.6 D1）**
  - ✅ `transition: "cut"` / `"morph"` 只改变相机六参数（位置 / yaw / pitch / roll / fov / zoom）；morph 窗口内混合两侧相机状态，但**不叠化、不划像画面**，也不生成额外画面（`CameraTrackPlayer`、`TransitionType`）
  - ✅ 画面转场由显式 clip + 关键帧表达：叠化 = 同轨重叠 clip，下层 A 保持 `opacity: 1`、上层 B `0 → 1`；划像 = 上层 clip 的 `mask_shape` + `mask_*` 关键帧；黑场 / 白场 = OVERLAY fade。不存在 `TRANSITION` 轨、`transition_in` / `transition_out` 或运行时生成器（`ScriptPlayer`、`LaneCompositor`）
