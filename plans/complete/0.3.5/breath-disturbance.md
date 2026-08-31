# 呼吸扰动（Breath Disturbance）：多类型可选手持晃动

**版本**: 0.3.5
**类型**: 相机效果改进（2026-08-18 扩展定稿）
**状态**: ✅ 已完成（多类型呼吸扰动已实现）
**关联**: 与 `dynamic-yaw-reference` 正交（呼吸加在最终 yaw/pitch/roll 上）

---

## 一句话定义

把现有 `cam_breath_*` 的“每帧白噪声”替换为**多种可选的平滑扰动类型**，作者通过 `cam_breath_type` 选择想要的晃动风格；`seed` 决定波形形状，`intensity` 决定振幅，`speed` 决定快慢。

---

## 现状与为什么不用

```java
Random rng = new Random((long)(globalTime * 100) + seed);
yawJitter   = (rng.nextFloat() - 0.5f) * 2f * intensity;
pitchJitter = ...;
rollJitter  = ...;
```

- 帧间完全无关的白噪声 → “静电感”，不像呼吸。
- 无速度/频率控制。
- **结论：旧白噪声效果不再作为默认/推荐方案。**

---

## 字段模型

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `cam_breath_enabled` | bool | false | 总开关 |
| `cam_breath_type` | enum | `perlin` | 扰动类型：`perlin` / `perlin_axis` / `sine` / `trauma` |
| `cam_breath_intensity` | float | 0.05 | 振幅/力度（约等于角度） |
| `cam_breath_seed` | int | 0 | 波形种子（决定形状/相位，重放一致） |
| `cam_breath_speed` | float | 1.0 | 时间推进速度，越大晃得越快 |
| `cam_breath_trauma` | float | 1.0 | 仅 `trauma` 类型：初始冲击强度 0~1 |
| `cam_breath_decay` | float | 0.5 | 仅 `trauma` 类型：强度每秒衰减速率 |

- 旧字段 `enabled/intensity/seed` 保持兼容；旧脚本缺省 `cam_breath_type` → 按 `perlin` 处理（行为从白噪声变为平滑 Perlin）。
- `seed` 语义统一为“波形种子”：同 seed + 同 `globalTime` → 同抖动。

---

## 扰动类型

### 类型 1：`perlin`（推荐 / 默认）

单 Perlin 实例 + 三通道不同 x 偏移，平滑手持感。

```java
// TrackPlayer 持有（seed 固定 → 确定性）
PerlinNoise noise = PerlinNoise.create(RandomSource.create(seed), -2, 1.0, 0.5);

// 每帧
double t = globalTime * speed;
yawJitter   = (float)(intensity * noise.getValue(1.0,  t, 0.0));
pitchJitter = (float)(intensity * noise.getValue(73.0, t, 0.0));
rollJitter  = (float)(intensity * noise.getValue(146.0, t, 0.0));
```

- 优点：帧间平滑、实例少、三通道互不相关。
- 参考：`MinecraftFoundFootage`、`NixLib`。

### 类型 2：`perlin_axis`

每个轴独立 Perlin 实例，轴间更独立、更“随机”。

```java
PerlinNoise yawNoise   = PerlinNoise.create(RandomSource.create(seed),     -7, 1, 1, 1);
PerlinNoise pitchNoise = PerlinNoise.create(RandomSource.create(seed + 1), -7, 1, 1, 1);
PerlinNoise rollNoise  = PerlinNoise.create(RandomSource.create(seed + 2), -7, 1, 1, 1);

double t = globalTime * speed;
yawJitter   = (float)(intensity * yawNoise.getValue(t, 0, 0));
pitchJitter = (float)(intensity * pitchNoise.getValue(t, 0, 0));
rollJitter  = (float)(intensity * rollNoise.getValue(t, 0, 0));
```

- 优点：轴间天然独立，适合更“散”的晃动。
- 参考：`VrTeX` / `AdInfinitum` 的 `ScreenshakeHandler`。

### 类型 3：`sine`

确定性低频正弦组合，规律“呼吸感”。

```java
double t = globalTime * speed;
float yawJitter   = (float)(intensity * Math.sin(t * 1.0 + seed * 0.1));
float pitchJitter = (float)(intensity * Math.sin(t * 0.8 + seed * 0.2 + 1.7));
float rollJitter  = (float)(intensity * Math.sin(t * 0.6 + seed * 0.3 + 4.2));
```

- 优点：非常规律、像呼吸/手持轻微起伏；参数直观。
- 适合“想要明显节奏感”的场景。

### 类型 4：`trauma`（冲击衰减）

Found Footage 风格：强度随时间衰减，适合受伤、爆炸、受击后的镜头晃动。

```java
// 确定性衰减：以脚本时间 globalTime 为时间轴
double trauma = cam_breath_trauma * Math.exp(-cam_breath_decay * globalTime);
double t = globalTime * speed;
PerlinNoise noise = PerlinNoise.create(RandomSource.create(seed), -2, 1.0, 0.5);

float scale = (float)(intensity * trauma * trauma);
yawJitter   = scale * (float)noise.getValue(1.0,  t, 0.0);
pitchJitter = scale * (float)noise.getValue(73.0, t, 0.0);
rollJitter  = scale * (float)noise.getValue(146.0, t, 0.0);
```

- 优点：有“受击后逐渐稳定”的叙事感。
- 参考：`MinecraftFoundFootage` 的 `CameraShake.java`（trauma² 模型）。

---

## 兼容与默认

- 旧脚本：`cam_breath_enabled=true` + `intensity/seed`，缺省 `type=perlin` → 自动从白噪声变为平滑 Perlin。
- 默认 `intensity=0.05` 作为 Perlin 振幅可能偏小，执行时校准；`perlin_axis` 与 `trauma` 可能也需要单独校准。
- 编辑器属性面板：新增 `cam_breath_type` 下拉 + `speed`（+ trauma/decay 当 type=trauma 时显示）。

## 边界与待定

1. Perlin 实例生命周期：per-TrackPlayer 持有（seed 固定）；多片段共用还是 per-clip 由 seed 字段决定（clip 级已有）。
2. `perlin_axis` / `trauma` 的默认 octave/amplitude 数值执行时统一校准。
3. `sine` 的频率系数（1.0/0.8/0.6）可按观感调整。
4. 未来可与动态 yaw 基准叠加：呼吸加在最终 yaw/pitch/roll 上，与基准体系正交。

## 参考链接

- `SpacePotatoee/MinecraftFoundFootage` → `src/main/java/com/sp/render/camera/CameraShake.java`
- `ninix44/NixLib` → `src/main/java/ru/ninix/nixlib/client/util/CameraShake.java`
- `vertexcubed/VrTeX` → `src/main/java/vertexcubed/vrtex/client/screenshake/ScreenshakeHandler.java`
- `ldgd2/modServerMinecraft` → BodycamShakeTrigger

## 执行前再看 / 具体方案

- **MC 源码**（已抽取到 `build/mc-sources/`）：
  - `net/minecraft/world/level/levelgen/synth/PerlinNoise.java`：`create(RandomSource, int firstOctave, double amplitude, double...)`、`getValue(x,y,z)`。
  - `net/minecraft/util/RandomSource.java`：`RandomSource.create(long seed)`。
- **项目文件**：
  - `script/CameraTrackPlayer.java`：`writeAttributes` 与 `renderMorph` 两处白噪声块，改为按 `cam_breath_type` 分派。
  - `common/src/main/resources/schema.json`：CAMERA clips 增加 `cam_breath_type/speed/trauma/decay`。
  - 编辑器属性面板：类型下拉 + 条件显示参数。
- **做法**：在 `CameraTrackPlayer` 内新增 `BreathDisturbance` 工具（或私有方法），按 type 计算 `[yawJitter, pitchJitter, rollJitter]`；TrackPlayer 持有 Perlin 实例（seed 固定）。
- **执行时再看**：`PerlinNoise.create/getValue` 签名、`CameraTrackPlayer` 两处噪声块、schema.json、三个外部参考实现。
