# 修复计划 B：🟡 建议改进的问题（5项）

> 验证结果：**全部属实**，以下为详细修复方案。

---

## 问题 #4：`TimelineTrack` 的 `List<?>` + unchecked cast — 类型安全缺失

### 问题确认

[`TimelineTrack.java`](src/main/java/com/immersivecinematics/immersive_cinematics/script/TimelineTrack.java:53-55) 中 `getCameraClips()` 等方法使用 `@SuppressWarnings("unchecked")` 直接强转 `List<?>`，无类型检查。如果 `type != CAMERA` 时调用 `getCameraClips()`，`ClassCastException` 不会在调用点抛出，而是在后续使用元素时才抛出，调试困难。

### 修复方案

在每个类型化 getter 方法入口添加 `type` 检查，类型不匹配时抛出 `IllegalStateException` 并给出清晰错误信息。

### 修改步骤

1. **修改 [`TimelineTrack.java`](src/main/java/com/immersivecinematics/immersive_cinematics/script/TimelineTrack.java)**
   - 修改 `getCameraClips()`（第 53-55 行）：添加 `if (type != TrackType.CAMERA)` 检查
   - 修改 `getLetterboxClips()`（第 61-63 行）：添加 `if (type != TrackType.LETTERBOX)` 检查
   - 修改 `getAudioClips()`（第 69-71 行）：添加 `if (type != TrackType.AUDIO)` 检查
   - 修改 `getEventClips()`（第 77-79 行）：添加 `if (type != TrackType.EVENT)` 检查
   - 修改 `getModEventClips()`（第 85-87 行）：添加 `if (type != TrackType.MOD_EVENT)` 检查

### 修改后的代码示例

```java
public List<CameraClip> getCameraClips() {
    if (type != TrackType.CAMERA) {
        throw new IllegalStateException(
            "Cannot get CameraClips from track type " + type + "; expected CAMERA");
    }
    @SuppressWarnings("unchecked")
    List<CameraClip> result = (List<CameraClip>) clips;
    return result;
}
```

---

## 问题 #5：`ScriptMeta` 兼容旧构造器 — 冗余代码

### 问题确认

[`ScriptMeta.java`](src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptMeta.java:68-82) 中的旧构造器缺少 `skippable` 和 `curveCompositionMode` 参数，`skippable` 硬编码为 `true`。搜索 `new ScriptMeta(` 的调用点，仅 [`ScriptParser.java:146`](src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptParser.java:146) 使用新构造器，旧构造器无调用者。

### 修复方案

直接删除旧构造器。当前无调用者，不存在兼容性问题。

### 修改步骤

1. **修改 [`ScriptMeta.java`](src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptMeta.java)**
   - 删除第 62-82 行的旧构造器及其 Javadoc（第 62-67 行）
   - 无需修改其他文件

---

## 问题 #6：`CameraManager.tick()` 空方法 — 死代码

### 问题确认

[`CameraManager.tick()`](src/main/java/com/immersivecinematics/immersive_cinematics/camera/CameraManager.java:320-323) 方法体为空（仅有一个 `if (!active) return;`），但仍由 [`Immersive_cinematics.ClientTickEvents.onClientTick()`](src/main/java/com/immersivecinematics/immersive_cinematics/Immersive_cinematics.java:56-60) 每帧调用。

### 修复方案

保留 `tick()` 方法但添加 staged 缓冲区的 tick 驱动逻辑，使其有实际用途。staged 缓冲区的 `CameraProperties.tick()` 和 `CameraPath.tick()` 需要每 tick 驱动过渡插值。

### 修改步骤

1. **修改 [`CameraManager.tick()`](src/main/java/com/immersivecinematics/immersive_cinematics/camera/CameraManager.java:320-323)**
   - 添加 staged 缓冲区的 tick 驱动逻辑

```java
public void tick() {
    if (!active) return;
    // 驱动 staged 缓冲区的过渡插值（供编辑器预览使用）
    if (stagedReady) {
        float deltaTime = 1f / 20f;  // 假设 20 TPS
        stagedProperties.tick(deltaTime);
        stagedPath.tick(deltaTime);
    }
}
```

2. **验证 `CameraPath.tick()` 是否存在**
   - 如果 `CameraPath` 没有 `tick()` 方法，只驱动 `stagedProperties.tick()`

### 备选方案

如果确认 staged 缓冲区在当前阶段不需要 tick 驱动，则：
- 删除 `tick()` 方法
- 删除 [`Immersive_cinematics.java`](src/main/java/com/immersivecinematics/immersive_cinematics/Immersive_cinematics.java:44) 中的 `MinecraftForge.EVENT_BUS.register(ClientTickEvents.class)`
- 删除整个 `ClientTickEvents` 内部类

---

## 问题 #7：`PathStrategies` 默认策略名 `"bezier"` 与 `null` fallback 语义冲突

### 问题确认

[`PathStrategies.java`](src/main/java/com/immersivecinematics/immersive_cinematics/script/PathStrategies.java:29) 中 `DEFAULT_TYPE = "bezier"`。当 [`KeyframeInterpolator.interpolatePosition()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/KeyframeInterpolator.java:156-157) 传入 `curveType=null`（无贝塞尔曲线）时，`PathStrategies.get(null)` 返回 `BezierPathStrategy`，而 [`BezierPathStrategy.interpolate()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/BezierPathStrategy.java:15-22) 在 `curve=null` 时退化为 `from.lerp(to, t)`。结果正确但路径绕了一圈：null → bezier strategy → lerp fallback。

### 修复方案

将 `DEFAULT_TYPE` 改为 `"linear"`，使 `null` 输入直接走线性插值路径，语义更清晰。

### 修改步骤

1. **修改 [`PathStrategies.java`](src/main/java/com/immersivecinematics/immersive_cinematics/script/PathStrategies.java)**
   - 将第 29 行 `DEFAULT_TYPE = "bezier"` 改为 `DEFAULT_TYPE = "linear"`
   - 更新第 28 行 Javadoc：`/** 默认策略：线性插值 */`

2. **验证影响**
   - `PathStrategies.get(null)` 现在返回 linear 策略而非 bezier 策略
   - `PathStrategies.get("unknown_type")` 也返回 linear 策略而非 bezier 策略
   - 这更符合语义：未知/空类型 → 最简单的线性插值
   - 显式指定 `"bezier"` 的调用不受影响

3. **更新 Javadoc**
   - 类级 Javadoc 中更新默认策略描述

---

## 问题 #8：`CinematicCommand` 第37行异常空白字符

### 问题确认

[`CinematicCommand.java`](src/main/java/com/immersivecinematics/immersive_cinematics/command/CinematicCommand.java:37) 第 37 行 `public class` 与 `CinematicCommand` 之间有约 500 个空格字符，疑似编辑器异常。

### 修复方案

直接清除多余空白。

### 修改步骤

1. **修改 [`CinematicCommand.java`](src/main/java/com/immersivecinematics/immersive_cinematics/command/CinematicCommand.java:37)**
   - 将第 37 行 `public class                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 CinematicCommand {` 改为 `public class CinematicCommand {`

---

## 修复顺序建议

| 顺序 | 问题 | 原因 |
|------|------|------|
| 1 | #8 清除 CinematicCommand 空白字符 | 极小改动，零风险 |
| 2 | #5 删除 ScriptMeta 旧构造器 | 小改动，无调用者，零风险 |
| 3 | #4 TimelineTrack getter 添加类型检查 | 小改动，纯防御性编程 |
| 4 | #7 PathStrategies 默认策略改为 linear | 小改动，需验证行为一致性 |
| 5 | #6 CameraManager.tick() 空方法决策 | 需要决策：保留并添加逻辑 vs 删除 |
