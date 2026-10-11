package com.immersivecinematics.immersive_cinematics.camera.cover;

/**
 * 通道位与通道缺省合成模式（{@link CameraModifier} 的声明口径，掩码位序唯一定义点）。
 *
 * 掩码位序（6 位；{@code position} 整体一位，不分 x / y / z）：{@code POSITION=1<<0}、
 * {@code YAW=1<<1}、{@code PITCH=1<<2}、{@code ROLL=1<<3}、{@code FOV=1<<4}、{@code ZOOM=1<<5}。
 * 缺省合成模式（作者不声明时）：偏移类 position / yaw / pitch / roll = {@link ComposeMode#ADD}；
 * 缩放类 fov / zoom = {@link ComposeMode#MULTIPLY}；{@link ComposeMode#REPLACE} 必须显式声明。
 */
public final class CameraChannels {

    /** 位置（世界坐标三轴同一位）。 */
    public static final int POSITION = 1 << 0;

    /** yaw（度）。 */
    public static final int YAW = 1 << 1;

    /** pitch（度）。 */
    public static final int PITCH = 1 << 2;

    /** roll（度）。 */
    public static final int ROLL = 1 << 3;

    /** fov（度）。 */
    public static final int FOV = 1 << 4;

    /** zoom（倍率）。 */
    public static final int ZOOM = 1 << 5;

    private CameraChannels() {
    }

    /**
     * 单通道的缺省合成模式。
     *
     * @param channel 单个通道位（本类 {@code POSITION} ~ {@code ZOOM} 之一）
     * @return fov / zoom = {@code MULTIPLY}；position / yaw / pitch / roll = {@code ADD}
     */
    public static ComposeMode defaultMode(int channel) {
        return channel == FOV || channel == ZOOM ? ComposeMode.MULTIPLY : ComposeMode.ADD;
    }
}
