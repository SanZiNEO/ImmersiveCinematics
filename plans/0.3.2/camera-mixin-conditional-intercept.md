# ③ 相机 Mixin 条件拦截 — 逐行修复指南

**版本**: 0.3.2  
**类型**: Bug 修复  
**执行方式**: 直接照做，无视解

---

## 背景

当前 Mixin 整个播放期间锁相机。修改后：只在 CAMERA track 有活跃 clip 时拦截。

---

## 步骤 1: ScriptPlayer.java — 新增 hasActiveCameraTrack()

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptPlayer.java`

在任意位置（建议放在 `getRemainingTime()` 方法后面）新增方法:

```java
public boolean hasActiveCameraTrack(float elapsed) {
    for (TrackPlayer tp : trackPlayers) {
        if (tp instanceof CameraTrackPlayer && tp.isActiveAt(elapsed)) {
            return true;
        }
    }
    return false;
}
```

> 注: trackPlayers 列表和 TrackPlayer/CameraTrackPlayer 类都已在同文件 import。

---

## 步骤 2: CameraManager.java — 新增 hasActiveCameraClip()

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/camera/CameraManager.java`

在任意位置（建议放在 `isScriptMode()` 方法后面）新增方法:

```java
public boolean hasActiveCameraClip() {
    return scriptPlayer.hasActiveCameraTrack((float)getGameTimeSeconds());
}
```

---

## 步骤 3: CameraMixin.java — 修改注入条件 (Line 73)

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/mixin/CameraMixin.java`

第 73 行，改 1 行:

```diff
- if (mgr.isActive()) {
+ if (mgr.isActive() && mgr.hasActiveCameraClip()) {
```

---

## 步骤 4: GameRendererMixin.java — 修改 5 处注入条件

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/mixin/GameRendererMixin.java`

### 步骤 4a — getFov (Line 23)

```diff
- if (mgr.isActive()) {
+ if (mgr.isActive() && mgr.hasActiveCameraClip()) {
```

### 步骤 4b — renderItemInHand (Line 45)

```diff
- if (CameraManager.INSTANCE.isActive() && CinematicController.INSTANCE.isHideArm()) {
+ if (CameraManager.INSTANCE.isActive() && CameraManager.INSTANCE.hasActiveCameraClip() && CinematicController.INSTANCE.isHideArm()) {
```

### 步骤 4c — bobHurt (Line 59)

```diff
- if (CameraManager.INSTANCE.isActive() && CinematicController.INSTANCE.isSuppressBob()) {
+ if (CameraManager.INSTANCE.isActive() && CameraManager.INSTANCE.hasActiveCameraClip() && CinematicController.INSTANCE.isSuppressBob()) {
```

### 步骤 4d — bobView (Line 66)

```diff
- if (CameraManager.INSTANCE.isActive() && CinematicController.INSTANCE.isSuppressBob()) {
+ if (CameraManager.INSTANCE.isActive() && CameraManager.INSTANCE.hasActiveCameraClip() && CinematicController.INSTANCE.isSuppressBob()) {
```

### 步骤 4e — redirectSpinningIntensity (Line 91)

```diff
- if (CameraManager.INSTANCE.isActive() && CinematicController.INSTANCE.isSuppressBob()) {
+ if (CameraManager.INSTANCE.isActive() && CameraManager.INSTANCE.hasActiveCameraClip() && CinematicController.INSTANCE.isSuppressBob()) {
```

---

## 完成检查清单

- [ ] `ScriptPlayer.java` 新增 `hasActiveCameraTrack()` 方法
- [ ] `CameraManager.java` 新增 `hasActiveCameraClip()` 方法
- [ ] `CameraMixin.java` 第 73 行条件已改为 `isActive() && hasActiveCameraClip()`
- [ ] `GameRendererMixin.java` 第 23 行已改
- [ ] `GameRendererMixin.java` 第 45 行已改
- [ ] `GameRendererMixin.java` 第 59 行已改
- [ ] `GameRendererMixin.java` 第 66 行已改
- [ ] `GameRendererMixin.java` 第 91 行已改
- [ ] 编译通过
- [ ] 测试: 纯黑边脚本中，视角可自由转动（CAMERA mixin 不拦截）
- [ ] 测试: 带 CAMERA clip 的脚本中，clip 期间视角锁、gap 期间视角释放
