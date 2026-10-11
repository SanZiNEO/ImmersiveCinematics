package com.immersivecinematics.immersive_cinematics.camera.cover;

/**
 * Modifier：在 Base 状态之上按通道掩码做部分修改（不做整帧覆盖）。
 *
 * 应用序：按 {@link #modifierPriority()} 升序应用（数值小者先、大者后——高优先级落在最终值上）；
 * 同值 = 先注册者先应用。优先级域独立于 {@link BaseCameraProvider#basePriority()}，两域数值互不比较。
 * 生效范围：只作用于主相机状态（链输出）；lane 快照不经过 Modifier。
 * 分配契约：{@link #writeValue(CameraStateBuffer)} 每帧调用，必须零分配（不新建对象 / 不装箱 / 不建集合）。
 * 实现位置：值工作区字段为包内可见，实现须与本包同包（对外实现形态另行开放）。
 * 线程契约：仅客户端主线程；注册 / 注销与每帧求值同线程。
 */
public interface CameraModifier {

    /** 应用序：升序（小者先应用）；同值 = 先注册者先应用。 */
    int modifierPriority();

    /** 生效通道位掩码（{@link CameraChannels} 的位；{@code 0} = 不生效）。 */
    int channelMask();

    /** 掩码内通道的合成方式；{@code null} = 逐通道缺省（{@link CameraChannels#defaultMode(int)}）。 */
    ComposeMode composeMode();

    /**
     * 把本 Modifier 的值写进值工作区；只保证掩码内通道有效（掩码外通道不参与合成，不必写）。
     *
     * @param out 值工作区：按 {@link #composeMode()} 的语义写入（{@code REPLACE} = 绝对值、{@code ADD} = 增量、
     *            {@code MULTIPLY} = 倍率）；位置写 {@code px} / {@code py} / {@code pz}
     */
    void writeValue(CameraStateBuffer out);
}
