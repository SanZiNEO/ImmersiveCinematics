package com.immersivecinematics.immersive_cinematics.client.post;

import java.util.List;
import java.util.function.Predicate;

/**
 * LUT 绑定自检的<b>纯判定</b>（无 MC / 无 GL 依赖，便于离线复算）：
 * 采样器单元口径 + 3D 表纹理完整性口径。两个口径都在 {@link ColorAdjustPass} 的绑定路径上用。
 *
 * <h2>为什么单元号不能写死</h2>
 * MC 1.20.1 的 {@code ShaderInstance.updateLocations()}（构造末尾、链接之后）逐名查
 * {@code glGetUniformLocation}，<b>查不到的采样器会被整条删掉</b>（{@code samplerNames.remove}）
 * ——之后的采样器下标随之<b>前移</b>；而 {@code ShaderInstance.apply()} 正是按这个压缩后的下标
 * 做 {@code glUniform1i(采样器 uniform, 下标)} + {@code GlStateManager._activeTexture(GL_TEXTURE0 + 下标)}。
 * <p>也就是说「采样器的纹理单元 = 它在 JSON {@code samplers} 里的下标」<b>只在没有任何采样器被
 * 优化掉时成立</b>。驱动 / GLSL 编译期判定某个采样器未使用（死代码消除）就会打破这个前提：
 * 此时 {@code Lut3D} 的实际单元 &lt; 声明下标，而 3D 表仍按声明下标手动绑定（{@code setSampler}
 * 那条路一律按 {@code GL_TEXTURE_2D} 目标绑，3D 表只能手动绑）——着色器会去采样<b>另一个单元</b>
 * 上绑的 2D 曲线 LUT（{@code sampler3D} 采到不完整纹理 = GL 语义未定义，常见实现返回
 * {@code (0,0,0,1)}），画面直接变黑，而且 GL 一个错都不报。</p>
 *
 * <p>{@link #resolveUnit} 复刻 {@code updateLocations()} 的口径，用一个「该采样器在已链接程序里
 * 是否有 uniform 位置」的判定给出<b>实际</b>单元号——绑定前拿它与 {@link #LUT_3D_UNIT} 比一次，
 * 不一致就不绑（见 {@link ColorAdjustPass} 的 LUT 步自检）。</p>
 */
public final class LutBindGuard {

    /**
     * 着色器 JSON {@code samplers} 的声明次序（{@code ic_color_adjust.json}，逐项对应）——
     * 也是<b>没有采样器被优化掉时</b>的纹理单元号。增删采样器必须同步本表。
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
     * 复刻 MC {@code ShaderInstance.updateLocations()} 的压缩口径，给出 {@code name} 的<b>实际</b>纹理单元。
     *
     * @param name   采样器名
     * @param active 该采样器在已链接的 GL program 里是否有 uniform 位置
     *               （{@code Uniform.glGetUniformLocation(program, name) >= 0}）；
     *               {@code false} = 被 GLSL 优化掉，MC 会把这一条整条删掉、后面的下标前移
     * @return 实际纹理单元号；{@code -1} = 该采样器自身不在程序里（着色器不会采样它）
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
     * 3D 表纹理的完整性口径：纹理已创建（{@code textureId > 0}）、回读到的 level 0 边长非 0，
     * 且与本次要采样的表边长一致（{@code Lut3DSize} uniform 用的就是同一个值）。
     * <p>任一条不成立 = 表不完整，{@code texelFetch} 取到的是未定义值（常见实现 {@code (0,0,0,1)}）；
     * 此时绝不上传 / 绑定，LUT 步直通。</p>
     *
     * @param textureId    GL 纹理名（{@code <= 0} = 没建出来）
     * @param level0Size   纹理 level 0 的<b>实际</b>边长（{@code glGetTexLevelParameteri} 回读；{@code 0} = 未定义）
     * @param expectedSize 本次要采样的表边长
     */
    public static boolean textureReady(int textureId, int level0Size, int expectedSize) {
        return textureId > 0 && expectedSize > 0 && level0Size == expectedSize;
    }
}
