# Letterbox 独立轨道化 + Camera Gap 释放修复

**版本**: 0.3.2  
**状态**: 计划中  
**类型**: 重构 + Bug 修复

---

## 背景

`Letterbox` 关键帧复用 `CameraKeyframe`，以 `roll` 字段存储 `aspect_ratio`。每新增轨道类型就要多一层字段混用，未来 IMAGE/AUDIO 轨道会爆炸。

**CameraMixin** 在 clip 间隙期已正确释放镜头控制（`hasActiveCameraClip()`→false→不拦截），但 `ComputeCameraAngles` 的 roll 注入和第 278 行只检查 `isActive()`——间隙期 roll 仍会叠加。

两者合并修改：Letterbox 独立为专属 keyframe，Camera 间隙彻底释放所有控制权。

---

## 1. LetterboxKeyframe.java — 新建专属 Keyframe

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/script/LetterboxKeyframe.java`

```java
package com.immersivecinematics.immersive_cinematics.script;

public class LetterboxKeyframe {
    private final float time;
    private final float aspectRatio;

    public LetterboxKeyframe(float time, float aspectRatio) {
        this.time = time;
        this.aspectRatio = aspectRatio;
    }

    public float getTime() { return time; }
    public float getAspectRatio() { return aspectRatio; }

    @Override
    public String toString() {
        return String.format("LetterboxKf{time=%.2f, ratio=%.3f}", time, aspectRatio);
    }
}
```

一个 keyframe 两个字段，不再复用任何 camera 字段。

---

## 2. LetterboxClip.java — 改为专属 Keyframe 列表

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/script/LetterboxClip.java`

```diff
- private final List<CameraKeyframe> keyframes;
+ private final List<LetterboxKeyframe> keyframes;

- public LetterboxClip(float startTime, float duration, List<CameraKeyframe> keyframes) {
+ public LetterboxClip(float startTime, float duration, List<LetterboxKeyframe> keyframes) {

- public List<CameraKeyframe> getKeyframes() { return keyframes; }
+ public List<LetterboxKeyframe> getKeyframes() { return keyframes; }
```

---

## 3. ScriptParser.java — 解析 LetterboxKeyframe

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptParser.java`

**3a)** 新增 `parseLetterboxKeyframe` 方法:

```java
private static LetterboxKeyframe parseLetterboxKeyframe(JsonObject obj, String p) throws ScriptParseException {
    float time = requireFloat(obj, p, "time");
    float aspectRatio = optFloat(obj, "aspect_ratio", 2.35f);
    return new LetterboxKeyframe(time, aspectRatio);
}
```

**3b)** 修改 `parseLetterboxClips`:

```diff
  JsonArray kfArr = obj.getAsJsonArray("keyframes");
- List<CameraKeyframe> kfs = new ArrayList<>();
+ List<LetterboxKeyframe> kfs = new ArrayList<>();
  for (int j = 0; j < kfArr.size(); j++) {
-     kfs.add(parseCameraKeyframe(kfArr.get(j).getAsJsonObject(), cp + ".keyframes[" + j + "]", false));
+     kfs.add(parseLetterboxKeyframe(kfArr.get(j).getAsJsonObject(), cp + ".keyframes[" + j + "]"));
  }
```

**3c)** `parseCameraKeyframe` 不动——camera 只解析 camera keyframe。

---

## 4. LetterboxTrackPlayer.java — 改用 LetterboxKeyframe

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/script/LetterboxTrackPlayer.java`

```diff
- List<CameraKeyframe> kfs = activeClip.getKeyframes();
+ List<LetterboxKeyframe> kfs = activeClip.getKeyframes();

- ratio = kfs.get(0).getRoll();
+ ratio = kfs.get(0).getAspectRatio();

- ratio = from.getRoll() + (to.getRoll() - from.getRoll()) * t;
+ ratio = from.getAspectRatio() + (to.getAspectRatio() - from.getAspectRatio()) * t;
```

---

## 5. EditorOperations.java — addClip 默认 keyframe 键名

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/EditorOperations.java`

LETTERBOX 分支:

```diff
- kf0.addProperty("roll", 2.35f);
- kf1.addProperty("roll", 2.35f);
+ kf0.addProperty("aspect_ratio", 2.35f);
+ kf1.addProperty("aspect_ratio", 2.35f);
```

---

## 6. LeftPanelArea.java — fillKeyframeDefaults 键名

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/area/LeftPanelArea.java`

```diff
  if ("LETTERBOX".equals(trackType)) {
-     addDefault(kf, "roll", 2.35f);
+     addDefault(kf, "aspect_ratio", 2.35f);
      return;
  }
```

---

## 7. TimelineArea.java — keyframes 渲染兼容

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/area/TimelineArea.java`

`keyframes(clip)` 方法通过 clip JSON 读取 keyframe 的 `time` 字段，通用的，LetterboxKeyframe 的 JSON 同样有 `time`——不改动。

---

## 8. 所有脚本 JSON — 键名更新

目录: `cinematics/`, `run/immersive_cinematics/scripts/`

每个 LETTERBOX keyframe 的 `"roll"` → `"aspect_ratio"`:

```diff
- { "time": 0.0, "roll": 2.35 }
+ { "time": 0.0, "aspect_ratio": 2.35 }
```

CAMERA 的 `"roll"` 不动——那是镜头旋转角度，不是 letterbox 画幅比。

---

## 9. CameraMixin.java — Camera Gap 释放修复

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/mixin/CameraMixin.java`

**已正确实现。** 第 87 行 `if (!mgr.hasActiveCameraClip()) { return; }` 在 clip 间隙期直接返回，让原版 `Camera.setup()` 用自己的位置。

---

## 10. OnComputeCameraAngles — Roll 条件修复

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/ImmersiveCinematics.java`

第 278-282 行，把 `isActive()` 也改为条件联动:

```diff
  public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
      CameraManager mgr = CameraManager.INSTANCE;
-     if (mgr.isActive()) {
+     if (mgr.isActive() && mgr.hasActiveCameraClip()) {
          float roll = mgr.getProperties().getRoll();
          event.setRoll(roll);
      }
  }
```

---

## 11. ScriptPlayer.hasActiveCameraTrack — Morph 窗口补齐

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptPlayer.java`

`CameraTrackPlayer.isActiveAt` 只检查 `findActiveClip`，不检查 morph 过渡窗口。morph 期间 `hasActiveCameraTrack` 返回 false 导致 camera 在 morph 时就被释放。需要在 `IsActiveAt` 中加入 morph 检查——但 morph 已由 `CameraTrackPlayer.onRenderFrame` 内部处理渲染，混合相机在 morph 期间仍应是 active camera clip。补：

```java
// CameraTrackPlayer.isActiveAt 改为:
public boolean isActiveAt(float globalTime) {
    if (findActiveClip(globalTime) != null) return true;
    // morph 过渡窗口也是活跃的
    for (int i = 0; i < clips.size() - 1; i++) {
        CameraClip prev = clips.get(i);
        if (prev.isMorph() && prev.getTransitionDuration() > 0f && !prev.isInfinite()) {
            float prevEnd = prev.getStartTime() + prev.getDuration();
            float morphEnd = prevEnd + prev.getTransitionDuration();
            if (globalTime >= prevEnd && globalTime < morphEnd) return true;
        }
    }
    return false;
}
```

这样 morph 期间 roll 注入正常、间隙期精确释放。

---

## 架构确认

| 轨道 | Keyframe 类 | 字段 | 强制 |
|------|-----------|------|------|
| CAMERA | `CameraKeyframe` | time, position, yaw, pitch, roll, fov, zoom, dof | time + position（mandatory），其余 optFloat 默认值 |
| LETTERBOX | `LetterboxKeyframe` | time, aspectRatio | time（mandatory），aspectRatio 默认 2.35 |
| AUDIO(未来) | `AudioKeyframe` | time, volume, pitch, position | time + volume（mandatory），其余默认值 |
| IMAGE(未来) | `ImageKeyframe` | time, opacity, scale, anchor | time + opacity（mandatory），其余默认值 |

每个轨道完全隔离，解析各自 JSON 格式。`CameraKeyframe` 不再复用。

---

## 改动文件汇总

| 文件 | 改动 |
|------|------|
| **新建** `LetterboxKeyframe.java` | 专属 keyframe，`(time, aspectRatio)` |
| `LetterboxClip.java` | `CameraKeyframe` → `LetterboxKeyframe` |
| `ScriptParser.java` | 新增 `parseLetterboxKeyframe`，字母盒只解析 letterbox |
| `LetterboxTrackPlayer.java` | `getRoll()` → `getAspectRatio()` + `LetterboxKeyframe` |
| `EditorOperations.java` | addClip LETTERBOX 键名 `roll` → `aspect_ratio` |
| `LeftPanelArea.java` | fillKeyframeDefaults LETTERBOX 键名 `roll` → `aspect_ratio` |
| `CameraTrackPlayer.java` | `isActiveAt` 加 morph 窗口判断 |
| `ImmersiveCinematics.java` | `onComputeCameraAngles` 加 `hasActiveCameraClip()` 条件 |
| 脚本 JSON（全部） | `"roll"` → `"aspect_ratio"` |
