# 编辑器交互优化：中间帧继承 + 飞行取景操控模式

**版本**: 0.3.5
**类型**: 编辑器功能（2026-08-13 讨论定稿）
**状态**: ✅ 已完成（中间帧继承 + 飞行取景 F7 已实现）
**关联**: 复用编辑器现有预览体系（PreviewArea / OrbitGizmo / 迷你滑块 / undo 快照）；与相机体系共用"取相机参数"逻辑

---

## 一句话定义

两个编辑器优化：① 修复"在两个关键帧之间创建新关键帧时，值用预设默认值而非当前插值值"的 bug——创建时继承当前预览相机参数；② 新增**飞行取景操控模式**（快捷键 toggle）：脚本暂停、预览相机被 WASD + 鼠标接管，退出时把当前位置/角度一键写入关键帧（无选中则在播放头处创建）——与 OrbitGizmo（3D 球）、迷你滑块并存的第三种取景操作模式，取景更直觉（"飞到想要的位置，一键记录"）。

## 决策记录（2026-08-13 用户拍板）

| # | 决策 | 内容 |
|---|------|------|
| 1 | 中间帧继承 | 创建关键帧时，值 = 当前预览相机参数（插值结果），非默认值；播放头在 gap 时回落默认值 |
| 2 | 操控模式形态 | 快捷键 toggle：进入 = 脚本暂停 + WASD/鼠标接管预览相机；再按 = 退出 + 记录 |
| 3 | 记录目标 | 当前选中关键帧；无选中 → 播放头位置创建关键帧再写入 |
| 4 | 记录字段 | position + yaw + pitch（按关键帧 position_mode 转换）；roll/fov/zoom 不动 |
| 5 | 操作模式并存 | 与 OrbitGizmo、迷你滑块并列第三种；不替代 |
| 6 | 全屏预览 | 操控模式下预览铺满编辑器（隐藏 UI），退出还原——倾向做（待最终确认） |
| 7 | 快捷键规划 | 绑定键多是编辑器形态的必然（MC 里的 PR/AE）——不因键多限制功能；本次用 F6（可改） |
| 8 | **输入还原原版** | 操控模式的输入处理 = 原版玩家操控链路（源码照抄）：键盘读 `Options` KeyMapping（keyW/A/S/D/Space/Shift，尊重玩家自定义键位）+ `KeyboardInput.calculateImpulse` 逻辑；鼠标用 `MouseHandler.turnPlayer` 公式（`(sensitivity×0.6+0.2)³×8` + 反转 Y + 平滑相机）；不经过编辑器 UI 输入分发 |

---

## 一、中间帧继承（bug 修复）

**问题**：播放头停在关键帧 A、B 之间（预览插值正确），新建关键帧填的是 schema 默认值（0/0/0、yaw 0）——位置/角度断裂，作者要手动重填。

**修复**：新建关键帧时，若播放头位于某 clip 的相邻关键帧之间 → 取当前预览相机参数（CameraManager 的当前位置/角度——预览相机显示的就是插值结果）作为新关键帧的属性值。播放头在 gap → 无插值可继承 → 默认值。

**实现**：与操控模式共用"取相机参数"逻辑（一处实现，两处使用）。

## 二、飞行取景操控模式

### 交互流程

```
按 F6 → 进入操控模式
  ├─ 播放头暂停（脚本暂停，预览画面停住）
  ├─ 预览铺满编辑器（UI 隐藏）
  ├─ WASD 平移相机（+ 空格/Shift 升降）
  ├─ 鼠标移动 → yaw/pitch（视角接管）
  └─ 画面 = 被操控的相机
再按 F6 → 退出 + 记录：
  ├─ 有选中关键帧 → 写入 position + yaw + pitch（position_mode: absolute → x/y/z；relative → dx/dy/dz，基准=当前玩家位置）
  ├─ 无选中关键帧 → 在播放头位置创建关键帧再写入
  ├─ roll/fov/zoom 保持关键帧原值不动
  └─ 一次写入 = 一个 undo 快照（与 gizmo 拖拽一致）
Esc → 取消退出（不记录，仅退出操控模式还原 UI）
```

### 关键点

- 操控模式只改**预览相机**（CameraManager 直控路径，与 gizmo 拖拽同机制：`setPreviewDirectControl`）——不改脚本数据，直到退出记录
- 鼠标输入：操控模式下鼠标在预览区拖拽/移动 → yaw/pitch（F5 视角式）；WASD 键盘事件接管
- relative 记录：dx/dy/dz = 相机位置 - 当前玩家位置（编辑器预览时玩家位置 = mc.player 实时位置）
- 暂停语义：进入时暂停播放（playback 暂停），退出后保持暂停（记录后不自动播放）
- 与 OrbitGizmo/滑块互斥：操控模式激活时隐藏 gizmo/滑块（避免双重控制）

### 输入处理（还原原版玩家操控，源码照抄）

| 输入 | 原版机制 | 还原方式 |
|---|---|---|
| 键盘移动 | `KeyboardInput.tick`：`Options.keyUp/Down/Left/Right/Jump/Shift` → `calculateImpulse`（前后/左右抵消 → -1/0/1） | 操控模式下每 tick 直接读 KeyMapping + 照抄 calculateImpulse → 得到移动输入 |
| 鼠标朝向 | `MouseHandler.turnPlayer`：`(sensitivity×0.6+0.2)³×8` + 反转 Y + 平滑相机 | 照抄公式应用到预览相机 yaw/pitch |
| 鼠标原始移动 | 原版靠 grabMouse（Screen 打开时不可用） | **软 grab**：每帧读 GLFW 鼠标位置 → delta → 应用 → `glfwSetCursorPos` 重置回窗口中心（无限旋转，不关编辑器） |

**移动语义**：飞行模式——WASD 相对相机朝向平移（前进 = look 方向水平投影）、空格/Shift 升降、速度固定（默认如 10 格/秒，可调）。键位全部尊重玩家自定义绑定。

### 全屏预览（待定确认）

- 操控模式下隐藏编辑器 UI、预览铺满窗口——视野大、操控舒服
- 退出（F6/Esc）还原编辑器 UI
- 实现：复用 PreviewCapture 全屏渲染路径（编辑器窗口即预览画面），UI 组件隐藏

## 三、快捷键规划（原则）

- 本模组编辑器 = MC 内的 PR/AE——**绑定键多是必然形态**，不因键多限制功能设计
- 本次新增：F6（操控模式 toggle，可改）；Esc（取消退出）
- 未来键位管理（冲突处理/自定义绑定）按需再做，本次不做

---

## 实施顺序

1. 取相机参数逻辑（CameraManager 当前 position/yaw/pitch + position_mode 转换）
2. 中间帧继承（新建关键帧路径接入）
3. 操控模式：toggle 快捷键 + 暂停 + WASD/鼠标接管 + 全屏预览
4. 退出记录（写入选中/创建关键帧 + undo 快照）+ Esc 取消
5. 冒烟测试：中间帧继承值正确、操控模式全流程、relative/absolute 记录、无选中自动创建、Esc 取消不写入、与 gizmo/滑块互斥

## 待定

1. 全屏预览：做 vs 只放大预览区（倾向做，用户确认中）
2. 键位 F6 最终确认
3. 升降键：空格/Shift（vs 滚轮）确认

## 执行前再看 / 具体方案

- **项目文件**：
  - `camera/CameraManager.java`：`setPreviewDirectControl(boolean)`、`previewSetCamera(...)`（飞行模式直驱入口）。
  - `script/CameraTrackPlayer.onRenderFrame`：`if (cameraManager.isPreviewDirectControl()) return;`。
  - `editor/EditorOperations.java`：`addKeyframeAt` / `interpolateKeyframe` / `copyKeyframeProperties`（中间帧继承复用）。
  - `control/InputRouter.java`、`mixin/MouseHandlerMixin.java`、`mixin/KeyboardHandlerMixin.java`（现有输入路由，飞行模式需新增路由/目标）。
  - `editor/EditorUndoManager.java`（记录 undo）。
- **MC 源码**（已抽取到 `build/mc-sources/`）：
  - `client/player/KeyboardInput.java`：`calculateImpulse`。
  - `client/MouseHandler.java`：`turnPlayer()` 灵敏度公式 `(sensitivity*0.6+0.2)^3*8`、`invertYMouse`、`SmoothDouble`。
  - `client/Options.java`：`keyUp/keyDown/keyLeft/keyRight/keyJump/keyShift`。
- **外部参考（软 grab / 自由相机）**：
  - **FreeCam（Zergatul）**：mcmod [class/7117](https://www.mcmod.cn/class/7117.html)，GitHub `Zergatul/freecam`。重点看：
    - `common/mod/java/com/zergatul/freecam/mixins/MixinMouseHandler.java`：拦截 `MouseHandler.turnPlayer` 里的 `LocalPlayer.turn`，转发给 `FreeCam.onPlayerTurn`——与本项目 `MouseHandlerMixin` + `InputRouter` 对应。
    - `common/mod/java/com/zergatul/freecam/FreeCam.java`：主逻辑（键位读取/位移/相机覆盖）。
    - `common/mod/java/com/zergatul/freecam/mixins/MixinCamera.java`、`MixinGameRenderer.java`。
  - mcmod 另有 [FreeCam by kapiteon](https://www.mcmod.cn/class/6729.html)（Fork: `mhornbacher/freercam-minecraft`）。
  - `SkyblockerMod/Skyblocker` → `MouseHandlerMixin`（`glfwSetCursorPos` 回中）。
  - `menglannnn/NewSim_U_Kraft` → `MixinMouseHandler`（帧间增量 + turnPlayer 接管自由相机）。
- **移动方案（已定，参考 Freecam/Zergatul）**：
  - 每 tick 直接读 `Options.keyUp/keyDown/keyLeft/keyRight/keyJump/keyShift` 的 `isDown()`，用 `KeyboardInput.calculateImpulse` 得前后/左右输入。
  - 由预览相机当前 yaw 计算 forward/right 水平向量：`forward = (-sin(yaw), 0, cos(yaw))`，`right = (cos(yaw), 0, sin(yaw))`（注意 MC yaw 约定）。
  - 位移 = `(forward * forwardImpulse + right * leftImpulse) * speed * dt`；空格/Shift 直接改 y；速度默认 10 格/秒（可调）。
  - 鼠标用 `GLFW.glfwGetCursorPos` 算 delta，再 `glfwSetCursorPos` 回中；应用 `MouseHandler.turnPlayer` 同款灵敏度公式到预览相机 yaw/pitch。
  - 不 cancel 原始输入事件，保持输入状态连续；飞行模式退出时复用 `input-handoff.md` 的状态重同步。
- **执行时再看**：`CameraManager` 直控、`EditorOperations`、`InputRouter`/两个输入 Mixin、`CinematicKeyBindings`、`EditorUndoManager`；MC `MouseHandler.turnPlayer`/`KeyboardInput`；FreeCam `FreeCam.java` 的位移读取逻辑。
