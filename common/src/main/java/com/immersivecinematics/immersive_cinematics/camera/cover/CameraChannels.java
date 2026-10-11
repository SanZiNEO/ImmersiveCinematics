package com.immersivecinematics.immersive_cinematics.camera.cover;

import java.util.List;
import java.util.Map;

/**
 * 通道位与通道缺省合成模式（{@link CameraModifier} 的声明口径，掩码位序与通道名唯一定义点）。
 *
 * 掩码位序（6 位；{@code position} 整体一位，不分 x / y / z）：{@code POSITION=1<<0}、
 * {@code YAW=1<<1}、{@code PITCH=1<<2}、{@code ROLL=1<<3}、{@code FOV=1<<4}、{@code ZOOM=1<<5}。
 * 缺省合成模式（作者不声明时）：偏移类 position / yaw / pitch / roll = {@link ComposeMode#ADD}；
 * 缩放类 fov / zoom = {@link ComposeMode#MULTIPLY}；{@link ComposeMode#REPLACE} 必须显式声明。
 * 通道名（{@link #NAMES}）= 脚本字段 {@code modifier.channels} 与文档用名，顺序与位序一致。
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

    /** 通道名（顺序 = 位序）：脚本字段 {@code modifier.channels} 与文档的用名。 */
    public static final List<String> NAMES = List.of("position", "yaw", "pitch", "roll", "fov", "zoom");

    /** 通道名 → 位；键集与 {@link #NAMES} 一致。 */
    private static final Map<String, Integer> BITS = Map.of(
            "position", POSITION, "yaw", YAW, "pitch", PITCH, "roll", ROLL, "fov", FOV, "zoom", ZOOM);

    private CameraChannels() {
    }

    /**
     * 通道名对应的掩码位。
     *
     * @param name 通道名（{@link #NAMES} 之一）
     * @return 位；未知通道名 = {@code 0}（不与任何通道匹配）
     */
    public static int bitOf(String name) {
        Integer bit = BITS.get(name);
        return bit == null ? 0 : bit;
    }

    /** 通道名的错误提示串（{@code position / yaw / pitch / roll / fov / zoom}），与 {@link #NAMES} 同源。 */
    public static String namesHint() {
        return String.join(" / ", NAMES);
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
