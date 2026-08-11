# 呼吸扰动改进（Breath Disturbance v2）：白噪声 → Perlin 平滑噪声

## 一句话定义

现有 `cam_breath_*` 是每帧独立随机（白噪声，帧间无关联抖动），改进为 **Perlin 噪声 + 时间连续采样**（手持相机式平滑低频摆动）。参考 `MinecraftFoundFootage`（Found Footage 风格 mod）的 `CameraShake` 实现。

## 现状问题

```java
Random rng = new Random((long)(globalTime * 100) + seed);   // 每帧新建 RNG
yawJitter = (rng.nextFloat() - 0.5f) * 2f * intensity;       // 白噪声
pitchJitter = ...; rollJitter = ...;
```

- 帧间完全无关的抖动（静电噪声感）——不是"呼吸"（呼吸 = 平滑低频摆动）
- 无速度/频率控制，只有 intensity + seed

## 参考实现（MinecraftFoundFootage / CameraShake.java）

```java
this.noiseY += this.noiseSpeed * frameDelta;                                    // 平滑时间推进
double pitchOffset = amplitude * getShakeIntensity() * noiseSampler.sample(1,  noiseY, 0);
double yawOffset   = amplitude * getShakeIntensity() * noiseSampler.sample(73, noiseY, 0);
double rollOffset  = amplitude * getShakeIntensity() * noiseSampler.sample(146, noiseY, 0);
```

要点：
- **Perlin 噪声**（MC 自带）采样 → 平滑波形，帧间连续
- 三通道（yaw/pitch/roll）用不同 x 偏移（1/73/146）→ 互不相关的平滑噪声
- 振幅模型：`amplitude × trauma²`（trauma 衰减）
- 参数：trauma（强度）、noiseSpeed（晃动速度）、amplitude（基础振幅）

## 改进方案（v2）

### 参数模型（兼容现有字段 + 新增）

| 字段 | 现状 | v2 |
|---|---|---|
| `cam_breath_enabled` | ✅ | 不变 |
| `cam_breath_intensity` | 0.05（振幅系数） | 不变（v2 中为振幅乘数） |
| `cam_breath_seed` | 确定性来源 | 不变（seed → Perlin 实例，重放一致） |
| `cam_breath_speed` | ❌ 无 | 🆕 噪声推进速度（~0.1~2.0，越大晃得越快） |

### 实现

```java
// TrackPlayer 实例持有（seed 固定 → 确定性）
PerlinNoise noise = PerlinNoise.create(RandomSource.create(seed), ...);
// 每帧：
double t = globalTime * speed;   // 时间轴（确定性：同一 globalTime 同值）
float yawJitter   = (float)(intensity * noise.getValue(1.0, t, 0.0));
float pitchJitter = (float)(intensity * noise.getValue(73.0, t, 0.0));
float rollJitter  = (float)(intensity * noise.getValue(146.0, t, 0.0));
```

- 确定性保持：seed → 固定 Perlin 实例；t 由 globalTime 推导 → 暂停/重放一致
- 平滑性：Perlin 输出连续 → 帧间平滑摆动
- 三通道解耦：不同 x 偏移

### 兼容

- 旧脚本（intensity/seed）行为变化但字段不变——intensity 语义从"白噪声幅度"变为"Perlin 振幅"，数值可沿用（0.05 附近）
- 需要数值校准：Perlin 输出范围 [-1,1]，intensity 直接乘（0.05 可能偏小，预设默认可调）

## 边界与待定

1. Perlin 实例数：per-TrackPlayer 一个（seed 固定）——多片段共用？还是 per-clip（seed 每 clip 可不同）？倾向 per-TrackPlayer + 每 clip 的 seed 字段（clip 级已有）
2. intensity 默认值校准（Perlin 幅度 vs 白噪声幅度差异）
3. 是否暴露"频率"而非"速度"（speed = 频率 × 系数，语义等价）
4. 未来可与动态 yaw 基准（yaw 相对化）叠加——呼吸加在最终 yaw/pitch 上，与基准体系正交

## 参考链接

- `SpacePotatoee/MinecraftFoundFootage` → `src/main/java/com/sp/render/camera/CameraShake.java`
- `ldgd2/modServerMinecraft` → BodycamShakeTrigger（注释确认 client mod 用平滑 Perlin）
