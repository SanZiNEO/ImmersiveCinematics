package com.immersivecinematics.immersive_cinematics.client.post;

import java.util.List;
import java.util.function.Predicate;

/**
 * LUT 绑定自检的纯判定（无 MC / 无 GL 依赖，便于离线复算）：采样器单元口径 + 3D 表纹理完整性口径。
 *
 * 采样器单元 = 该采样器在已链接程序里实际存活的次序（未使用被删除 → 靠后的下标前移），
 * 与 JSON 声明下标可能不同；resolveUnit 复刻该口径，-1 = 该采样器自身不在程序里。
 * 若按声明下标绑 3D 表，实际单元前移后着色器会去采别的单元上的 2D 曲线表——常见返回 (0,0,0,1)，
 * 画面变黑且 GL 不报任何错；所以必须先算实际单元再绑。
 * textureReady = 纹理已创建、回读的 level 0 边长与预期一致；任一条不成立 = 表不完整。
 * 两项供 ColorAdjustPass 的 LUT 步在绑定前自检：不过 = 本帧按「没有 LUT」处理（只跳过 LUT 步）。
 */
public final class LutBindGuard {

    /**
     * 着色器 JSON {@code samplers} 的声明次序（{@code ic_color_adjust.json}，逐项对应），
     * 也是没有采样器被优化掉时的纹理单元号；增删采样器必须与 {@code ic_color_adjust.json} 同步。
     */
    public static final List<String> DECLARED_SAMPLERS = List.of(
            "Sampler0",
            "CurveLut", "RCurveLut", "GCurveLut", "BCurveLut",
            "HvHLut", "HvSLut", "HvLLut", "LvSLut", "SvSLut", "SvLLut",
            "Lut3D");

    /** 3D 表的采样器名（{@code ic_color_adjust.fsh} 的 {@code uniform sampler3D}）。 */
    public static final String LUT_3D = "Lut3D";

    /** Java 侧绑定 3D 表用的纹理单元 = {@link #LUT_3D} 在 {@link #DECLARED_SAMPLERS} 里的下标。 */
    public static final int LUT_3D_UNIT = DECLARED_SAMPLERS.indexOf(LUT_3D);

    private LutBindGuard() {
    }

    /**
     * 复刻 MC {@code ShaderInstance.updateLocations()} 的压缩口径，给出 {@code name} 的实际纹理单元。
     *
     * @param name   采样器名
     * @param active 该采样器在已链接程序里是否有 uniform 位置；{@code false} = 被优化掉，后面的下标前移
     * @return 实际纹理单元号；{@code -1} = 该采样器自身不在程序里
     */
    public static int resolveUnit(String name, Predicate<String> active) {
        int unit = 0;
        for (String declared : DECLARED_SAMPLERS) {
            if (!active.test(declared)) {
                continue;   // 未使用即被优化掉：不占单元
            }
            if (declared.equals(name)) {
                return unit;
            }
            unit++;
        }
        return -1;
    }

    /**
     * 3D 表纹理完整性口径：纹理已创建、level 0 边长非 0 且与要采样的表边长一致。
     * 任一条不成立 = 表不完整（{@code texelFetch} 取到未定义值），LUT 步直通。
     *
     * @param textureId    GL 纹理名（{@code <= 0} = 没建出来）
     * @param level0Size   纹理 level 0 的实际边长（{@code glGetTexLevelParameteri} 回读；{@code 0} = 未定义）
     * @param expectedSize 要采样的表边长
     * @return 是否可用
     */
    public static boolean textureReady(int textureId, int level0Size, int expectedSize) {
        return textureId > 0 && expectedSize > 0 && level0Size == expectedSize;
    }
}
