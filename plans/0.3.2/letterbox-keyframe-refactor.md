# ④ Letterbox 关键帧驱动重构 — 逐文件修复指南

**版本**: 0.3.2  
**类型**: 重构  
**文件数**: 9  
**执行方式**: 按文件编号顺序执行，每步照做不可跳

---

## 文件 1 — LetterboxClip.java（数据模型）

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/script/LetterboxClip.java`

**替换全部内容为**:

```java
package com.immersivecinematics.immersive_cinematics.script;

import java.util.List;

public class LetterboxClip {

    private final float startTime;
    private final float duration;
    private final List<CameraKeyframe> keyframes;

    public LetterboxClip(float startTime, float duration, List<CameraKeyframe> keyframes) {
        this.startTime = startTime;
        this.duration = duration;
        this.keyframes = keyframes;
    }

    public float getStartTime() { return startTime; }
    public float getDuration() { return duration; }
    public List<CameraKeyframe> getKeyframes() { return keyframes; }

    public boolean isInfinite() { return duration < 0f; }

    @Override
    public String toString() {
        return String.format("LetterboxClip{start=%.2f, dur=%.2f, keyframes=%d}",
                startTime, duration, keyframes != null ? keyframes.size() : 0);
    }
}
```

> 注: 复用 `CameraKeyframe` 类而非新建 `LetterboxKeyframe`，因为 CameraKeyframe 已含 `aspect_ratio` 字段。letterbox clip 只用 `time` + `aspect_ratio`，忽略 yaw/pitch/roll/fov/zoom/dof/position。

---

## 文件 2 — ScriptParser.java（解析）

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/script/ScriptParser.java`

**2a)** 第 353-360 行，改 `parseLetterboxClips` 方法中的 constructor 调用:

```diff
  clips.add(new LetterboxClip(
          requireFloat(obj, cp, "start_time"),
-         requireFloat(obj, cp, "duration"),
-         optBool(obj, "enabled", true),
-         optFloat(obj, "aspect_ratio", 2.35f),
-         optFloat(obj, "fade_in", 0.5f),
-         optFloat(obj, "fade_out", 0.5f)
+         requireFloat(obj, cp, "duration"),
+         parseKeyframes(obj, "keyframes", cp, false)
  ));
```

`parseKeyframes` 已存在（用于 camera clip 解析），`false` = 不需要 position_mode 校验（letterbox 无 position）。

---

## 文件 3 — LetterboxTrackPlayer.java（播放器）

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/script/LetterboxTrackPlayer.java`

**替换全部内容为**:

```java
package com.immersivecinematics.immersive_cinematics.script;

import com.immersivecinematics.immersive_cinematics.overlay.OverlayManager;
import com.immersivecinematics.immersive_cinematics.overlay.LetterboxLayer;

import java.util.List;

public class LetterboxTrackPlayer implements TrackPlayer {

    private final List<LetterboxClip> clips;
    private final OverlayManager overlayManager;
    private int lastClipIdx = -1;

    public LetterboxTrackPlayer(TimelineTrack track, OverlayManager overlayManager) {
        this.clips = track.getLetterboxClips();
        this.overlayManager = overlayManager;
    }

    @Override
    public boolean isActiveAt(float globalTime) {
        return findActiveClip(globalTime) != null;
    }

    @Override
    public void onRenderFrame(float globalTime) {
        LetterboxLayer letterbox = overlayManager.getLetterboxLayer();
        LetterboxClip activeClip = findActiveClip(globalTime);

        if (activeClip == null) {
            if (letterbox.isVisible()) {
                letterbox.setAspectRatio(0.0f);
            }
            lastClipIdx = -1;
            return;
        }

        int clipIdx = clips.indexOf(activeClip);
        float localTime = clipTime(activeClip, globalTime);
        float ratio = KeyframeInterpolator.interpolateDof(
                activeClip.getKeyframes().get(0),
                activeClip.getKeyframes().get(activeClip.getKeyframes().size() - 1),
                localTime / activeClip.getDuration());

        // 使用 CameraKeyframe 的 aspect_ratio — 实际上需要从关键帧里读 aspect_ratio。
        // 简单方案: 用 getDof() 字段存储 aspect_ratio（临时 hack）。
        // 更精确: 遍历 keyframes 做二分查找然后 lerp aspect_ratio。

        if (clipIdx != lastClipIdx || Math.abs(letterbox.getAspectRatio() - ratio) > 0.001f) {
            letterbox.setAspectRatio(ratio);
        }
        lastClipIdx = clipIdx;
    }

    @Override
    public void onStop() {
        overlayManager.getLetterboxLayer().startFadeOut();
    }

    private float clipTime(LetterboxClip clip, float globalTime) {
        return Math.max(0f, Math.min(clip.getDuration(), globalTime - clip.getStartTime()));
    }

    private LetterboxClip findActiveClip(float globalTime) {
        for (LetterboxClip clip : clips) {
            boolean isActive;
            if (clip.getDuration() < 0) {
                isActive = globalTime >= clip.getStartTime();
            } else {
                float clipEnd = clip.getStartTime() + clip.getDuration();
                isActive = globalTime >= clip.getStartTime() && globalTime < clipEnd;
            }
            if (isActive) return clip;
        }
        return null;
    }
}
```

**修正版（更健壮）** — 使用 CameraKeyframe.getRoll() 存储 letterbox aspect_ratio。CameraKeyframe.getRoll() 和 getDof() 都可被复用为通用 float 槽位。

找到 `onRenderFrame` 方法内，把 ratio 计算替换为:

```java
float localTime = clipTime(activeClip, globalTime);
List<CameraKeyframe> kfs = activeClip.getKeyframes();
if (kfs.size() < 2) {
    float ratio = kfs.get(0).getRoll(); // aspect_ratio stored in roll slot
    letterbox.setAspectRatio(ratio);
    lastClipIdx = clipIdx;
    return;
}

// find surrounding keyframes
CameraKeyframe from = kfs.get(0), to = kfs.get(kfs.size() - 1);
for (int i = 0; i < kfs.size() - 1; i++) {
    if (localTime >= kfs.get(i).getTime() && localTime <= kfs.get(i + 1).getTime()) {
        from = kfs.get(i);
        to = kfs.get(i + 1);
        break;
    }
}

float t = (localTime - from.getTime()) / (to.getTime() - from.getTime());
t = Math.max(0f, Math.min(1f, t));
float ratio = from.getRoll() + (to.getRoll() - from.getRoll()) * t;
letterbox.setAspectRatio(ratio);
```

同时更新 `CameraKeyframe` 注释: `getRoll()` / `setRoll()` 加一条 "Also used by LETTERBOX track for aspect_ratio keyframe property"。

---

## 文件 4 — LetterboxLayer.java（覆盖层）

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/overlay/LetterboxLayer.java`

**4a)** 删除 fields 56-58（fadeIn/fadeOut）:

```diff
- /** 入场动画时长（秒），0 = 无动画即时出现 */
- private float fadeIn = 0.5f;
- 
- /** 退场动画时长（秒），0 = 无动画即时消失 */
- private float fadeOut = 0.5f;
```

**4b)** 改 `tick()` 方法（第 99-126 行）。把 fadeIn/fadeOut 替换为固定速度:

```diff
  case FADE_IN -> {
-     if (fadeIn <= 0f) {
-         progress = 1f;
-         transitionState = TransitionState.VISIBLE;
-     } else {
-         progress += deltaTime / fadeIn;
-         if (progress >= 1f) {
-             progress = 1f;
-             transitionState = TransitionState.VISIBLE;
-         }
-     }
+     progress += deltaTime * 4.0f; // 固定 0.25s 过渡
+     if (progress >= 1f) {
+         progress = 1f;
+         transitionState = TransitionState.VISIBLE;
+     }
  }
  case FADE_OUT -> {
-     if (fadeOut <= 0f) {
-         progress = 0f;
-         transitionState = TransitionState.HIDDEN;
-     } else {
-         progress -= deltaTime / fadeOut;
-         if (progress <= 0f) {
-             progress = 0f;
-             transitionState = TransitionState.HIDDEN;
-         }
-     }
+     progress -= deltaTime * 4.0f; // 固定 0.25s 过渡
+     if (progress <= 0f) {
+         progress = 0f;
+         transitionState = TransitionState.HIDDEN;
+     }
  }
```

**4c)** 删除 `setFadeIn()`（第 178-180 行）、`setFadeOut()`（第 188-190 行）、`getFadeIn()`（第 191-193 行）:

```diff
- public void setFadeIn(float seconds) {
-     this.fadeIn = seconds;
- }
- ...
- public void setFadeOut(float seconds) {
-     this.fadeOut = seconds;
- }
- public float getFadeIn() {
-     return fadeIn;
- }
- public float getFadeOut() {
-     return fadeOut;
- }
```

**4d)** 改 `setAspectRatio()` 方法（第 154-162 行）:

```diff
  public void setAspectRatio(float ratio) {
      this.targetAspectRatio = ratio;
-     if (ratio > 0f && transitionState == TransitionState.HIDDEN) {
-         transitionState = TransitionState.FADE_IN;
-         progress = 0f;
-     }
+     if (ratio > 0f) {
+         if (transitionState == TransitionState.HIDDEN) {
+             transitionState = TransitionState.FADE_IN;
+             progress = 0f;
+         }
+         // else: VISIBLE 状态下直接更新目标比，无需动画
+     } else {
+         if (transitionState != TransitionState.HIDDEN) {
+             startFadeOut();
+         }
+     }
  }
```

---

## 文件 5 — EditorScreen.java（canAddKf）

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/EditorScreen.java`

**5a)** 第 427-428 行，改条件:

```diff
- boolean canAddKf = "CAMERA".equals(trackType)
-         && EditorOperations.canAddKeyframeAt(sel.getClip(), time);
+ boolean canAddKf = EditorOperations.canAddKeyframeAt(sel.getClip(), time);
```

---

## 文件 6 — EditorOperations.java（addClip 默认关键帧）

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/EditorOperations.java`

**6a)** 第 24-40 行的 `addClip()` 方法，改签名加 trackType 参数:

```diff
- public static JsonObject addClip(JsonArray tracks, int trackIndex, float startTime, float duration) {
+ public static JsonObject addClip(JsonArray tracks, int trackIndex, float startTime, float duration, String trackType) {
```

**6b)** 在 `clip.add("keyframes", kfs);` 行（第 36 行）之前加按类型填充:

```java
if ("CAMERA".equals(trackType)) {
    JsonObject pos = new JsonObject();
    pos.addProperty("dx", 0f);
    pos.addProperty("dy", 0f);
    pos.addProperty("dz", 0f);
    kf0.add("position", pos);
    kf0.addProperty("yaw", 0f);
    kf0.addProperty("pitch", 0f);
    kf0.addProperty("roll", 0f);
    kf0.addProperty("fov", 70f);
    kf0.addProperty("zoom", 1.0f);
    kf0.addProperty("dof", 0f);
    kf1.addProperty("yaw", 0f);
    kf1.addProperty("pitch", 0f);
    kf1.addProperty("roll", 0f);
    kf1.addProperty("fov", 70f);
    kf1.addProperty("zoom", 1.0f);
    kf1.addProperty("dof", 0f);
    JsonObject pos1 = new JsonObject();
    pos1.addProperty("dx", 0f);
    pos1.addProperty("dy", 0f);
    pos1.addProperty("dz", 0f);
    kf1.add("position", pos1);
} else if ("LETTERBOX".equals(trackType)) {
    kf0.addProperty("roll", 2.35f); // aspect_ratio stored in roll slot
    kf1.addProperty("roll", 2.35f);
}
```

**6c)** EditorScreen.java 第 125、171、256、358 行的 4 处 `addClip` 调用，把 `addClip(doc.getTracks(), 0, start, dur)` 改为:

```
addClip(doc.getTracks(), 0, start, dur, "CAMERA")
```

这些调用都是 CAMERA 轨道，加第 5 个参数。

---

## 文件 7 — LeftPanelArea.java（fillClipDefaults）

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/area/LeftPanelArea.java`

**7a)** 第 245-249 行，删除 LETTERBOX 的整个 case 块:

```diff
- case "LETTERBOX" -> {
-     addDefault(clip, "enabled", true);
-     addDefault(clip, "aspect_ratio", 2.35f);
-     addDefault(clip, "fade_in", 0.5f);
-     addDefault(clip, "fade_out", 0.5f);
- }
```

---

## 文件 8 — LeftPanelArea.java（fillKeyframeDefaults）

**8a)** 改 `fillKeyframeDefaults` 方法（第 268-279 行），改为接受 trackType 参数:

```diff
- private static void fillKeyframeDefaults(JsonObject kf) {
+ private static void fillKeyframeDefaults(JsonObject kf, String trackType) {
```

**8b)** 在方法开头加 LETTERBOX 分支:

```java
if ("LETTERBOX".equals(trackType)) {
    addDefault(kf, "roll", 2.35f); // aspect_ratio in roll slot
    return;
}
```

**8c)** 搜索所有调用 `fillKeyframeDefaults(kf)` 的地方，改为 `fillKeyframeDefaults(kf, trackType)`。

---

## 文件 9 — CameraKeyframe.java（注释 + 复用）

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/script/CameraKeyframe.java`

**9a)** 找到 `getRoll()` / `getDof()` 的 getter，不作代码改动。只确认 LETTERBOX track 用 `roll` 槽位存储 `aspect_ratio`。

> CameraKeyframe 的 7 槽位复用关系:
> - CAMERA: roll = camera roll angle
> - LETTERBOX: roll = aspect_ratio (2.35, 1.85, etc.)

---

## 完成检查清单

- [ ] 文件1 LetterboxClip 替换
- [ ] 文件2 ScriptParser 构造函数调用改
- [ ] 文件3 LetterboxTrackPlayer 替换
- [ ] 文件4 LetterboxLayer 改 tick + 删 setFadeIn/setFadeOut
- [ ] 文件5 EditorScreen canAddKf 条件改
- [ ] 文件6 EditorOperations addClip 加 trackType 参数 + 在 4 处调用加 "CAMERA"
- [ ] 文件7 LeftPanelArea fillClipDefaults 删除 LETTERBOX 分支
- [ ] 文件8 LeftPanelArea fillKeyframeDefaults 加 trackType 参数
- [ ] 编译通过
- [ ] 脚本中 LETTERBOX 关键帧正常播放
- [ ] 编辑器中 LETTERBOX 轨道关键帧菱形可编辑
