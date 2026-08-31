# 输入中间层退出优雅化（Input Handoff）

**版本**: 0.3.5
**类型**: 修复 / 稳定性
**状态**: ✅ 已完成（退出输入重同步已实现）
**关联**: `editor-interaction-improvements.md`（飞行取景共用输入中间层）、`control/InputRouter.java`、`CinematicController.java`

---

## 一句话定义

脚本播放期间中间层接管/转发输入；退出时不再“全量释放按键”，而是把 `KeyMapping` 状态按当前 GLFW 物理按键重同步，并清理鼠标累积量，避免玩家持续按键时退出导致“按键断开/操作卡一下”。

## 问题现象

- 播放中允许移动时，玩家持续按住 W，中间层/游戏侧状态正常。
- 脚本结束，`CinematicController.releaseAllKeys()` 调 `KeyMapping.releaseAll()` 把所有键强制置为松开。
- 但物理键仍按住，GLFW 不会因放行而补发 `keyPress` 事件 → 游戏侧认为键已松开，直到玩家松开重按才恢复。
- 鼠标同理：播放中 `turnPlayer` 被 cancel 时 `MouseHandler.accumulatedDX/DY` 未清，退出后可能带出视角跳变。

## 方案

### 1. 键盘状态重同步（核心）

退出时用 `KeyMapping.setAll()` 替代 `KeyMapping.releaseAll()`：

```java
// MC 1.20.1 自带：把全部 KEYSYM 绑定按物理按键状态 setDown
KeyMapping.setAll();
```

- 播放开始仍可 `releaseAll()` 清旧状态。
- 播放结束/退出改为 `setAll()`，让仍按住的键自动恢复为 down。
- 玩家之后松开时，因已放行，GLFW release 事件正常到达，状态自然闭合。

### 2. 鼠标按键状态重同步

`KeyMapping.setAll()` 只处理键盘（`Type.KEYSYM`），不处理鼠标键。需要补：

```java
long window = Minecraft.getInstance().getWindow().getWindow();
for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
    if (mapping.getKey().getType() == InputConstants.Type.MOUSE) {
        boolean down = GLFW.glfwGetMouseButton(window, mapping.getKey().getValue()) == GLFW.GLFW_PRESS;
        KeyMapping.set(mapping.getKey(), down);
    }
}
```

### 3. 鼠标视角累积量清理

- 在 `MouseHandlerMixin` 暴露 `resetAccumulated()`（accessor/unique），退出时清零 `accumulatedDX/accumulatedDY`。
- 避免退出后第一次 `turnPlayer` 消费播放期间积压的鼠标位移。

### 4. 调用点覆盖所有退出路径

- 自然结束、用户跳过、强制退出、被打断、编辑器退出、紧急停止（视情况）。
- 统一收敛到 `CinematicController` 的退出同步方法（如 `syncInputStateAfterExit()`），不要在 `CameraManager` 各处散落调用。

## 项目改动点

| 文件 | 改动 |
|---|---|
| `control/CinematicController.java` | 拆分/新增：开始用 `releaseAllKeys()`，退出用 `syncInputStateAfterExit()`（键盘 `KeyMapping.setAll()` + 鼠标按钮同步） |
| `mixin/MouseHandlerMixin.java` | 暴露清空 `accumulatedDX/DY` 的方法 |
| `camera/CameraManager.java` | `deactivateNow()` / 退出路径调用新的退出同步 |
| `control/InputRouter.java` | 播放中拦截逻辑保持不变 |

## 参考

- MC 1.20.1 `KeyMapping.setAll()` / `KeyMapping.releaseAll()` 源码（已确认存在）。
- Freecam（Zergatul）思路：不 cancel 原始事件、在 tick 里直接读物理键状态，底层输入状态始终连续；本项目采用“退出时重同步”等价达到无缝交接。
