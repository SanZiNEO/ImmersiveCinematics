# 相机功能实施计划

## 当前状态

阶段1-4 ✅ 已完成 | 阶段5 ⏳ 待实现（清理阶段，非核心功能）

## ✅ 阶段1：CameraTestPlayer + 基础测试

**实施内容**：创建 [`CameraTestPlayer.java`](../src/main/java/com/immersivecinematics/immersive_cinematics/camera/CameraTestPlayer.java)，硬编码 18 段叙事运镜测试脚本（~107 秒），P 键激活后自动播放。

**关键问题与解决**：
- **帧级插值缺失** → 20tick/s 步进感 → 在 CameraMixin.setup() 中添加 partialTick 帧级插值
- **角度环绕** → CameraProperties 添加 lerpAngle() 处理 -180°~180° 跳变
- **共享插值状态** → CameraTestPlayer 使用 duration=0 直接写入 CameraManager，绕过 CameraPath/CameraProperties 内部插值

**验证**：✅ Position/Yaw/Pitch/FOV 平滑过渡，HUD/手臂隐藏，玩家身体可见，播放结束自动恢复

## ✅ 阶段2：Roll 实现

**最终方案**：Forge 事件 `ViewportEvent.ComputeCameraAngles` → `event.setRoll(roll)`

**失败方案**：CameraMixin 注入 setRotation 修改 rotation 四元数（对渲染管线无效）
- **根因**：renderLevel() 不使用 camera.rotation，渲染视角由 Forge 事件独立构建 PoseStack

**清理**：CameraMixin 删除 @Shadow rotation/forwards/up/left 和 roll 注入块

**验证**：✅ Roll 非零时画面绕视线轴旋转，Roll=0 与原版一致

## ✅ 阶段3：Zoom 实现

**方案**：`effectiveFov = baseFov / zoom`（在 GameRendererMixin.onGetFov 中）
- zoom > 1 → 放大（望远镜效果）
- zoom < 1 → 广角效果
- zoom = 1 → 正常视野

**验证**：✅ Zoom 增大/减小/默认均正确表现，关键帧间平滑过渡

## ✅ 阶段4：画幅比黑边 + 分层覆盖系统

### 4.1 分层覆盖系统架构
- **核心设计**：覆盖层（黑边/文字/视频）独立于镜头跳转逻辑，全程保持
- **多时间轴架构**：相机属性跟随镜头跳转（staged/active 双缓冲），覆盖层属性独立管理
- **OverlayLayer 接口**：统一层接口（`render()`/`isVisible()`/`getZIndex()`/`reset()`），按 zIndex 排序渲染
- **OverlayManager**：单例层管理器，`reset()` 遍历所有层自动调用，支持 `getLayers(Class<T>)` 多实例查询
- **CinematicOverlay**：Forge GuiOverlay 注册入口（`registerAboveAll`），接入 HUD 渲染管线
- **白名单放行**：CinematicOverlay 加入 HudOverlayHandler 白名单，其他 HUD 在电影模式下隐藏

### 4.2 画幅比黑边（LetterboxLayer）
- 实现方式：LetterboxLayer 中使用 `GuiGraphics.fill()` 绘制纯黑矩形（`0xFF000000`）
- 不使用 OpenGL/FBO/shader，与光影包完美兼容
- **永远只用上下黑边（Letterbox）**，不使用左右黑边（Pillarbox），与电影工业标准一致
- 算法：`contentHeight = screenWidth / aspectRatio`，若 `contentHeight < screenHeight` 则画上下黑边
- aspectRatio = 0.0 时禁用黑边
- 激活相机时设置默认 2.35:1，停用时自动消失

### 4.3 README 光影包推荐
- 添加推荐光影包章节（BSL/Complementary/Sildur's）
- 说明 DOF/调色委托给光影包（已放弃自实现，详见原计划 DOF 诊断记录）

### 修改文件

| 文件 | 操作 |
|------|------|
| `overlay/OverlayLayer.java` | 新增：覆盖层接口（含 `reset()` 方法） |
| `overlay/LetterboxLayer.java` | 新增：黑边层实现（纯 Letterbox） |
| `overlay/OverlayManager.java` | 新增：层管理器（泛化 reset + 多实例查询） |
| `overlay/CinematicOverlay.java` | 新增：Forge GuiOverlay 注册入口 |
| `HudOverlayHandler.java` | 修改：白名单添加 CinematicOverlay，移除未使用 import |
| `CameraManager.java` | 修改：activate() 设置黑边，deactivate() 重置覆盖层 |
| `Immersive_cinematics.java` | 修改：注册 CinematicOverlay 到 MOD 事件总线 |
| `README.md` | 修改：添加光影包推荐章节 |

### 验证清单
- [x] aspectRatio=0.0 无黑边，aspectRatio=2.35 上下黑边
- [x] 停用相机后黑边立即消失，窗口 resize 后重新计算
- [x] 黑边在电影模式下正确显示，非电影模式下零开销
- [ ] 安装 OptiFine+BSL / Oculus+Complementary 后相机动画正常
- [ ] Roll/Zoom/FOV 动画与光影效果无冲突

## ⏳ 阶段5：整体验证 + 清理

### 待清理
- [ ] 删除 `CameraTestPlayer.java`
- [ ] 从 `CameraManager.java` 移除 testPlayer 相关代码
- [ ] 恢复 P键为纯 `CameraManager.INSTANCE.toggle()`

### 待验证
- [ ] 光影包兼容性
- [ ] 性能可接受

## 修改文件汇总

| 阶段 | 文件 | 状态 |
|------|------|------|
| 1-3 | CameraTestPlayer / CameraManager / CameraMixin / GameRendererMixin / Immersive_cinematics | ✅ |
| 2 | CameraMixin 清理（删除无效 roll 注入） | ✅ |
| 4 | OverlayLayer / LetterboxLayer / OverlayManager / CinematicOverlay（新增） | ✅ |
| 4 | HudOverlayHandler + CameraManager + Immersive_cinematics（修改） | ✅ |
| 4 | README 光影包推荐 | ⏳ |
| 5 | 删除 CameraTestPlayer | ⏳ |

## 关键设计约束

1. 每次 P 键激活从玩家当前位置/朝向开始
2. deltaTime 固定 1/20 秒，保证脚本时间精度
3. 所有自实现后处理已移除（DOF/曝光度 → 委托给光影包）
4. 画幅比黑边使用 HUD 叠加层实现（不涉及 OpenGL/FBO），永远只用 Letterbox（上下黑边）
5. Zoom 通过 FOV 映射：`effectiveFov = baseFov / zoom`
6. CameraTestPlayer 使用 duration=0 直接写入，绕过共享插值状态
7. 测试完成后删除 CameraTestPlayer，零残留
8. 覆盖层属性（aspectRatio/文字/视频）独立于镜头跳转逻辑，不受 staged/active 双缓冲影响
9. OverlayLayer.reset() 由 OverlayManager 遍历调用，新增层无需修改 OverlayManager
