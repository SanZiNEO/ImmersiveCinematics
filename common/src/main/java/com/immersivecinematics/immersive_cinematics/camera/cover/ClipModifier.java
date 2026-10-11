package com.immersivecinematics.immersive_cinematics.camera.cover;

/**
 * 片段级静态 Modifier 声明（脚本字段 {@code modifier} 的运行时形态）：常量值 + 通道掩码 + 应用序 + 合成模式。
 *
 * 形态：{@link CameraModifier} 的「脚本声明」实现——值在解析期归一为 {@code double[]}（槽位序见 {@code SLOT_*}
 * 常量），逐帧 {@link #writeValue(CameraStateBuffer)} 只做字段直写（零分配、无分支外的计算）。
 * 存活期：声明随所在片段对象存活（整个播放期）；注册 / 注销由播放实例驱动（{@code PlaybackInstance}），
 * 一个片段至多一条声明——多效果 = 多片段 / 多脚本，链按优先级逐条合成。
 * 线程契约：仅客户端主线程；解析期建成后只读（{@code values} 不再写入）。
 */
public final class ClipModifier implements CameraModifier {

    /** 值槽位：位置 x（世界坐标，方块）。 */
    public static final int SLOT_POSITION_X = 0;

    /** 值槽位：位置 y。 */
    public static final int SLOT_POSITION_Y = 1;

    /** 值槽位：位置 z。 */
    public static final int SLOT_POSITION_Z = 2;

    /** 值槽位：yaw（度）。 */
    public static final int SLOT_YAW = 3;

    /** 值槽位：pitch（度）。 */
    public static final int SLOT_PITCH = 4;

    /** 值槽位：roll（度）。 */
    public static final int SLOT_ROLL = 5;

    /** 值槽位：fov（度）。 */
    public static final int SLOT_FOV = 6;

    /** 值槽位：zoom（倍率）。 */
    public static final int SLOT_ZOOM = 7;

    /** 值槽位数（= {@code values} 的长度）。 */
    public static final int VALUE_COUNT = 8;

    /** 应用序：升序（小者先应用）；同值 = 先注册者先应用。 */
    private final int priority;

    /** 生效通道位掩码（{@link CameraChannels} 的位；{@code 0} = 不生效）。 */
    private final int mask;

    /** 掩码内通道的合成方式；{@code null} = 逐通道缺省（{@link CameraChannels#defaultMode(int)}）。 */
    private final ComposeMode mode;

    /** 通道值（长度 {@link #VALUE_COUNT}，槽位序见 {@code SLOT_*}；掩码外槽位不参与合成，值为 0）。 */
    private final double[] values;

    /**
     * @param priority 应用序（升序，小者先应用）
     * @param mask     生效通道位掩码
     * @param mode     合成方式；{@code null} = 逐通道缺省
     * @param values   通道值（长度 {@link #VALUE_COUNT}）；本类只读该数组，调用方不得再改
     */
    public ClipModifier(int priority, int mask, ComposeMode mode, double[] values) {
        this.priority = priority;
        this.mask = mask;
        this.mode = mode;
        this.values = values;
    }

    @Override
    public int modifierPriority() {
        return priority;
    }

    @Override
    public int channelMask() {
        return mask;
    }

    @Override
    public ComposeMode composeMode() {
        return mode;
    }

    @Override
    public void writeValue(CameraStateBuffer out) {
        out.px = values[SLOT_POSITION_X];
        out.py = values[SLOT_POSITION_Y];
        out.pz = values[SLOT_POSITION_Z];
        out.yaw = (float) values[SLOT_YAW];
        out.pitch = (float) values[SLOT_PITCH];
        out.roll = (float) values[SLOT_ROLL];
        out.fov = (float) values[SLOT_FOV];
        out.zoom = (float) values[SLOT_ZOOM];
    }
}
