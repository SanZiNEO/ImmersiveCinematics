package com.immersivecinematics.immersive_cinematics.script;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.util.HashMap;
import java.util.Map;

/**
 * 呼吸扰动 v2：多类型可选手持晃动（perlin / perlin_axis / sine / trauma）。
 * <p>
 * 所有类型确定性：同 seed + 同 globalTime → 同抖动（重放一致，编辑器预览与播放共用）。
 * 旧的"每帧白噪声"方案已废弃（帧间无关 → 静电感、无速度/频率控制）。
 * <p>
 * 字段模型（clip 级，旧 enabled/intensity/seed 保持兼容）：
 * <ul>
 *   <li>cam_breath_type：perlin（默认）/ perlin_axis / sine / trauma</li>
 *   <li>cam_breath_intensity：振幅/力度（约等于角度）</li>
 *   <li>cam_breath_seed：波形种子（决定形状/相位）</li>
 *   <li>cam_breath_speed：时间推进速度，越大晃得越快</li>
 *   <li>cam_breath_trauma（仅 trauma）：初始冲击强度 0~1</li>
 *   <li>cam_breath_decay（仅 trauma）：强度每秒衰减速率</li>
 * </ul>
 */
public final class BreathDisturbance {

    public static final String TYPE_PERLIN = "perlin";
    public static final String TYPE_PERLIN_AXIS = "perlin_axis";
    public static final String TYPE_SINE = "sine";
    public static final String TYPE_TRAUMA = "trauma";

    /** PerlinNoise 创建成本较高，按 (类型参数+seed) 缓存（渲染线程单线程访问，确定性实例） */
    private static final Map<String, PerlinNoise> NOISE_CACHE = new HashMap<>();

    public float intensity = 0.05f;
    public int seed = 0;
    public String type = TYPE_PERLIN;
    public float speed = 1.0f;
    public float trauma = 1.0f;
    public float decay = 0.5f;

    public BreathDisturbance() {}

    /** 从 clip 的 cam_breath_* 字段构造（缺省字段 → 设计默认值，旧脚本按 perlin 处理） */
    public static BreathDisturbance fromClip(Clip clip) {
        BreathDisturbance b = new BreathDisturbance();
        b.intensity = clip.getFloat("cam_breath_intensity", b.intensity);
        b.seed = clip.getInt("cam_breath_seed", b.seed);
        b.type = clip.getString("cam_breath_type", TYPE_PERLIN);
        b.speed = clip.getFloat("cam_breath_speed", b.speed);
        b.trauma = clip.getFloat("cam_breath_trauma", b.trauma);
        b.decay = clip.getFloat("cam_breath_decay", b.decay);
        return b;
    }

    /**
     * 计算 [yaw, pitch, roll] 抖动（度），按 cam_breath_type 分派。
     *
     * @param globalTime 脚本全局时间（秒）
     */
    public float[] compute(float globalTime) {
        return switch (type) {
            case TYPE_PERLIN -> perlin(globalTime);
            case TYPE_PERLIN_AXIS -> perlinAxis(globalTime);
            case TYPE_SINE -> sine(globalTime);
            case TYPE_TRAUMA -> trauma(globalTime);
            // 未知类型回落到平滑 Perlin（不抛错，作者脚本容错）
            default -> perlin(globalTime);
        };
    }

    /**
     * 类型 1：perlin（推荐/默认）— 单 Perlin 实例三通道不同 x 偏移，平滑手持感。
     */
    private float[] perlin(double globalTime) {
        double t = globalTime * speed;
        PerlinNoise noise = cached(-2, seed, 1.0, 0.5);
        return new float[]{
                (float) (intensity * noise.getValue(1.0, t, 0.0)),
                (float) (intensity * noise.getValue(73.0, t, 0.0)),
                (float) (intensity * noise.getValue(146.0, t, 0.0))
        };
    }

    /**
     * 类型 2：perlin_axis — 每轴独立 Perlin 实例，轴间更独立、更“随机”。
     */
    private float[] perlinAxis(double globalTime) {
        double t = globalTime * speed;
        PerlinNoise yawNoise = cached(-7, seed, 1.0, 1.0, 1.0);
        PerlinNoise pitchNoise = cached(-7, seed + 1, 1.0, 1.0, 1.0);
        PerlinNoise rollNoise = cached(-7, seed + 2, 1.0, 1.0, 1.0);
        return new float[]{
                (float) (intensity * yawNoise.getValue(t, 0, 0)),
                (float) (intensity * pitchNoise.getValue(t, 0, 0)),
                (float) (intensity * rollNoise.getValue(t, 0, 0))
        };
    }

    /**
     * 类型 3：sine — 确定性低频正弦组合，规律“呼吸感”。
     */
    private float[] sine(double globalTime) {
        double t = globalTime * speed;
        return new float[]{
                (float) (intensity * Math.sin(t * 1.0 + seed * 0.1)),
                (float) (intensity * Math.sin(t * 0.8 + seed * 0.2 + 1.7)),
                (float) (intensity * Math.sin(t * 0.6 + seed * 0.3 + 4.2))
        };
    }

    /**
     * 类型 4：trauma（冲击衰减）— 强度随时间衰减（trauma² 模型），适合受伤/爆炸/受击。
     */
    private float[] trauma(double globalTime) {
        double traumaVal = trauma * Math.exp(-decay * globalTime);
        double t = globalTime * speed;
        PerlinNoise noise = cached(-2, seed, 1.0, 0.5);
        float scale = (float) (intensity * traumaVal * traumaVal);
        return new float[]{
                scale * (float) noise.getValue(1.0, t, 0.0),
                scale * (float) noise.getValue(73.0, t, 0.0),
                scale * (float) noise.getValue(146.0, t, 0.0)
        };
    }

    /**
     * 按参数缓存 PerlinNoise（同 seed + 同参数 → 同实例，确定性复用）。
     */
    private static PerlinNoise cached(int firstOctave, int seed, double amplitude, double... smoothness) {
        // ⚠️ 不要用 new StringBuilder(firstOctave)——负数（perlin=-2 / perlin_axis=-7）会被当成初始容量，
        // 直接抛 NegativeArraySizeException（历史 bug：呼吸开启即相机钉死被此吞掉）。
        StringBuilder key = new StringBuilder().append(firstOctave).append('|').append(seed).append('|').append(amplitude);
        for (double s : smoothness) key.append('|').append(s);
        return NOISE_CACHE.computeIfAbsent(key.toString(),
                k -> PerlinNoise.create(RandomSource.create(seed), firstOctave, amplitude, smoothness));
    }
}
