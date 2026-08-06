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
- **镜头追踪（cam_tracking_*）**
  - ✅ 支持 `cam_tracking_look_at=coordinate` 注视固定坐标（`CameraTrackPlayer`）
  - ✅ 支持 `cam_tracking_look_at=entity` 注视实体（`cam_tracking_target_selector` 选择器解析目标）（`CameraTrackPlayer`）
  - ✅ 支持 `cam_tracking_follow=entity` 跟随实体，可配三轴偏移（`CameraTrackPlayer`）
- **镜头呼吸扰动（cam_breath_*）**
  - ✅ 支持 `cam_breath_enabled` 开关、`cam_breath_intensity` 强度、`cam_breath_seed` 随机种子，运行时叠加随机微晃（`CameraTrackPlayer`）
