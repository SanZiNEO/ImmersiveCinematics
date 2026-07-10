# 镜头呼吸扰动

**版本**: 0.4.x  
**类型**: 新功能  
**工作流**: 快速 (<1h)

---

## 设计

运镜全程或片段性叠加随机微晃，模拟手持/呼吸感。不打进关键帧系统——作为 clip 输出后的独立后处理层，避免污染镜头设计数据。

### 属性

| 属性 | 作用域 | 说明 |
|------|--------|------|
| `breath_enabled` | clip 级 | 是否启用 |
| `breath_intensity` | clip 级 | 0.0-1.0，扰动幅度上限 |
| `breath_seed` | clip 级 | 随机种子（可失可复现） |

`CameraKeyframe` 不动，不新增任何字段。

### 实现

`CameraTrackPlayer.writeAttributes()` 最后一段：

```java
// yaw/pitch/roll 都写入 activeProperties 后
if (clip.breathEnabled) {
    float a = clip.breathIntensity * 1.5f;
    long seed = (long)(globalTime * 100 + clip.breathSeed);
    Random rng = new Random(seed);

    yaw   += (rng.nextFloat() - 0.5f) * 2 * a;
    pitch += (rng.nextFloat() - 0.5f) * 2 * a;
    roll  += (rng.nextFloat() - 0.5f) * 2 * a * 0.3f; // roll 衰减

    activeProperties.setAllDirect(yaw, pitch, roll, fov, zoom, dof);
}
```

每帧用同一 `globalTime * 100` 做种子保证复现（跳转/回放同样位置同样晃动）。不用 `Math.random()` 或 `System.nanoTime()`——后者每帧不同值导致抖动不稳定。

### CLIP 级字段无需关键帧

呼吸效果是全场氛围，使用者想要的是"全程轻微晃动"而不是"在 3.5 秒时 yaw 突然抖一下"。放 clip 级，不用 timeline 上的关键帧。

---

## 改动

| 文件 | 改动 |
|------|------|
| `script/CameraClip.java` | 新增 breathEnabled/breathIntensity/breathSeed 三个属性 |
| `script/ScriptParser.java` | 解析 `breath_enabled`/`breath_intensity`/`breath_seed` |
| `script/CameraTrackPlayer.java` | `writeAttributes()` 末尾加扰动叠加 |
