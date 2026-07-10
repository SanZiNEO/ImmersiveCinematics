# 修复计划 D：深度审查额外发现的问题（6项）

> 来源：对全部 30+ 源文件的逐行审查，在计划 A/B/C 之外发现的额外问题。

---

## 问题 B1：🟡 `LetterboxClip.isInfinite()` 和 `Timeline.isInfinite()` 同样使用 `== -1f` 浮点等值比较

### 问题确认

计划 C 的 A2 只提到了 [`CameraClip.isInfinite()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/CameraClip.java:100)，但 [`LetterboxClip.isInfinite()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/LetterboxClip.java:53) 和 [`Timeline.isInfinite()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/Timeline.java:30) 存在完全相同的浮点等值比较问题：

```java
// LetterboxClip.java:53
public boolean isInfinite() { return duration == -1f; }

// Timeline.java:30
public boolean isInfinite() { return totalDuration == -1f; }
```

此外，[`LetterboxTrackPlayer.findActiveClip()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/LetterboxTrackPlayer.java:68) 中也使用了 `clip.getDuration() < 0` 来判断无限时长，与 `isInfinite()` 的 `== -1f` 语义不一致。

### 修复方案

与计划 C 的 A2 一并修复，统一改为 `duration < 0f` / `totalDuration < 0f`。

### 修改步骤

1. **修改 [`LetterboxClip.isInfinite()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/LetterboxClip.java:53)**
   - 将 `return duration == -1f;` 改为 `return duration < 0f;`

2. **修改 [`Timeline.isInfinite()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/Timeline.java:30)**
   - 将 `return totalDuration == -1f;` 改为 `return totalDuration < 0f;`

3. **更新 Javadoc**
   - [`LetterboxClip`](src/main/java/com/immersivecinematics/immersive_cinematics/script/LetterboxClip.java:20): `/** 持续时长（秒），-1=无限 */` → `/** 持续时长（秒），负数=无限 */`
   - [`Timeline`](src/main/java/com/immersivecinematics/immersive_cinematics/script/Timeline.java:15): `/** 脚本总时长（秒），-1 = 含无限时长片段 */` → `/** 脚本总时长（秒），负数 = 含无限时长片段 */`

4. **验证一致性**
   - [`LetterboxTrackPlayer.findActiveClip()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/LetterboxTrackPlayer.java:68) 已使用 `clip.getDuration() < 0`，修复后与 `isInfinite()` 语义一致 ✅
   - [`ScriptPlayer.isFinished()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptPlayer.java:141) 已使用 `totalDuration < 0`，修复后与 `Timeline.isInfinite()` 语义一致 ✅

---

## 问题 B2：🟡 `CinematicCommand.playScript()` 在客户端执行前就发送成功消息 — 反馈不准确

### 问题确认

[`CinematicCommand.playScript()`](src/main/java/com/immersivecinematics/immersive_cinematics/command/CinematicCommand.java:100-106) 中，`Minecraft.getInstance().execute()` 是异步调度到客户端线程，`playScript()` 可能返回 `false`（如当前脚本 `interruptible=false` 拒绝抢占），但成功消息已经发出：

```java
net.minecraft.client.Minecraft.getInstance().execute(() -> {
    CameraManager.INSTANCE.playScript(script);  // ← 可能返回 false
});

source.sendSuccess(() -> Component.literal("§a脚本播放开始: ..."), false);  // ← 无条件成功
```

[`stopScript()`](src/main/java/com/immersivecinematics/immersive_cinematics/command/CinematicCommand.java:109-118) 也有同样的问题。

### 修复方案

**短期方案**（本次修复）：在客户端回调内检查结果，通过客户端聊天消息反馈实际状态。

**长期方案**（后续迭代）：通过网络包将结果回传服务端，使用 `source.sendSuccess/Failure` 正确反馈。

### 修改步骤

1. **修改 [`CinematicCommand.playScript()`](src/main/java/com/immersivecinematics/immersive_cinematics/command/CinematicCommand.java:99-107)**
   ```java
   net.minecraft.client.Minecraft.getInstance().execute(() -> {
       boolean ok = CameraManager.INSTANCE.playScript(script);
       if (ok) {
           net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(
                   Component.literal("§a脚本播放开始: §f" + script.getName() +
                           " §7(总时长: " + script.getTotalDuration() + "s)"), false);
       } else {
           net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(
                   Component.literal("§c脚本播放被拒绝（当前脚本不可打断）"), false);
       }
   });
   // 移除 source.sendSuccess() 的无条件成功消息
   source.sendSuccess(() -> Component.literal("§7脚本加载成功，正在调度播放..."), false);
   ```

2. **修改 [`CinematicCommand.stopScript()`](src/main/java/com/immersivecinematics/immersive_cinematics/command/CinematicCommand.java:109-118)**
   - 类似处理：在客户端回调内反馈实际停止结果

### 注意事项

- `displayClientMessage()` 只在客户端显示，不会记录到服务端日志
- 如果 `Minecraft.getInstance().player` 为 null（理论上不会，因为命令在单人游戏执行），需要 null 检查
- 此修改改变了消息显示方式：从服务端系统消息变为客户端聊天消息，视觉上可能略有差异

---

## 问题 B3：🟡 `ScriptProperties.revert()` 硬编码默认值 — DRY 违反

### 问题确认

[`ScriptProperties.revert()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptProperties.java:93-110) 中 15 个布尔值全部硬编码，与 [`ScriptMeta.RuntimeBehavior.DEFAULT`](src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptMeta.java:173-177) 定义了相同的默认值。如果 `DEFAULT` 常量修改，`revert()` 必须手动同步。

同样，[`ScriptProperties`](src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptProperties.java:27-47) 的字段初始值也是硬编码的，与 `DEFAULT` 重复。

### 修复方案

`revert()` 从 `RuntimeBehavior.DEFAULT` 读取默认值，消除重复定义。

### 修改步骤

1. **修改 [`ScriptProperties.revert()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptProperties.java:93-110)**
   ```java
   public void revert() {
       current = null;
       ScriptMeta.RuntimeBehavior defaults = ScriptMeta.RuntimeBehavior.DEFAULT;
       this.blockKeyboard = defaults.blockKeyboard();
       this.blockMouse = defaults.blockMouse();
       this.blockMobAi = defaults.blockMobAi();
       this.hideHud = defaults.hideHud();
       this.hideArm = defaults.hideArm();
       this.suppressBob = defaults.suppressBob();
       this.blockChat = defaults.blockChat();
       this.blockScoreboard = defaults.blockScoreboard();
       this.blockActionBar = defaults.blockActionBar();
       this.blockParticles = defaults.blockParticles();
       this.renderPlayerModel = defaults.renderPlayerModel();
       this.pauseWhenGamePaused = defaults.pauseWhenGamePaused();
       this.interruptible = defaults.interruptible();
       this.skippable = defaults.skippable();
       this.holdAtEnd = defaults.holdAtEnd();
   }
   ```

2. **考虑字段初始值也使用 DEFAULT**
   - 当前字段声明 `private boolean blockKeyboard = true;` 也是硬编码
   - 但 Java 要求字段初始值是编译期常量，不能调用方法
   - 可选方案：在类初始化块中从 DEFAULT 读取，但增加了复杂度
   - **建议**：仅修复 `revert()`，字段初始值保持硬编码（因为 `revert()` 是运行时唯一起作用的路径，构造时 `new ScriptProperties()` 后会立即被 `apply()` 覆盖）

---

## 问题 B4：🟡 `MathUtil.smoothstep()` 当 `edge0 == edge1` 时除零产生 NaN

### 问题确认

[`MathUtil.smoothstep()`](src/main/java/com/immersivecinematics/immersive_cinematics/util/MathUtil.java:162-166) 中，当 `edge0 == edge1` 时：

- `x > edge0`：`(x - edge0) / 0 = +Infinity`，`clamp(Infinity) = 1f`，结果 `1f` ✅
- `x < edge0`：`(x - edge0) / 0 = -Infinity`，`clamp(-Infinity) = 0f`，结果 `0f` ✅
- `x == edge0`：`0 / 0 = NaN`，`clamp(NaN) = NaN`，结果 `NaN` ❌

当前无调用者传入 `edge0 == edge1`，但作为公共工具方法，应做防御处理。

### 修复方案

添加守卫条件，处理 `edge0 == edge1` 的边界情况。

### 修改步骤

1. **修改 [`MathUtil.smoothstep()`](src/main/java/com/immersivecinematics/immersive_cinematics/util/MathUtil.java:162-166)**
   ```java
   public static float smoothstep(float edge0, float edge1, float x) {
       if (edge0 == edge1) {
           return (x >= edge0) ? 1f : 0f;
       }
       float t = (x - edge0) / (edge1 - edge0);
       t = Math.max(0f, Math.min(1f, t));
       return t * t * (3f - 2f * t);
   }
   ```

2. **添加 Javadoc 说明**
   - 标注当 `edge0 == edge1` 时的行为：`x >= edge0` 返回 1，否则返回 0

---

## 问题 B5：🟢 `ScriptPlayer.onRenderFrame()` holdAtEnd 使用魔法数 `0.0001f`

### 问题确认

[`ScriptPlayer.onRenderFrame()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptPlayer.java:188) 中：

```java
if (script.getMeta().isHoldAtEnd()) {
    elapsedSeconds = totalDuration - 0.0001f;  // ← 魔法数
}
```

`0.0001f` 是硬编码的微小偏移量，无注释说明其含义和选择依据。

### 修复方案

提取为命名常量并添加注释。

### 修改步骤

1. **修改 [`ScriptPlayer.java`](src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptPlayer.java)**
   - 在类顶部添加常量：
   ```java
   /**
    * holdAtEnd 模式下的时间偏移量 — 略小于 totalDuration，
    * 避免 isFinished() 判定脚本已结束。
    * 0.1ms 的偏移在视觉上不可察觉，但确保插值仍取最后一帧的值。
    */
   private static final float HOLD_END_EPSILON = 0.0001f;
   ```
   - 将第 188 行改为 `elapsedSeconds = totalDuration - HOLD_END_EPSILON;`

---

## 问题 B6：🟢 `ScriptPlayer.onRenderFrame()` 对每个 TrackPlayer 双重扫描

### 问题确认

[`ScriptPlayer.onRenderFrame()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptPlayer.java:196-204) 中：

```java
for (TrackPlayer tp : trackPlayers) {
    if (tp.isActiveAt(elapsedSeconds)) {    // ← 第1次扫描
        tp.onRenderFrame(elapsedSeconds);    // ← 第2次扫描
    }
}
```

对于 [`CameraTrackPlayer`](src/main/java/com/immersivecinematics/immersive_cinematics/script/CameraTrackPlayer.java)，`isActiveAt()` 调用 `findActiveClip()`，`onRenderFrame()` 也调用 `findActiveClip()`，每帧对同一轨道扫描两次。[`LetterboxTrackPlayer`](src/main/java/com/immersivecinematics/immersive_cinematics/script/LetterboxTrackPlayer.java) 同理。

当前 clips 数量通常很少（<10），性能影响可忽略，但作为每帧热路径，值得优化。

### 修复方案

移除 `isActiveAt()` 预检查，让 `onRenderFrame()` 自行处理"无活跃 clip"的情况。当前所有 TrackPlayer 实现的 `onRenderFrame()` 已在无活跃 clip 时 early return。

### 修改步骤

1. **修改 [`ScriptPlayer.onRenderFrame()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptPlayer.java:196-204)**
   ```java
   for (TrackPlayer tp : trackPlayers) {
       try {
           tp.onRenderFrame(elapsedSeconds);  // onRenderFrame 内部自行判断是否有活跃 clip
       } catch (Exception e) {
           LOGGER.error("TrackPlayer 执行异常", e);
       }
   }
   ```

2. **验证所有 TrackPlayer 实现的 `onRenderFrame()` 在无活跃 clip 时安全退出**
   - [`CameraTrackPlayer.onRenderFrame()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/CameraTrackPlayer.java:44-45): `if (activeClip == null) return;` ✅
   - [`LetterboxTrackPlayer.onRenderFrame()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/LetterboxTrackPlayer.java:37-51): `findActiveClip()` 返回 null 时走 `startFadeOut()` 分支 ✅（但需确认无活跃 clip 时调用 `startFadeOut()` 是否合理 — 这正是计划 C A5 要修复的）
   - [`AudioTrackPlayer`](src/main/java/com/immersivecinematics/immersive_cinematics/script/AudioTrackPlayer.java) 和 [`ModEventTrackPlayer`](src/main/java/com/immersivecinematics/immersive_cinematics/script/ModEventTrackPlayer.java): 需确认

3. **考虑保留 `isActiveAt()` 的场景**
   - [`ScriptPlayer.start()`](src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptPlayer.java:87-95) 中预执行第一帧时使用了 `isActiveAt()`
   - 如果移除 `isActiveAt()` 预检查，`start()` 中的预执行也需要调整
   - **备选方案**：保留 `isActiveAt()` 接口，但在 `onRenderFrame()` 热路径中不再调用它

---

## 修复顺序建议

| 顺序 | 编号 | 严重度 | 问题 | 原因 |
|------|------|--------|------|------|
| 1 | B1 | 🟡 | LetterboxClip/Timeline.isInfinite() 浮点等值 | 与计划 C A2 一并修复，极小改动 |
| 2 | B4 | 🟡 | MathUtil.smoothstep() 除零 NaN | 小改动，纯防御性编程 |
| 3 | B5 | 🟢 | ScriptPlayer holdAtEnd 魔法数 | 极小改动，提取常量 |
| 4 | B3 | 🟡 | ScriptProperties.revert() 硬编码默认值 | 小改动，DRY 修复 |
| 5 | B2 | 🟡 | CinematicCommand 异步反馈不准确 | 小改动，需调整消息显示方式 |
| 6 | B6 | 🟢 | ScriptPlayer 双重扫描 TrackPlayer | 小改动，但需验证所有 TrackPlayer 实现 |

### 与计划 A/B/C 的关系

- **B1** 应与计划 C 的 A2 合并修复（同一类问题）
- **B3** 与计划 B 的 #5（删除 ScriptMeta 旧构造器）相关，都是清理冗余代码
- **B6** 与计划 C 的 A5（LetterboxTrackPlayer 每帧调用 startFadeOut）有依赖关系：A5 修复后，B6 的移除 `isActiveAt()` 预检查才更安全
- **B2、B4、B5** 是独立问题，可单独修复
