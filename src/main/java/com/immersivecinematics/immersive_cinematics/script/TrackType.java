package com.immersivecinematics.immersive_cinematics.script;

/**
 * 轨道类型枚举 — 定义时间轴上的轨道种类
 * <p>
 * 每种轨道类型对应不同的 Clip 子类：
 * <ul>
 *   <li>CAMERA → CameraClip</li>
 *   <li>LETTERBOX → LetterboxClip</li>
 *   <li>AUDIO → AudioClip</li>
 *   <li>EVENT → EventClip</li>
 *   <li>MOD_EVENT → ModEventClip</li>
 * </ul>
 * <p>
 * 轨道数量限制：
 * <ul>
 *   <li>CAMERA: 最多1条</li>
 *   <li>LETTERBOX: 最多1条（建议）</li>
 *   <li>EVENT: 最多1条（建议）</li>
 *   <li>AUDIO: 不限制</li>
 *   <li>MOD_EVENT: 不限制</li>
 * </ul>
 */
public enum TrackType {

    /** 镜头位置/朝向/光学控制 */
    CAMERA,

    /** 画幅比黑边控制 */
    LETTERBOX,

    /** 背景音乐/音效 */
    AUDIO,

    /** 游戏内命令事件（服务端执行） */
    EVENT,

    /** 第三方模组扩展事件 */
    MOD_EVENT
}
