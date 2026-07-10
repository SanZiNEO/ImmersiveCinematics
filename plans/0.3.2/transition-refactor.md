# ⑧ 过渡系统重构 — morph 挂在当前 clip 末尾

**版本**: 0.3.2  
**类型**: 重构  
**文件数**: 4  
**执行方式**: 按文件顺序，每步照做

---

## 设计

三种过渡类型统一为当前 clip 的退场行为：

```
CUT:       ████████████|████████████   transition_duration=0
MORPH:     ████████████▓▓▓▓▓▓▓▓▓▓▓▓   transition_duration>0
CROSSFADE: (预留)
```

```
clip 总时长 = duration + transition_duration
└── 关键帧内容 ──┘└── 过渡 ──┘
   start              end    totalEnd
```

过渡时间只占 clip 间隙，不重叠下一段 clip 的内容。

---

## 文件 1 — EditorOperations.java

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/EditorOperations.java`

### 1a) 新增 getTransitionDuration 方法（第 20 行后）

```java
public static float getTransitionDuration(JsonObject clip) {
    if (clip.has("transition") && "morph".equals(clip.get("transition").getAsString())) {
        return clip.has("transition_duration") ? clip.get("transition_duration").getAsFloat() : 0f;
    }
    return 0f;
}
```

### 1b) 新增 getTotalEnd 方法（在 getTransitionDuration 后）

```java
public static float getTotalEnd(JsonObject clip) {
    return getEnd(clip) + getTransitionDuration(clip);
}
```

### 1c) 改 recalc（第 205-220 行）

```diff
  for (JsonElement ce : clips) {
      JsonObject clip = ce.getAsJsonObject();
-     float end = getEnd(clip);
-     if (clip.has("transition") && "morph".equals(clip.get("transition").getAsString())) {
-         end += clip.has("transition_duration") ? clip.get("transition_duration").getAsFloat() : 0;
-     }
+     float end = getTotalEnd(clip);
      maxEnd = Math.max(maxEnd, end);
  }
```

### 1d) 改 recalcDuration（第 221-235 行）

```diff
-     float end = getEnd(clip);
-     if (clip.has("transition") && "morph".equals(clip.get("transition").getAsString())) {
-         end += clip.has("transition_duration") ? clip.get("transition_duration").getAsFloat() : 0;
-     }
+     float end = getTotalEnd(clip);
```

### 1e) 改 addClip 默认值（第 27-38 行附近，之前已加了 trackType 参数）

在 kf0/kf1 之后、`clip.add("keyframes", kfs)` 之前，加:

```java
clip.addProperty("transition", "cut");
clip.addProperty("transition_duration", 0.5f);
```

注意: 这行在前面的 ④ 计划中也要同步加上（如果还没加的话）。

---

## 文件 2 — CameraTrackPlayer.java

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/script/CameraTrackPlayer.java`

### 2a) 改 morph 逻辑（第 35-38 行）

把从 next clip 读 transition 改为从 prev clip 读:

```diff
- if (next.isMorph() && next.getTransitionDuration() > 0f && !prev.isInfinite()) {
+ if (prev.isMorph() && prev.getTransitionDuration() > 0f && !prev.isInfinite()) {
      float prevEnd = prev.getStartTime() + prev.getDuration();
-     float morphEnd = prevEnd + next.getTransitionDuration();
+     float morphEnd = prevEnd + prev.getTransitionDuration();
```

### 2b) 改 weight 计算（Line 39）

```diff
- float weight = (globalTime - prevEnd) / next.getTransitionDuration();
+ float weight = (globalTime - prevEnd) / prev.getTransitionDuration();
```

---

## 文件 3 — TimelineArea.java

> ⑩ (layout-responsive) 将 `TRACK_H` 等静态常量改为实例方法 `trackH()`。本计划中的代码已统一使用 `trackH()`。

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/area/TimelineArea.java`

### 3a) 改 drawClip（第 186-187 行）

clip 矩形从 `getEnd` 改为 `getTotalEnd`:

```diff
  float sx = timeToX(EditorOperations.getStart(clip));
- float ex = timeToX(EditorOperations.getEnd(clip));
+ float ex = timeToX(EditorOperations.getTotalEnd(clip));
```

### 3b) 新增 Transition 矩形渲染（第 198 行之后，fill 后面）

在 clip 主体渲染后、关键帧菱形前，加过渡区域渲染:

```java
float transDur = EditorOperations.getTransitionDuration(clip);
if (transDur > 0f) {
    float tx = timeToX(EditorOperations.getEnd(clip));
    float tex = timeToX(EditorOperations.getTotalEnd(clip));
    int transW = Math.max(2, (int)(tex - tx));
    // 过渡区域用不同颜色（半透明紫色）
    ctx.graphics.fill((int)tx, ty + 2, (int)tx + transW, ty + trackH() - 2, 0x885533AA);
    // 标签
    String tLabel = "morph " + fmt(transDur);
    int tlw = ctx.font.width(tLabel);
    if (tlw + 4 < transW)
        ctx.graphics.drawString(ctx.font, tLabel, (int)tx + 2, ty + (trackH() - 8) / 2, 0xFFCCCCFF);
```

插入位置: 在第 198 行 `ctx.graphics.fill(clipX, ty+2, clipX+clipW, ty+trackH()-2, fill);` 之后，第 199 行 `ctx.graphics.renderOutline(...)` 之前。

---

## 文件 4 — LeftPanelArea.java（微调）

文件: `src/main/java/com/immersivecinematics/immersive_cinematics/editor/area/LeftPanelArea.java`

### 4a) 更新 transition 类型枚举

找到第 294-295 行（`case "transition"`），检查是否需要增加 `crossfade` 选项:

```diff
- case "transition" -> current.equals("cut") ? "morph" : "cut";
+ case "transition" -> switch (current) {
+     case "cut" -> "morph";
+     case "morph" -> "cut";
+     default -> "cut";
+ };
```

不破坏原有逻辑，只是把两个值的切换明确化。

---

## 完成检查清单

- [ ] EditorOperations 新增 getTransitionDuration/getTotalEnd 方法
- [ ] recalc/recalcDuration 改用 getTotalEnd
- [ ] addClip 默认 transition=cut + transition_duration=0.5
- [ ] CameraTrackPlayer morph 窗口改为 `prev.transitionDuration`
- [ ] CameraTrackPlayer weight 改为 `prev.getTransitionDuration()`
- [ ] TimelineArea drawClip 用 getTotalEnd 算矩形边界
- [ ] TimelineArea 过渡区域渲染紫色矩形 + 标签
- [ ] LeftPanelArea transition 选项逻辑更新
- [ ] 编译通过
- [ ] 脚本播放 morph 过渡正确（前段末尾触发）
- [ ] 时间轴显示过渡矩形
- [ ] 总时长正确
