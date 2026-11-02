package com.immersivecinematics.immersive_cinematics.script.object.position;

import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 点源冻结桶：{@code freeze=true} 的点源在首次解析成功后固定世界点，之后同一桶直接复用。
 *
 * <p>桶键 = {@link #key(String, String, String)}（片段 + 对象路径 + 来源签名）。签名由
 * {@link PointSourceSpec#signature()} / {@link LookAtSpec#signature()} 在构造期算好；来源值变化
 * （选择器 / 坐标 / 方块 / 结构 / 偏移 / 冻结开关）即新桶，避免"第一次捕获后整个片段冻结"的歧义。</p>
 *
 * <p>语义：{@code freeze=false} 不写桶、原样返回实时值（跟随来源）；{@code freeze=true} 且已捕获
 * 则返回捕获值，未捕获则写入并返回；解析失败（{@code null}）不写桶，下次继续尝试——冻结的是坐标值，
 * 不是时间。{@code seek} 时由持有方 {@link #clear()} 后重取；{@code loop} 折返是否延续由执行层决定。</p>
 *
 * <p>非线程安全：按播放实例 / 片段执行侧持有（morph A/B 各自一份）。</p>
 */
public final class FreezeStore {

    private final Map<String, Vec3> buckets = new HashMap<>();

    /** 取已捕获的点；未捕获 / {@code key == null} = {@code null}。 */
    public Vec3 get(String key) {
        return key == null ? null : buckets.get(key);
    }

    /**
     * 冻结取值：{@code freeze=false} 原样返回（不写桶）；{@code true} 时已捕获返回捕获值，
     * 否则写入 {@code resolved} 并返回。
     *
     * @param key      桶键（{@link #key} 产物）；{@code freeze=true} 时必须非空
     * @param freeze   冻结开关
     * @param resolved 本次实时解析结果；{@code null} = 解析失败（不写桶，返回 {@code null}）
     */
    public Vec3 getOrCapture(String key, boolean freeze, Vec3 resolved) {
        if (!freeze) {
            return resolved;
        }
        Objects.requireNonNull(key, "key");
        if (resolved == null) {
            return null;
        }
        Vec3 captured = buckets.get(key);
        if (captured != null) {
            return captured;
        }
        buckets.put(key, resolved);
        return resolved;
    }

    /** 清空全部桶（seek / 片段重启时由执行层调用）。 */
    public void clear() {
        buckets.clear();
    }

    /** 当前桶数量（诊断 / 探针）。 */
    public int size() {
        return buckets.size();
    }

    /** 桶键：片段 + 对象路径 + 来源签名；{@code null} 段按空串处理。 */
    public static String key(String clipKey, String path, String signature) {
        return (clipKey == null ? "" : clipKey) + '|' + (path == null ? "" : path) + '|'
                + (signature == null ? "" : signature);
    }
}
