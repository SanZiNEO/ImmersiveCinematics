package com.immersivecinematics.immersive_cinematics.script;

/**
 * 轨道类型：所有轨道共用 {@link Clip} 容器，轨道特有字段存在 {@code Clip.data} 中（结构由 {@link SchemaLoader} 定义）。
 * 数量限制：CAMERA 最多 1 条；LETTERBOX / EVENT 建议 1 条；AUDIO / MOD_EVENT / OVERLAY / ADJUST 不限
 * （同帧多条 ADJUST 轨道时后发布者生效）。
 */
public enum TrackType {

    /** 镜头位置 / 朝向 / 光学控制；每份脚本最多 1 条。 */
    CAMERA,

    /** 画幅比黑边控制；每份脚本建议 1 条（多实例各持自己的层）。 */
    LETTERBOX,

    /** 背景音乐 / 音效；不限数量。 */
    AUDIO,

    /** 游戏内命令事件（服务端执行）；建议 1 条。 */
    EVENT,

    /** 第三方模组扩展事件；不限数量。 */
    MOD_EVENT,

    /** 覆盖层轨道（fade / image / subtitle）；不限数量。 */
    OVERLAY,

    /** 画面颜色调整轨道（master，作用于合成输出）：全部参数写在关键帧上；不限数量。 */
    ADJUST
}
