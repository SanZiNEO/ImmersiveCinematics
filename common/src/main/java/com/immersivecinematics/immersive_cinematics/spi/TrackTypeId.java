package com.immersivecinematics.immersive_cinematics.spi;

/**
 * 轨道类型标识：值对象，{@code equals}/{@code hashCode} 按 {@link #id()} 判定（record 语义）。
 *
 * <p>内置 7 个常量沿用旧枚举的常量名（{@link #CAMERA} / {@link #LETTERBOX} /
 * {@link #AUDIO} / {@link #EVENT} / {@link #MOD_EVENT} / {@link #OVERLAY} / {@link #ADJUST}），
 * 脚本里的 {@code timeline.tracks[].type} 与 {@code clips[].track} 写这些名字。</p>
 *
 * <p>第三方注册的轨道建议用带命名空间的小写 id（{@code modid:xxx}），避免与内置或别的模组撞名；
 * id <b>区分大小写</b>（{@code camera} 与 {@code CAMERA} 是两个不同的 id）。解析期
 * {@code TrackRegistry.resolve} 只对<b>内置</b> id 做一次大写回退，第三方 id 必须逐字匹配。</p>
 *
 * @param id 轨道类型标识；非 {@code null}、非空白、无首尾空白
 */
public record TrackTypeId(String id) {

    /** 镜头位置 / 朝向 / 光学控制；每份脚本最多 1 条。 */
    public static final TrackTypeId CAMERA = new TrackTypeId("CAMERA");

    /** 画幅比黑边控制；每份脚本建议 1 条（多实例各持自己的层）。 */
    public static final TrackTypeId LETTERBOX = new TrackTypeId("LETTERBOX");

    /** 背景音乐 / 音效；不限数量。 */
    public static final TrackTypeId AUDIO = new TrackTypeId("AUDIO");

    /** 游戏内命令事件（服务端执行）；建议 1 条。 */
    public static final TrackTypeId EVENT = new TrackTypeId("EVENT");

    /** 第三方模组扩展事件；不限数量。 */
    public static final TrackTypeId MOD_EVENT = new TrackTypeId("MOD_EVENT");

    /** 覆盖层轨道（fade / image / subtitle）；不限数量。 */
    public static final TrackTypeId OVERLAY = new TrackTypeId("OVERLAY");

    /** 画面颜色调整轨道（master，作用于合成输出）；不限数量。 */
    public static final TrackTypeId ADJUST = new TrackTypeId("ADJUST");

    public TrackTypeId {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("轨道类型 id 不能为 null 或空白");
        }
        if (!id.equals(id.strip())) {
            throw new IllegalArgumentException("轨道类型 id 不能有首尾空白: '" + id + "'");
        }
    }

    /**
     * 按 id 构造（等价于 {@code new TrackTypeId(id)}）。
     *
     * @param id 轨道类型标识
     * @return 新的值对象
     * @throws IllegalArgumentException id 为 {@code null} / 空白 / 有首尾空白
     */
    public static TrackTypeId of(String id) {
        return new TrackTypeId(id);
    }

    /** 脚本 / 导出里使用的名字（= {@link #id()}）。 */
    @Override
    public String toString() {
        return id;
    }
}
